"""Separate user intent axes; observations do not override authored geometry."""
from __future__ import annotations
import re

from .style_identity import identity_catalog, style_identity

STYLE_ALIASES = identity_catalog()['lexical_aliases']
STYLE_ALIASES = {**STYLE_ALIASES, **{key: () for key in identity_catalog()['variants']}}
PURPOSE_ALIASES = {
    'residential': ('住宅', '民居', '别墅'), 'library': ('图书馆',),
    'hotel': ('旅馆', '酒店'), 'commercial': ('商店', '商铺', '商业建筑'),
    'industrial': ('工厂', '仓库'), 'religious': ('教堂', '寺庙', '神社'),
    'civic_hall': ('公共大厅', '市政厅'), 'education': ('学校', '教学楼'),
    'fortification': ('城堡', '堡垒'), 'sports': ('体育场', '体育馆'),
}
STRUCTURE_ALIASES = {
    'courtyard': ('庭院式', '四合院'), 'frame': ('框架结构', '钢框架', '木框架'),
    'arch': ('拱券结构', '拱券承重'), 'stilt': ('吊脚', '架空结构'),
    'tower': ('塔楼',), 'load_bearing_wall': ('承重墙',),
}

def _observations(text: str, aliases: dict) -> list[dict]:
    found = []
    candidates = [(alias, key) for key, values in aliases.items() for alias in (key, *values)]
    candidates.sort(key=lambda item: (-len(item[0]), item[0]))
    occupied = set()
    for alias, identity in candidates:
        pattern = re.escape(alias)
        if alias.isascii(): pattern = r'(?<![a-z0-9_])' + pattern + r'(?![a-z0-9_])'
        for match in re.finditer(pattern, text, re.I):
            span = set(range(match.start(), match.end()))
            if occupied & span: continue
            occupied |= span
            prefix = re.sub(r'\s+', '', text[max(0, match.start()-12):match.start()])
            excluded = bool(re.search(r'(不要|不采用|不使用|禁止|避免)(采用|使用)?$', prefix))
            part = re.search(r'(屋顶|立面|外墙|室内|主体)(?:采用|使用|为)?$', prefix)
            found.append({'id': identity, 'phrase': match.group(), 'start': match.start(),
                          'status': 'excluded' if excluded else 'requested',
                          'part': part.group(1) if part else 'building'})
    return sorted(found, key=lambda item: (item['start'], item['id']))

def describe_architecture(text: str) -> dict:
    # Labels are deterministic user scopes, not guesses about generated component order.
    original_text = text or ''
    text = re.sub(r'\s+', ' ', original_text)
    while re.search(r'(?<=[\u3400-\u9fff])\s+(?=[\u3400-\u9fff])',text):
        text = re.sub(r'(?<=[\u3400-\u9fff])\s+(?=[\u3400-\u9fff])','',text)
    markers = list(re.finditer(r'左栋|右栋|第[一二三四五六七八九十\d]+栋', text))
    segments = [('global', text[:markers[0].start()] if markers else text)]
    for index, match in enumerate(markers):
        end = markers[index+1].start() if index+1 < len(markers) else len(text)
        segments.append((match.group(), text[match.end():end]))
    scopes = []
    for scope, phrase in segments:
        scopes.append({'scope': scope, 'text': phrase,
                       'purposes': _observations(phrase, PURPOSE_ALIASES),
                       'styles': _observations(phrase, STYLE_ALIASES),
                       'structures': _observations(phrase, STRUCTURE_ALIASES)})
    return {'schema': 'formacraft.architecture_intent.v1', 'stage': 'request_observation',
            'precedence': ['explicit_request', 'purpose_rules', 'style_defaults', 'material_defaults'],
            'style_identities': [style_identity(v['id']) for scope in scopes for v in scope['styles']],
            'scopes': scopes, 'original_text': original_text, 'scope_binding': 'lexical_not_component_binding',
            'unrecognized_text_preserved': True,
            'limits': ['Alias coverage is finite.', 'Observations do not certify generated style or functional rooms.']}
