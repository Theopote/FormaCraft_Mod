# Archived Documentation

Historical snapshots. These files are **not maintained**. Prefer live docs in the table below; use `git log --follow` if you need the original context of a deleted report.

## Where to look instead

| Need | Document |
|------|----------|
| System overview | [ARCHITECTURE.md](../../ARCHITECTURE.md) |
| Generation pipeline | [GENERATION_PIPELINE.md](../GENERATION_PIPELINE.md) |
| Generalization strategy | [GENERALIZATION_STRATEGY.md](../GENERALIZATION_STRATEGY.md) |
| Full doc index | [INDEX.md](../INDEX.md) |
| Preserved design ideas | [GENERALIZATION_STRATEGY.md](../GENERALIZATION_STRATEGY.md) §4 |

## Subdirectories

| Directory | Contents |
|-----------|----------|
| `archive/` (root) | Pre-2026-07 snapshots moved from repository root |
| `archive/superseded/` | Superseded design docs (e.g. `ROOF_PLATE_V1`, old ranking systems) |

## Do not commit session dumps

Point-in-time `*_SUMMARY` / `*_FIX` / `COMPLETE_*` / `PHASE*` / `FINAL_*` reports and local diagnostic `.txt` dumps are **not** kept in the repo. Record lasting decisions in live Tier 0–2 docs; leave ephemeral notes out of git (see [DOC_CONVENTIONS.md](../DOC_CONVENTIONS.md)).
