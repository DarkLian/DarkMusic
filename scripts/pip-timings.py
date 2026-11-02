"""Summarize one isolated PiP trace run. Standard library only; durations are milliseconds."""
import argparse
import csv
import json
import math
import tempfile
from pathlib import Path


def percentiles(values):
    values = sorted(values)
    if not values:
        return None
    def at(p):
        index = (len(values) - 1) * p
        lo = int(index)
        return values[lo] + (values[math.ceil(index)] - values[lo]) * (index - lo)
    return {f'p{p}': at(p / 100) for p in (50, 95, 99)}


def rate(rows, field, units):
    times = [int(r[field]) for r in rows]
    return (len(times) - 1) * units / (max(times) - min(times)) if len(times) > 1 and max(times) > min(times) else None


def summarize(folder):
    java_files = list(folder.glob('java-*.csv'))
    native_files = list(folder.glob('native-*.csv'))
    if len(java_files) != 1 or len(native_files) != 1:
        raise ValueError('Use one isolated run directory containing exactly one Java and native trace')
    with java_files[0].open() as f:
        java = list(csv.DictReader(f))
    with native_files[0].open() as f:
        native = list(csv.DictReader(f))
    window_file = folder / 'window.json'
    window = json.loads(window_file.read_text()) if window_file.exists() else None
    java_dropped = sum(int(r['sequence']) for r in java if r['stage'] == 'dropped')
    native_dropped = sum(int(r['value']) for r in native if r['stage'] == 'summary')
    if window:
        start_us, end_us = window['startMicros'], window['endMicros']
        java = [r for r in java if start_us <= int(r['unixMicros']) <= end_us]
        clock = next((r for r in native if r['stage'] == 'clock'), None)
        if not clock:
            raise ValueError('Native clock record missing')
        native = [r for r in native if start_us <= int(clock['source100ns']) +
                  (int(r['qpc']) - int(clock['qpc'])) * 1e6 / int(clock['value']) <= end_us]
    stages = {stage: [r for r in java if r['stage'] == stage] for stage in ('receive', 'upload_submit', 'display', 'present')}
    display, present = stages['display'], stages['present']
    sequences = [int(r['sequence']) for r in present]
    # Sequence numbers restart on a new capture session. Never count a restart as a skip.
    gaps = [b - a for a, b in zip(sequences, sequences[1:])]
    intervals = [(int(b['nanos']) - int(a['nanos'])) / 1e6 for a, b in zip(display, display[1:]) if int(b['sequence']) > int(a['sequence'])]
    frames = []
    hidden_frames = []
    start = min((int(r['nanos']) for r in present), default=0)
    end = max((int(r['nanos']) for r in present), default=0)
    render_file = folder / 'render.jsonl'
    if render_file.exists():
        for line in render_file.read_text().splitlines():
            row = json.loads(line)
            if row.get('kind') == 'render' and row.get('gapMicros', 0) > 0:
                if start <= row['nanos'] <= end:
                    frames.append(row['gapMicros'] / 1000)
                elif row['nanos'] > end + 3_000_000_000:
                    hidden_frames.append(row['gapMicros'] / 1000)
    arrivals = sum(r['stage'] == 'arrival' for r in native)
    return {
        'window': window,
        'fps': {stage: rate(rows, 'nanos', 1e9) for stage, rows in stages.items() if stage != 'present'},
        'display_interval_ms': percentiles(intervals),
        'capture_to_display_ms': percentiles([(int(r['unixMicros']) - int(r['capturedMicros'])) / 1000 for r in display]),
        'update_ms': percentiles([int(r['durationNanos']) / 1e6 for r in present]),
        'repeated': gaps.count(0), 'skipped': sum(max(0, g - 1) for g in gaps),
        'helper_ms': {stage: percentiles([int(r['value']) / 1000 for r in native if r['stage'] == stage]) for stage in ('readback', 'convert', 'transfer')},
        'staging_dropped': sum(int(r['value']) for r in native if r['stage'] == 'staging_drop'),
        'busy_dropped_percent': 100 * sum(r['stage'] == 'busy' for r in native) / arrivals if arrivals else None,
        'minecraft_frame_ms': percentiles(frames),
        'minecraft_hidden_frame_ms': percentiles(hidden_frames),
        'java_trace_dropped': java_dropped,
        'native_trace_dropped': native_dropped,
        'clock_step_warning': any(int(r['unixMicros']) < int(r['capturedMicros']) for r in display),
    }


def self_test():
    with tempfile.TemporaryDirectory() as directory:
        folder = Path(directory)
        (folder / 'java-1.csv').write_text(
            'stage,sequence,nanos,capturedMicros,sentMicros,durationNanos,unixMicros\n'
            'receive,1,1000000,100,200,0,300\n'
            'receive,3,3000000,2100,2200,0,2300\n'
            'upload_submit,1,1000000,100,200,1000,300\n'
            'display,1,1000000,100,200,1000,300\n'
            'display,3,3000000,2100,2200,2000,2300\n'
            'present,1,1000000,100,200,1000,300\n'
            'present,1,2000000,100,200,1000,1300\n'
            'present,3,3000000,2100,2200,2000,2300\n')
        (folder / 'native-1.csv').write_text(
            'stage,callback,sequence,qpc,source100ns,value\n'
            'arrival,1,0,0,0,0\narrival,2,0,0,0,0\n'
            'busy,2,0,0,0,0\nreadback,1,0,0,0,100\n')
        result = summarize(folder)
        assert result['repeated'] == 1 and result['skipped'] == 1
        assert result['fps']['receive'] == 500
        assert result['display_interval_ms']['p99'] == 2
        assert result['capture_to_display_ms']['p50'] == 0.2
        assert result['busy_dropped_percent'] == 50
        assert result['helper_ms']['readback']['p95'] == 0.1
        assert result['minecraft_frame_ms'] is None
    print('PiP timing self-test passed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path, nargs='?')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if not args.folder:
        parser.error('folder is required')
    print(json.dumps(summarize(args.folder), indent=2, allow_nan=False))


if __name__ == '__main__':
    main()
