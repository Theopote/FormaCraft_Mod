# Archived Documentation

Historical snapshots. These files are **not maintained**.

## Where to look instead

| Need | Document |
|------|----------|
| System overview | [ARCHITECTURE.md](../../ARCHITECTURE.md) |
| Generation pipeline | [GENERATION_PIPELINE.md](../GENERATION_PIPELINE.md) |
| Generalization strategy | [GENERALIZATION_STRATEGY.md](../GENERALIZATION_STRATEGY.md) |
| Full doc index | [INDEX.md](../INDEX.md) |
| Preserved design ideas | [GENERALIZATION_STRATEGY.md](../GENERALIZATION_STRATEGY.md) §4 |

Git history preserves original context (`git log --follow <file>`).

## Subdirectories (2026-07 reorganization)

| Directory | Contents |
|-----------|----------|
| `archive/` (root) | Pre-2026-07 snapshots moved from repository root |
| `archive/reports/` | Point-in-time reports: `*_SUMMARY`, `COMPLETE_*`, `PHASE*`, `FINAL_*`, `*_STATUS`, `*_REPORT` |
| `archive/fixes/` | Bug fix notes: `*_FIX`, `THUMBNAIL_*`, `SELECTION_*`, `BUG_FIX_*` |
| `archive/superseded/` | Superseded design docs (e.g. `ROOF_PLATE_V1`, old ranking systems) |

## Adding to archive

See [DOC_CONVENTIONS.md](../DOC_CONVENTIONS.md) — do not create `*_SUMMARY` or `*_FIX` files in `docs/` root.
