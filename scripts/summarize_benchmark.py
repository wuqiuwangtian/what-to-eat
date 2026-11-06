#!/usr/bin/env python3
"""校验完整Macrobenchmark结果，输出只含白名单字段的可公开摘要；不导出原始跟踪。"""
import argparse
import hashlib
import json
import math
from pathlib import Path

SCENARIOS = {
    'startup': ('timeToFullDisplayMs', None, 2000),
    'firstRecommendation': ('meal.recommendSumMs', 'meal.recommendCount', 500),
    'switchRecommendation': ('meal.switchSumMs', 'meal.switchCount', 200),
}


def samples(metric, minimum, *, nonnegative=True):
    values = metric.get('runs')
    if not isinstance(values, list) or len(values) < minimum:
        raise ValueError(f'Need at least {minimum} samples')
    if any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v)
           or (nonnegative and v < 0) for v in values):
        raise ValueError('Invalid numeric samples')
    return values


def summarize(raw, minimum=20):
    entries = raw.get('benchmarks', [])
    by_name = {entry['name']: entry for entry in entries}
    if len(by_name) != len(entries) or set(by_name) != set(SCENARIOS):
        raise ValueError('Require exactly startup, firstRecommendation and switchRecommendation')
    results = {}
    for name, (key, count_key, target) in SCENARIOS.items():
        entry = by_name[name]
        values = samples(entry.get('metrics', {}).get(key, {}), minimum)
        if entry.get('repeatIterations') != len(values):
            raise ValueError('Iteration count does not match samples')
        if count_key:
            counts = samples(entry['metrics'].get(count_key, {}), minimum)
            if len(counts) != len(values) or any(v != 1 for v in counts):
                raise ValueError('Every measured iteration must contain exactly one completed request')
        ordered = sorted(values)
        p95 = ordered[math.ceil(len(values) * .95) - 1]
        result = {'metric': key, 'samples_ms': values, 'sample_count': len(values),
                  'p50_ms': ordered[math.ceil(len(values) * .5) - 1], 'p95_ms': p95,
                  'max_ms': ordered[-1], 'target_ms': target, 'within_defined_target': p95 <= target}
        frame = entry.get('sampledMetrics', {}).get('frameOverrunMs')
        if frame:
            p95_frame = frame.get('P95')
            if isinstance(p95_frame, bool) or not isinstance(p95_frame, (int, float)) or not math.isfinite(p95_frame):
                raise ValueError('Invalid frame percentile')
            result['frame_overrun_p95_ms'] = p95_frame
        results[name] = result
    context = raw.get('context', {})
    build = context.get('build', {})
    return {
        'format_version': 1,
        'device': {'model': build.get('model'), 'sdk': build.get('version', {}).get('sdk')},
        'compilation_mode': context.get('compilationMode'),
        'percentile_method': 'nearest rank',
        'fixture': '1000 synthetic events reset outside every measured iteration',
        'definitions': {
            'startup': 'Android StartupTimingMetric TTFD; ready when local settings and Room events are loaded',
            'recommendation': 'app click callback to second frame boundary after result composition',
        },
        'limitations': ['One phone and one compilation mode; not a universal Android performance claim',
                        'Hot-path trace excludes physical touch dispatch and display scanout',
                        'Frame overrun is a separate frame distribution, not request latency'],
        'results': results,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--target-apk', type=Path, required=True)
    parser.add_argument('--runner-apk', type=Path, required=True)
    args = parser.parse_args()
    report = summarize(json.loads(args.input.read_text()))
    report['source_sha256'] = hashlib.sha256(args.input.read_bytes()).hexdigest()
    report['target_apk_sha256'] = hashlib.sha256(args.target_apk.read_bytes()).hexdigest()
    report['runner_apk_sha256'] = hashlib.sha256(args.runner_apk.read_bytes()).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    for name, result in report['results'].items():
        print(f'{name}: P95={result["p95_ms"]:.1f} ms; defined target={result["target_ms"]} ms')


if __name__ == '__main__':
    main()
