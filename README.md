# Redstone PCBs

A Minecraft mod for **Fabric** and **NeoForge** (Minecraft 1.21.1) that puts a compact,
save-persistent redstone board inside a single block.

![Redstone PCBs demo](redstonepcb.gif)

## What it does

- **PCB block** - right-click to open the board editor. The board is a 16x16x16 voxel grid shown in an
  orthographic 3D view: drag to orbit, `Ctrl`+drag to pan, `Ctrl`+scroll to zoom toward the cursor, and
  the scroll wheel (or the layer buttons) to step the active layer. The view presets are laid out in
  three rows (NE/SE/SW/NW, N/E/S/W, Top). Left-click places the selected part, `Shift`+left-click
  erases, right-click interacts (toggles levers/buttons, cycles a repeater's delay, toggles comparator
  mode, or opens a container/hopper UI), `R` rotates the hovered part, `D` cycles a repeater's delay,
  `M` toggles comparator mode, and a Reset button recentres the view. The active layer is outlined, and
  **Pulse Layer** toggles every lever and presses every button on that layer. Parts show their vanilla
  item icon, and the side palette is an inventory-style list with names, counts and tooltips.
- **Vanilla redstone** - the board's circuit is a real 16x16x16 region of a hidden board dimension,
  so Minecraft itself runs the redstone: signal strength, dust shapes, torch/repeater/comparator/observer
  timings, hopper locks, quasi-connectivity and all. The board dimension is an empty, void world
  registered by the mod at server start (no datapack), so it adds no worldgen and never triggers an
  experimental-settings warning. Boards are laid out one per chunk on a stride-2 grid, so each board
  has a clear one-chunk border on all sides (including diagonals); the board's own chunk is held
  ticking so redstone runs with no player nearby, and the border chunks are held loaded without
  ticking. Breaking a board packs the region back into the PCB item; placing it writes it back.
  The dimension's height and mob-spawning settings are code parameters, so taller boards and
  in-board mob spawning can be enabled later.
- **Signal section** - the editor shows **Input** and **Output** switches (both off by default). The
  board's redstone is currently self-contained; world-facing redstone I/O is not wired yet.
- **Saved designs** - the editor's Library panel keeps named circuits per player (stored with the
  world). A design's name is typed in the panel's name field; "Save" costs one paper in survival, and
  loading a design back onto a board consumes the matching items in survival. The per-player limit is
  `maxDesigns` in `config/redstonepcbs.json` (default 16), so single-player and creative worlds can
  raise it.
- **Sharing blueprints** - the Library panel exports a design with "Exp" to
  `redstonepcbs/blueprints/<name>.json` and imports files from that folder with "Import". A blueprint
  is a small JSON document (name + base64 grid), so designs can be copied between worlds and players.
  Importing is free (no paper) but still counts against the per-player limit; set `allowImport` to
  `false` in the config to disable it.
- **Container parts** - place a furnace, blast furnace, smoker, brewing stand, crafter or hopper inside
  the board. Right-click one in the editor to open its normal vanilla UI; it processes and transfers
  items exactly like the world block, and comparators read it natively.
- **Hoppers** - real vanilla hoppers with their 5-slot UI. Filtering is the vanilla technique: lock the
  hopper with a redstone signal, put the 41 + 4 reference items in, and read it with a comparator.
- **Item gateway** - the board exchanges items with world hoppers through the PCB block. Each face
  maps to the in-board containers on that face's **edge layer** (bottom face -> the board's lowest
  layer `y=0`, top face -> `y=15`, the four sides -> their edge columns), one port per grid line.
  - **Bottom** - an in-world hopper attached under the PCB pulls from any hopper (whatever its facing)
    or processor (furnace, blast furnace, smoker, brewer) sitting on the board's lowest layer.
  - **Top** - the block above the PCB may be air or a container (hopper, chest, furnace, blast
    furnace, smoker, brewer); the board pulls down from it, so a hopper chain passing over the top
    feeds the board.
  - **Sides** - receive only, and only from a hopper directly attached to that side.
  Slots and direction checks are delegated to the in-board container's own vanilla rules, so a furnace
  or brewing stand keeps its real face restrictions, while a plain hopper exposes all five slots as it
  does in the world. This is **items only** - world-facing **redstone** in/out is not wired yet (the
  Signal toggles are placeholders).

Components are the vanilla items (redstone, torch, repeater, comparator, block of redstone, lever,
button, stone, glass, lamp, observer, note block, hopper, furnace, blast furnace, smoker, brewing
stand, crafter). Placing a part in the editor consumes the matching item in survival and removing it
refunds; in survival the palette offers every part whose item is in your inventory, and in creative
every part is available. The PCB itself appears in the vanilla Redstone Blocks creative tab.

## Build

```bash
./gradlew build              # default Minecraft version (gradle.properties -> mc)
./gradlew -Pmc=1.21.1 build  # a specific Minecraft version
./gradlew cleanArtifacts     # wipe every generated jar/dir (use before a fully fresh build)
```

Jars are written to `build/release/` (`<id>-<version>-universal.jar` plus the per-loader jars) and
`build/libs/`. Every build **sweeps stale artifacts**: the release directories are synced (old
versions removed) and each build's `build` task deletes jars from earlier version stamps, so
`build/release` and the `build/libs` directories only ever hold the current build.

The version comes from `versions.properties`. A bare `1.0.0` is stamped at build time to
`1.0.0.<yymmddhh>` in the project timezone (`TIMEZONE` in `tools/versioning.py`), so built jars and
their metadata carry the full version. Override with `-PversionStamp=<stamp>` or disable with
`-Pnostamp=true`. The release driver (`tools/release.py`) writes the stamped value into
`versions.properties`, which the build then uses as-is.

> Gradle 8.14 must run on JDK 17-23 (the example targets Java 21). If your default `java` is newer,
> point `JAVA_HOME` at a JDK 21 or set `org.gradle.java.home` in `gradle.properties`.

## Layout

```
versions/<mc>/<module>/
├─ common/                 # engine + gameplay (loader-agnostic)          -> relocated per loader
│  ├─ .../block/           # PcbBlock, PcbBlockEntity, the board region (BoardSpace, BoardChunks,
│  │                       #   GridSerializer, BoardStates, BoardEdit, BoardMenus)
│  ├─ .../dimension/       # PcbDimension + RuntimeDimension: the empty board dimension
│  ├─ .../chip/            # palette metadata (Part, Dir) for the editor
│  ├─ .../item/            # PcbItem (block item carrying the saved grid component)
│  ├─ .../content/         # registry ids shared by both loaders
│  ├─ .../config/          # PcbsConfig (config/redstonepcbs.json)
│  ├─ .../craft/           # survival item consume/craft logic for parts
│  ├─ .../data/            # saved designs (SavedData), grid component, Blueprint JSON, level.dat scrub
│  ├─ .../net/             # editor payloads + server-side edit handling
│  ├─ .../platform/        # loader abstraction implemented by each loader
│  ├─ .../client/          # editor screen + blueprint file I/O (client-only)
│  └─ src/main/resources/  # assets + data (loot table, recipe)
├─ fabric/                 # Fabric Loom build (+ mixins that create the board dimension)
└─ neoforge/               # NeoGradle build (+ mixins that create the board dimension)
```

The board's circuit lives as real blocks in the empty board dimension, so Minecraft runs the
redstone; the editor syncs that region to the client as a palette + index snapshot. The dimension is
registered in code at server start (never a datapack entry, and its key is kept out of
`WorldGenSettings`), so worlds never show an experimental-settings warning.

See `PROJECT-GUIDE.md` for building and adding Minecraft versions and `RELEASE-GUIDE.md` for releases.
