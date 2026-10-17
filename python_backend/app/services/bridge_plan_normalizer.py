"""Bounded repairs of model-authored hosted floors and short bridge connections."""
from .resolved_geometry import mass_origin


def normalize_hosted_connections(plan: dict) -> None:
    if (plan.get('plan_meta') or {}).get('allow_compiler_satellites') is False:
        return
    components = plan.get('components') or []
    slots = {s.get('slot_id'): s.get('anchor') or {} for s in (plan.get('layout') or {}).get('slots', [])}
    bodies = [c for c in components if c.get('component_type') in
              ('MASS_MAIN', 'MAIN_MASS', 'MASS_SECONDARY', 'MASS_WING', 'SIDE_WING', 'TOWER', 'MASS_ANCILLARY')
              and (c.get('params') or {}).get('extrude_mode') != 'plate' and c.get('dimensions')]
    by_id = {(c.get('params') or {}).get('component_id'): c for c in bodies}
    all_by_id = {(c.get('params') or {}).get('component_id'): c for c in components}

    def bounds(c):
        origin = mass_origin(c)
        offset = slots.get(c.get('slot_id'), {})
        low = {a: origin[a] + int(offset.get(a, 0)) for a in ('x', 'y', 'z')}
        return low, {a: low[a] + int(c['dimensions'][d]) for a, d in (('x', 'width'), ('y', 'height'), ('z', 'depth'))}

    def overlap(a, b):
        al, ah = bounds(a); bl, bh = bounds(b)
        return max(0, min(ah['x'], bh['x'])-max(al['x'], bl['x'])) * max(0, min(ah['z'], bh['z'])-max(al['z'], bl['z']))

    def touches(plate, body):
        pl, ph = bounds(plate); bl, bh = bounds(body)
        return (bl['y'] <= pl['y'] <= bh['y'] and all(
            pl[a]-1 < bh[a] and ph[a]+1 > bl[a] for a in ('x', 'z')))

    def record(c, reason):
        repairs = plan.setdefault('proportion_hints', {}).setdefault('connection_repairs', [])
        entry = {'component_id': c['params'].get('component_id'), 'reason': reason}
        if entry not in repairs:
            repairs.append(entry)

    # Older contract versions shortened detached tier-indexed wings. Their authored
    # floor plates provide explicit evidence of the lost storeys and intended host.
    detached = []
    for body in bodies:
        p = body.get('params') or {}
        root = by_id.get(p.get('host_id'))
        if 'tier_index' in p and root is not None and overlap(root, body) == 0:
            detached.extend([root, body])
    detached_ids = {id(c) for c in detached}
    for plate in components:
        p = plate.get('params') or {}
        if p.get('extrude_mode') != 'plate' or p.get('plate_role', 'interior') not in ('interior', 'floor', 'slab', 'gallery'):
            continue
        root = by_id.get(p.get('host_id'))
        if root is None or id(root) not in detached_ids:
            continue
        candidates = [(overlap(plate, b), b) for b in bodies if id(b) in detached_ids]
        candidates.sort(key=lambda item: item[0], reverse=True)
        if not candidates or candidates[0][0] <= 0 or len(candidates)>1 and candidates[0][0] == candidates[1][0]:
            continue
        host = candidates[0][1]
        if candidates[0][0] < plate['dimensions']['width'] * plate['dimensions']['depth'] / 2:
            continue
        low, _ = bounds(host); floor_y = bounds(plate)[0]['y']
        hp = host.setdefault('params', {})
        rise = int(hp.get('floor_height') or 0)
        if rise < 3 or floor_y < low['y'] or (floor_y-low['y']) % rise:
            continue
        count = (floor_y-low['y']) // rise + 1
        if count > 8 or count*rise > 32:
            continue
        if p.get('host_id') != hp.get('component_id'):
            p['host_id'] = hp['component_id']
            p['building_id'] = hp.get('building_id', hp['component_id'])
            record(plate, 'detached_volume_floor_host')
        if host['dimensions']['height'] < count*rise:
            host['dimensions']['height'] = count*rise
            hp['floor_count'] = max(int(hp.get('floor_count') or 1), count)
            record(host, 'restore_authored_floor_envelope')

    for satellite in components:
        p = satellite.get('params') or {}
        if satellite.get('component_type') not in ('ROOF', 'ROOF_STRUCTURE', 'FACADE_WINDOWS', 'FACADE'):
            continue
        root = by_id.get(p.get('host_id'))
        if root is None or id(root) not in detached_ids or not satellite.get('dimensions'):
            continue
        candidates = sorted([(overlap(satellite, b), b) for b in bodies if id(b) in detached_ids],
                            key=lambda item: item[0], reverse=True)
        if not candidates or candidates[0][0] <= 0 or len(candidates)>1 and candidates[0][0] == candidates[1][0]:
            continue
        host = candidates[0][1]
        if p.get('host_id') != host['params'].get('component_id'):
            p['host_id'] = host['params']['component_id']
            p['building_id'] = host['params'].get('building_id', p['host_id'])
            record(satellite, 'detached_volume_satellite_host')

    for plate in components:
        p = plate.get('params') or {}
        if p.get('extrude_mode') != 'plate' or p.get('plate_role') not in ('bridge', 'link', 'connector'):
            continue
        host, target = by_id.get(p.get('host_id')), by_id.get(p.get('target_host_id'))
        if host is None or target is None:
            continue
        if not touches(plate, host):
            candidates = [b for b in bodies if b is not target and touches(plate, b)]
            if len(candidates) == 1:
                host = candidates[0]
                p['host_id'] = host['params']['component_id']
                p['building_id'] = host['params'].get('building_id', p['host_id'])
                record(plate, 'bridge_endpoint_host')
        pl, ph = bounds(plate); hl, hh = bounds(host); tl, th = bounds(target)
        level = p.get('connection_level')
        rise = int(host.get('params', {}).get('floor_height') or 0)
        if isinstance(level, int) and rise > 0 and touches(plate, host) and touches(plate, target):
            if level in (plate['relative_position']['y'], pl['y']) and hl['y']+level*rise > hh['y']:
                p['legacy_connection_height'] = p.pop('connection_level')
                record(plate, 'connection_height_in_blocks')
        if not touches(plate, host) or touches(plate, target):
            continue
        # Extend a short, straight shortfall only. Keep Y, transverse span and all
        # building positions; distant or diagonal connections still fail validation.
        for axis, transverse, size in (('x', 'z', 'width'), ('z', 'x', 'depth')):
            cross = min(ph[transverse], th[transverse], hh[transverse]) - max(pl[transverse], tl[transverse], hl[transverse])
            if cross <= 0 or not tl['y'] <= pl['y'] <= th['y']:
                continue
            if ph[axis] < tl[axis] and 0 < tl[axis]-ph[axis] <= 4:
                plate['dimensions'][size] += tl[axis]-ph[axis]
            elif pl[axis] > th[axis] and 0 < pl[axis]-th[axis] <= 4:
                gap = pl[axis]-th[axis]
                plate['relative_position'][axis] -= gap
                plate['dimensions'][size] += gap
            else:
                continue
            record(plate, 'short_bridge_span')
            for canopy in components:
                cp = canopy.get('params') or {}
                if cp.get('host_id') == p.get('component_id') and cp.get('extrude_mode') == 'plate' and cp.get('plate_role') in ('roof', 'canopy'):
                    for coord in ('x', 'z'):
                        canopy['relative_position'][coord] = plate['relative_position'][coord]
                    for dimension in ('width', 'depth'):
                        canopy['dimensions'][dimension] = plate['dimensions'][dimension]
                    record(canopy, 'short_bridge_roof_span')
            # A bridge into a single-height open workshop needs a small landing,
            # not a new full storey spanning the whole production hall.
            tp = target.get('params') or {}
            landing_id = str(p.get('component_id', 'bridge')) + '#target_landing'
            if (int(tp.get('floor_count') or 1) == 1 and tl['y'] < pl['y']
                    and pl['y'] + 2 < th['y'] and landing_id not in all_by_id
                    and min(target['dimensions']['width'], target['dimensions']['depth']) >= 5):
                landing_origin = dict(tl)
                landing_origin[axis] = tl[axis] if pl[axis] < tl[axis] else th[axis]-3
                landing_origin[transverse] = max(tl[transverse], min(pl[transverse], th[transverse]-3))
                landing_origin['y'] = pl['y']
                offset = slots.get(target.get('slot_id'), {})
                landing = {'component_type': 'MASS_SECONDARY', 'slot_id': target.get('slot_id'),
                           'relative_position': {a: landing_origin[a]-int(offset.get(a, 0)) for a in ('x', 'y', 'z')},
                           'dimensions': {'width': 3, 'depth': 3, 'height': 1}, 'features': ['bridge_landing'],
                           'params': {'component_id': landing_id, 'host_id': tp['component_id'],
                                      'building_id': tp.get('building_id', tp['component_id']),
                                      'anchor_mode': 'min_corner', 'extrude_mode': 'plate', 'plate_role': 'interior',
                                      'material': tp.get('floor_block', 'minecraft:stone_bricks')}}
                components.append(landing)
                record(landing, 'bridge_target_landing')
            break

    for plate in components:
        p = plate.get('params') or {}
        if p.get('extrude_mode') != 'plate' or p.get('plate_role') not in ('roof', 'canopy'):
            continue
        if p.get('roof_block') and not p.get('material'):
            p['material'] = p['roof_block']
        host = all_by_id.get(p.get('host_id'))
        seen = set()
        while host is not None and (host.get('params') or {}).get('extrude_mode') == 'plate' and id(host) not in seen:
            seen.add(id(host))
            host = all_by_id.get((host.get('params') or {}).get('host_id'))
        if host is not None and id(host) not in seen and (host.get('params') or {}).get('component_id') in by_id:
            if p.get('host_id') != host['params']['component_id']:
                p['host_id'] = host['params']['component_id']
                record(plate, 'canopy_building_host')
