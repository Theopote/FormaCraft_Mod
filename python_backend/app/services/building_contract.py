"""Versioned, user-owned requirements and component identity for the existing LlmPlan route."""
from __future__ import annotations

from copy import deepcopy
import re
from typing import Any

SCHEMA = 'formacraft.building_contract.v1'
LEGACY_ENTRANCE_FACING = {'NORTH': 'SOUTH', 'SOUTH': 'NORTH', 'EAST': 'WEST', 'WEST': 'EAST'}
NUMBERS = {'一': 1, '二': 2, '两': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9, '十': 10}
HOSTED = {'ROOF', 'ROOF_STRUCTURE', 'FOUNDATION', 'FACADE_WINDOWS', 'FACADE', 'ENTRANCE',
          'CROWN', 'CUPOLA', 'DOME', 'MASS_SECONDARY', 'DECOR_DETAIL', 'BALCONY', 'TERRACE', 'STRUCTURE', 'ASSEMBLY'}

NAMED_BUILDINGS = {
    'main_body': ('主楼', 'main_body', 'main_building'),
    'keep': ('主堡', 'keep'),
    # Parity with Java UserConstraintContract.NAMED_BUILDING_ALIASES (incl. pagoda).
    'tower': ('塔楼', '钟楼', 'pagoda', 'tower'),
    'office': ('办公楼', 'office'),
    'workshop': ('主车间', '维修车间', 'workshop'),
    'boiler': ('锅炉房', 'boiler'),
    'main_hall': ('正厅', '中央大厅', '主殿', '正殿', '大殿', 'main_hall', 'townhall_main'),
    'pavilion': ('凉亭', '亭子', '小亭', '水上亭', '水榭', '八角亭', '六角亭', '方亭', 'garden_pavilion', 'pavilion'),
    'wing': ('厢房', '侧翼', 'east_wing', 'west_wing'),
    'gatehouse': ('门楼', 'gatehouse'),
    'mill': ('磨坊', 'mill'),
    'warehouse': ('仓库', 'warehouse'),
    'reception': ('接待楼', 'reception'),
    'research': ('研究楼', 'research'),
    'dispatch': ('调度楼', 'dispatch'),
    'residence': ('居住楼', 'residence'),
    'chapel': ('礼拜堂', 'chapel'),
}

ELEMENT_SUBJECTS = ('楼梯', '步道', '走道', '连廊', '木桥', '水渠', '栏杆', '阳台', '回廊', '窗口', '窗户', '入口', '门洞')

# Building-level design-constraint hosts — parity with Java BuildingHostResolver.isDesignConstraintHost.
_DESIGN_HOST_MASS_TYPES = (
    'MASS_MAIN', 'MASS_SECONDARY', 'MASS_WING', 'SIDE_WING', 'MAIN_MASS',
    'MASS_ANCILLARY', 'TOWER', 'GATE', 'HALL',
)


def _has_building_identity_evidence(comp: dict[str, Any]) -> bool:
    """MASS_ANCILLARY / multipurpose volumes need explicit use/name evidence.

    Do not scan ``component_id`` — corridor ids like ``corridor_office_workshop`` embed
    neighboring building names and must not become whole-building hosts.
    """
    params = comp.get('params') if isinstance(comp.get('params'), dict) else {}
    scope = str(params.get('requirement_scope') or '').strip().lower()
    if scope.startswith('named_'):
        return True
    blob = ' '.join(
        str(params.get(k) or '').strip().lower()
        for k in ('building_role', 'buildingRole', 'part', 'label')
        if params.get(k) is not None and str(params.get(k)).strip()
    )
    if not blob:
        return False
    for names in NAMED_BUILDINGS.values():
        if any(name and str(name).lower() in blob for name in names):
            return True
    return False


def is_design_constraint_host(comp: dict[str, Any] | None) -> bool:
    """Whole-building constraint host identity (height / roof / window / entrance).

    Classification: HARD_CONSTRAINT host inventory — excludes plates, attached
    satellites, and bare STRUCTURE accessories. Independent typology STRUCTURE
    shells are allowed. MASS_ANCILLARY requires building-identity evidence so
    corridors/quays do not become whole-building hosts. Does not reclassify
    geometry generation types.
    """
    if not isinstance(comp, dict):
        return False
    ctype = str(comp.get('component_type') or '').strip().upper()
    params = comp.get('params') if isinstance(comp.get('params'), dict) else {}
    if str(params.get('extrude_mode') or '').strip().lower() == 'plate':
        return False
    host = params.get('host_id')
    if host is None:
        host = params.get('hostId')
    if host is not None and str(host).strip() and str(host).strip().lower() != 'null':
        return False
    if ctype == 'MASS_ANCILLARY':
        return _has_building_identity_evidence(comp)
    if ctype.startswith('MASS') or ctype in _DESIGN_HOST_MASS_TYPES:
        return True
    if ctype == 'STRUCTURE':
        features = comp.get('features') if isinstance(comp.get('features'), list) else []
        blob = ' '.join(str(f or '').lower() for f in features)
        if any(
            tok in blob
            for tok in ('stair', 'bridge', 'fence', 'railing', 'path', 'column', 'pillar', 'porch')
        ):
            return False
        if any(str(f or '').lower().startswith('typology:') for f in features):
            return True
        tid = params.get('typology_id') or params.get('typologyId') or params.get('structural_typology')
        return bool(tid and str(tid).strip())
    return False


def design_constraint_hosts(components: list | None) -> list[dict[str, Any]]:
    """Design-constraint host list shared by contract validation and normalization."""
    if not isinstance(components, list):
        return []
    return [c for c in components if isinstance(c, dict) and is_design_constraint_host(c)]


# Properties whose all_main_masses / plan scope must cover the full design-host inventory
# (parity with Java RequestIntentInjector seals). Dimensional / material envelope stays MASS_MAIN.
_DESIGN_HOST_PROPERTIES = frozenset({
    'roof_type', 'roof_block', 'window_style', 'entrance_type',
})


def _is_typology_structure(comp: dict[str, Any] | None) -> bool:
    """STRUCTURE shell with explicit typology identity (native roof params path)."""
    return bool(_typology_id(comp))


def _typology_id(comp: dict[str, Any] | None) -> str:
    """Normalized typology id for STRUCTURE shells; empty when not a typology host."""
    if not isinstance(comp, dict):
        return ''
    if str(comp.get('component_type') or '').strip().upper() != 'STRUCTURE':
        return ''
    features = comp.get('features') if isinstance(comp.get('features'), list) else []
    for feat in features:
        text = str(feat or '').strip()
        if text.lower().startswith('typology:'):
            return text.split(':', 1)[1].strip().lower()
    params = comp.get('params') if isinstance(comp.get('params'), dict) else {}
    tid = params.get('typology_id') or params.get('typologyId') or params.get('structural_typology')
    return str(tid).strip().lower() if tid and str(tid).strip() else ''


# Typologies whose positive roof forms are owned by chinese_roof_contract (not snake_case).
_CHINESE_ROOF_CONTRACT_TYPOLOGIES = frozenset({'tailiang_timber_hall'})

# Parity with Java TypologyRoofCapability — param equality is never proof of support.
_CHINESE_HALL_ROOF_FORMS = frozenset({
    'xieshan', 'xie_shan', '歇山',
    'xuanshan', 'xuan_shan', '悬山',
    'yingshan', 'ying_shan', '硬山',
    'wudian', 'wu_dian', '庑殿',
    'double_eave_xieshan', 'double_eave_xie_shan', '重檐歇山',
})


def _defers_positive_roof_to_chinese_contract(comp: dict[str, Any] | None) -> bool:
    return _typology_id(comp) in _CHINESE_ROOF_CONTRACT_TYPOLOGIES


def typology_supports_roof_type(typology_id: str | None, roof_type: str | None) -> bool | None:
    """Return True/False for known typologies; None when not a typology STRUCTURE concern."""
    tid = str(typology_id or '').strip().lower()
    roof = str(roof_type or '').strip().lower()
    if not tid or not roof:
        return None
    if tid == 'dense_eaves_pagoda':
        # Intrinsic eaves/finial are not plan roof_type modes (including omit).
        return False
    if tid == 'tailiang_timber_hall':
        if roof == 'none':
            return True
        return roof in _CHINESE_HALL_ROOF_FORMS or 'double_eave' in roof
    if tid in ('courtyard_compound', 'gothic_cathedral_hall'):
        return False
    return False


def _stacked_family_key(component_id: str | None) -> str | None:
    """pagoda_tier_3 → pagoda; non-tier ids → None."""
    cid = str(component_id or '').strip()
    if not cid:
        return None
    match = re.match(r'^(.*?)(?:_tier_?|_level_?|_storey_?)(\d+)$', cid, flags=re.IGNORECASE)
    if not match:
        return None
    stem = match.group(1).strip()
    return stem.lower() if stem else None


# Role-class scopes that legitimately bind multiple masses of one compound (west+east wings).
_PLURAL_NAMED_KEYS = frozenset({'wing'})

# Local wing side cues — keep plural named_wing, but never expand 西厢房 to east wing.
_WING_SIDE_PATTERNS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ('west', (r'西(?:厢|翼|侧)', r'west[_\s-]?wing', r'\bwest\b', r'左厢')),
    ('east', (r'东(?:厢|翼|侧)', r'east[_\s-]?wing', r'\beast\b', r'右厢')),
    ('south', (r'南(?:厢|翼|侧)', r'south[_\s-]?wing', r'\bsouth\b')),
    ('north', (r'北(?:厢|翼|侧)', r'north[_\s-]?wing', r'\bnorth\b')),
)
_WING_SIDE_HOST_TOKENS: dict[str, tuple[str, ...]] = {
    'west': ('west', '西', '左厢', 'west_wing', 'wing_west', 'westwing'),
    'east': ('east', '东', '右厢', 'east_wing', 'wing_east', 'eastwing'),
    'south': ('south', '南', 'south_wing', 'wing_south', 'southwing'),
    'north': ('north', '北', 'north_wing', 'wing_north', 'northwing'),
}


def _wing_sides_from_text(text: str | None) -> list[str]:
    """Return every west/east/south/north cue in the local clause (joint subjects keep both)."""
    raw = str(text or '').strip()
    if not raw:
        return []
    sides: list[str] = []
    for side, patterns in _WING_SIDE_PATTERNS:
        for pat in patterns:
            if re.search(pat, raw, flags=re.IGNORECASE):
                if side not in sides:
                    sides.append(side)
                break
    return sides


def _wing_side_from_text(text: str | None) -> str | None:
    """Return the sole wing side when exactly one is present; else None."""
    sides = _wing_sides_from_text(text)
    return sides[0] if len(sides) == 1 else None


def _wing_sides_from_texts(texts: Any) -> list[str]:
    if isinstance(texts, (list, tuple)):
        found: list[str] = []
        for item in texts:
            for side in _wing_sides_from_text(str(item) if item is not None else ''):
                if side not in found:
                    found.append(side)
        return found
    return _wing_sides_from_text(str(texts) if texts is not None else '')


def _wing_side_from_texts(texts: Any) -> str | None:
    sides = _wing_sides_from_texts(texts)
    return sides[0] if len(sides) == 1 else None


def _span_clause_window(text: str | None, start: int | None, end: int | None) -> str:
    """Expand a match span to the surrounding clause so 西厢房 is visible beside 不要屋顶.

    Commas/顿号 bound sibling clauses (parity with Java omitClauseWindow / _roof_clause_window)
    so 「西厢房不要屋顶，东厢房不要屋顶」keeps independent side cues.
    """
    raw = str(text or '')
    if not raw:
        return ''
    try:
        s = int(start) if start is not None else 0
        e = int(end) if end is not None else len(raw)
    except (TypeError, ValueError):
        return raw
    s = max(0, min(len(raw), s))
    e = max(s, min(len(raw), e))
    bounds = '。；！？\n\r.!?;，,、'
    left = s
    while left > 0 and raw[left - 1] not in bounds:
        left -= 1
    right = e
    while right < len(raw) and raw[right] not in bounds:
        right += 1
    return raw[left:right]


def _requirement_clause_text(requirement: dict[str, Any] | None, full_text: str | None) -> str:
    """Prefer clause window around match_span; fall back to source_text fragments."""
    req = requirement if isinstance(requirement, dict) else {}
    span = req.get('match_span')
    if isinstance(span, (list, tuple)) and len(span) >= 2 and full_text:
        window = _span_clause_window(full_text, span[0], span[1])
        if window.strip():
            return window
    texts = req.get('source_text')
    if isinstance(texts, (list, tuple)):
        return ' '.join(str(x) for x in texts if x)
    return str(texts or '')


def _host_matches_wing_side(comp: dict[str, Any] | None, side: str) -> bool:
    blob = _host_identity_blob(comp)
    tokens = _WING_SIDE_HOST_TOKENS.get(str(side or '').strip().lower(), ())
    return any(tok and tok.lower() in blob for tok in tokens)


def _filter_hosts_by_wing_side(
        hosts: list[dict[str, Any]],
        source_text: Any,
) -> list[dict[str, Any]] | None:
    """None → side cue present but no unique matching wing (ambiguity).

    Multiple declared sides (西厢房和东厢房都…) must each resolve to exactly one host.
    """
    sides = _wing_sides_from_texts(source_text)
    if not sides:
        return list(hosts)
    resolved: list[dict[str, Any]] = []
    seen: set[str] = set()
    for side in sides:
        filtered = [h for h in hosts if _host_matches_wing_side(h, side)]
        if len(filtered) != 1:
            return None
        cid = str((filtered[0].get('params') or {}).get('component_id') or id(filtered[0]))
        if cid in seen:
            continue
        seen.add(cid)
        resolved.append(filtered[0])
    return resolved if resolved else None


def _unique_named_hosts(
        found: list[dict[str, Any]],
        *,
        named_key: str | None = None,
) -> list[dict[str, Any]] | None:
    """Coherent named targets: unique host, stacked storeys, or plural role-class members.

    Returns None when several unrelated buildings share a singular named alias
    (e.g. two distinct towers for named_tower). Stacked pagoda_tier_* count as one
    building. Plural keys such as wing keep every matching mass.
    """
    uniq: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for mass in found:
        cid = str((mass.get('params') or {}).get('component_id') or '')
        if cid:
            if cid in seen_ids:
                continue
            seen_ids.add(cid)
            uniq.append(mass)
        elif mass not in uniq:
            uniq.append(mass)
    if not uniq:
        return None
    if len(uniq) == 1:
        return uniq
    families = {_stacked_family_key((m.get('params') or {}).get('component_id')) for m in uniq}
    if len(families) == 1 and next(iter(families)) is not None:
        return uniq
    key = str(named_key or '').strip().lower()
    if key in _PLURAL_NAMED_KEYS:
        return uniq
    return None


def _host_identity_blob(comp: dict[str, Any] | None) -> str:
    """Stable building-identity text for named scope_bindings.

    Fields (HARD_CONSTRAINT identity for bindings):
    - ``component_id`` (historical)
    - ``building_role`` / ``buildingRole`` / ``label`` / ``part``
      (explicit use identity; covers ``building_a`` + ``building_role=office``)

    Excludes (Python bindings append every hit; loose fields cause false joins):
    - bare ``role`` (often geometry descriptors: ``reception_hall_block``)
    - ``slot_id`` layout frames (``mill_slot`` ↛ ``named_mill``)
    - ``building_id`` / ``requirement_scope`` / free-form ``features``

    Java ``hostIdentityBlob`` still scans a wider set but fails closed on multi-hit.
    """
    if not isinstance(comp, dict):
        return ''
    parts: list[str] = []
    params = comp.get('params') if isinstance(comp.get('params'), dict) else {}
    for key in ('component_id', 'building_role', 'buildingRole', 'part', 'label'):
        value = params.get(key)
        if value is not None and str(value).strip():
            parts.append(str(value).strip())
    return ' '.join(parts).lower()


def _all_mass_design_targets(
    requirement: dict[str, Any],
    design_hosts: list[dict[str, Any]],
    masses: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Select all_main_masses targets: design hosts for roof/window/entrance, else MASS_MAIN."""
    prop = str(requirement.get('property') or '')
    if prop not in _DESIGN_HOST_PROPERTIES:
        return list(masses)
    targets = list(design_hosts)
    # Positive roofs: only defer hosts owned by chinese_roof_contract. Other typology
    # STRUCTURE stay in scope so unsupported forms surface as gaps — never silent skip.
    if prop == 'roof_type' and requirement.get('value') not in (None, 'none'):
        targets = [t for t in targets if not _defers_positive_roof_to_chinese_contract(t)]
        if not targets:
            return list(masses)
    return targets


# Explicit whole-compound quantifiers only — never inferred from missing building names.
_EXPLICIT_ALL_MASS_QUANTIFIERS = re.compile(
    r'所有建筑|全部建筑|两栋均|两栋都|每栋均|每栋都|全部主体|所有主体|所有房子|全部房子'
)


def _clause_window(text: str, match: re.Match) -> str:
    """Sentence/clause containing the match (stops at 。；; and newlines)."""
    bounds = ('。', '；', ';', '\n', '\r')
    start = max(text.rfind(s, 0, match.start()) for s in bounds) + 1
    end_candidates = [text.find(s, match.end()) for s in bounds]
    end_candidates = [i for i in end_candidates if i >= 0]
    end = min(end_candidates) if end_candidates else len(text)
    return text[start:end]


def _explicit_all_mass_quantifier(text: str, match: re.Match | None = None) -> bool:
    blob = _clause_window(text, match) if match is not None else text
    return bool(_EXPLICIT_ALL_MASS_QUANTIFIERS.search(blob))


_JOINT_MULTI_SUBJECT = re.compile(
    r'(?:都|均|全部|一起|皆)(?:要|采用|使用|改成|改用|设置|不要|不需要|不需)?'
)


def _roof_clause_window(text: str, match: re.Match) -> tuple[str, int]:
    """Comma/newline-bounded clause window and match offset inside it.

    Newlines are hard scope boundaries so adjacent lines cannot merge into one
    multi_named declaration (parity with Java isHeightClauseBoundary).
    """
    bounds = ('。', '；', ';', '，', ',', '、', '\n', '\r')
    start = max(text.rfind(s, 0, match.start()) for s in bounds) + 1
    end_at = [text.find(s, match.end()) for s in bounds]
    end_at = [i for i in end_at if i >= 0]
    end = min(end_at) if end_at else len(text)
    return text[start:end], match.start() - start


def _named_keys_in_window(window: str) -> list[str]:
    """Unique NAMED_BUILDINGS keys present in window, preserving first-seen order."""
    found: list[str] = []
    for key, names in NAMED_BUILDINGS.items():
        for name in names:
            if name and name in window and key not in found:
                found.append(key)
                break
    return found


def _roof_clause_subject(text: str, match: re.Match) -> str:
    """Named/ordinal subject for a roof token using comma-bounded clause evidence only.

    Unlike ``_numeric_subject`` (dimensions may span commas), roof ownership must not
    borrow a building name from a sibling clause in the same sentence.
    Joint multi-subject clauses (主殿和亭子都…) return ``multi_named``.
    """
    window, rel_token = _roof_clause_window(text, match)
    keys = _named_keys_in_window(window)
    if len(keys) > 1 and _JOINT_MULTI_SUBJECT.search(window):
        return 'multi_named'
    # Prefer the named building closest to the roof token (never a later clause subject).
    candidates: list[tuple[int, str]] = []
    for key, names in NAMED_BUILDINGS.items():
        for name in names:
            if not name:
                continue
            from_idx = 0
            while True:
                idx = window.find(name, from_idx)
                if idx < 0:
                    break
                candidates.append((abs(idx - rel_token), key))
                from_idx = idx + len(name)
    if candidates:
        candidates.sort()
        # Ambiguous equidistant distinct keys without a joint quantifier → unresolved.
        best = candidates[0][0]
        best_keys = {k for d, k in candidates if d == best}
        if len(best_keys) > 1:
            return 'unresolved'
        return 'named_' + candidates[0][1]
    return 'unresolved'


def _refine_roof_scope(text: str, requirement: dict, match: re.Match | None) -> None:
    """Separate EXPLICIT global quantifiers from INFERRED default whole-compound scopes.

    Classification: FORMAT_REPAIR / HARD_CONSTRAINT — does not invent building ownership.
    """
    if match is None:
        requirement['scope'] = 'unresolved'
        requirement['scope_source'] = 'INFERRED'
        # Keep status=pending: the roof value is known; only ownership is unresolved.
        return
    window, _ = _roof_clause_window(text, match)
    keys = _named_keys_in_window(window)
    if len(keys) > 1 and _JOINT_MULTI_SUBJECT.search(window):
        requirement['scope'] = 'multi_named'
        requirement['scope_source'] = 'EXPLICIT_MULTI'
        requirement['named_keys'] = list(keys)
        return
    subject = _roof_clause_subject(text, match)
    if subject == 'multi_named':
        requirement['scope'] = 'multi_named'
        requirement['scope_source'] = 'EXPLICIT_MULTI'
        requirement['named_keys'] = keys
        return
    if subject.startswith('named_') or subject.startswith('building_'):
        requirement['scope'] = subject
        requirement['scope_source'] = 'NAMED' if subject.startswith('named_') else 'EXPLICIT_ORDINAL'
        return
    if subject == 'element_scope':
        requirement['scope'] = 'element_scope'
        requirement['scope_source'] = 'ELEMENT'
        requirement['status'] = 'unsupported_scope'
        return
    if _explicit_all_mass_quantifier(text, match):
        requirement['scope'] = 'all_main_masses'
        requirement['scope_source'] = 'EXPLICIT_QUANTIFIER'
        return
    # Missing building name must NOT upgrade to all_main_masses for design overwrite.
    # status stays pending — unresolved ownership is expressed by scope, not status.
    requirement['scope'] = 'unresolved'
    requirement['scope_source'] = 'INFERRED'


def _omit_positive_match(text: str, match: re.Match) -> bool:
    """True when an omit cue is not wrapped in outer negation (不是无窗 / 并非无屋顶)."""
    prefix = text[max(0, match.start() - 16):match.start()]
    if re.search(r'(?:不是|并非|绝非|绝不是)\s*$', prefix):
        return False
    if re.search(r'(?:不能|不会|别)没有\s*$', prefix):
        return False
    return _positive_match(text, match)


def _numeric_subject(text: str, match: re.Match) -> str:
    # A clause's named building owns its dimensions, not every MASS_MAIN in
    # the compound. Carry the subject across commas, but not sentence boundaries.
    start = max(text.rfind(s, 0, match.start()) for s in ('。', '；', ';')) + 1
    prefix = text[start:match.start()]
    suffix = re.split(r'[，。；;,]', text[match.end():match.end()+12])[0]
    if (re.match(r'(?:宽度|宽|长度|长|深度|深)', match[0])
            and '门洞' in re.split(r'[。；;]',text[match.end():match.end()+24])[0]):
        return 'element_scope'
    if re.match(r'(?:宽度|宽|长度|长|深度|深)', match[0]) and any(name in suffix for name in ELEMENT_SUBJECTS):
        return 'element_scope'
    if re.match(r'[一二两三四五六七八九十\d]+\s*层', match[0]):
        for key, names in NAMED_BUILDINGS.items():
            if any(suffix.startswith(name) or suffix.startswith('的'+name) for name in names):
                return 'named_' + key
    candidates = [(m.start(), key) for key, names in NAMED_BUILDINGS.items()
                  for m in re.finditer('|'.join(map(re.escape, names)), prefix)]
    elements = [m.start() for name in ELEMENT_SUBJECTS for m in re.finditer(name, prefix)]
    if elements and (not candidates or max(elements) > max(candidates)[0]):
        # A stair/path width is a part constraint, never a building envelope width.
        if re.match(r'(?:宽度|宽|长度|长|深度|深)', match[0]):
            return 'element_scope'
    if candidates:
        return 'named_' + max(candidates)[1]
    # Chinese declarations also put the noun after the count (两层正厅).
    suffix = text[match.start():match.end()+8]
    for key, names in NAMED_BUILDINGS.items():
        if any(name in suffix for name in names):
            return 'named_' + key
    return 'all_main_masses'



def _positive_match(text: str, match: re.Match) -> bool:
    prefix = text[max(0, match.start()-12):match.start()]
    return not re.search(r'(?:不要|不用|不使用|不采用|避免)(?:用|采用|使用)?\s*$|\b(?:no|not|without|avoid)(?:\s+(?:use|using|a|an))?\s*$', prefix, re.I)


def _normalize_request_text(text: str) -> str:
    """Collapse intra-line CJK spaces for keyword recognition.

    Newlines/carriage returns are preserved as clause boundaries (Java parity).
    Prefer :func:`_normalize_request_text_with_span_map` when recording match_span.
    """
    normalized, _ = _normalize_request_text_with_span_map(text or '')
    return normalized


def _normalize_request_text_with_span_map(text: str) -> tuple[str, list[int]]:
    """Normalize CJK intra-line spaces while mapping each normalized index to the original.

    Classification: FORMAT_REPAIR — original player text remains the span coordinate system.
    """
    raw = text or ''
    out: list[str] = []
    orig_map: list[int] = []
    i = 0
    n = len(raw)
    while i < n:
        ch = raw[i]
        # Skip non-newline whitespace between two CJK characters.
        if (
            '\u4e00' <= ch <= '\u9fff'
            and i + 1 < n
            and raw[i + 1] not in '\n\r'
            and raw[i + 1].isspace()
        ):
            j = i + 1
            while j < n and raw[j] not in '\n\r' and raw[j].isspace():
                j += 1
            if j < n and '\u4e00' <= raw[j] <= '\u9fff':
                out.append(ch)
                orig_map.append(i)
                i = j
                continue
        out.append(ch)
        orig_map.append(i)
        i += 1
    return ''.join(out), orig_map


def _remap_match_span_to_original(span: list, orig_map: list[int]) -> list[int] | None:
    if not isinstance(span, list) or len(span) < 2 or not orig_map:
        return None
    try:
        start = int(span[0])
        end = int(span[1])
    except (TypeError, ValueError):
        return None
    if start < 0 or end < start:
        return None
    if start >= len(orig_map):
        return None
    orig_start = orig_map[start]
    if end == 0:
        orig_end = orig_start
    elif end - 1 < len(orig_map):
        orig_end = orig_map[end - 1] + 1
    else:
        orig_end = orig_map[-1] + 1
    return [orig_start, orig_end]


def _extract_global_requirements(text: str) -> list[dict]:
    """Only unambiguous supported expressions; conflicting values remain unscoped, never guessed."""
    result = []
    patterns = {
        'length': r'(?:长度|长)\s*(\d+)\s*格',
        'width': r'(?:宽度|宽)\s*(\d+)\s*格',
        'depth': r'(?:深度|深)\s*(\d+)\s*格',
        'floor_height': r'每层(?:的)?(?:高度|高)\s*(\d+)\s*格',
        'net_height': r'室内净高\s*(\d+)\s*格',
        'floor_count': r'(?<![\d每])([一二两三四五六七八九十]|\d+)\s*层(?!地面|地板|楼板)(?:的)?(?=[^，。；\n]{0,10}(?:住宅|建筑|大厅|塔楼|民居|房屋|别墅|主楼|办公楼|调度楼|居住楼|磨坊|高|，|、|。))',
    }
    for key, pattern in patterns.items():
        matches = [m for m in re.finditer(pattern, text) if _positive_match(text, m)]
        if key == 'floor_count':
            matches = [m for m in matches if not re.match(r'(?:入口|设置|通过|沿|使用|抵达|地面|地板|楼板|沿庭院)', text[m.end():])]
        if not matches: continue
        groups = {}
        for match in matches:
            groups.setdefault(_numeric_subject(text, match), []).append(match)
        for subject, group in groups.items():
            values = {NUMBERS[m[1]] if m[1] in NUMBERS else int(m[1]) for m in group}
            result.append({'id': f'req_{key}' + ('_' + subject if subject != 'all_main_masses' else ''), 'property': key, 'value': next(iter(values)) if len(values) == 1 else None,
                       'unit': 'floors' if key == 'floor_count' else 'blocks', 'source': 'user_explicit',
                       'source_text': [m[0] for m in group], 'scope': subject if len(values) == 1 else 'unresolved',
                       'priority': 'hard', 'status': 'pending' if len(values) == 1 and subject != 'element_scope' else 'unsupported_scope'})
    # 破风 / 三角山墙屋顶 is a Japanese pediment cue — do NOT force western gable
    # (pattern 山墙屋顶 previously false-triggered req_roof_gable).
    # Only explicit 双坡/人字顶 should hard-require roof_type=gable.
    for i, match in enumerate(
            m for m in re.finditer(r'(?<!十字)双坡屋\s*顶|人字顶', text) if _positive_match(text, m)):
        req = {'id': f'req_roof_gable_{i}' if i else 'req_roof_gable', 'property': 'roof_type',
               'value': 'gable', 'source': 'user_explicit', 'source_text': [match.group(0)],
               'scope': 'unresolved', 'scope_source': 'INFERRED', 'priority': 'hard', 'status': 'pending'}
        _refine_roof_scope(text, req, match)
        result.append(req)
    for i, match in enumerate(
            m for m in re.finditer(r'平屋顶|平顶', text) if _positive_match(text, m)):
        req = {'id': f'req_roof_type_{i}' if i else 'req_roof_type', 'property': 'roof_type',
               'value': 'flat', 'source': 'user_explicit', 'source_text': [match.group(0)],
               'scope': 'unresolved', 'scope_source': 'INFERRED', 'priority': 'hard', 'status': 'pending'}
        _refine_roof_scope(text, req, match)
        result.append(req)
    specialty_roofs = {
        # Classical Chinese / East Asian roofs — must participate in ordinal building scopes
        # (第一栋歇山 / 第二栋平顶) the same way as western specialty roofs.
        'double_eave_xieshan': r'重檐歇山(?:屋\s*)?顶|\bdouble[ _-]?eave[ _-]?xieshan\b',
        'xieshan': r'(?<!重檐)歇山(?:屋\s*)?顶|\bxieshan\b|\bhip[ _-]?and[ _-]?gable\b',
        'xuanshan': r'悬山(?:屋\s*)?顶|\bxuanshan\b',
        'yingshan': r'硬山(?:屋\s*)?顶|\byingshan\b',
        'wudian': r'庑殿(?:屋\s*)?顶|\bwudian\b',
        'gambrel': r'折线双坡顶|\bgambrel(?:[ _]roof)?\b',
        'barrel_vault': r'筒拱(?:屋)?顶|\bbarrel[ _]vault\b',
        'onion_dome': r'洋葱穹顶|\bonion[ _]dome\b',
        'hemispherical_dome': r'半球穹顶|\bhemispherical[ _]dome\b',
        'pointed_dome': r'尖拱穹顶|\bpointed[ _]dome\b',
        'steep_thatch': r'陡坡茅草顶|\bsteep[ _]thatch\b',
        'juanpeng_yingshan': r'硬山卷棚(?:屋?顶)?|\bjuanpeng_yingshan\b',
        'juanpeng_xuanshan': r'悬山卷棚(?:屋?顶)?|\bjuanpeng_xuanshan\b',
        'juanpeng': r'(?<!硬山)(?<!悬山)卷棚(?:屋?顶)?|\bjuan_?peng\b',
        'saltbox': r'盐盒顶|\bsaltbox(?:[ _]roof)?\b',
        'butterfly': r'蝶形(?:屋)?顶|\bbutterfly[ _]roof\b|^butterfly$',
        'bellcast_hip': r'钟形四坡顶|四坡钟形顶|\bbellcast[ _]hip\b',
        'bellcast': r'(?<!四坡)钟形(?:双坡)?顶|\bbell_?cast\b(?![ _]hip\b)',
        'cross_gable': r'十字双坡(?:屋\s*)?顶|\bcross[ _-]gabled?\b',
    }
    for roof_type, pattern in specialty_roofs.items():
        hits = [match for match in re.finditer(pattern, text, re.I) if _positive_match(text, match)]
        # One requirement per player declaration — do not collapse identical roof values.
        for i, match in enumerate(hits):
            req = {'id': f'req_roof_{roof_type}' + (f'_{i}' if i else ''),
                   'property': 'roof_type', 'value': roof_type,
                   'source': 'user_explicit', 'source_text': [match.group(0)],
                   'scope': 'unresolved', 'scope_source': 'INFERRED',
                   'priority': 'hard', 'status': 'pending'}
            _refine_roof_scope(text, req, match)
            result.append(req)
    for requirement in result:
        if requirement['property'] != 'roof_type':
            continue
        # Already scoped at declaration time (per-hit refine).
        if requirement.get('scope_source') in (
                'NAMED', 'EXPLICIT_ORDINAL', 'EXPLICIT_QUANTIFIER', 'EXPLICIT_MULTI', 'ELEMENT'):
            continue
        token = (requirement.get('source_text') or [''])[0]
        hit = re.search(re.escape(token), text) if token else None
        _refine_roof_scope(text, requirement, hit)
    roof_requirements = [requirement for requirement in result if requirement['property']=='roof_type']
    if any(requirement['value'] in specialty_roofs for requirement in roof_requirements):
        for scope in {requirement['scope'] for requirement in roof_requirements}:
            # Same scope with multiple roof values is incompatible (incl. unresolved co-mentions).
            # Distinct clause subjects refine to different named_* scopes first, so they survive.
            scoped = [requirement for requirement in roof_requirements if requirement['scope']==scope]
            if len({requirement['value'] for requirement in scoped if requirement.get('value') is not None}) > 1:
                for requirement in scoped:
                    requirement.update(value=None, scope='unresolved', scope_source='INFERRED', status='unresolved')
    if re.search(r'山墙[^。；\n]{0,15}(?:不要|不需要|不设|不开|无)[^。；\n]{0,6}(?:窗|开口)|(?:不要|不需要|不设|不开)[^。；\n]{0,6}山墙[^。；\n]{0,6}窗|(?:普通)?封闭山墙', text):
        result.append({'id': 'req_gable_windows', 'property': 'gable_windows', 'value': False,
                       'source': 'user_explicit', 'source_text': ['山墙禁窗或封闭山墙'],
                       'scope': 'all_main_masses', 'scope_source': 'INFERRED',
                       'priority': 'hard', 'status': 'pending'})
    if any(_positive_match(text, m) for m in re.finditer(r'玻璃窗', text)):
        result.append({'id': 'req_glass_windows', 'property': 'glass_block', 'value': 'minecraft:glass',
                       'source': 'user_explicit', 'scope': 'all_main_masses', 'scope_source': 'INFERRED',
                       'priority': 'hard', 'status': 'pending'})
    for prop, pattern in (
        ('roof_type', r'(?:不要|不需要|不需)(?:生成|添加|建造)?屋顶|无屋顶'),
        ('window_style', r'(?:不要|不需要|不需)(?:生成|添加)?(?:窗户|窗洞)|不开窗|无窗(?:户)?'),
        ('entrance_type', r'(?:不要|不需要|不需)(?:生成|添加)?(?:入口|门洞)|无入口'),
    ):
        hits = [m for m in re.finditer(pattern, text) if _omit_positive_match(text, m)]
        if prop == 'window_style':
            hits = [m for m in hits if not re.search(r'山墙[^。；\n]*$', text[max(0, m.start()-30):m.start()])]
        # One omit declaration per text span — keep each host binding independent.
        for i, match in enumerate(hits):
            req = {'id': f'req_disable_{prop}' + (f'_{i}' if i else ''), 'property': prop,
                   'value': 'none', 'source': 'user_explicit', 'source_text': [match.group(0)],
                   'match_span': [match.start(), match.end()],
                   'scope': 'unresolved', 'scope_source': 'INFERRED',
                   'priority': 'hard', 'status': 'pending'}
            # Roof/window/entrance share named / quantifier / multi_named host refinement.
            _refine_roof_scope(text, req, match)
            result.append(req)
    if re.search(r'(?:不(?:要|需)|暂不).{0,18}复杂装饰', text):
        result.append({'id': 'req_no_complex_decor', 'property': 'no_complex_decor', 'value': True,
                       'source': 'user_explicit', 'source_text': [re.search(r'(?:不(?:要|需)|暂不).{0,18}复杂装饰', text)[0]],
                       'scope': 'plan', 'priority': 'hard', 'status': 'pending'})
    materials = {'石砖': 'minecraft:stone_bricks', '橡木木板': 'minecraft:oak_planks',
                 '橡木板': 'minecraft:oak_planks', '橡木': 'minecraft:oak_planks', '云杉木板': 'minecraft:spruce_planks',
                 '砖块': 'minecraft:bricks', '圆石': 'minecraft:cobblestone', '石英块': 'minecraft:quartz_block',
                 '深板岩瓦': 'minecraft:deepslate_tiles', '深板岩砖': 'minecraft:deepslate_bricks',
                 '深色橡木板': 'minecraft:dark_oak_planks'}
    names = '|'.join(sorted(materials, key=len, reverse=True))
    for prop, role in (('wall_block', '外墙|墙体'), ('floor_block', '楼板|地板'), ('roof_block', '屋顶|屋面')):
        pattern = rf'(?:(?P<before>{names})(?:制)?(?:{role})|(?:{role})(?:使用|采用|用)(?P<after>{names}))'
        hits = [m for m in re.finditer(pattern, text) if _positive_match(text, m)]
        if not hits:
            continue
        values = {materials[m.group('before') or m.group('after')] for m in hits}
        result.append({'id': 'req_' + prop, 'property': prop,
                       'value': next(iter(values)) if len(values) == 1 else None,
                       'source': 'user_explicit', 'source_text': [m[0] for m in hits],
                       'scope': 'all_main_masses' if len(values) == 1 else 'unresolved',
                       'priority': 'hard', 'status': 'pending' if len(values) == 1 else 'unsupported_scope'})
    hits = [m for m in re.finditer(r'(?:正门|入口)(?:位于|朝向|朝|向)(?:建筑)?([东南西北])(?:侧|面|方)?', text) if _positive_match(text, m)]
    if hits:
        values = {{'东': 'EAST', '南': 'SOUTH', '西': 'WEST', '北': 'NORTH'}[m[1]] for m in hits}
        result.append({'id': 'req_entrance_facing', 'property': 'entrance_facing',
                       'direction_convention': 'minecraft_world',
                       'runtime_field_value': LEGACY_ENTRANCE_FACING[next(iter(values))] if len(values) == 1 else None,
                       'value': next(iter(values)) if len(values) == 1 else None,
                       'source': 'user_explicit', 'source_text': [m[0] for m in hits],
                       'scope': 'all_main_masses' if len(values) == 1 else 'unresolved',
                       'priority': 'hard', 'status': 'pending' if len(values) == 1 else 'unsupported_scope'})
    return result



def extract_requirements(text: str) -> list[dict]:
    """Recognize explicit ordinal declarations; never infer ownership from component order.

    Keyword matching uses newline-preserving normalization; match_span is remapped onto the
    original player-text coordinate system before return.
    """
    original = text or ''
    text, orig_map = _normalize_request_text_with_span_map(original)
    declarations = list(re.finditer(
        r'第([一二两三四五六七八九十]|\d+)栋(?:建筑|住宅|房屋)?[：:，,\s]*(?:是)?', text))
    directional = list(re.finditer(r'(左|右)栋(?:建筑|住宅|房屋)?[：:，,\s]*(?:使用|采用|是)?', text))
    cardinal = list(re.finditer(r'(东|西|南|北)侧(?:的)?(?:住宅|建筑|房屋|民居|别墅)', text))
    if not declarations and not directional and not cardinal:
        result = _extract_global_requirements(text)
        _remap_requirement_spans(result, orig_map)
        return result
    events = [(m.start(), m.end(), 'building_' + str(NUMBERS[m[1]] if m[1] in NUMBERS else int(m[1])))
              for m in declarations]
    events += [(m.start(), m.end(), 'building_1' if m[1] == '左' else 'building_2') for m in directional]
    events += [(m.start(), m.end(), 'all_main_masses') for m in re.finditer(r'所有建筑|全部建筑|两栋均|两栋都|每栋均|每栋都', text)]
    events += [(m.start(), m.end(), 'building_' + {'东': 'east', '西': 'west', '南': 'south', '北': 'north'}[m[1]])
               for m in cardinal]
    events.sort()
    result = _extract_global_requirements(text[:events[0][0]])
    for index, (_, end, scope) in enumerate(events):
        stop = events[index+1][0] if index+1 < len(events) else len(text)
        for requirement in _extract_global_requirements(text[end:stop]):
            # Fragments are sliced from the normalized full text — shift then remap to original.
            _shift_match_span(requirement, end)
            if str(scope).startswith('building_'):
                # Ordinal clause owns the fragment — bind inferred/unresolved design
                # requirements to that building (do not leave them as whole-compound).
                if requirement.get('scope') in (
                    None, '', 'unresolved', 'all_main_masses', 'plan'
                ) or requirement.get('scope_source') == 'INFERRED':
                    requirement['scope'] = scope
                requirement['scope_source'] = 'EXPLICIT_ORDINAL'
                if requirement.get('status') in ('unresolved', 'unsupported_scope') and requirement.get('value') is not None:
                    requirement['status'] = 'pending'
            elif scope == 'all_main_masses':
                requirement['scope'] = 'all_main_masses'
                requirement['scope_source'] = 'EXPLICIT_QUANTIFIER'
                if requirement.get('status') in ('unresolved', 'unsupported_scope') and requirement.get('value') is not None:
                    requirement['status'] = 'pending'
            else:
                if requirement['scope'] != 'unresolved' and requirement['scope'] != 'plan':
                    requirement['scope'] = scope
                elif requirement['scope'] == 'plan' and scope != 'all_main_masses':
                    requirement['scope'] = scope
            requirement['id'] += '_' + scope
            result.append(requirement)
    _remap_requirement_spans(result, orig_map)
    return result


def _shift_match_span(requirement: dict, offset: int) -> None:
    """Convert fragment-relative match_span to full normalized-text coordinates."""
    if not offset:
        return
    span = requirement.get('match_span')
    if not isinstance(span, list) or len(span) < 2:
        return
    try:
        requirement['match_span'] = [int(span[0]) + offset, int(span[1]) + offset]
    except (TypeError, ValueError):
        return


def _remap_requirement_spans(requirements: list[dict], orig_map: list[int]) -> None:
    """Rewrite match_span from normalized coordinates onto the original player text."""
    for requirement in requirements:
        span = requirement.get('match_span')
        remapped = _remap_match_span_to_original(span, orig_map) if span is not None else None
        if remapped is not None:
            requirement['match_span'] = remapped


def request_text(req: Any) -> str:
    current = (getattr(req, 'userMessage', '') or '').strip()
    if extract_requirements(current) or re.search(r'建造|生成|搭建|build|create', current, re.I): return current
    # A short answer to a style question can inherit the last human building request.
    # Never extract requirements from AI history or unrelated earlier requests.
    history = getattr(req, 'chatHistory', None)
    if not isinstance(history, (list, tuple)): return current
    for line in reversed(history):
        if str(line).lower().startswith('player:'):
            previous = str(line)[7:].strip()
            if extract_requirements(previous): return previous + '\n' + current
            break
    return current


def _center(comp: dict) -> tuple[float, float]:
    from .resolved_geometry import mass_center
    return mass_center(comp)


def apply_building_contract(plan: dict, text: str, *, finalize: bool = False) -> dict:
    if not isinstance(plan, dict) or str(plan.get('mode', 'build')).lower() == 'patch': return plan
    text = _normalize_request_text(text)
    out = deepcopy(plan)
    from .plan_coordinate_normalizer import recover_slot_bindings
    recover_slot_bindings(out)
    from .gothic_building_contract import repair_gothic_route
    repair_gothic_route(out, text)
    hints = out.setdefault('proportion_hints', {})
    if not isinstance(hints, dict): hints = {}; out['proportion_hints'] = hints
    requirements = extract_requirements(text)
    # Persist scope_source so downstream trust / scope audits can prove authorization.
    persisted_requirements = [deepcopy(r) for r in requirements]
    contract = {'schema': SCHEMA, 'requirements': persisted_requirements, 'diagnostics': [], 'validation_stage': 'plan'}
    hints['building_contract'] = contract
    from .architecture_intent import describe_architecture
    hints['architecture_intent'] = describe_architecture(text)
    hints['architecture_intent']['materials'] = [deepcopy(r) for r in persisted_requirements
                                               if r['property'] in ('wall_block', 'floor_block', 'roof_block')]
    from .request_intent import attach_request_intent
    attach_request_intent(out, text)
    components = [c for c in (out.get('components') or []) if isinstance(c, dict)]
    # A provider may emit all requested placement slots but only one module.
    slots_for_modules = (out.get('layout') or {}).get('slots') or []
    count_match = re.search(r'([2-9二三四五六七八九])(?:座|栋)', text)
    slot_ids = [s.get('slot_id', s.get('id')) for s in slots_for_modules if isinstance(s, dict)]
    if len(components) == 1 and components[0].get('component_type') == 'MODULE' and count_match:
        count = int(count_match[1]) if count_match[1].isdigit() else '零一二三四五六七八九'.index(count_match[1])
        if (len(slots_for_modules) < count and components[0].get('dimensions')
                and (not slots_for_modules or components[0].get('slot_id') in slot_ids)):
            prototype = components[0]
            dims = prototype['dimensions']
            gap_match = re.search(r'间隔(?:至少|约)?\s*(\d+)\s*格', text)
            gap = max(8, int(gap_match[1])) if gap_match else 8
            first = deepcopy(slots_for_modules[0]) if slots_for_modules else {'slot_id': prototype.get('slot_id') or 'module_1', 'anchor': {'x': 0,'y': 0,'z': 0}}
            slots_for_modules = deepcopy(slots_for_modules) or [first]
            for i in range(len(slots_for_modules),count):
                slot = deepcopy(first)
                slot['slot_id'] = f"{first['slot_id']}_{i+1}" if slot_ids else f'module_{i+1}'
                slot.pop('id',None)
                slot.setdefault('anchor',{})['x'] = max(s.get('anchor',{}).get('x',0) for s in slots_for_modules)+dims['width']+gap
                slots_for_modules.append(slot)
            out.setdefault('layout', {})['slots'] = slots_for_modules
            slot_ids = [s['slot_id'] for s in slots_for_modules]
            prototype['slot_id'] = slot_ids[0]
        if (count == len(slots_for_modules) == len(set(slot_ids))
                and all(slot_ids) and components[0].get('slot_id') in slot_ids):
            prototype = components[0]
            for key in slot_ids:
                if key == prototype.get('slot_id'):
                    continue
                clone = deepcopy(prototype)
                clone['slot_id'] = key
                clone.setdefault('params', {})['component_id'] = str(prototype.get('params', {}).get('component_id', 'module')) + '#' + str(key)
                components.append(clone)
            out['components'] = components
    # Execute explicit rectangular setback schedules rather than leaving their
    # full-height circular placeholder as the only shell.
    for body in components:
        p = body.get('params') or {}
        footprints, levels = p.get('tier_footprints'), p.get('tier_base_levels')
        d = body.get('dimensions') or {}
        if (body.get('component_type') != 'MASS_MAIN' or not re.search(r'退台|逐层缩小|每层逐渐缩小',text)
                or not isinstance(footprints,list) or not isinstance(levels,list)
                or len(footprints) != len(levels) or not 2 <= len(levels) <= 16
                or any(not isinstance(f,list) or len(f)!=2 or any(type(v) is not int or v<3 for v in f) for f in footprints)
                or any(type(v) is not int for v in levels) or levels != sorted(set(levels))):
            continue
        from .resolved_geometry import mass_origin
        origin = mass_origin(body)
        if (levels[0] != origin['y'] or footprints[0] != [d.get('width'),d.get('depth')]
                or any(b[0]>a[0] or b[1]>a[1] for a,b in zip(footprints,footprints[1:]))
                or levels[-1] >= origin['y']+d.get('height',0)):
            continue
        top = origin['y']+d['height']
        p.update(shape='rectangle',plan_type='none',terraced=True,roof_type='flat')
        p['masses'] = [{'offset':{'x':0,'y':level-origin['y'],'z':0},
                        'dimensions':{'width':f[0],'depth':f[1],'height':(levels[i+1] if i+1<len(levels) else top)-level},
                        'shape':'rectangle'} for i,(f,level) in enumerate(zip(footprints,levels))]
        for plate in components:
            pp = plate.get('params') or {}
            if pp.get('host_id') == p.get('component_id') and pp.get('extrude_mode') == 'plate' and pp.get('plate_role') == 'floor':
                plate['dimensions']['height'] = 1
                pp.update(floor_count=1,floor_height=1)
            if (plate.get('component_type') == 'DECOR_DETAIL' and pp.get('host_id') == p.get('component_id')
                    and pp.get('planter_count') and pp.get('saplings') and not pp.get('planting_layout_source')):
                # Keep the planter inside the two-cell exposed terrace band,
                # above the deck rather than inside the next tier's wall.
                plate['relative_position']['y'] += 1
                plate['relative_position']['z'] -= 1
                plate['dimensions'].update(depth=2,height=2)
                pp.update(decor_type='planter',foliage_block='minecraft:oak_leaves[persistent=true]',
                          planting_layout_source='terrace_outer_edge')
    for component in components:
        params = component.get('params') or {}
        roof_aliases = {'chinese_xieshan':'xieshan', 'chinese_xuanshan':'xuanshan', 'chinese_yingshan':'yingshan', 'spire':'pyramid'}
        if params.get('roof_type') in roof_aliases:
            params['roof_type'] = roof_aliases[params['roof_type']]
        if (component.get('component_type') == 'MASS_SECONDARY'
                and params.get('shape') == 'sphere' and not params.get('floor_count')):
            params.update(shape='circle', kind='sphere', volume_geometry='sphere')
        if component.get('component_type') in ('MASS_MAIN','MAIN_MASS','MASS_SECONDARY','MASS_ANCILLARY','MASS_WING') and params.get('shape') == 'pyramid':
            params.update(shape='rectangle', volume_geometry='pyramid', roof_type='none', hollow=True)
            params['plan_type'] = 'none'
        if component.get('component_type') == 'MASS_SECONDARY' and params.get('bridge') is True:
            params.setdefault('plan_type', 'none')
        # A tower gate is an occupied building shell with hosted roof/openings,
        # rather than the standalone thin gate-frame generator.
        if component.get('component_type') == 'GATE' and params.get('gate_type') == 'tower_gate':
            component['component_type'] = 'MASS_SECONDARY'
            params.setdefault('hollow', True)
            params.setdefault('floor_count', 1)
            params.setdefault('floor_height', component['dimensions']['height'])
            params.setdefault('shape', 'rectangle')
            params['geometry_source'] = 'tower_gate_shell'
        if params.get('interior') == 'single_open_bay' and component.get('component_type') == 'MASS_MAIN':
            params.update(floor_count=1, floor_height=component['dimensions']['height'])
    # A stack of authored tiers describes one storey per body, not a full-height
    # building repeated at every setback. Use the next tier's floor as its top.
    bodies_by_id = {(c.get('params') or {}).get('component_id'): c for c in components}
    for identity, body in list(bodies_by_id.items()):
        if body.get('component_type') != 'MASS_MAIN': continue
        tiers = [body] + [c for c in components if c.get('component_type') == 'MASS_SECONDARY'
                         and (c.get('params') or {}).get('host_id') == identity
                         and 'tier_index' in (c.get('params') or {})
                         and (c.get('params') or {}).get('extrude_mode') != 'plate']
        if len(tiers) < 3: continue
        tiers.sort(key=lambda c: c['relative_position']['y'])
        from .resolved_geometry import mass_origin
        def contains_tier(lower, upper):
            a, b = mass_origin(lower), mass_origin(upper)
            return lower.get('slot_id') == upper.get('slot_id') and all(
                a[axis] <= b[axis] and b[axis] + upper['dimensions'][size] <= a[axis] + lower['dimensions'][size]
                for axis, size in (('x', 'width'), ('z', 'depth')))
        if not all(contains_tier(a, b) for a, b in zip(tiers, tiers[1:])):
            continue  # Offset wings are separate volumes, not stacked setback storeys.
        for index, tier in enumerate(tiers):
            height = (tiers[index+1]['relative_position']['y'] - tier['relative_position']['y']
                      if index+1 < len(tiers) else int(tier['params'].get('floor_height', 0)))
            if height > 0 and tier['dimensions']['height'] > height:
                tier['dimensions']['height'] = height
                tier['params'].update(floor_count=1, floor_height=height)
    for roof in components:
        params = roof.get('params') or {}
        host = bodies_by_id.get(params.get('host_id'))
        overhang = params.get('overhang')
        if roof.get('component_type') != 'ROOF' or host is None or not isinstance(overhang, int) or overhang < 0: continue
        dims, hd = roof.get('dimensions') or {}, host.get('dimensions') or {}
        extra = max((dims.get('width', 0)-hd.get('width', 0))//2, (dims.get('depth', 0)-hd.get('depth', 0))//2)
        if extra > 0 and extra != overhang:
            dims.update(width=hd['width']+2*overhang, depth=hd['depth']+2*overhang)
    slots = (out.get('layout') or {}).get('slots') or []
    aliases = {s['id']: s['slot_id'] for s in slots if isinstance(s, dict) and s.get('id') and s.get('slot_id')}
    for comp in components:
        if not isinstance(comp.get('params'), dict): comp['params'] = {}
        # Older model outputs nested the coordinate frame in params.
        if not comp.get('slot_id') and comp['params'].get('slot_id'):
            comp['slot_id'] = comp['params']['slot_id']
        if comp.get('slot_id') in aliases:
            comp['slot_id'] = aliases[comp['slot_id']]
    # Repair legacy plan-space coordinates duplicated in a building slot anchor.
    # Only exact center repetition is evidence; min_corner offsets remain local.
    for slot in slots:
        if not isinstance(slot, dict): continue
        key = slot.get('slot_id', slot.get('id'))
        anchor = slot.get('anchor')
        if not isinstance(anchor, dict) or not any(anchor.get(a, 0) for a in ('x', 'z')): continue
        bodies = [c for c in components if c.get('component_type') == 'MASS_MAIN' and c.get('slot_id') == key]
        if len(bodies) != 1: continue
        body = bodies[0]
        pos = body.get('relative_position') or {}
        if body['params'].get('anchor_mode', 'center') != 'center' or not all(
                isinstance(anchor.get(a), (int, float)) and pos.get(a) == anchor[a] for a in ('x', 'z')): continue
        for c in components:
            if c.get('slot_id') != key or not isinstance(c.get('relative_position'), dict): continue
            for axis in ('x', 'z'):
                c['relative_position'][axis] -= anchor[axis]
            c['params']['coordinate_frame_source'] = 'legacy_plan_space'
    supplied = [str((c.get('params') or {}).get('component_id')) for c in components if (c.get('params') or {}).get('component_id')]
    used = set(supplied)
    if len(supplied) != len(used): contract['diagnostics'].append({'code': 'E_COMPONENT_ID_DUPLICATE'})
    for index, comp in enumerate(components):
        params = comp.setdefault('params', {})
        if not isinstance(params, dict): params = {}; comp['params'] = params
        if not params.get('component_id'):
            stem, counter = f'component_{index+1}', 0
            identity = stem
            while identity in used:
                counter += 1; identity = f'{stem}_{counter}'
            params['component_id'] = identity; used.add(identity)
    masses = [c for c in components if c.get('component_type') == 'MASS_MAIN']
    # Design-constraint hosts: MASS*/TOWER/GATE/HALL + independent typology STRUCTURE.
    # Used for all_main_masses / named design requirements — parity with Java.
    design_hosts = design_constraint_hosts(components)
    # Towers and wings own their attached roofs/windows too. Keep requirement
    # targets separate: a main-building width must not be imposed on its towers.
    owners = [c for c in components if c.get('component_type') in
              ('MASS_MAIN', 'TOWER', 'MASS_WING', 'SIDE_WING', 'MASS_ANCILLARY', 'MASS_SECONDARY',
               'TERRACE', 'COURTYARD', 'COURTYARD_SPACE', 'GATE', 'HALL')]
    referenced_hosts = {c['params'].get('host_id') for c in components if c.get('component_type') in HOSTED}
    owners.extend(c for c in components if c.get('component_type') in ('PLAZA','PAVING','PATH','ROAD','FOUNDATION','STRUCTURE','MODULE')
                  and c['params']['component_id'] in referenced_hosts)
    # Independent design hosts (e.g. typology STRUCTURE) must participate in owner graphs.
    for host in design_hosts:
        if host not in owners:
            owners.append(host)
    _repair_compound_courtyard_inheritance(out, components)
    ids = {c['params']['component_id']: c for c in owners}
    # Global attributes cannot encode different materials for separate buildings.
    attrs = out.get('style_attributes') or {}
    for role in ('wall', 'floor', 'roof'):
        value = attrs.get(role + '_material')
        if isinstance(value, str) and '/' in value and 'building_' in value and masses and all(
                isinstance(m['params'].get(role + '_block'), str) and m['params'][role + '_block'] for m in masses):
            attrs.pop(role + '_material', None)
    # Historical dimensional / material named bindings (MASS_MAIN/WING/named ANCILLARY).
    # Building-level design named scopes bind from design_hosts — do not keep appending
    # component types into this list (independent MASS_SECONDARY must participate).
    requirement_masses = [c for c in owners if c.get('component_type') in
                          ('MASS_MAIN', 'MASS_WING', 'SIDE_WING', 'MASS_ANCILLARY')
                          and (c.get('component_type') != 'MASS_ANCILLARY'
                               or str((c.get('params') or {}).get('requirement_scope', '')).startswith('named_'))
                          and (c.get('params') or {}).get('extrude_mode') != 'plate'
                          and c.get('dimensions', {}).get('height', 0) > 1]
    # Named design bindings sourced from design_hosts (MASS_ANCILLARY already identity-gated).
    # Independent MASS_SECONDARY / TOWER / GATE / HALL / typology STRUCTURE join here.
    binding_hosts: list[dict[str, Any]] = list(requirement_masses)
    for host in design_hosts:
        if host not in binding_hosts:
            binding_hosts.append(host)
    for mass in masses:
        params = mass['params']
        own = [r for r in requirements if r['scope'] in ('all_main_masses', 'plan', params.get('requirement_scope'))]
        for prop, kind, positive in (('window_style', 'FACADE_WINDOWS', r'窗户|玻璃窗'),
                                     ('entrance_type', 'ENTRANCE', r'入口|正门')):
            if params.get(prop) == 'none' and re.search(positive, text) and not any(
                    r['property'] == prop and r['value'] == 'none' for r in own) and any(
                    c.get('component_type') == kind and c['params'].get('host_id') == params['component_id'] for c in components):
                params.pop(prop)
    # Legacy "none" on the body meant delegate roofing to a hosted component.
    # Only reconcile when user requirements explicitly request roof material/type;
    # a real user opt-out must still fail rather than being silently overwritten.
    for mass in masses:
        params = mass['params']
        scope = params.get('requirement_scope')
        own = [r for r in requirements if r['scope'] in ('all_main_masses', 'plan', scope)]
        prohibited = any(r['property'] == 'roof_type' and r['value'] == 'none' for r in own)
        requested = any(r['property'] in ('roof_block', 'roof_type') and r['value'] not in (None, 'none') for r in own)
        roofs = [c for c in components if c.get('component_type') in ('ROOF', 'ROOF_STRUCTURE')
                 and c['params'].get('host_id') == params['component_id']]
        if params.get('roof_type') == 'none' and requested and not prohibited and roofs:
            params['roof_type'] = roofs[0]['params'].get('roof_type', 'gable')
    has_slots = bool((out.get('layout') or {}).get('slots'))
    for mass in owners:
        mass['params'].setdefault('building_id', mass['params']['component_id'])
    by_id = {c['params']['component_id']: c for c in components}
    for comp in components:
        params = comp['params']
        attachment = by_id.get(params.get('attachment_host_id'))
        if attachment in owners and attachment is not None:
            # Earlier contracts flattened tier attachments to the root building.
            # Preserve the actual geometric host while retaining building_id.
            params['host_id'] = attachment['params']['component_id']
    for comp in components:
        params = comp['params']
        if comp in owners:
            params.setdefault('building_id', params['component_id'])
            if not params.get('host_id'):
                continue
        if comp.get('component_type') not in HOSTED: continue
        if params.get('host_source') == 'legacy_geometry_inference':
            params.pop('host_id', None)
        if params.get('host_id'):
            # Roof ornaments may name the roof as their host. Resolve its ownership
            # chain to the building before enforcing the building coordinate frame.
            original_host = params['host_id']
            host = original_host
            visited = {params['component_id']}
            while host not in ids and host in by_id and host not in visited:
                visited.add(host)
                parent = by_id[host]['params'].get('host_id')
                if not parent: break
                host = parent
            if host in ids and host != original_host:
                params['attachment_host_id'] = original_host
                params['host_id'] = host
            if params['host_id'] not in ids:
                contract['diagnostics'].append({'code': 'E_HOST_UNKNOWN', 'component_id': params['component_id'], 'host_id': params['host_id']})
            elif has_slots and ids[params['host_id']].get('slot_id') != comp.get('slot_id'):
                frames = {s.get('slot_id', s.get('id')): s for s in slots if isinstance(s, dict)}
                a = frames.get(comp.get('slot_id'), {}).get('anchor')
                b = frames.get(ids[params['host_id']].get('slot_id'), {}).get('anchor')
                pos = comp.get('relative_position')
                if all(isinstance(v, dict) for v in (a, b, pos)) and all(
                        isinstance(v.get(axis), (int, float)) for v in (a, b, pos) for axis in ('x', 'y', 'z')):
                    comp['relative_position'] = {axis: pos[axis] + a[axis] - b[axis] for axis in ('x', 'y', 'z')}
                    comp['slot_id'] = ids[params['host_id']]['slot_id']
                    params['slot_id'] = comp['slot_id']
                    params['building_id'] = ids[params['host_id']]['params']['building_id']
                    params['coordinate_frame_rebased'] = True
                else:
                    contract['diagnostics'].append({'code': 'E_HOST_COORDINATE_FRAME', 'component_id': params['component_id'], 'host_id': params['host_id']})
            else:
                params['building_id'] = ids[params['host_id']]['params']['building_id']
            continue
        # Connectors have two hosts and cannot be represented by a guessed single owner.
        if any('bridge' in str(f).lower() or '连廊' in str(f) for f in comp.get('features', [])): continue
        candidates = [m for m in masses if m.get('slot_id') == comp.get('slot_id')] if has_slots else masses
        if not candidates: continue
        x, z = _center(comp)
        host = min(candidates, key=lambda m: (_center(m)[0]-x)**2 + (_center(m)[1]-z)**2)
        params['host_id'] = host['params']['component_id']
        params['building_id'] = host['params']['building_id']
        params['host_source'] = 'legacy_geometry_inference'
    # Left/right are geometric scopes, never the model's component array order.
    if re.search(r'左栋', text) and re.search(r'右栋', text) and len(masses) == 2:
        frames = {s.get('slot_id'): s for s in slots if isinstance(s, dict)}
        ordered = sorted(masses, key=lambda m: _center(m)[0] + frames.get(m.get('slot_id'), {}).get('anchor', {}).get('x', 0))
        coordinates = [_center(m)[0] + frames.get(m.get('slot_id'), {}).get('anchor', {}).get('x', 0) for m in ordered]
        if coordinates[0] != coordinates[1]:
            for index, mass in enumerate(ordered, 1):
                mass['params']['requirement_scope'] = 'building_' + str(index)
    if len(masses) == 2:
        frames = {s.get('slot_id'): s for s in slots if isinstance(s, dict)}
        for axis, low, high in (('x', 'west', 'east'), ('z', 'north', 'south')):
            requested = {r['scope'] for r in requirements}
            if not requested.intersection({'building_' + low, 'building_' + high}):
                continue
            def world_coordinate(mass):
                center = _center(mass)[0 if axis == 'x' else 1]
                return center + frames.get(mass.get('slot_id'), {}).get('anchor', {}).get(axis, 0)
            ordered = sorted(masses, key=world_coordinate)
            if world_coordinate(ordered[0]) == world_coordinate(ordered[1]):
                contract['diagnostics'].append({'code': 'E_REQUIREMENT_DIRECTION_AMBIGUOUS', 'axis': axis})
                continue
            for mass, direction in zip(ordered, (low, high)):
                mass['params']['requirement_scope'] = 'building_' + direction
    for mass in masses:
        own = [r for r in requirements if r['scope'] in ('all_main_masses', mass['params'].get('requirement_scope'))]
        # A one-cell-short model envelope is a roof/floor datum discrepancy.
        # Preserve declared storeys and spacing; never shrink them to fit.
        fh, count = mass['params'].get('floor_height'), mass['params'].get('floor_count')
        dims = mass.get('dimensions') or {}
        if (type(fh) is int and type(count) is int and fh > 0 and count > 0
                and dims.get('height') == fh * count - 1):
            old_top = mass['relative_position']['y'] + dims['height']
            dims['height'] += 1
            mass['params']['envelope_source'] = 'floor_band_datum'
            for roof in components:
                if (roof.get('component_type') in ('ROOF','ROOF_STRUCTURE')
                        and roof['params'].get('host_id') == mass['params']['component_id']
                        and roof.get('relative_position',{}).get('y') in (old_top-1,old_top)):
                    roof['relative_position']['y'] += 1
        if any(r['property'] == 'glass_block' for r in own):
            mass['params']['glass_block'] = 'minecraft:glass'
            for child in components:
                if child.get('component_type') == 'FACADE_WINDOWS' and child['params'].get('host_id') == mass['params']['component_id']:
                    child['params']['glass_block'] = 'minecraft:glass'
        if any(r['property'] == 'gable_windows' and r['value'] is False for r in own):
            mass['params']['gable_windows'] = False
        clear = next((r['value'] for r in own if r['property'] == 'net_height' and isinstance(r['value'], int)), None)
        if clear is not None:
            floors = max(1, int(mass['params'].get('floor_count', 1)))
            mass['params']['floor_height'] = clear + 1
            mass['dimensions']['height'] = max(mass['dimensions'].get('height', 0), floors * (clear + 1) + 1)
    bindings = {}
    for mass in binding_hosts:
        scope = mass['params'].get('requirement_scope')
        if isinstance(scope, str):
            targets = bindings.setdefault(scope, [])
            if mass not in targets:
                targets.append(mass)
        # Identity parity with Java hostIdentityBlob: component_id, roles, labels, features.
        identity = _host_identity_blob(mass)
        for key, names in NAMED_BUILDINGS.items():
            if any(name and name.lower() in identity for name in names):
                targets = bindings.setdefault('named_' + key, [])
                if mass not in targets:
                    targets.append(mass)
            elif key == 'wing' and re.search(r'(?:^|[_\s-])wing(?:$|[_\s-])', identity):
                targets = bindings.setdefault('named_wing', [])
                if mass not in targets:
                    targets.append(mass)
    if len(masses) == 1:
        bindings.setdefault('named_main_body', masses)
    for tower in owners:
        if tower.get('component_type') == 'TOWER':
            tower_targets = bindings.setdefault('named_tower', [])
            if tower not in tower_targets:
                tower_targets.append(tower)
    contract['scope_bindings'] = {scope: [m['params']['component_id'] for m in targets]
                                  for scope, targets in bindings.items()}
    slots = {slot.get('slot_id'): slot for slot in (out.get('layout') or {}).get('slots', []) if isinstance(slot, dict)}
    # Validate against persisted requirements (same objects as contract['requirements']).
    requirements = contract['requirements']
    for requirement in requirements:
        scope = requirement['scope']
        if scope == 'element_scope':
            continue
        if scope == 'unresolved':
            # Only roof_type may verify on a lone host without claiming all_main_masses.
            # Positive roofs deferred only for chinese_roof_contract typologies.
            # Conflicting floor/dim values (value=None / unsupported_scope) stay unbound.
            sole = design_hosts if len(design_hosts) == 1 else (masses if len(masses) == 1 else [])
            may_bind_single = (
                len(sole) == 1
                and requirement.get('property') == 'roof_type'
                and requirement.get('value') is not None
                and requirement.get('status') not in ('unsupported_scope', 'unresolved')
            )
            if (may_bind_single and requirement.get('value') != 'none'
                    and _defers_positive_roof_to_chinese_contract(sole[0])):
                continue
            if not may_bind_single:
                if (not design_hosts and not masses
                        and requirement.get('status') == 'pending'
                        and requirement.get('property') in _DESIGN_HOST_PROPERTIES):
                    requirement['status'] = 'unverified'
                    contract['diagnostics'].append({
                        'code': 'E_DESIGN_HOST_MISSING',
                        'scope': scope,
                        'requirement_id': requirement['id'],
                        'message': 'explicit design requirement with no design-constraint host',
                    })
                continue
            targets = sole
        elif scope in ('all_main_masses', 'plan'):
            targets = _all_mass_design_targets(requirement, design_hosts, masses)
            if (not targets and requirement.get('status') == 'pending'
                    and requirement.get('property') in _DESIGN_HOST_PROPERTIES
                    and not design_hosts and not masses):
                requirement['status'] = 'unverified'
                contract['diagnostics'].append({
                    'code': 'E_DESIGN_HOST_MISSING',
                    'scope': scope,
                    'requirement_id': requirement['id'],
                    'message': 'all-mass design requirement with no design-constraint host',
                })
                continue
        elif scope == 'multi_named':
            keys = [str(k).strip() for k in (requirement.get('named_keys') or []) if k]
            targets = []
            incomplete = False
            for key in keys:
                found = bindings.get('named_' + key, []) or []
                # Unique / stacked / plural-role coherent hosts per named key.
                uniq = _unique_named_hosts(found, named_key=key)
                if uniq is None:
                    incomplete = True
                    break
                targets.extend(uniq)
            if incomplete or not targets:
                requirement['status'] = 'unverified' if not design_hosts else 'unresolved_binding'
                if design_hosts:
                    contract['diagnostics'].append({
                        'code': 'E_REQUIREMENT_SCOPE',
                        'scope': scope,
                        'requirement_id': requirement['id'],
                        'named_keys': keys,
                        'message': 'multi_named requires every listed subject to bind uniquely',
                    })
                continue
        else:
            targets = bindings.get(scope, [])
            if str(scope).startswith('named_'):
                named_key = str(scope)[len('named_'):]
                collapsed = _unique_named_hosts(targets or [], named_key=named_key)
                if collapsed is None and targets:
                    # Distinct buildings sharing a singular named alias — fail closed.
                    # Plural role classes (wing) and stacked storeys are coherent above.
                    requirement['status'] = 'unverified' if not design_hosts else 'unresolved_binding'
                    if design_hosts:
                        contract['diagnostics'].append({
                            'code': 'E_REQUIREMENT_SCOPE',
                            'scope': scope,
                            'requirement_id': requirement['id'],
                            'message': 'named scope binds multiple distinct buildings',
                        })
                    continue
                if collapsed is not None:
                    targets = collapsed
                # 西厢房 / west wing: keep plural named_wing, but never expand side-local cues.
                if named_key == 'wing' and targets:
                    clause = _requirement_clause_text(requirement, text)
                    sided = _filter_hosts_by_wing_side(targets, clause)
                    if sided is None:
                        requirement['status'] = 'unverified' if not design_hosts else 'unresolved_binding'
                        if design_hosts:
                            contract['diagnostics'].append({
                                'code': 'E_REQUIREMENT_SCOPE',
                                'scope': scope,
                                'requirement_id': requirement['id'],
                                'message': 'wing side cue could not uniquely bind a wing host',
                            })
                        continue
                    targets = sided
        if not targets or (str(scope).startswith('building_') and len(targets) != 1):
            # Envelope dims / materials still key off MASS_MAIN; design omit props use design hosts.
            pool = (
                design_hosts
                if requirement.get('property') in _DESIGN_HOST_PROPERTIES
                else masses
            )
            requirement['status'] = 'unverified' if not pool else 'unresolved_binding'
            if pool:
                contract['diagnostics'].append({
                    'code': 'E_REQUIREMENT_SCOPE',
                    'scope': scope,
                    'requirement_id': requirement['id'],
                })
            elif (
                requirement.get('property') in _DESIGN_HOST_PROPERTIES
                and requirement.get('status') == 'pending'
                and not design_hosts
            ):
                contract['diagnostics'].append({
                    'code': 'E_DESIGN_HOST_MISSING',
                    'scope': scope,
                    'requirement_id': requirement['id'],
                    'message': 'explicit design requirement with no design-constraint host',
                })
            continue
        target_ids = {m['params']['component_id'] for m in targets}
        owned = components if scope in ('all_main_masses', 'plan', 'unresolved', 'multi_named') else [
            c for c in components if c['params'].get('host_id') in target_ids or c['params']['component_id'] in target_ids]
        key, expected = requirement['property'], requirement['value']
        if key == 'no_complex_decor':
            invalid = [c['params']['component_id'] for c in owned if c.get('component_type') in ('CROWN', 'CUPOLA', 'DOME')]
        elif key in ('width', 'depth', 'length'):
            from .resolved_geometry import multi_mass_geometry, body_dimensions
            axis = 'x' if key == 'width' else 'z'
            def envelope_size(component):
                envelope = multi_mass_geometry(component)['envelope_local']
                paired = next((r for r in requirements
                               if key in ('length', 'width') and r['property'] == ('length' if key == 'width' else 'width')
                               and r['scope'] == scope and isinstance(r['value'], int)), None)
                if paired is not None:
                    spans = [envelope['max_x'] - envelope['min_x'], envelope['max_z'] - envelope['min_z']]
                    return expected if sorted(spans) == sorted([expected, paired['value']]) else None
                if key == 'length':
                    return max(envelope['max_x'] - envelope['min_x'], envelope['max_z'] - envelope['min_z'])
                return envelope['max_' + axis] - envelope['min_' + axis]
            requirement['dimension_subject'] = 'building_envelope'
            # Compound churches / annexes: a global width×length binds the dominant
            # footprint per spatially connected cluster, not every apse/wing MASS_MAIN.
            # Separate buildings (non-adjacent in plan coordinates) still each must match.
            dim_targets = targets
            if scope in ('all_main_masses', 'plan') and len(targets) > 1:
                plan_slots = {
                    s.get('slot_id'): s
                    for s in ((out.get('layout') or {}).get('slots') or [])
                    if isinstance(s, dict) and s.get('slot_id')
                }

                def _plan_envelope(component, gap=0):
                    env = multi_mass_geometry(component)['envelope_local']
                    anchor = (plan_slots.get(component.get('slot_id')) or {}).get('anchor') or {}
                    ox = int(anchor.get('x', 0) or 0)
                    oz = int(anchor.get('z', 0) or 0)
                    return {
                        'min_x': env['min_x'] + ox - gap,
                        'max_x': env['max_x'] + ox + gap,
                        'min_z': env['min_z'] + oz - gap,
                        'max_z': env['max_z'] + oz + gap,
                    }

                def _near(a, b, gap=2):
                    return not (a['max_x'] + gap < b['min_x'] or b['max_x'] + gap < a['min_x']
                                or a['max_z'] + gap < b['min_z'] or b['max_z'] + gap < a['min_z'])

                envelopes = [_plan_envelope(c) for c in targets]
                parent = list(range(len(targets)))

                def _find(i):
                    while parent[i] != i:
                        parent[i] = parent[parent[i]]
                        i = parent[i]
                    return i

                for i, a in enumerate(envelopes):
                    for j in range(i + 1, len(envelopes)):
                        if _near(a, envelopes[j]):
                            ri, rj = _find(i), _find(j)
                            if ri != rj:
                                parent[rj] = ri
                clusters = {}
                for i, component in enumerate(targets):
                    clusters.setdefault(_find(i), []).append(component)
                dim_targets = []
                for members in clusters.values():
                    if len(members) == 1:
                        dim_targets.append(members[0])
                    else:
                        dim_targets.append(max(
                            members,
                            key=lambda c: max(1, body_dimensions(c).get('width', 1))
                            * max(1, body_dimensions(c).get('depth', 1))))
            invalid = [c['params']['component_id'] for c in dim_targets if envelope_size(c) != expected]
        elif key == 'floor_count':
            def storeys(c):
                p = c['params']
                tiers = [c] + [t for t in components if t.get('component_type') == 'MASS_SECONDARY'
                               and t['params'].get('host_id') == p['component_id']
                               and 'tier_index' in t['params'] and t['params'].get('extrude_mode') != 'plate']
                tiers.sort(key=lambda t: t['relative_position']['y'])
                if (len(tiers) > 1 and 'tier_index' in p
                        and all(a.get('slot_id') == b.get('slot_id')
                                and a['relative_position']['y'] + a['dimensions']['height'] == b['relative_position']['y']
                                for a,b in zip(tiers,tiers[1:]))):
                    return sum(int(t['params'].get('floor_count',1)) for t in tiers)
                return p.get(key)
            invalid = [c['params']['component_id'] for c in targets if storeys(c) != expected]
        elif key in ('floor_height', 'wall_block', 'floor_block', 'window_style', 'entrance_type', 'gable_windows', 'glass_block'):
            invalid = [c['params']['component_id'] for c in targets if c['params'].get(key) != expected]
        elif key == 'net_height':
            invalid = [c['params']['component_id'] for c in targets
                       if c['dimensions']['height'] < int(c['params'].get('floor_count', 1)) * (expected + 1) + 1]
        elif key == 'entrance_facing':
            def facing(c):
                slot = slots.get(c.get('slot_id'))
                if slot is not None:
                    runtime = slot.get('facing') or 'SOUTH'
                else:
                    runtime = (out.get('global_constraints') or {}).get('facing') or 'SOUTH'
                return LEGACY_ENTRANCE_FACING.get(runtime)
            invalid = [c['params']['component_id'] for c in targets if facing(c) != expected]
        elif key in ('roof_type', 'roof_block'):
            invalid = []
            for mass in targets:
                roofs = [c for c in owned if c.get('component_type') in ('ROOF', 'ROOF_STRUCTURE')
                         and c['params'].get('host_id') == mass['params']['component_id']]
                # Capability table first — param equality is never proof the generator honors roof_type.
                if key == 'roof_type' and expected is not None and _is_typology_structure(mass):
                    supported = typology_supports_roof_type(_typology_id(mass), expected)
                    if supported is False:
                        cid = mass['params']['component_id']
                        invalid.append(cid)
                        contract['diagnostics'].append({
                            'code': 'E_TYPOLOGY_ROOF_UNSUPPORTED',
                            'component_id': cid,
                            'typology_id': _typology_id(mass),
                            'requested': expected,
                            'message': 'typology STRUCTURE cannot execute plan roof_type; capability_gap',
                        })
                        continue
                    if _defers_positive_roof_to_chinese_contract(mass) and expected not in (None, 'none'):
                        # chinese_roof_contract owns positive hall forms.
                        continue
                invalid += [c['params']['component_id'] for c in (roofs or [mass])
                            if c['params'].get(key, mass['params'].get(key) if key == 'roof_block' else None) != expected]
        else:
            continue
        requirement['status'] = 'mismatch' if invalid else 'planned'
        requirement['target_components'] = sorted(target_ids)
        requirement['mismatched_components'] = invalid
    _clamp_entrance_door_widths(components)
    _repair_site_foundation_datum(out, components)
    from .resolved_geometry import resolve_buildings
    contract['resolved_buildings'] = resolve_buildings(out)
    for building in contract['resolved_buildings']:
        if not building['floor_layout_fits']:
            contract['diagnostics'].append({'code': 'E_FLOOR_ENVELOPE', 'component_id': building['component_id']})
    failures = [r for r in requirements if r['status'] == 'mismatch']
    previous_gap = out.get('capability_gap') or {}
    if previous_gap.get('code') == 'E_BUILDING_CONTRACT' \
            and previous_gap.get('path') == 'proportion_hints.building_contract':
        out.pop('capability_gap', None)
        out.pop('plan_status', None)
    if finalize and not out.get('capability_gap') and (failures or contract['diagnostics']):
        out['plan_status'] = 'capability_gap'
        out['capability_gap'] = {'code': 'E_BUILDING_CONTRACT', 'message': '建筑方案未满足明确要求或主体引用无效',
                                 'path': 'proportion_hints.building_contract',
                                 'suggestions': [f"{r['id']}: expected {r['value']}, components {r['mismatched_components']}" for r in failures]
                                                + [str(d) for d in contract['diagnostics']]}
    from .window_language_contract import apply_window_language_contract
    apply_window_language_contract(out, text)
    from .building_use_intent import record_building_use_intent
    record_building_use_intent(out, text)
    from .building_use_audit import audit_building_use
    audit_building_use(out)
    from .ornament_contract import apply_ornament_contracts
    apply_ornament_contracts(out, text)
    from .chinese_roof_contract import apply_chinese_roof_contract
    apply_chinese_roof_contract(out, text)
    from .chinese_material_contract import apply_chinese_material_contract
    apply_chinese_material_contract(out, text)
    return out


def _clamp_entrance_door_widths(components: list[dict]) -> None:
    """Pre-normalize oversized door_width so a thin balcony/service bay cannot abort the build."""
    for comp in components:
        if not isinstance(comp, dict) or comp.get('component_type') != 'ENTRANCE':
            continue
        params = comp.setdefault('params', {})
        if not isinstance(params, dict):
            continue
        dims = comp.get('dimensions') if isinstance(comp.get('dimensions'), dict) else {}
        try:
            width = int(dims.get('width') or 0)
            depth = int(dims.get('depth') or 0)
        except (TypeError, ValueError):
            continue
        facing = str(params.get('facing') or params.get('entrance_facing') or '').upper()
        if width == 1 and depth > 1:
            span = depth
        elif depth == 1 and width > 1:
            span = width
        elif facing in ('EAST', 'WEST'):
            span = depth if depth > 0 else width
        else:
            span = width if width > 0 else depth
        if span < 2:
            continue
        max_door = span - 1
        for key in ('door_width', 'doorWidth'):
            if key not in params or params[key] is None:
                continue
            try:
                door_width = int(params[key])
            except (TypeError, ValueError):
                continue
            if door_width > max_door:
                params[key] = max_door
                params['door_width_clamped_from'] = door_width
                params['door_width_clamp_span'] = span


def _repair_site_foundation_datum(plan: dict, components: list[dict]) -> None:
    """Put a shared buried foundation below the compound's authored ground floors."""
    from .resolved_geometry import mass_origin
    slots = {s.get('slot_id',s.get('id')):s.get('anchor') or {} for s in (plan.get('layout') or {}).get('slots',[])}
    def origin(c):
        p=mass_origin(c); a=slots.get(c.get('slot_id'),{})
        return {k:p[k]+a.get(k,0) for k in ('x','y','z')}
    for foundation in components:
        p=foundation.get('params') or {}; d=foundation.get('dimensions') or {}
        if foundation.get('component_type')!='FOUNDATION' or p.get('host_id') or not d.get('height'): continue
        fo=origin(foundation); bodies=[]
        for body in components:
            if body.get('component_type') not in ('MASS_MAIN','MASS_SECONDARY','TOWER','MASS_WING','SIDE_WING'): continue
            bd=body.get('dimensions') or {}; bp=body.get('params') or {}
            if bp.get('extrude_mode')=='plate' or bd.get('height',0)<3: continue
            bo=origin(body)
            if bo['y'] != fo['y']: continue
            if all(fo[k] <= bo[k] and bo[k]+bd[size] <= fo[k]+d[size] for k,size in (('x','width'),('z','depth'))):
                bodies.append(body)
        if len({b.get('slot_id') for b in bodies}) < 2: continue
        foundation['relative_position']['y'] -= min(5,d['height'])
        p['foundation_datum_source']='shared_compound_ground_floor'


def _repair_compound_courtyard_inheritance(plan: dict, components: list[dict]) -> None:
    """Repair legacy genome defaults only where a separate site court proves ownership.

    An authored ring surrounding its courtyard overlaps that courtyard's AABB;
    independent wings outside it must not each acquire another central hole.
    """
    if 'courtyard' not in (plan.get('genome') or {}).get('modules', []):
        return
    slots = {s.get('slot_id'): s.get('anchor') or {} for s in (plan.get('layout') or {}).get('slots', [])}
    from .resolved_geometry import mass_origin
    def bounds(c):
        o = (dict(c['relative_position']) if c.get('component_type') in ('COURTYARD','COURTYARD_SPACE')
             and not (c.get('params') or {}).get('anchor_mode') else mass_origin(c))
        off = slots.get(c.get('slot_id'), {})
        x, z = o['x']+off.get('x', 0), o['z']+off.get('z', 0)
        d = c['dimensions']
        return x, z, x+d['width'], z+d['depth']
    courts = [bounds(c) for c in components if c.get('component_type') in ('COURTYARD', 'COURTYARD_SPACE')
              and c.get('dimensions')]
    if not courts:
        return
    for c in components:
        p = c.get('params') or {}
        if c.get('component_type') not in ('MASS_MAIN', 'MASS_WING', 'SIDE_WING', 'MASS_ANCILLARY', 'MASS_SECONDARY'):
            continue
        if p.get('plan_type') != 'courtyard' or p.get('courtyard_ratio') != p.get('void_ratio'):
            continue
        if any('courtyard' in str(f).lower() or 'siheyuan' in str(f).lower() for f in c.get('features', [])):
            continue
        x0, z0, x1, z1 = bounds(c)
        if any(x0 < b[2] and b[0] < x1 and z0 < b[3] and b[1] < z1 for b in courts):
            continue
        p['plan_type'] = 'none'
        p.pop('courtyard_ratio', None)
        p['plan_type_source'] = 'separate_site_courtyard'
