"""Compare PiP pattern RGB values against an area-averaged reference (Pillow only)."""
import argparse
import json
import math
import re
from pathlib import Path
from PIL import Image, ImageChops, ImageStat

PATTERN = Path(__file__).resolve().parents[1] / 'src/smoke/resources/pip-pattern.html'


def specification():
    return json.loads(re.search(r'<script id="spec" type="application/json">\s*(.*?)\s*</script>', PATTERN.read_text(), re.S).group(1))


def source(spec, width, height):
    pixels = bytearray(width * height * 3)
    for y in range(height):
        row = y * 4 // height
        for x in range(width):
            if row == 0:
                c = [(x + y) % 2 * 255] * 3
            elif row == 1:
                c = [x % 2 * 255] * 3
            elif row == 2:
                v = math.floor((0.5 + 0.5 * math.cos(spec['zoneScale'] * ((x-width/2)**2 + (y-5*height/8)**2))) * 255 + 0.5)
                c = [v] * 3
            else:
                c = spec['patches'][min(5, x * 6 // width)]
            v = math.floor(x * 255 / max(1, width-1) + 0.5)
            if height-64 <= y < height-32:
                c = [v] * 3
            elif y >= height-32:
                c = [v, 255-v, v]
            i = (y * width + x) * 3
            pixels[i:i+3] = bytes(c)
    image = Image.frombytes('RGB', (width, height), bytes(pixels))
    m = spec['markerSize']
    for xy, color in zip(((0,0),(width-m,0),(0,height-m),(width-m,height-m)), spec['markers']):
        image.paste(tuple(color), (*xy, xy[0]+m, xy[1]+m))
    return image


def area_resize(image, size):
    """Exact rational coverage; unlike Pillow BOX this includes fractional edge pixels."""
    sw, sh = image.size
    ow, oh = size
    data = image.convert('RGB').tobytes()
    result = bytearray(ow * oh * 3)
    previous = -1
    row = None
    for y in range(oh):
        sums = [0] * (ow * 3)
        top, bottom = y * sh, (y+1) * sh
        for sy in range(top // oh, (bottom + oh - 1) // oh):
            wy = min(bottom, (sy+1)*oh) - max(top, sy*oh)
            if sy != previous:
                row = [0] * (ow * 3)
                for x in range(ow):
                    left, right = x * sw, (x+1) * sw
                    for sx in range(left // ow, (right + ow - 1) // ow):
                        wx = min(right, (sx+1)*ow) - max(left, sx*ow)
                        for c in range(3):
                            row[x*3+c] += data[(sy*sw+sx)*3+c] * wx
                previous = sy
            for i, value in enumerate(row):
                sums[i] += value * wy
        denominator = sw * sh
        for i, value in enumerate(sums):
            result[y*ow*3+i] = (value + denominator//2)//denominator
    return Image.frombytes('RGB', size, bytes(result))


def metrics(actual, expected):
    diff = ImageChops.difference(actual.convert('RGB'), expected.convert('RGB'))
    bands = diff.split()
    mse = sum(ImageStat.Stat(diff).sum2) / (diff.width * diff.height * 3)
    return {'differing_values': sum(diff.width * diff.height - b.histogram()[0] for b in bands),
            'maximum_difference': max(b.getextrema()[1] for b in bands),
            'psnr_db': 10 * math.log10(255**2/mse) if mse else None}


def locate(image, spec, source_size):
    # Exact solid marker interiors survive BOX reduction. Use all four corners to reject unrelated colors.
    image = image.convert('RGB')
    boxes = []
    for color in spec['markers']:
        mask = Image.new('L', image.size)
        mask.putdata([255 if p == tuple(color) else 0 for p in zip(*[iter(image.tobytes())] * 3)])
        box = mask.getbbox()
        if box is None:
            raise ValueError('Fiducials not found; supply --bounds for a cropped PiP')
        boxes.append(box)
    bounds = (boxes[0][0], boxes[0][1], boxes[3][2], boxes[3][3])
    if not (boxes[1][2] == bounds[2] and boxes[2][3] == bounds[3]):
        raise ValueError('Ambiguous fiducials; supply explicit --bounds')
    return bounds


def compare(image, size, crop, bounds, identity=False, frame_size=None, pattern_offset=(0, 0)):
    spec = specification()
    frame_size = tuple(frame_size or size)
    bounds = bounds or locate(image, spec, size)
    if min(size) < 1 or frame_size[0] * frame_size[1] > 3840 * 2160:
        raise ValueError('Source dimensions exceed the capture limit')
    if not (0 <= bounds[0] < bounds[2] <= image.width and 0 <= bounds[1] < bounds[3] <= image.height):
        raise ValueError('PiP bounds must lie inside the framebuffer')
    crop = crop or (0, 0, *frame_size)
    if not (0 <= crop[0] < crop[2] <= frame_size[0] and 0 <= crop[1] < crop[3] <= frame_size[1]):
        raise ValueError('Crop must lie inside the source')
    actual = image.convert('RGB').crop(bounds)
    if identity and actual.size != (crop[2]-crop[0], crop[3]-crop[1]):
        raise ValueError('Identity verification requires exact source-sized bounds')
    full_source = Image.new('RGB', frame_size)
    full_source.paste(source(spec, *size), tuple(pattern_offset))
    expected_source = full_source.crop(crop)
    enlarged = actual.width > expected_source.width or actual.height > expected_source.height
    reference = expected_source.resize(actual.size, Image.Resampling.BILINEAR) if enlarged else area_resize(expected_source, actual.size)
    box_reference = expected_source.resize(actual.size, Image.Resampling.BOX)
    sx, sy = pattern_offset
    cw, ch = crop[2]-crop[0], crop[3]-crop[1]
    regions = {}
    # Twelve source pixels exclude the window's rounded corner mask. Two output pixels
    # avoid samples overlapping browser chrome or an adjacent test region.
    for name, y0, y1 in [('checkerboard', 68, size[1]/4), ('lines',size[1]/4,size[1]/2),
                         ('zone_plate',size[1]/2,3*size[1]/4), ('patches_and_ramps',3*size[1]/4,size[1]-12)]:
        top = max(0, math.ceil((sy+y0-crop[1])*actual.height/ch)+2)
        bottom = min(actual.height, math.floor((sy+y1-crop[1])*actual.height/ch)-2)
        left = max(0, math.ceil((sx+12-crop[0])*actual.width/cw)+2)
        right = min(actual.width, math.floor((sx+size[0]-12-crop[0])*actual.width/cw)-2)
        if bottom > top and right > left:
            box = (left, top, right, bottom)
            regions[name] = metrics(actual.crop(box), reference.crop(box))
            regions[name]['pillow_box'] = metrics(actual.crop(box), box_reference.crop(box))
    return {'bounds': bounds, 'reference': 'bilinear enlargement' if enlarged else 'exact area', 'regions': regions,
            'passed': bool(regions) and all(r['maximum_difference'] <= (0 if identity else 2 if enlarged else 1) and (r['psnr_db'] is None or r['psnr_db'] >= 45) for r in regions.values())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path, nargs='?')
    parser.add_argument('--source-size', type=int, nargs=2, default=[1280,720])
    parser.add_argument('--frame-size', type=int, nargs=2, help='full WGC size including window chrome')
    parser.add_argument('--pattern-offset', type=int, nargs=2, default=[0, 0])
    parser.add_argument('--crop', type=int, nargs=4, help='source pixel left top right bottom')
    parser.add_argument('--bounds', type=int, nargs=4, help='framebuffer left top right bottom')
    parser.add_argument('--identity', action='store_true', help='require zero differing RGB values')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        a = Image.new('RGB', (2,2)); b = a.copy(); b.putpixel((1,0),(1,0,0))
        assert metrics(a,a)['differing_values'] == 0
        assert metrics(a,b)['differing_values'] == 1
        assert metrics(a,b)['maximum_difference'] == 1
        spec = specification(); pattern = source(spec,1280,720)
        assert locate(pattern,spec,pattern.size) == (0,0,1280,720)
        assert compare(pattern,pattern.size,None,None)['passed']
        tiny = Image.new('RGB', (3, 1))
        tiny.putdata([(0, 0, 0), (0, 0, 0), (255, 255, 255)])
        assert area_resize(tiny, (2, 1)).tobytes() == bytes([0, 0, 0, 170, 170, 170])
        print('PiP fidelity self-test passed')
        return 0
    if not args.capture:
        parser.error('capture is required')
    with Image.open(args.capture) as image:
        result = compare(image, args.source_size, args.crop, args.bounds, args.identity, args.frame_size, args.pattern_offset)
    print(json.dumps(result, indent=2))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
