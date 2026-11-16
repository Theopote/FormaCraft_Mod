## Summary

<!-- Briefly describe what this PR changes and why. -->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Documentation
- [ ] Refactor / chore
- [ ] Other (describe below)

## Checklist

- [ ] World changes go through `BlockPatch` (no direct `setBlock`)
- [ ] New behavior is previewable and undoable where applicable
- [ ] Tool constraints are respected
- [ ] `./gradlew build` passes locally
- [ ] Python smoke passes if backend code changed (`python python_backend/tools/spec_contract_smoke.py`)
- [ ] Documentation updated if behavior or architecture changed

## AI Design Authority (required when touching planning / generation)

Applies to prompts, normalizers, routing, Assembly repair/fallback, typologies, presets, style heuristics.

- [ ] N/A — this PR does not touch planning/generation
- [ ] Read `AGENTS.md` + `docs/architecture/GENERATION_AUTHORITY_MAP.md`
- [ ] No silent redesign of `USER_EXPLICIT` / `AI_PLANNED` fields
- [ ] No new keyword→building/preset authoritative router (hints only)
- [ ] New defaults declare provenance (`TECHNICAL_DEFAULT`) and only fill absent fields
- [ ] Unsupported geometry returns `capability_gap` / replan, not a disguised template
- [ ] Updated `docs/architecture/DESIGN_DECISION_INVENTORY.md` when design decisions change
- [ ] Ran Design Authority Audit Skill and/or relevant pytest (`test_design_authority_regressions.py`, preservation/omission tests)

## Testing

<!-- How did you verify this change? Include steps or scenarios. -->

## Related issues

<!-- e.g. Closes #123 -->
