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

Use `--mod all` when a change spans the library and its dependents, so the release cannot mix old and
new component versions.

## Local build

```bash
python tools/release.py --mod all --dry-run   # preview every version; change nothing
python tools/release.py --mod all             # stamp and build every version
```

With no `--mc`, every directory under `versions/` is built. `--mc <mc>` restricts the run to one
Minecraft version. The build:

1. stamps the named component(s) once in `versions.properties`;
2. publishes library modules to the in-repo maven (`repo/`);
3. builds the staged groups (`releaseLoaderJars` → `releaseLoaderBundles` → `releaseUniversal` →
   `releaseUniversalBundles`), then `releaseJars`;
4. copies the jars into `dist/<mc>/`, verifies the complete set, and writes `dist/<mc>/changelog.md`.

The script does not commit. Commit and push the prepared files yourself.

## Publish the GitHub release

```bash
python tools/release.py --create-tags
```

This uses the committed version and the local `dist/<mc>/` jars. It:

1. creates the `v<version>-<mc>` tags and pushes them;
2. creates a draft GitHub release per MC with your stored GitHub token;
3. attaches every locally built jar and sources jar;
4. publishes the release.

Publishing fires `release: published`, which runs the separate `publish-release.yml` workflow.
That workflow downloads the attached jars and uploads **only the universal jars** to
CurseForge/Modrinth. Per-loader jars and sources jars are never sent to the platforms.

The build does not run in GitHub. There is no tag-build workflow.

## Bundles

A bundle is for a project whose bundles have different membership or dependency sets — mcsan is the
example. Two bundles with the same module set produce duplicate jars, duplicate release assets and
duplicate platform uploads. `tools/release.py` rejects that configuration before building, and the
platform workflow rejects it before uploading. A project with no distinct bundle should have none.

mc-personas has no bundles: it is installed as `api` + `personas`.

## Flags

```
--mc <version>       one Minecraft version under versions/ (default: every version)
--mod <id>|all       component(s) to stamp (required for preparation and --bump)
--create-tags        push tags and publish the GitHub releases from the prepared dist/<mc>/
--tag <tag>          explicit tag for --create-tags (requires --mc)
--bump patch|minor|major
                     move the semantic version line and stop; never commits
--local-only         stamp and build every selected version; never create tags or releases
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
with `git checkout -- versions.properties`. No commit, tag, GitHub release or platform upload is
performed.

## No `origin` remote yet?

Preparation works locally. `--create-tags` needs the GitHub origin and a stored GitHub credential to
publish the release; without them it stops instead of creating a partial release. The stored token is
deliberate: a release published with the Actions `GITHUB_TOKEN` does not trigger `release: published`,
so the platform workflow would never start.

## Windows note

`tools/release.py` runs the Gradle wrapper through `cmd.exe` (`cmd /c gradlew.bat`). Do not replace
that with `bash gradlew`, which resolves to Git's bash and mangles the Windows paths.
