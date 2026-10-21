"""Declarative optional elements on existing typology hosts; no geometry inference."""
from copy import deepcopy
from functools import lru_cache
import json
from pathlib import Path
import re


_ROOT = Path(__file__).resolve().parents[3] / 'src/main/resources/assets/formacraft/ornament_contracts'
_SCHEMA = 'formacraft.ornament_contract.v1'


def _pattern(value, label):
    if not isinstance(value, str) or not value:
        raise ValueError(f'{label}: expected a nonempty regex')
    try:
        re.compile(value, re.I)
    except re.error as exc:
        raise ValueError(f'{label}: invalid regex: {exc}') from exc


def validate_ornament_contract(definition: dict) -> None:
    """Fail at asset loading, before any plan parameters can be modified."""
    if not isinstance(definition, dict):
        raise ValueError('ornament contract must be an object')
    if definition.get('schema') != _SCHEMA:
        raise ValueError('unsupported ornament contract schema')
    if not isinstance(definition.get('id'), str) or not definition['id']:
        raise ValueError('ornament contract requires an id')
    target = definition.get('target') or {}
    if (not isinstance(target, dict) or not isinstance(target.get('component_type'), str)
            or not target['component_type']):
        raise ValueError('ornament contract requires a component type and typology')
    styles = target.get('style_ids')
    if 'typology_id' in target and (not isinstance(target['typology_id'], str) or not target['typology_id']):
        raise ValueError('ornament typology_id must be a nonempty string')
    if styles is not None and (not isinstance(styles, list) or not styles
                              or any(not isinstance(style, str) or not style for style in styles)):
        raise ValueError('ornament style_ids must be a nonempty string list')
    if not isinstance(target.get('typology_id'), str) or not target.get('typology_id'):
        if not styles:
            raise ValueError('ornament contract requires a typology or style_ids')
    if definition.get('application') not in ('standalone', 'collect'):
        raise ValueError('ornament contract application must be standalone or collect')
    _pattern(definition.get('unresolved_scope_pattern'), 'unresolved_scope_pattern')
    if definition['application'] == 'standalone':
        report = definition.get('report') or {}
        if (not isinstance(report, dict) or not isinstance(report.get('key'), str)
                or not isinstance(report.get('schema'), str) or not report['key'] or not report['schema']):
            raise ValueError('standalone ornament contract requires report identity')
    if not isinstance(definition.get('toggles', []), list) or not isinstance(definition.get('choices', []), list):
        raise ValueError('toggles and choices must be arrays')
    properties = set()
    for toggle in definition.get('toggles', []):
        if not isinstance(toggle, dict):
            raise ValueError('toggle must be an object')
        key = toggle.get('property')
        if not isinstance(key, str) or not key or key in properties:
            raise ValueError('toggle properties must be nonempty and unique')
        properties.add(key)
        if not toggle.get('negative') and not toggle.get('positive'):
            raise ValueError(f'{key}: toggle requires a pattern')
        for field in ('negative', 'positive'):
            if field in toggle:
                _pattern(toggle[field], f'{key}.{field}')
        if ('positive_verification_level' in toggle
                and toggle['positive_verification_level'] != 'plan_parameters_only'):
            raise ValueError('ornament toggles can only report plan parameter verification')
    for choice in definition.get('choices', []):
        if not isinstance(choice, dict):
            raise ValueError('choice must be an object')
        _pattern(choice.get('pattern'), 'choice.pattern')
        if 'unless' in choice:
            _pattern(choice['unless'], 'choice.unless')
        if not isinstance(choice.get('params'), dict) or not choice['params']:
            raise ValueError('choice requires parameter values')
        if not isinstance(choice.get('report_overrides', []), list):
            raise ValueError('report overrides must be an array')
        for override in choice.get('report_overrides', []):
            if not isinstance(override, dict) or override.get('property') not in properties:
                raise ValueError('report override references an unknown toggle')
            if not isinstance(override.get('updates'), dict):
                raise ValueError('report override requires updates')
            if set(override['updates']) - {'value', 'overridden_by'}:
                raise ValueError('report overrides can only change value and override attribution')
    bindings = definition.get('building_requirement_bindings', {})
    if not isinstance(bindings, dict) or any(not isinstance(key, str) or not isinstance(value, str)
                                             for key, value in bindings.items()):
        raise ValueError('building requirement bindings must map property names to parameter names')


@lru_cache(maxsize=4)
def _load_contracts(directory: Path) -> dict:
    contracts = {}
    reports = set()
    for path in sorted(directory.glob('*.json')):
        definition = json.loads(path.read_text(encoding='utf-8'))
        validate_ornament_contract(definition)
        if definition['id'] != path.stem:
            raise ValueError(f'{path.name}: contract id must match filename')
        if definition['application'] == 'standalone':
            key = definition['report']['key']
            if key in reports:
                raise ValueError(f'{path.name}: duplicate ornament report key: {key}')
            reports.add(key)
        contracts[definition['id']] = definition
    return contracts


def get_ornament_contract(contract_id: str) -> dict:
    return deepcopy(_load_contracts(_ROOT)[contract_id])


def ornament_targets(plan: dict, definition: dict) -> list[dict]:
    target = definition['target']
    from .style_identity import canonical_style_id
    styles = {canonical_style_id(style) for style in target.get('style_ids', [])}
    return [component for component in plan.get('components', []) if isinstance(component, dict)
            and component.get('component_type') == target['component_type']
            and (not target.get('typology_id')
                 or (component.get('params') or {}).get('typology_id') == target['typology_id']
                 or f"typology:{target['typology_id']}" in (component.get('features') or []))
            and (not styles or canonical_style_id((component.get('params') or {}).get('style_profile')
                 or component.get('style_profile') or component.get('_style_hint') or plan.get('style_profile')) in styles)]


def has_unresolved_ornament_scope(text: str, definition: dict) -> bool:
    return bool(re.search(definition['unresolved_scope_pattern'], text, re.I))


def collect_ornament_hits(text: str, definition: dict) -> dict:
    """Preserve every occurrence in text order for a caller's atomic conflict policy."""
    hits = {}
    for toggle in definition.get('toggles', []):
        entries = []
        negatives = list(re.finditer(toggle.get('negative', r'(?!)'), text, re.I))
        for field, value in (('negative', False), ('positive', True)):
            for match in re.finditer(toggle.get(field, r'(?!)'), text, re.I):
                if value and any(negative.start() <= match.start() < negative.end() for negative in negatives):
                    continue
                entries.append((match.start(), value, match[0]))
        if entries:
            hits[toggle['property']] = [(value, source) for _, value, source in sorted(entries)]
    return hits


def apply_ornament_contract(plan: dict, text: str, contract_id: str) -> None:
    definition = get_ornament_contract(contract_id)
    if definition['application'] != 'standalone':
        raise ValueError('collect contracts must be applied by their atomic owner')
    targets = ornament_targets(plan, definition)
    unique_scope = len(targets) == 1 and not has_unresolved_ornament_scope(text, definition)
    report = []
    negative_keys = set()
    for toggle in definition.get('toggles', []):
        match = re.search(toggle.get('negative', r'(?!)'), text, re.I)
        if not match:
            continue
        key = toggle['property']
        negative_keys.add(key)
        ids = []
        if unique_scope:
            params = targets[0].setdefault('params', {})
            params[key] = False
            ids = [params.get('component_id')]
        report.append({'property': key, 'value': False, 'source_text': match[0],
                       'status': 'planned' if unique_scope else 'unresolved_building_scope',
                       'target_components': ids})
    if unique_scope:
        params = targets[0].setdefault('params', {})
        for toggle in definition.get('toggles', []):
            key = toggle['property']
            if key in negative_keys or not re.search(toggle.get('positive', r'(?!)'), text, re.I):
                continue
            params[key] = True
            entry = {'property': key, 'value': True, 'status': 'planned',
                     'target_components': [params.get('component_id')]}
            if toggle.get('positive_verification_level'):
                entry['verification_level'] = toggle['positive_verification_level']
            report.append(entry)
        for choice in definition.get('choices', []):
            if (not re.search(choice['pattern'], text, re.I)
                    or re.search(choice.get('unless', r'(?!)'), text, re.I)):
                continue
            params.update(deepcopy(choice['params']))
            for override in choice.get('report_overrides', []):
                for entry in report:
                    if entry['property'] == override['property']:
                        entry.update(deepcopy(override['updates']))
            break
        # Native typologies already execute these exact building-contract parameters.
        bindings = definition.get('building_requirement_bindings', {})
        for requirement in plan.get('proportion_hints', {}).get('building_contract', {}).get('requirements', []):
            key = bindings.get(requirement.get('property'))
            if key and requirement.get('scope') == 'all_main_masses' and params.get(key) == requirement['value']:
                requirement.update(status='planned', target_components=[params.get('component_id')],
                                   verification_level='native_parameters_only')
    identity = definition['report']
    plan.setdefault('proportion_hints', {})[identity['key']] = {
        'schema': identity['schema'], 'requirements': report, 'verification_level': 'plan_parameters_only'}


def apply_ornament_contracts(plan: dict, text: str) -> None:
    """New standalone asset files participate without a per-style Python call site."""
    if str(plan.get('mode', 'build')).lower() == 'patch':
        return
    for contract_id, definition in _load_contracts(_ROOT).items():
        if definition['application'] == 'standalone':
            apply_ornament_contract(plan, text, contract_id)
