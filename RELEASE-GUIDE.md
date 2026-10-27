# Release guide

This file covers **releasing**. For building, adding Minecraft versions or adding modules, see
`PROJECT-GUIDE.md`.

## Version scheme

```
<major>.<minor>.<patch>.<yymmddhh>        e.g. 1.0.2.26091512
v<major>.<minor>.<patch>.<yymmddhh>-<mc>   e.g. v1.0.2.26091512-1.21.1   (the tag)
```

- the trailing `yymmddhh` stamp is written by `tools/versioning.py` in the project's timezone;
- the semantic line is changed only through `release.py --bump patch|minor|major`;
- tags carry the Minecraft version, and previous-tag lookup is per Minecraft version;
- jar names carry the Minecraft version after the loader; the platform version number is
  `<mod version>-<mc>`;
- dependent modules resolve a library through a floor range computed from `versions.properties`.
  Nothing rewrites that floor; the version file is the single source.

## Which `--mod` to use

```
--mod <module id>     a single component (see versions.properties)
--mod all             every component
```

Use `--mod all` when a change spans the library and its dependents, so the bundle cannot mix old and
new component versions.

## Local release preparation

`tools/release.py` does not commit, push, create GitHub releases or upload to CurseForge/Modrinth.

```bash
python tools/release.py --mod all --dry-run   # preview every version; change nothing
python tools/release.py --mod all             # stamp and build every version
```

With no `--mc`, every directory under `versions/` is prepared. `--mc <mc>` restricts the run to one
Minecraft version. The preparation:

1. stamps the named component(s) once in `versions.properties`;
2. publishes library modules to the in-repo maven (`repo/`);
3. builds the staged groups (`releaseLoaderJars` → `releaseLoaderBundles` → `releaseUniversal` →
   `releaseUniversalBundles`), then `releaseJars`;
4. copies the jars into `dist/<mc>/`, verifies the complete set, and writes `dist/<mc>/changelog.md`.

It leaves `versions.properties` and any `repo/` changes in the working tree. **Commit and push them
yourself.** Then create the local tags:

```bash
python tools/release.py --create-tags
```

That creates one `v<version>-<mc>` tag per selected version; it does not commit or push. Push the tags
yourself. The tag push is the release trigger.

## What the tag push does

A `v*-*` tag push runs `.github/workflows/release-tag.yml`:

1. checks out the tag and builds the staged release set in CI;
2. verifies the complete jar set;
3. creates the GitHub release as a draft, uploads **every jar and sources jar**, and publishes it.

Publishing the release fires `release: published`, which runs `publish-release.yml` and
`release-mod.yml`:

- they download the assets from GitHub;
- they publish **only universal jars** to CurseForge/Modrinth:
  - single-module projects publish the primary universal jar;
  - multi-module projects publish every component universal jar and universal bundle jar;
- per-loader jars and sources jars are never sent to the platforms;
- the workflow does **not** commit. It uploads the updated `release-state.properties` as a workflow
  artifact. Download and commit it yourself when instructed.

Platform publishing uses the repository variables and secrets documented in `PROJECT-GUIDE.md`.

## Flags

```
--mc <version>       one Minecraft version under versions/ (default: every version)
--mod <id>|all       component(s) to stamp (required for preparation and --bump)
--create-tags        create the local v<version>-<mc> tags from the committed versions.properties
--tag <tag>          explicit tag for --create-tags (requires --mc)
--bump patch|minor|major
                     move the semantic version line and stop; never commits
--local-only         stamp and build every selected version; never create tags
--dry-run            print the plan; change nothing
--unsigned           create unsigned tags
--allow-dirty        skip the clean-tree check during preparation
--skip-build         verify the existing dist/<mc>/ instead of rebuilding
--no-daemon          do not reuse a Gradle daemon (slower; reproduces a CI-like cold build)
```

## Testing a change without releasing

```bash
python tools/release.py --mod <id> --local-only --allow-dirty
```

The jars land in `build/release/<mc>/` and `dist/<mc>/` with the next stamp. Undo the version bump
with `git checkout -- versions.properties`. No commit, tag, push, GitHub release or platform upload is
performed.

The local build is verification only. The release jars that are published are built by the tag
workflow from the committed tag.

## No `origin` remote yet?

Preparation and `--create-tags` still work locally. You need a remote to push the commit and tags and
to run the GitHub release workflows.

## Windows note

`tools/release.py` runs the Gradle wrapper through `cmd.exe` (`cmd /c gradlew.bat`). Do not replace
that with `bash gradlew`, which resolves to Git's bash and mangles the Windows paths.
