"""Nonblocking hosted plan audit; component presence is not traversability proof."""

def audit_building_use(plan: dict) -> None:
    components = [c for c in plan.get('components', []) if isinstance(c, dict)]
    intent = plan.get('proportion_hints', {}).get('building_use_intent', {})
    rows = []
    for binding in intent.get('bindings', []):
        if binding.get('status') != 'bound':
            continue
        for identity in binding.get('target_components', []):
            masses = [c for c in components if c.get('component_type') == 'MASS_MAIN'
                      and c.get('params', {}).get('component_id') == identity]
            if len(masses) != 1:
                rows.append({'building_id': identity, 'status': 'ambiguous_host', 'checks': {}})
                continue
            mass = masses[0]
            children = [c for c in components if c.get('params', {}).get('host_id') == identity]
            entrances = [c for c in children if c.get('component_type') == 'ENTRANCE']
            stairs = [c for c in children if c.get('component_type') == 'STRUCTURE'
                      and (any(str(f).startswith('stair:') for f in c.get('features', []))
                           or any(op.get('op') == 'STAIR_SYSTEM' for op in (c.get('params', {}).get('assembly') or {}).get('ops', []) if isinstance(op, dict)))]
            params = mass.get('params', {})
            try:
                floors = int(params.get('floor_count', 0))
            except (ValueError, TypeError):
                floors = 0
            checks = {'entrance_component': 'declared' if entrances else 'not_declared',
                      'interfloor_circulation': 'not_required' if floors == 1 else
                          'declared_not_verified' if floors > 1 and stairs else
                          'not_declared' if floors > 1 else 'unknown_floor_count',
                      'interior_clearance': 'not_assessed', 'final_block_traversability': 'not_assessed'}
            rows.append({'building_id': identity, 'uses': binding['uses'], 'status': 'plan_review',
                         'checks': checks, 'entrance_components': [c.get('params', {}).get('component_id') for c in entrances],
                         'stair_components': [c.get('params', {}).get('component_id') for c in stairs],
                         'notes': ['An entrance may also be generated inline by the main mass; missing ENTRANCE is not proof of a missing door.',
                                   'Declared stairs do not prove connection to every floor or final clearance.']})
    plan.setdefault('proportion_hints', {})['building_use_audit'] = {
        'schema': 'formacraft.building_use_audit.v1', 'verification_level': 'plan_components_only',
        'blocking': False, 'buildings': rows}
