import copy
import unittest
from analyze_trial import analyze


def fixture():
    sessions = []
    for i, method in enumerate(['RANDOM', 'SIMPLE', 'PERSONALIZED']):
        sessions.append({'sessionId': f'10000000-0000-0000-0000-{i:012d}', 'ordinal': i, 'strategy': method,
                         'dayIndex': 0, 'mealPeriod': 'lunch', 'catalogHash': 'a'*64, 'learningEnabled': True,
                         'appVersion': 'fixture-v1', 'candidateCount': 100, 'historyCount': 0,
                         'presentations': [{'foodId': 'food-001', 'elapsedMs': 10, 'historyCount': 0, 'algorithmVersion': 'rules-v1', 'appVersion': 'fixture-v1'}],
                         'endReason': 'IDLE' if i == 0 else 'ADOPTED', 'selectedFoodId': None if i == 0 else 'food-001',
                         'selectedIndex': None if i == 0 else 0, 'elapsedMs': 30, 'confirmedEaten': None})
    return {'appVersion': 'fixture-v1', 'formatVersion': 1, 'protocol': 'local-crossover-v1', 'participantId': '20000000-0000-0000-0000-000000000000', 'sessions': sessions}


class TrialAnalysisTest(unittest.TestCase):
    def test_idle_and_execution_silence_stay_missing(self):
        result = analyze([fixture()]); methods = result['strata'][0]['methods']
        self.assertEqual(1, methods['RANDOM']['missing_choice_end_reasons']['IDLE'])
        self.assertEqual(0, methods['RANDOM']['explicit_adoptions'])
        self.assertEqual(1, methods['PERSONALIZED']['execution_feedback_missing'])
        self.assertEqual(0, methods['PERSONALIZED']['confirmed_eaten_yes'])
        self.assertEqual(1, result['participant_count'])

    def test_duplicate_files_do_not_inflate_people_or_sessions(self):
        data = fixture(); result = analyze([data, copy.deepcopy(data)])
        self.assertEqual(1, result['participant_count'])
        self.assertEqual(1, result['strata'][0]['methods']['SIMPLE']['assigned_sessions'])
        changed = copy.deepcopy(data); changed['sessions'][0]['endReason'] = 'MANUAL'
        with self.assertRaises(ValueError): analyze([data, changed])

    def test_unbalanced_assignments_and_fabricated_adoption_are_rejected(self):
        data = fixture(); data['sessions'][1]['strategy'] = 'RANDOM'
        with self.assertRaises(ValueError): analyze([data])
        data = fixture(); data['sessions'][0]['selectedIndex'] = 0
        with self.assertRaises(ValueError): analyze([data])
        data = fixture(); data['sessions'][1]['confirmedEaten'] = False
        with self.assertRaises(ValueError): analyze([data])

    def test_changed_catalogs_are_not_pooled(self):
        data = fixture(); data['sessions'][2]['catalogHash'] = 'b'*64
        result = analyze([data]); self.assertEqual(2, len(result['strata']))
        self.assertTrue(all(p['within_participant_recording_rate_differences'] is None
                            for stratum in result['strata'] for p in stratum['per_participant']))

    def test_changed_app_versions_are_not_pooled(self):
        data = fixture(); data['sessions'][2]['appVersion'] = 'fixture-v2'
        data['sessions'][2]['presentations'][0]['appVersion'] = 'fixture-v2'
        self.assertEqual(2, len(analyze([data])['strata']))

    def test_invalid_time_and_duplicate_session_fail(self):
        data = fixture(); data['sessions'][0]['elapsedMs'] = 1
        with self.assertRaises(ValueError): analyze([data])
        data = fixture(); data['sessions'].append(copy.deepcopy(data['sessions'][0]))
        with self.assertRaises(ValueError): analyze([data])
        data = fixture(); data['sessions'][0]['presentations'][0]['elapsedMs'] = True
        with self.assertRaises(ValueError): analyze([data])
