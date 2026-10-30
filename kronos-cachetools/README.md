# kronos-cachetools

Pack custom models, sprites, maps and raw cache files into the Kronos cache (`Cache/`), and dump existing
assets so they can be edited. Written for this project's cache: **OSRS revision 184**, named map groups with
XTEA keys in `kronos-server/data/region_keys.json`, served by `kronos-update-server`.

The cache-writing layer is the displee cache library vendored from OpenRune FileStore (see
`src/main/kotlin/com/displee/VENDORED.md`); the packers themselves are written for rev 184 (see "Format rules").

## Workflow

1. Stop `kronos-server` and `kronos-update-server` (the tool refuses to write while they run).
2. Put files in `raw-cache/` at the repository root:

   | Folder | File names | Goes to |
   |---|---|---|
   | `raw-cache/models/` | `<id>.dat` or `<id>.gz` | index 7, group `id` |
   | `raw-cache/sprites/` | `<id>.png`, or `<id>_0.png`, `<id>_1.png`, ... for multi-frame sprites; optional `<id>.json` | index 8, group `id` |
   | `raw-cache/maps/` | `m<x>_<y>` + `l<x>_<y>` (`.dat` or `.gz`), or one `<name>@<x>_<y>.pack` per map square | index 5, groups `m<x>_<y>` / `l<x>_<y>` |
   | `raw-cache/definitions/` | `items/`, `npcs/`, `objects/` with one `<any name>.toml` per definition | index 2, archive 10 / 9 / 6, file `id` (see [Definitions](#definitions-toml)) |
   | `raw-cache/files/` | `<index>/<archive>/<file>.dat` | that exact cache file (enums, interfaces, ...) |

3. Run `gradlew :kronos-cachetools:packCache` (try `-PcacheArgs="--dry-run"` first).
4. Start the servers again. Clients pick up changed archives automatically on their next connect.

Packing is all-or-nothing:

- The tool packs a copy of the cache in `build/cachetools/`.
- It rebuilds that copy into a compact cache.
- It verifies the rebuilt cache:
  - every packed file is read back through Kronos' own `FileStore`;
  - every archive you did not pack must be byte-identical to the original.
- Only after verification does it install the new files. With `--backup` it first backs up `Cache/` and `region_keys.json` to `Cache-backups/<timestamp>/`; the last 5 backups are kept.

If any input is refused, nothing is written. `--force` packs the valid inputs anyway.

## Tasks

All tasks are in the `kronos cache` group. Extra arguments go in `-PcacheArgs="..."`.

| Task | What it does |
|---|---|
| `packCache` | Pack `raw-cache/`. Flags: `--dry-run`, `--only files,definitions,models,sprites,maps`, `--force`, `--allow-running`, `--backup` |
| `dumpCache` | Export to `cache-dump/` in the `raw-cache` layout: `model 1234`, `sprite 535,536`, `map 50_50` (or region id `12850`), `item 4151`, `npc 3080`, `object 375` (as TOML), `file 2 10 4151`. Ids accept ranges like `1000-1010`. |
| `cacheInfo` | Print the indices, archive counts and revisions |
| `restoreCache` | List backups, or `-PcacheArgs="<name>"` to restore one. The current state is backed up first. |
| `cacheTool` | Raw CLI, e.g. `-PcacheArgs="pack --raw some/other/dir"` |

Global options: `--cache`, `--raw`, `--dump`, `--keys`, `--backups`. The cache and keys locations default to
`cache_path` and `data_path` in `kronos-server/server.properties`.

To edit an existing asset:

1. Dump it with `dumpCache`.
2. Edit the file in `cache-dump/`.
3. Copy it into `raw-cache/`.
4. Run `packCache`.

## Format rules (rev 184)

The tool enforces these, because breaking them produces assets that the client or the server cannot load.

- **Models**
  - The rev-184 client only decodes the original format and the `-1`-trailer ("type 1") format.
  - Models exported for newer revisions are converted automatically while packing: `-2`-trailer ("type 2") models
    become the original format and `-3`-trailer ("type 3") models become type 1. Geometry, colours, textures, priorities,
    transparency and animation labels are copied unchanged; the packer prints a note for each converted model.
  - Skeletal animation data from newer models is dropped (the rev-184 client cannot play skeletal animations), with a
    warning. The model still renders, and frame-based animations still work through the vertex labels it keeps.
  - Every converted model is checked by reading it back the way the client does before it is packed.
- **Sprites**
  - The rev-184 client has **no sprite alpha**. Pixels with alpha below 50% become transparent; all others become fully opaque. The tool reports how many pixels it changed.
  - A sprite group has one shared palette of at most 255 colours. Larger images are colour-reduced (median cut), with a warning.
  - Frame offsets and canvas size:
    - With `<id>.json` (written by `dumpCache`), they come from that file.
    - Otherwise, a replacement with the same frame count keeps the existing sprite's offsets and canvas.
    - Otherwise, offsets are 0 and the canvas is the size of the largest frame.
- **Maps**
  - Terrain and location files are parsed with the same decoder the server uses (`Region.init`).
  - Files exported for newer revisions use a different tile format and are refused.
  - `m` = terrain and `l` = locations, as in Jagex's naming. OpenRune and RSPSi export them the other way round; the tool detects a reversed pair and packs it correctly.
  - `.pack` map bundles (e.g. `Tutorial_Island.pack`) are accepted too. A pack holds one map square (terrain + locations,
    rev-184 format) but does not say which one, so name it `<name>@<x>_<y>.pack` with the region coordinates you want it
    placed at, e.g. `Tutorial_Island@48_48.pack` for region 48_48. It is checked and packed exactly like an `m`/`l` pair
    (same format checks, object-definition check and region key handling). A pack whose layout is not the known one is
    refused with the reason; the trailing metadata block of a pack is ignored. Giving the same square as a pack and as
    `m`/`l` files, or as two packs, is refused.
  - Every object placed must have a definition in index 2 archive 6. If it doesn't, the region is refused.
  - The landscape is stored unencrypted, and the region's `region_keys.json` entry is set to `[0,0,0,0]`. A region with no entry would load without objects on the server.
- **Files**
  - Index 5 is refused here: map groups are named and keyed, so put them in `maps/`.
  - The other files in the target archive are kept.

## Definitions (TOML)

One file per item, NPC or object, in `raw-cache/definitions/items/`, `npcs/` or `objects/`. The file name is free;
the id is inside. Examples are in `raw-cache/definitions/`.

```toml
id = 20003            # the definition to write
inherit = 3080        # optional: start from this definition (in the cache, or another TOML file of the same kind)
name = "Example guide"
option1 = "Talk-to"
option2 = "Hidden"    # "Hidden" removes an inherited option
remove = ["combatLevel"]   # optional: drop inherited values
```

- Without `inherit` the definition starts empty, i.e. with the client's defaults. Writing an id that already exists
  without `inherit = <that id>` replaces it entirely, with a warning.
- `dumpCache` with `item`, `npc` or `object` writes an existing definition as TOML with `inherit = <id>`, so it can
  be edited and packed back, or copied under a new `id`. It also lists any opcodes the keys below cannot express;
  `inherit` keeps those.
- Unknown keys, out-of-range values and values that share one opcode but are only partly given are refused.
- `description`, `examine` and `wearPos1-3` (from the OpenRune TOML files) are ignored with a warning: rev 184 has
  no such cache fields; Kronos keeps examines and equipment data in its own data files.

| Kind | Keys |
|---|---|
| all | `name`, `option1`-`option5`, `recolor` / `retexture` (`[[from, to], ...]`), `ambient`, `contrast` |
| items | `inventoryModel`, `zoom2d`, `xan2d`, `yan2d`, `zan2d`, `xOffset2d`, `yOffset2d`, `ioption1`-`ioption5` (inventory; `option` is the ground menu), `stackable`, `members`, `grandExchange`, `cost` (alias `value`), `maleModel0`-`2` (`maleModel` = `maleModel0`), `maleOffset`, `femaleModel0`-`2`, `femaleOffset`, `maleHeadModel0`-`1`, `femaleHeadModel0`-`1`, `noteLinkId`, `noteTemplateId`, `placeholderLink`, `placeholderTemplate`, `resizeX/Y/Z`, `team` |
| npcs | `models`, `chatheadModels`, `size`, `combatLevel`, `standAnim`, `walkAnim`, `walkBackAnim`, `walkLeftAnim`, `walkRightAnim`, `turnLeftAnim`, `turnRightAnim`, `widthScale`, `heightScale`, `headIcon`, `rotation`, `minimapVisible`, `clickable`, `renderPriority`, `follower`, `varbit`, `varp`, `transforms`, `transformDefault` |
| objects | `objectModels`, `objectTypes`, `sizeX`, `sizeY`, `animationId` (-1 = none), `interactive`, `solid`, `impenetrable`, `clipMask`, `obstructive`, `isHollow`, `supportsItems`, `isRotated`, `castsShadow`, `contouredGround`, `nonFlatShading`, `modelClipped`, `decorDisplacement`, `mapSceneID`, `modelSizeX/Y/Z`, `offsetX/Y/Z`, `ambientSoundId`, `ambientSoundDistance`, `varbit`, `varp`, `transforms`, `transformDefault` |

Some values share one opcode and must be given together, unless `inherit` already provides the rest:
`walkBackAnim`, `walkLeftAnim` and `walkRightAnim` with `walkAnim`; `transforms` with `varbit` / `varp`;
`ambientSoundId` with `ambientSoundDistance`.

Opcodes that only one side decodes are never written from TOML: items 42 (client only), objects 60 (server only),
82 and 249 (client only). When an inherited definition has one, it is kept with a warning, because that side
misreads the rest of the definition.

Note: item ids from 30000 upwards are already used by Kronos custom items. `cacheInfo` and `dumpCache file 2 10 <id>` show
what is there before you overwrite something.
