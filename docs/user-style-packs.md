# User style packs v1

User style packs are additive JSON directories. They cannot replace bundled IDs,
install code, register native generators, or load arbitrary files. Changes take
effect after restarting the Java game/server process **and** Python backend.
There is no live reload or upload/install HTTP endpoint in v1.

## Install and check

1. Copy `docs/examples/style-packs/lake` to
   `<game>/config/formacraft/style_packs/lake`. The configured directory is the
   parent `style_packs`, containing one subdirectory per pack.
2. In Settings, under the local backend section, set **用户风格包目录** and click
   **验证并保存风格包目录**. The result reports accepted/rejected packs; errors are
   available in the button tooltip. This does not change the active generation
   snapshot. General settings saves also persist the directory.
3. Restart the game and backend. An automatically launched local backend receives
   the same absolute directory through `FORMACRAFT_STYLE_PACKS_DIR`.
4. Request `湖畔木屋` or `build a lakeside timber` to use the sample.

The check does not download or execute content. A missing directory is an empty
pack collection. Invalid packs are rejected as a whole; valid siblings remain
available. To uninstall a pack, move its folder out of the configured directory
and restart both processes.

For a manually launched backend, set `FORMACRAFT_STYLE_PACKS_DIR` to its local
absolute pack directory before startup. `GET /style-packs` reports its accepted
and rejected packs, with no activation or write operation. A remote backend does
not read the client's filesystem: install the same packs on the Java generation
host and backend separately. A dedicated Java server can set this environment
variable or `-Dformacraft.stylePacksDir=/absolute/pack/root`.

The JVM property overrides the environment variable, which overrides the saved
Settings directory. If a startup override is set, update it separately when
changing directories; the Settings check always checks the entered directory.

## Manifest

Each pack has `style-pack.json` at its root:

```json
{
  "schema": "formacraft.user_style_pack.v1",
  "id": "lake",
  "files": [
    "style_genomes/lake_hall.json",
    "style_profiles/style_profile_catalog_v1.json"
  ]
}
```

This short snippet illustrates syntax; the supplied lake example contains the
complete cross-referenced eleven-file pack. The manifest schema is
`user-style-pack-v1.schema.json`. Optional `name`, `version` and `license` strings
are contribution metadata. Versions do not select executable code.

Pack IDs match `[a-z][a-z0-9_]{1,31}`. Every introduced asset/catalog ID must start
with `<pack_id>_` (case-insensitive prefix). Each individual asset's `id` must
match its filename, including plan-example wrappers. Catalog collisions and
ambiguous style aliases reject the pack. Packs are inspected in folder order;
the first accepted owner retains an ID, later conflicts are rejected.

Paths use `/`, have one directory and a `.json` filename, and cannot traverse
outside the pack. External symlinks are rejected. Limits: 32 root entries,
128 declared files per pack, 256 KiB per file and 4 MiB per pack. Only files
listed in the manifest participate.

## Asset kinds

| Path | Data added |
| --- | --- |
| `style_genomes/<id>.json` | Existing genome schema, all twelve palette roles; Minecraft block IDs |
| `style_profiles/style_profile_catalog_v1.json` | `profiles` object with defaults and constraints |
| `style_profiles/style_identity_catalog_v1.json` | `lexical_aliases` object; no builtin alias overrides |
| `palettes/palette_catalog_v1.json` | `palettes` object using existing weighted semantic parts |
| `structural_typologies/structural_typologies_v1.json` | `typologies` array; `compositional_only`, no native interpreter |
| `culture_cards/<id>.json` | Existing card schema with profile/type/proportion/example references |
| `proportion_cards/<id>.json` | Existing ratio/opening grammar cards |
| `llmplan_examples/<id>.json` | Existing plan example with matching wrapper `id` |
| `ornament_contracts/<id>.json` | Existing declaration; standalone report key starts with `<contract_id>_` |
| `style_visual_cases/catalog_v1.json` | `cases` array; known style, at least two feature groups, an owned plan example |

Catalogs add entries to their named table. Builtin table entries are preserved.
User typologies may compose existing components, but cannot declare a new
interpreter or migration map. Material registration and geometric feasibility
still require Java generation checks; Python's static block-name check is not
proof that an arbitrary block exists in a game version.

Visual matches remain advisory and do not create verified source evidence.
Ornament scope ambiguity remains unresolved rather than applying to an arbitrary
building. Pack installation does not weaken explicit-request priority or the
existing evidence/geometry gates.

## Contribute a pack

Start by copying the complete lake example, choose a unique pack ID, then rename
every introduced ID and update all references. Use two genuinely different plan
sizes, valid materials, bilingual keywords and explicit host bindings. Preserve
the ordinary MASS/ROOF/FACADE execution path for compositional typologies.

From `python_backend`, run:

```text
python -m eval.validate_style_packs ../docs/examples/style-packs
python -m unittest tests.test_user_style_packs
```

The validator prints per-pack reports and exits nonzero if any pack is rejected.
It does not activate the pack. For a contribution, include the pack directory,
license/author information in its README, positive and refusal prompts, two
authored plans and screenshots from the target game version. Run actual Java
plan compilation and Patch/Build parity before asserting visual support.

The repository's `userStylePackTest` Gradle task uses an isolated JVM with the
example directory enabled. `constraintParityTest` depends on that task, so the
existing seven-step CI also exercises user-pack generation. The example is not
silently installed into normal game settings or bundled style registries.
