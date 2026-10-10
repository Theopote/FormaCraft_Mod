# Window language contract — batch 2

Chinese explicit counts can now target one-based host floors:

- 一楼入口两侧各一扇窗，二楼各两扇窗。
- 每面侧墙每层三扇窗。二楼每面侧墙各一扇窗。

Parameters use `window_counts_by_floor`, for example
`{"1":{"count_per_side":1},"2":{"count_per_side":2}}`.
Each floor selects its specific count before falling back to general counts.
Zero counts suppress windows; integral JSON numbers are accepted.
Different floor counts coexist; conflicting values for the same wall, property
and floor are reported and are not written by this binder.

Abbreviated “二楼各两扇窗” inherits only the immediately preceding count
clause's wall scope, across one punctuation separator with no intervening text.
Named building scopes, arbitrary floor ranges, other languages and arbitrary
wall descriptions remain outside this conservative binder. An existing hosted
FACADE_WINDOWS component with a matching wall is required.

The language report verifies parameters only. Java regression tests verify
actual generated glass coordinates, full rectangular windows, floor-specific
counts, zero counts and fallback to defaults. Space constraints can reduce a
requested count while preserving full windows and logging WindowCountLayout.
Game rendering and final terrain integration still require user acceptance.
