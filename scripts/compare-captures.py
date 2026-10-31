"""Compare original full-size native captures, including RGB when the alpha channels match. Requires Pillow."""
import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageChops


def difference(first, second):
    if first.size != second.size:
        raise ValueError(f"Capture size mismatch: {first.size} != {second.size}")
    rgba_first, rgba_second = first.convert("RGBA"), second.convert("RGBA")
    bands = ImageChops.difference(rgba_first, rgba_second).split()
    # RGBA.getbbox() defaults to alpha-only on recent Pillow. Union all channels explicitly.
    changed = ImageChops.lighter(ImageChops.lighter(bands[0], bands[1]), ImageChops.lighter(bands[2], bands[3]))
    return {
        "bounds": changed.getbbox(),
        "pixels": first.width * first.height - changed.histogram()[0],
        "channel_max": [band.getextrema()[1] for band in bands],
        "first_sha256": hashlib.sha256(rgba_first.tobytes()).hexdigest(),
        "second_sha256": hashlib.sha256(rgba_second.tobytes()).hexdigest(),
    }


def compare(first, second):
    with Image.open(first) as a, Image.open(second) as b:
        if a.size != (1920, 1080) or b.size != (1920, 1080):
            raise ValueError("Only original fullscreen 1920x1080 captures are accepted")
        return {"first": str(first), "second": str(second), **difference(a, b)}


def repeated(directory):
    firsts = sorted(directory.glob("*-a.png"))
    if not firsts:
        raise ValueError(f"No paired captures in {directory}")
    return [compare(first, directory / first.name.replace("-a.png", "-b.png")) for first in firsts]


def self_test():
    a = Image.new("RGBA", (2, 2), (0, 0, 0, 255))
    b = a.copy()
    assert difference(a, b)["pixels"] == 0
    b.putpixel((1, 0), (1, 0, 0, 255))
    assert difference(a, b)["bounds"] == (1, 0, 2, 1)
    assert difference(a, b)["pixels"] == 1
    b = a.copy()
    b.putpixel((0, 1), (0, 0, 0, 254))
    assert difference(a, b)["bounds"] == (0, 1, 1, 2)
    print("Capture comparison self-test passed (identical, RGB-only and alpha-only changes).")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path)
    parser.add_argument("--after", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.before is None or args.report is None:
        parser.error("--before and --report are required")
    results = {"before_repeats": repeated(args.before)}
    if args.after:
        before_names = {p.name for p in args.before.glob("*-?.png")}
        after_names = {p.name for p in args.after.glob("*-?.png")}
        if before_names != after_names:
            raise ValueError(f"Capture set mismatch: {sorted(before_names ^ after_names)}")
        results["after_repeats"] = repeated(args.after)
        results["before_after"] = [compare(args.before / name, args.after / name) for name in sorted(before_names)]
    comparisons = [item for group in results.values() for item in group]
    results["passed"] = all(item["pixels"] == 0 for item in comparisons)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    failures = [item for item in comparisons if item["pixels"]]
    print(f"{len(comparisons)} comparisons, {len(failures)} differ; {args.report}")
    for item in failures:
        print(f"{item['first']} -> {item['second']}: {item['pixels']} pixels, bounds {item['bounds']}")
    return 0 if results["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
