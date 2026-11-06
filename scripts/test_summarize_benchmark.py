import json
import unittest
from summarize_benchmark import summarize


def fixture():
    entries = []
    for name, key, count in [('startup', 'timeToFullDisplayMs', None),
                             ('firstRecommendation', 'meal.recommendSumMs', 'meal.recommendCount'),
                             ('switchRecommendation', 'meal.switchSumMs', 'meal.switchCount')]:
        metrics = {key: {'runs': list(range(1, 21))}}
        if count:
            metrics[count] = {'runs': [1] * 20}
        entries.append({'name': name, 'repeatIterations': 20, 'metrics': metrics,
                        'profilerOutputs': ['private-trace-fixture']})
    return {'benchmarks': entries, 'context': {'build': {'model': 'fixture', 'serial': 'private-serial-fixture',
                                                       'fingerprint': 'private-fingerprint-fixture', 'version': {'sdk': 36}}}}


class BenchmarkSummaryTest(unittest.TestCase):
    def test_percentiles_and_private_fields(self):
        result = summarize(fixture())
        self.assertEqual(19, result['results']['firstRecommendation']['p95_ms'])
        self.assertEqual(10, result['results']['firstRecommendation']['p50_ms'])
        self.assertNotIn('private-', json.dumps(result))

    def test_missing_scenario_and_short_runs_fail(self):
        data = fixture(); data['benchmarks'].pop()
        with self.assertRaises(ValueError): summarize(data)
        data = fixture(); data['benchmarks'][0]['metrics']['timeToFullDisplayMs']['runs'].pop()
        with self.assertRaises(ValueError): summarize(data)

    def test_missing_or_multiple_trace_requests_fail(self):
        for value in [0, 2]:
            data = fixture(); data['benchmarks'][1]['metrics']['meal.recommendCount']['runs'][0] = value
            with self.assertRaises(ValueError): summarize(data)

    def test_invalid_frame_percentile_fails(self):
        data = fixture()
        data['benchmarks'][1]['sampledMetrics'] = {'frameOverrunMs': {'P95': {'serial': 'private-fixture'}}}
        with self.assertRaises(ValueError): summarize(data)

    def test_invalid_samples_fail(self):
        for value in [float('nan'), float('inf'), -1, True]:
            data = fixture(); data['benchmarks'][2]['metrics']['meal.switchSumMs']['runs'][0] = value
            with self.assertRaises(ValueError): summarize(data)


if __name__ == '__main__': unittest.main()
