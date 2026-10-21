"""Match structured vision observations to authored style examples.

Scores describe feature overlap, never historical identity or source verification.
"""
import json
import re
from functools import lru_cache
from pathlib import Path

CATALOG_PATH = Path(__file__).resolve().parents[3] / 'src/main/resources/assets/formacraft/style_visual_cases/catalog_v1.json'


@lru_cache(maxsize=1)
def visual_case_catalog():
    from .user_style_packs import read_asset
    data = read_asset(CATALOG_PATH)
    if data.get('version') != 1:
        raise ValueError('Unsupported visual case catalog version')
    cases = data['cases']
    ids = [case['id'] for case in cases]
    if len(ids) != len(set(ids)):
        raise ValueError('Duplicate visual case IDs')
    return cases


def _contains(text, phrase):
    phrase = phrase.casefold().strip()
    if not phrase:
        return False
    pattern = re.escape(phrase).replace(r'\ ', r'\s+')
    if phrase.isascii():
        pattern = r'(?<![a-z0-9_])' + pattern + r'(?![a-z0-9_])'
    for match in re.finditer(pattern, text.casefold()):
        prefix = text.casefold()[max(0, match.start()-24):match.start()]
        if not re.search(r'(?:\b(?:no|not|without|lacks|lacking)(?:\s+visible)?\s*|没有|无|缺少|不含)\s*$', prefix):
            return True
    return False


def retrieve_visual_cases(observations, *, confidence, limit=3):
    """Require reliable image analysis and at least two distinct feature groups.

    Caption fallback, names, URLs and model style labels are deliberately excluded.
    Tied candidates remain suggestions rather than selecting an arbitrary style.
    """
    if not 0.6 <= confidence <= 1 or limit <= 0:
        return []
    text = '\n'.join(str(value) for value in observations)
    matches = []
    for case in visual_case_catalog():
        groups = case['feature_groups']
        matched = [name for name, phrases in groups.items() if any(_contains(text, p) for p in phrases)]
        if len(matched) < 2:
            continue
        matches.append(dict(case_id=case['id'], style_id=case['style_id'],
                            plan_fixture=case['plan_fixture'], matched_groups=matched,
                            score=round(len(matched) / len(groups), 4)))
    return sorted(matches, key=lambda item: (-item['score'], item['case_id']))[:limit]
