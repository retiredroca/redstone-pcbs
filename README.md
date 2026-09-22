# Redstone PCBs

A Minecraft mod for **Fabric** and **NeoForge** (Minecraft 1.21.1) that puts a compact,
save-persistent redstone board inside a single block.

![Redstone PCBs demo](redstonepcb.gif)

## What it does

- **PCB block** - right-click to open the board editor. The board is a 16x16x16 voxel grid shown in an
  orthographic 3D view: drag to orbit, `Ctrl`+drag to pan, `Ctrl`+scroll to zoom toward the cursor, and
  the scroll wheel (or the layer buttons) to step the active layer. Left-click places the selected
  part, `Shift`+left-click erases, right-click interacts (toggles levers/buttons), `R` rotates the
  hovered part, `D` cycles a repeater's delay, `M` toggles comparator mode, and a Reset button recentres
  the view. Parts show their vanilla item icon, and the side palette is an inventory-style list with
  names, counts and tooltips.
- **Real redstone parts** - dust, torches, repeaters, comparators, blocks of redstone, levers,
  buttons, solid blocks and lamps, with vanilla-like signal strength, weak/strong power and delays.
  The simulation is deterministic (no quasi-connectivity / update-order quirks) and runs on the
  server.
- **Six-sided I/O** - each face of the board participates in redstone with whatever block touches
  it: adjacent signals feed the boundary cells, and boundary emitters drive the adjacent block.
- **Attached parts** - because each face joins the world's redstone, pistons, dispensers,
  droppers, note blocks, doors, lamps and rails can be driven from the board, observers pulse on its
  output, and a comparator in the board can read a chest/hopper/barrel touching a face.
- **Saved designs** - the editor's Library panel keeps named circuits per player (stored with the
  world). "Save Design" costs one paper in survival, and loading a design back onto a board consumes
  the matching items in survival.

Components are the vanilla items (redstone, torch, repeater, comparator, block of redstone, lever,
button, stone, glass, lamp, observer, note block, hopper). Placing a part in the editor consumes the
matching item in survival and removing it refunds; in survival the palette only offers parts you are
holding or can craft from your inventory.

## Build

```bash
./gradlew build              # default Minecraft version (gradle.properties -> mc)
./gradlew -Pmc=1.21.1 build  # a specific Minecraft version
./gradlew :enginetest:test   # pure-Java redstone engine tests
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
│  ├─ .../chip/            # pure-Java redstone engine (unit-tested, no Minecraft imports)
│  ├─ .../block/           # PcbBlock, PcbBlockEntity
│  ├─ .../item/            # PcbItem (block item carrying the saved chip component)
│  ├─ .../content/         # registry ids shared by both loaders
│  ├─ .../craft/           # survival item consume/craft logic for parts
│  ├─ .../data/            # saved designs (SavedData) + chip component
│  ├─ .../net/             # editor payloads + server-side edit handling
│  ├─ .../platform/        # loader abstraction implemented by each loader
│  ├─ .../client/          # editor screen (client-only, never loaded on a dedicated server)
│  └─ src/main/resources/  # assets + data (shared by both loaders)
├─ fabric/                 # Fabric Loom build
└─ neoforge/               # NeoGradle build
enginetest/                # standalone JUnit project for the chip engine
```

The two loader builds each compile `common/` into a loader-specific relocated package and are merged
into a single universal jar.

See `PROJECT-GUIDE.md` for building and adding Minecraft versions and `RELEASE-GUIDE.md` for releases.
