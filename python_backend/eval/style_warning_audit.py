"""Export every CI warning with a follow-up category; never waive gate checks."""
import argparse
import json
from collections import Counter
from pathlib import Path
from eval.golden_eval import evaluate_plan, promote_scenario_hard_checks

ROOT = Path(__file__).resolve().parents[2]


def audit():
    fixtures = ROOT/'python_backend/eval/fixtures'
    rows = []
    hard = []
    scenarios = json.loads((fixtures/'scenarios.json').read_text(encoding='utf-8'))
    count = 0
    for scenario in scenarios:
        if not scenario.get('ci', True):
            continue
        count += 1
        plan = json.loads((fixtures/scenario['plan_fixture']).read_text(encoding='utf-8'))
        result = evaluate_plan(plan, label=scenario['id'], prompt=scenario.get('prompt'))
        promote_scenario_hard_checks(result, scenario)
        types = {c.get('component_type','').upper() for c in plan.get('components',[])}
        opaque = bool(types) and types <= {'STRUCTURE','MODULE','ASSEMBLY','PRIMITIVE'}
        for check in result.hard_failures:
            hard.append(dict(scenario=scenario['id'], check=check.name, detail=check.detail))
        for check in result.soft_failures:
            if check.name in {'facade_params_valid','not_too_short','has_proportion_hints'}:
                category = 'parameter_or_fixture_defect'
                action = 'Check the consuming compiler and repair the authored fixture; retain regression coverage.'
            elif opaque and check.name in {'has_roof','has_windows','has_entrance','has_entrance_component','has_enclosure_mass','component_richness'}:
                category = 'route_specific_evidence_required'
                action = 'Top-level component tokens cannot inspect this route. Bind compiled geometry or explicit program applicability before any waiver.'
            else:
                category = 'composition_or_intent_review_required'
                action = 'Check the requested program against compiled geometry; missing access or enclosure may be a real defect.'
            rows.append(dict(scenario=scenario['id'],fixture=scenario['plan_fixture'],check=check.name,
                             category=category,status='unresolved',detail=check.detail,action=action))
    return dict(scenarios=count,hard_failures=hard,warning_count=len(rows),
                by_check=dict(sorted(Counter(row['check'] for row in rows).items())),
                by_category=dict(sorted(Counter(row['category'] for row in rows).items())),warnings=rows)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    report=audit()
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({key:value for key,value in report.items() if key!='warnings'},ensure_ascii=False))
    return int(bool(report['hard_failures']))


if __name__=='__main__':
    raise SystemExit(main())
