"""Deterministic adapters for the frozen semantic city/composite route."""

from typing import Any, Dict, List, Optional

from ..models.request import BuildRequest
from ..models.semantic_spatial_plan import SemanticSpatialPlan
from ..models.skeleton_layout import SkeletonLayout, SkeletonNode, IntVec3
from .planning_request_hints import _infer_scale


def _disturbance_to_policy_and_budget(level: str, scale: str) -> tuple[str, int]:
    lvl = (level or "MEDIUM").strip().upper()
    sc = (scale or "MEDIUM").strip().upper()
    mult = 1.0
    if sc == "SMALL":
        mult = 0.7
    elif sc == "LARGE":
        mult = 1.8

    if lvl == "LOW":
        return "preserve_dominant", int(900 * mult)
    if lvl == "HIGH":
        return "engineered", int(6500 * mult)
    return "balanced", int(2600 * mult)


def _policy_upper_to_lower(policy: Optional[str]) -> Optional[str]:
    if policy is None:
        return None
    p = policy.strip().upper()
    return {
        "PRESERVE_DOMINANT": "preserve_dominant",
        "BALANCED": "balanced",
        "ENGINEERED": "engineered",
    }.get(p)


def _compile_semantic_plan_extra(req: BuildRequest, plan: SemanticSpatialPlan) -> Dict[str, Any]:
    """
    Convert SemanticSpatialPlan -> spec.extra knobs that Java CityBuilder/ClusterLayoutConfig already consumes.
    This avoids protocol changes while making I-layer effective immediately.
    """
    scale = _infer_scale(req)

    # Default from constraints
    policy_s, budget = _disturbance_to_policy_and_budget(plan.constraints.max_terrain_disturbance, scale)
    if plan.constraints.terrain_budget_blocks is not None and plan.constraints.terrain_budget_blocks > 0:
        budget = int(plan.constraints.terrain_budget_blocks)

    # If any high-priority zone is engineered, promote cluster policy.
    for z in plan.zones:
        if z.priority >= 8 and z.terrain_policy == "ENGINEERED":
            policy_s = "engineered"
            break

    extra: Dict[str, Any] = {}
    extra["clusterTerrainPolicy"] = policy_s
    extra["clusterTerrainBudgetBlocks"] = budget
    # be conservative: preserve/balanced avoid water/lava edits by default
    extra["allowWaterEdit"] = (policy_s == "engineered")
    extra["allowLavaEdit"] = False

    # Axis alignment hint: if any AXIAL relation exists, enable axis scoring.
    axial = plan.constraints.prefer_axis_alignment or any(r.relation == "AXIAL" for r in plan.relations)
    if axial:
        extra["axisMode"] = "z"
        extra["axisWeight"] = 0.18

    # Semantic spacing knobs: tighten buffers when user wants privacy/access separation.
    if plan.constraints.avoid_direct_private_access:
        extra["semanticBufferMinDistN"] = 0.22
        extra["semanticBufferWeight"] = 0.55
        extra["semanticServicePrivateMinDistN"] = 0.30
        extra["semanticServicePrivateWeight"] = 0.55
        extra["semanticWeightPrivate"] = 0.30
        extra["semanticWeightService"] = 0.25
        extra["semanticWeightPublic"] = 0.22

    # zoneTerrainRules: map semantic zones -> CitySpec zone types (best-effort)
    # pick the highest-priority policy per semantic type
    best_policy: Dict[str, str] = {}
    best_pri: Dict[str, int] = {}
    for z in plan.zones:
        t = z.type
        pri = int(z.priority)
        if t not in best_pri or pri > best_pri[t]:
            p = _policy_upper_to_lower(z.terrain_policy) or policy_s
            best_pri[t] = pri
            best_policy[t] = p

    def policy_for(semantic_type: str) -> Optional[str]:
        return best_policy.get(semantic_type)

    zone_rules: Dict[str, Any] = {}
    mapping = {
        "PLAZA": policy_for("PUBLIC"),
        "MARKET": policy_for("PUBLIC"),
        "COMMERCIAL": policy_for("PUBLIC"),
        "RESIDENTIAL": policy_for("PRIVATE"),
        "INDUSTRIAL": policy_for("SERVICE"),
        "WALL": policy_for("TRANSITION"),
        "GATE": policy_for("TRANSITION"),
    }
    for zone_type, pol in mapping.items():
        if not pol:
            continue
        rule: Dict[str, Any] = {"policy": pol}
        # optional per-zone budget: small slice of cluster budget
        if budget > 0:
            rule["budget"] = max(0, int(budget * 0.25))
        if pol != "preserve_dominant":
            rule["allowWaterEdit"] = (pol == "engineered")
            rule["allowLavaEdit"] = False
        zone_rules[zone_type] = rule
    if zone_rules:
        extra["zoneTerrainRules"] = zone_rules

    return extra


def _cardinal_facing_from_dxz(dx: int, dz: int) -> str:
    # Default to player's facing if degenerate
    if abs(dx) <= 0 and abs(dz) <= 0:
        return "NORTH"
    # Prefer dominant axis
    if abs(dz) >= abs(dx):
        return "SOUTH" if dz > 0 else "NORTH"
    return "EAST" if dx > 0 else "WEST"


def _default_size_for_zone(zone_type: str, scale: str) -> tuple[int, int]:
    """
    Returns (w, d) for RECTANGLE zones. Keep conservative; actual building specs may override.
    """
    sc = (scale or "MEDIUM").upper()
    base = 12
    if sc == "SMALL":
        base = 10
    elif sc == "LARGE":
        base = 16

    zt = (zone_type or "").upper()
    if zt == "CORE":
        return base + 2, base
    if zt in ("PUBLIC", "SEMI_PUBLIC"):
        return base + 6, base + 4
    if zt == "PRIVATE":
        return base, base
    if zt == "SERVICE":
        return base, base - 2 if base > 6 else base
    if zt == "TRANSITION":
        return base + 2, max(6, base - 4)
    return base, base


def _default_radius_for_core(scale: str) -> int:
    sc = (scale or "MEDIUM").upper()
    if sc == "SMALL":
        return 8
    if sc == "LARGE":
        return 14
    return 10


def _semantic_to_skeleton_layout(req: BuildRequest, plan: SemanticSpatialPlan) -> SkeletonLayout:
    """
    J-layer (v0): Convert SemanticSpatialPlan -> SkeletonLayout.
    This is a light, deterministic layout hint (no world sampling).
    Coordinates are relative to cluster center (0,0,0), matching CitySpec conventions.
    """
    scale = _infer_scale(req)
    t = (req.requestText or "").lower()
    # Hint for iconic circular core (tulou/temple/ring)
    prefer_circular_core = any(k in t for k in ["土楼", "tulou", "天坛", "temple of heaven", "ring", "圆形", "环形"])

    # Index zones by id for quick lookup
    zones_by_id: Dict[str, Any] = {z.id: z for z in (plan.zones or []) if z and z.id}
    # Choose a CORE zone id if present, else highest priority
    core_id: Optional[str] = None
    for z in (plan.zones or []):
        if z.type == "CORE":
            core_id = z.id
            break
    if core_id is None and plan.zones:
        core_id = sorted(plan.zones, key=lambda zz: (-int(zz.priority), zz.id))[0].id

    # Build adjacency from relations (treat as undirected for placement order)
    adj: Dict[str, List[str]] = {}
    axial_edges: List[tuple[str, str]] = []
    for r in (plan.relations or []):
        a = getattr(r, "from_zone", None) or getattr(r, "from", None)
        b = getattr(r, "to_zone", None) or getattr(r, "to", None)
        if not a or not b:
            continue
        adj.setdefault(a, []).append(b)
        adj.setdefault(b, []).append(a)
        if r.relation == "AXIAL":
            axial_edges.append((a, b))

    # Basic placement: CORE at origin, expand neighbors on rings.
    spacing = 18
    if scale == "SMALL":
        spacing = 14
    elif scale == "LARGE":
        spacing = 24

    pos: Dict[str, tuple[int, int]] = {}
    if core_id:
        pos[core_id] = (0, 0)

    # If we have an axial edge involving core, place its counterpart along +Z (forward).
    for a, b in axial_edges:
        if core_id and a == core_id and b not in pos:
            pos[b] = (0, -spacing)  # north
        elif core_id and b == core_id and a not in pos:
            pos[a] = (0, -spacing)

    # BFS from core
    if core_id:
        q = [core_id]
        visited = set(q)
        while q:
            cur = q.pop(0)
            cx, cz = pos.get(cur, (0, 0))
            neigh = adj.get(cur, [])
            # deterministic order by zone priority (higher first)
            neigh_sorted = sorted(
                [n for n in neigh if n in zones_by_id],
                key=lambda nid: (-int(zones_by_id[nid].priority), nid),
            )
            # fan out around current
            fan = [
                (0, -1),  # north
                (1, 0),   # east
                (-1, 0),  # west
                (0, 1),   # south
                (1, -1),
                (-1, -1),
                (1, 1),
                (-1, 1),
            ]
            fi = 0
            for n in neigh_sorted:
                if n in visited:
                    continue
                visited.add(n)
                # find first free slot
                while fi < len(fan):
                    dx, dz = fan[fi]
                    fi += 1
                    nx, nz = cx + dx * spacing, cz + dz * spacing
                    if (nx, nz) in pos.values():
                        continue
                    pos[n] = (nx, nz)
                    break
                q.append(n)

    # Any unplaced zones: place on a loose ring
    ring_i = 0
    for z in (plan.zones or []):
        if z.id in pos:
            continue
        ring_i += 1
        pos[z.id] = (ring_i * 6, -spacing - ring_i * 4)

    # Facing: use player's facing as default, or infer from axial core->other direction
    default_facing = (req.player.facing or "NORTH").strip().upper()
    if default_facing not in ("NORTH", "SOUTH", "EAST", "WEST"):
        default_facing = "NORTH"

    def infer_facing_for_zone(zid: str) -> str:
        # if axial edge touches zid, face toward its axial partner
        for a, b in axial_edges:
            if a == zid and b in pos:
                ax, az = pos[a]
                bx, bz = pos[b]
                return _cardinal_facing_from_dxz(bx - ax, bz - az)
            if b == zid and a in pos:
                bx, bz = pos[b]
                ax, az = pos[a]
                return _cardinal_facing_from_dxz(ax - bx, az - bz)
        # if core exists, non-core face toward core (helps entrances)
        if core_id and zid != core_id and core_id in pos:
            zx, zz = pos[zid]
            cx, cz = pos[core_id]
            return _cardinal_facing_from_dxz(cx - zx, cz - zz)
        return default_facing

    sks: List[SkeletonNode] = []
    for z in (plan.zones or []):
        zx, zz = pos.get(z.id, (0, 0))
        facing = infer_facing_for_zone(z.id)

        if z.type == "CIRCULATION":
            # Minimal: linear from core to this zone if core exists
            pts = []
            if core_id and core_id in pos:
                cx, cz = pos[core_id]
                pts = [
                    IntVec3(x=cx, y=0, z=cz),
                    IntVec3(x=zx, y=0, z=zz),
                ]
            sks.append(
                SkeletonNode(
                    zone=z.id,
                    zoneType=z.type,
                    shape="LINEAR",
                    anchor=IntVec3(x=zx, y=0, z=zz),
                    facing=facing,
                    points=pts or None,
                    notes=z.notes or "",
                )
            )
            continue

        if z.type == "CORE" and prefer_circular_core:
            sks.append(
                SkeletonNode(
                    zone=z.id,
                    zoneType=z.type,
                    shape="CIRCLE",
                    anchor=IntVec3(x=zx, y=0, z=zz),
                    facing=facing,
                    radius=_default_radius_for_core(scale),
                    notes=z.notes or "",
                )
            )
            continue

        # Default: rectangles for most zones
        w, d = _default_size_for_zone(z.type, scale)
        sks.append(
            SkeletonNode(
                zone=z.id,
                zoneType=z.type,
                shape="RECTANGLE",
                anchor=IntVec3(x=zx, y=0, z=zz),
                facing=facing,
                width=w,
                depth=d,
                notes=z.notes or "",
            )
        )

    # Ensure CORE is first (helps debug/consumption)
    def sort_key(node: SkeletonNode) -> tuple[int, str]:
        zz = zones_by_id.get(node.zone)
        t0 = zz.type if zz else ""
        pri = int(zz.priority) if zz else 1
        core_rank = 0 if t0 == "CORE" else 1
        return (core_rank, -pri, node.zone)

    sks_sorted = sorted(sks, key=sort_key)
    return SkeletonLayout(skeletons=sks_sorted)
