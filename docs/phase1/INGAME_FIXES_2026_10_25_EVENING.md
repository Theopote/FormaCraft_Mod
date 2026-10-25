# Evening live-feedback fixes (2026-10-25)

Evidence: player screenshots and `run/logs/latest.log`, session 17:50–18:18.
The log may be replaced by the next client launch. The failed stilt-hut response
is preserved as `src/test/resources/regressions/stilt-hut-20261025.json`.

| Observation | Cause and change |
| --- | --- |
| 17:52 cabin edit built another cabin | The mode resolver missed “修改刚才”. Shared existing-building edit recognition now runs in the client and at server request intake. PATCH requests retain the existing whole-building-response rejection gate. |
| 17:55 fence produced 36 blocks from 10 compiled patches | The linear-plan exemption covered the first pad stage but not the second automatic foundation stage. Both stages now exempt fence-only plans. |
| 18:11 stilt-hut retry collided with an exterior stair | Solid 1×1 `MASS_ANCILLARY` pilings were widened into 3×3 connector bodies. Solid ancillary masses now preserve authored dimensions; hollow connector minimums remain. |
| 18:16 open pavilion rejected zero wall thickness | Explicit `open=true` masses now permit zero wall thickness and generate floor/posts/clear sides. Authored roofs use the roof generator. Closed masses still reject zero thickness. |

These are offline fixes, not a new live acceptance pass. Restart the development
client to load Java changes before retesting. Reuse the four original prompts;
for the cabin edit, stand on the roof again and verify that no complete cabin is
offered. If the backend returns BUILD despite PATCH, safe refusal is expected.
Also test Undo separately: this change does not certify world-history restoration.

Validation: 43 targeted JUnit tests passed, covering edit intent, fence-plan
classification, open pavilion geometry/roof, mass validation, exterior stair
collision rejection and the original logged stilt-hut plan replay.
`constraintParityTest` and its `userStylePackTest` dependency also passed.
No game-world retest was performed in this repair turn.
