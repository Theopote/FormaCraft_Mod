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
    # A full-size floor starting exactly at its corner-anchored host is evidence
    # of the planner's local corner convention, rather than a centered slab.
    corner_frames = set()
    for c in components:
        p=c.get('params') or {}; host=by_id.get(p.get('host_id'))
        if p.get('extrude_mode')!='plate' or not host or p.get('anchor_mode'): continue
        if 'corner' not in str((host.get('params') or {}).get('anchor_mode','')): continue
        if all(c['dimensions'][a]==host['dimensions'][a] for a in ('width','depth')) and all(
                c['relative_position'][a]==mass_origin(host)[a] for a in ('x','z')):
            corner_frames.add(c.get('slot_id'))
    for _ in range(len(components)):
        previous=set(corner_frames)
        for c in components:
            p=c.get('params') or {}; target=by_id.get(p.get('target_host_id'))
            if (p.get('extrude_mode')=='plate' and not p.get('anchor_mode') and target
                    and target.get('slot_id') in corner_frames): corner_frames.add(c.get('slot_id'))
        if previous==corner_frames: break
    for c in components:
        p=c.get('params') or {}
        if p.get('extrude_mode')!='plate' or p.get('anchor_mode'): continue
        target=by_id.get(p.get('target_host_id'))
        if c.get('slot_id') in corner_frames or target and target.get('slot_id') in corner_frames:
            p['anchor_mode']='min_corner'
            p['coordinate_frame_source']='full_floor_corner_evidence'

    # A one-block standalone landing is a terrace plate, not a miniature house.
    for body in bodies:
        p = body.get('params') or {}
        if (p.get('plate_role') == 'landing' or p.get('role') in ('bank_landing','quay')) and body['dimensions']['height'] == 1 and not p.get('host_id'):
            p.update(extrude_mode='plate', plate_role='terrace', anchor_mode='min_corner')

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

    for body in bodies:
        p = body.get('params') or {}
        clear = p.get('clear_height')
        height = body['dimensions']['height']
        if (p.get('interior_open') is True and isinstance(clear, (int, float))
                and clear >= height-2 and int(p.get('floor_count') or 1) > 1
                and clear > int(p.get('floor_height') or height)):
            p['floor_count'] = 1
            p['floor_height'] = height
            record(body, 'preserve_explicit_clear_height')

    for plate in components:
        p = plate.get('params') or {}
        if p.get('extrude_mode') != 'plate' or p.get('plate_role') != 'tower_floor':
            continue
        candidates = [b for b in bodies if b.get('component_type') == 'TOWER'
                      and overlap(plate, b) >= plate['dimensions']['width'] * plate['dimensions']['depth'] * 0.9
                      and bounds(b)[0]['y'] <= bounds(plate)[0]['y'] <= bounds(b)[1]['y']]
        if len(candidates) == 1:
            tower = candidates[0]
            root = by_id.get(p.get('host_id'))
            tp = tower['params']
            if root is not None and root is not tower and not tp.get('host_id') and overlap(root, tower) > 0:
                tp['host_id'] = root['params']['component_id']
                record(tower, 'integrated_tower_host')
            p['host_id'] = tp['component_id']
            p['building_id'] = tp.get('building_id', tp['component_id'])
            p['plate_role'] = 'interior'
            record(plate, 'tower_floor_host')

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
        def crosses_water_channel():
            pl,ph=bounds(plate)
            for water in components:
                wp=water.get('params') or {}
                if wp.get('plate_role') not in ('water','water_channel','canal','channel') or not water.get('dimensions'): continue
                wl,wh=bounds(water)
                if not 0 <= pl['y']-wl['y'] <= 2: continue
                for axis,side in (('x','z'),('z','x')):
                    if pl[axis]<wl[axis] and ph[axis]>wh[axis] and wl[side]<=pl[side] and ph[side]<=wh[side]: return True
            return False
        if target is None and not p.get('target_host_id') and ('bridge_over_channel' in plate.get('features', []) or crosses_water_channel()):
            # A footbridge crossing water has no second building endpoint.
            p['plate_role'] = 'terrace'
            p['connection_source'] = 'standalone_channel_crossing'
            if 'connection_level' in p: p['legacy_connection_level'] = p.pop('connection_level')
            continue
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
        if (level == 0 and target['dimensions']['height'] == 1 and (target.get('params') or {}).get('role') in ('bank_landing','quay')
                and abs(pl['y']-tl['y']) == 1 and touches(plate,target)):
            target['relative_position']['y'] += pl['y']-tl['y']
            tl,th = bounds(target)
            record(target,'bank_landing_deck_datum')
        rise = int(host.get('params', {}).get('floor_height') or 0)
        if isinstance(level,int) and rise > 0 and level in (plate['relative_position']['y'],pl['y']) and hl['y']+level*rise > hh['y']:
            p['legacy_connection_height'] = p.pop('connection_level')
            record(plate, 'connection_height_in_blocks')
            level = None
        if (level == 0 and hl['y']-pl['y'] == 1 and tl['y'] == pl['y']
                and target['dimensions']['height'] == 1):
            plate['relative_position']['y'] += 1
            target['relative_position']['y'] += 1
            pl, ph = bounds(plate); tl, th = bounds(target)
            record(plate, 'ground_bridge_floor_datum')
        if isinstance(level, int) and rise > 0 and touches(plate, host) and touches(plate, target):
            if hl['y']+level*rise != pl['y'] and level*rise == pl['y'] and (pl['y']-hl['y']) % rise == 0:
                p['legacy_plan_connection_level'] = level
                p['connection_level'] = (pl['y']-hl['y']) // rise
                record(plate, 'connection_level_plan_datum')
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

    # Existing, correctly spanned bridges also need a landing in a tall open hall.
    for plate in list(components):
        p = plate.get('params') or {}
        if p.get('extrude_mode') != 'plate' or p.get('plate_role') not in ('bridge', 'link', 'connector'):
            continue
        target = by_id.get(p.get('target_host_id'))
        if target is None or not touches(plate, target):
            continue
        tp = target.get('params') or {}
        pl, ph = bounds(plate); tl, th = bounds(target)
        landing_id = str(p.get('component_id', 'bridge')) + '#target_landing'
        if (int(tp.get('floor_count') or 1) != 1 or not tl['y'] < pl['y'] < th['y']-2
                or any((c.get('params') or {}).get('component_id') == landing_id for c in components)
                or min(target['dimensions']['width'], target['dimensions']['depth']) < 5):
            continue
        axis, transverse = ('z', 'x') if plate['dimensions']['depth'] >= plate['dimensions']['width'] else ('x', 'z')
        origin = dict(tl)
        origin[axis] = tl[axis] if pl[axis] < tl[axis] else th[axis]-3
        origin[transverse] = max(tl[transverse], min((pl[transverse]+ph[transverse])//2-1, th[transverse]-3))
        origin['y'] = pl['y']
        offset = slots.get(target.get('slot_id'), {})
        landing = {'component_type': 'MASS_SECONDARY', 'slot_id': target.get('slot_id'),
                   'relative_position': {a: origin[a]-int(offset.get(a, 0)) for a in ('x', 'y', 'z')},
                   'dimensions': {'width': 3, 'depth': 3, 'height': 1}, 'features': ['bridge_landing'],
                   'params': {'component_id': landing_id, 'host_id': tp['component_id'],
                              'building_id': tp.get('building_id', tp['component_id']),
                              'anchor_mode': 'min_corner', 'extrude_mode': 'plate', 'plate_role': 'interior',
                              'material': tp.get('floor_block', 'minecraft:stone_bricks')}}
        components.append(landing)
        record(landing, 'bridge_target_landing')

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
