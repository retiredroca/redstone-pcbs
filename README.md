# Redstone PCBs

A Minecraft mod for **Fabric** and **NeoForge** (Minecraft 1.21.1) that puts a compact,
save-persistent redstone board inside a single block.

![Redstone PCBs demo](redstonepcb.gif)

## What it does

- **PCB block** - right-click to open the board editor. The board is a 16x16x16 voxel grid shown in an
  orthographic 3D view: drag to orbit, `Ctrl`+drag to pan, `Ctrl`+scroll to zoom toward the cursor, and
  the scroll wheel (or the layer buttons) to step the active layer; the wheel over the palette scrolls
  it. Left-click places the selected item, `Shift`+left-click erases, right-click uses the hovered
  block, and `R` rotates the hovered block (or the pending facing). The view presets are two rows
  (NE/SE/SW/NW/Top, N/E/S/W) and a Reset button recentres the view. The active layer is
  outlined, and **Pulse Layer** toggles every lever and presses every button on that layer. The palette
  is a creative-style 9x3 icon grid with tabs and search. `G` assigns a container's gateway face.
- **Vanilla redstone** - the board's circuit is a real 16x16x16 region of a hidden board dimension,
  so Minecraft itself runs the redstone. The board dimension is an empty, void world registered by the
  mod at server start (no datapack), so it adds no worldgen and never triggers an
  experimental-settings warning. Boards are laid out one per chunk on a stride-2 grid, so each board
  has a clear one-chunk border on all sides (including diagonals); the board's own chunk is held
  ticking so redstone runs with no player nearby, and the border chunks are held loaded without
  ticking. Breaking a board packs the region back into the PCB item; placing it writes it back.
  The dimension's height is a code parameter, so taller boards can be enabled later.
- **Redstone in and out** - the PCB block itself is a power source and a power sink, and the level it
  carries is the real 0-15 from the world rather than a fixed 15. A board has **two taps**: at most one
  **in** and at most one **out**, and both can exist at the same time, so a single block can take a
  signal and drive one. A tap is a **glass or tinted glass block** placed in the gap *beside* the
  component you want bridged - not on it. Vanilla redstone never reads a block's own position to decide
  its power (a wire calls `getBestNeighborSignal`, a lamp calls `hasNeighborSignal`, a diode reads the
  cell behind it), so a component on the tap cell itself would see nothing; beside it, it reads the tap
  as an ordinary neighbour and behaves exactly as it would anywhere else. An **in** tap injects the
  level the block receives from any of its six neighbours; an **out** tap collects the strongest level
  in the board around it and offers it to the world from every face. Press `P` on a glass cell to cycle
  it `in` -&gt; `out` -&gt; clear; assigning a direction that another cell already holds moves it, and the
  editor names the cell that lost its tap. Taps persist on the block and travel with the PCB item.
  Item gateways (below) remain a separate mechanism and are not affected by any of this.

  > **Untested.** The obvious build for this - a half hopper clock on each side, feeding and draining
  > containers across the gateway to steer the level - has not been built or tested. Treat it as a
  > starting point to try, not a documented design.
- **Saved designs** - the editor's Library panel keeps named circuits per player (stored with the
  world). A design's name is typed in the panel's name field; "Save" costs one paper in survival, and
  loading a design back onto a board consumes the matching items in survival. The per-player limit is
  `maxDesigns` in `config/redstonepcbs.json` (default 16), so single-player and creative worlds can
  raise it.
- **Sharing designs** - the Library panel has **Exp** to export a design as JSON to
  `redstonepcbs/blueprints/<name>.json` inside the game folder (so it lands in the player's own files,
  on a server or single-player), and **Import** to read files back from there. A blueprint is a small
  JSON document (name + base64 grid + gateway attachments), so designs can be copied between worlds
  and players; older grid-only files still import, just without their gateway faces. **Share** pushes a
  saved design straight into another online player's library, stamped with your name, and it appears
  in their list as an ordinary editable entry. Importing is free (no paper) but still counts against
  the per-player limit; set `allowImport` to `false` in the config to disable it.
- **Container parts** - place a furnace, blast furnace, smoker, brewing stand, crafter or hopper inside
  the board and right-click one in the editor to open its UI.
- **Item gateway** - the board exchanges items with world hoppers through the PCB block. The gateway
  is **manual**: hover an in-board **container** (a block with inventory slots) in the editor and press
  `G` to assign it to a PCB face; press `G` again to step to the next free face, and once more past the
  last face to clear it. A face can be held by only one container and a container by only one face;
  the editor only offers faces that are still free, so clear one before moving another container onto
  it. The assigned PCB face is highlighted in the 3D view while attached. A container with no assigned
  face is **not reachable** from the world at all. Non-containers cannot be assigned.
  - **Bottom** - an in-world hopper attached under the PCB pulls from the assigned container.
  - **Top** - the block above the PCB may be a container; the board pulls down from it.
  - **Sides** - feed the assigned container from a hopper attached to that side.
  The PCB face only decides *whether* the container is reachable, not which of its slots accepts or
  yields a transfer. Placing, clearing or rotating a cell clears its attachment (rotate it, then
  re-press `G`). Items crossing the gateway also notify the in-board container, so comparators and
  neighbours reading it update in the board dimension on both loaders. Attachments persist on the
  block and travel with the PCB item when it is carried. This is the **item** gateway and is
  independent of the redstone taps: a board can move items, carry a signal in, or drive one out, and
  the two mechanisms do not interfere.

The board is a real-block sandbox: any block item can be placed, not just the classic redstone set
(redstone, torch, repeater, comparator, block of redstone, lever, button, stone, glass, lamp,
observer, note block, hopper, furnace, blast furnace, smoker, brewing stand, crafter). The palette is
a creative-style **tabbed** selector (Search, Building Blocks, Redstone, Functional, Ingredients)
sourced from the live creative-tab contents, with a **search box** that filters by name. In creative
every placeable item is offered; in survival only items the player holds. Placing consumes the item
and removing it refunds (blocks refund their item, so redstone dust refunds correctly). The PCB
itself appears in the vanilla Redstone Blocks creative tab.

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
