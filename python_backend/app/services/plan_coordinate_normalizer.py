"""Recover omitted layout references from unique authored slot identities."""
import re


def recover_slot_bindings(plan: dict) -> None:
    slots = (plan.get('layout') or {}).get('slots') or []
    components = plan.get('components') or []
    frames = {s.get('slot_id', s.get('id')): s for s in slots if isinstance(s, dict)}
    by_id = {(c.get('params') or {}).get('component_id'): c for c in components}
    def tokens(value):
        return [t for t in re.split(r'[_\s-]+', str(value).lower()) if t and t != 'slot']
    for _ in range(len(components)):
        changed = False
        for c in components:
            p = c.get('params') or {}
            if c.get('slot_id'): continue
            name = tokens(p.get('component_id', ''))
            matches = []
            for key, s in frames.items():
                label = tokens(s.get('id') or key)
                if label and name[:len(label)] == label: matches.append(key)
            host = by_id.get(p.get('host_id'))
            frame = host.get('slot_id') if host and p.get('host_source') != 'legacy_geometry_inference' else None
            if not frame and len(matches) == 1: frame = matches[0]
            if frame in frames:
                c['slot_id'] = frame
                p['coordinate_frame_source'] = 'recovered_slot_identity'
                changed = True
        if not changed: break
