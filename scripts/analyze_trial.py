#!/usr/bin/env python3
"""离线汇总local-crossover-v1导出；按参与者和菜品版本分层，不推断缺失反馈或总体优越性。"""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import statistics
import uuid

STRATEGIES = ('RANDOM', 'SIMPLE', 'PERSONALIZED')
ENDINGS = {None, 'ADOPTED', 'MANUAL', 'IDLE', 'WITHDRAWN', 'CONTEXT_CHANGED', 'NO_CANDIDATE'}


def integer(value, name):
    if type(value) is not int or value < 0:
        raise ValueError(f'Invalid {name}')
    return value


def validate(data):
    if type(data.get('formatVersion')) is not int or data.get('formatVersion') != 1 or data.get('protocol') != 'local-crossover-v1':
        raise ValueError('Unsupported export protocol')
    uuid.UUID(data['participantId'])
    if not isinstance(data.get('appVersion'), str) or not data['appVersion']:
        raise ValueError('Missing application version')
    sessions = data['sessions']
    if not isinstance(sessions, list):
        raise ValueError('Sessions must be a list')
    seen_ids, seen_ordinals, blocks = set(), set(), defaultdict(list)
    for s in sessions:
        uuid.UUID(s['sessionId'])
        ordinal = integer(s['ordinal'], 'ordinal')
        if s['sessionId'] in seen_ids or ordinal in seen_ordinals:
            raise ValueError('Duplicate session or ordinal')
        seen_ids.add(s['sessionId']); seen_ordinals.add(ordinal)
        if s['strategy'] not in STRATEGIES or s['endReason'] not in ENDINGS:
            raise ValueError('Unknown strategy or end reason')
        blocks[ordinal // 3].append(s['strategy'])
        if not isinstance(s['catalogHash'], str) or len(s['catalogHash']) != 64 or any(c not in '0123456789abcdef' for c in s['catalogHash']):
            raise ValueError('Invalid catalog hash')
        integer(s['candidateCount'], 'candidateCount'); integer(s['historyCount'], 'historyCount')
        integer(s['dayIndex'], 'dayIndex')
        if type(s['learningEnabled']) is not bool or s['mealPeriod'] not in {'breakfast', 'lunch', 'dinner', 'other'}:
            raise ValueError('Invalid session context')
        if not isinstance(s.get('appVersion'), str) or not s['appVersion']:
            raise ValueError('Missing session application version')
        shown = s['presentations']
        if not isinstance(shown, list):
            raise ValueError('Invalid presentation sequence')
        previous = 0
        for p in shown:
            elapsed = integer(p['elapsedMs'], 'presentation time')
            if elapsed < previous or not isinstance(p['foodId'], str) or not p['foodId'] or p['algorithmVersion'] != 'rules-v1':
                raise ValueError('Invalid presentation or clock sequence')
            if p.get('appVersion') != s['appVersion']:
                raise ValueError('Mixed application versions within one session')
            integer(p['historyCount'], 'presentation history count'); previous = elapsed
        if s['endReason'] is None:
            if s['elapsedMs'] is not None:
                raise ValueError('Open session cannot have completion time')
        elif integer(s['elapsedMs'], 'session elapsed time') < previous:
            raise ValueError('Session ends before its presentation')
        if s['endReason'] == 'ADOPTED':
            index = integer(s['selectedIndex'], 'selectedIndex')
            if not shown or index != len(shown) - 1 or s['selectedFoodId'] != shown[index]['foodId']:
                raise ValueError('Adoption must identify the last displayed candidate')
        elif s['selectedIndex'] is not None or s['selectedFoodId'] is not None:
            raise ValueError('Missing choice must not contain inferred adoption')
        if s['confirmedEaten'] is not None and (s['confirmedEaten'] is not True or s['endReason'] != 'ADOPTED'):
            raise ValueError('Missing eating feedback must stay null')
    for methods in blocks.values():
        if len(set(methods)) != len(methods):
            raise ValueError('Allocation block is not balanced')
    return sessions


def describe(sessions):
    exposed = [s for s in sessions if s['presentations']]
    adopted = [s for s in exposed if s['endReason'] == 'ADOPTED']
    first = sum(s['selectedIndex'] == 0 for s in adopted)
    missing = [s for s in exposed if s['endReason'] != 'ADOPTED']
    return {
        'assigned_sessions': len(sessions), 'exposed_sessions': len(exposed),
        'unexposed_sessions': len(sessions) - len(exposed),
        'explicit_adoptions': len(adopted), 'first_choice_adoptions': first,
        'missing_choice_sessions': len(missing),
        'missing_choice_end_reasons': dict(Counter(s['endReason'] or 'OPEN' for s in missing)),
        'explicit_adoption_recording_rate': len(adopted) / len(exposed) if exposed else None,
        'first_choice_recording_rate': first / len(exposed) if exposed else None,
        'median_switches_when_adopted': statistics.median(len(s['presentations']) - 1 for s in adopted) if adopted else None,
        'median_decision_ms_when_adopted': statistics.median(s['elapsedMs'] for s in adopted) if adopted else None,
        'confirmed_eaten_yes': sum(s['confirmedEaten'] is True for s in adopted),
        'execution_feedback_missing': sum(s['confirmedEaten'] is None for s in adopted),
    }


def analyze(exports):
    participants = {}
    for data in exports:
        validate(data)
        participant = data['participantId']
        if participant in participants and participants[participant] != data:
            raise ValueError('Different exports for one participant; use only the latest complete export')
        participants[participant] = data  # 完全相同的重复文件不增加样本数。
    strata = defaultdict(lambda: defaultdict(list))
    for label, (_, data) in enumerate(sorted(participants.items()), 1):
        for s in data['sessions']:
            strata[(s['catalogHash'], s['appVersion'])][f'participant-{label}'].append(s)
    results = []
    for (catalog, app_version), people in sorted(strata.items()):
        per_person = []
        for person, sessions in people.items():
            methods = {method: describe([s for s in sessions if s['strategy'] == method]) for method in STRATEGIES}
            paired = None
            if all(methods[m]['exposed_sessions'] for m in STRATEGIES):
                paired = {f'PERSONALIZED_minus_{m}': methods['PERSONALIZED']['explicit_adoption_recording_rate'] - methods[m]['explicit_adoption_recording_rate'] for m in ['RANDOM', 'SIMPLE']}
            per_person.append({'participant': person, 'methods': methods, 'within_participant_recording_rate_differences': paired})
        results.append({'catalog_hash': catalog, 'app_version': app_version, 'participant_count': len(people),
                        'methods': {m: describe([s for sessions in people.values() for s in sessions if s['strategy'] == m]) for m in STRATEGIES},
                        'per_participant': per_person})
    return {'format_version': 1, 'protocol': 'local-crossover-v1', 'participant_count': len(participants), 'strata': results,
            'definitions': {'rate': 'Recorded explicit choices / exposed sessions; missing feedback stays missing, not inferred satisfaction or dissatisfaction',
                            'switches': 'Number of displayed candidates minus one within the session',
                            'execution': 'Only explicit EATEN confirmation is yes; no confirmation is null'},
            'limitations': ['Exploratory descriptive results; no population superiority or significance claim',
                            'Sessions from one participant are correlated; clicks are not independent people',
                            'Shared history can carry learning across methods; wall-clock changes can affect elapsed times']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('exports', type=Path, nargs='+')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = analyze([json.loads(path.read_text()) for path in args.exports])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(f'Analyzed {report["participant_count"]} participants in {len(report["strata"])} catalog strata; descriptive results only.')


if __name__ == '__main__':
    main()
