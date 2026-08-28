# @mrpinoys/dsh-fabric-modding

A [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) plugin that makes `dsh` faster and more accurate at writing Minecraft Fabric mods in this workspace.

It does five things, mapped to the goals you picked:

1. **Better domain knowledge**: registers a system-prompt section with Fabric modding essentials tuned to this workspace's stack: Minecraft 26.x unobfuscated (Mojang/official mappings, no Yarn), Java 25, Fabric Loom, Kotlin DSL gradle, server-side mods.
2. **Less rework**: registers a dynamic prompt context that scans the workspace at assembly time, detects every gradle mod, and reports the versions, mappings, entrypoints, and mixin packages already in use, so the model reuses your conventions instead of inventing new ones. A `read_mod_reference` tool pulls real snippets from your existing mods on demand.
3. **Faster scaffolding**: registers tools that generate boilerplate matching the detected versions: `scaffold_fabric_mod`, `scaffold_mixin`, `scaffold_registry`, `scaffold_entrypoint`.
4. **Verification loop**: a `build_mod` tool runs gradle tasks inside a workspace mod and returns structured compile errors (file, line, column, message), failed tasks, and a log tail, so the model can verify a change compiles and tests pass before declaring it done. Long builds can run in the background.
5. **Situational skills**: ships four `SKILL.md` files under `skills/` that the model loads on demand for deep knowledge: mixin development, compatibility troubleshooting, the mod ecosystem, and Stonecutter multi version builds.

## Tools

| Tool | What it produces |
|---|---|
| `scaffold_fabric_mod` | A full mod skeleton: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `fabric.mod.json`, `<mod>.mixins.json`, a `ModInitializer` entrypoint, and a mixin stub. Versions default from the first mod already in the workspace. |
| `scaffold_mixin` | A mixin class under `<mod>.mixin` plus an entry appended to the mod's mixins json. |
| `scaffold_registry` | A registry helper (`<Mod>Registries`) plus one stub for `item`, `block`, or `command`. |
| `scaffold_entrypoint` | A `ModInitializer` or `ClientModInitializer`. |
| `read_mod_reference` | Searches the workspace's Java sources for a query and returns the best matching snippets, so the model copies your own patterns. |
| `build_mod` | Runs a gradle task (default `build`) inside a workspace mod project and returns a structured result: exit code, parsed compile errors and warnings (file, line, column, message), failed gradle tasks, and a log tail. Confined to the workspace root. Supports `run_in_background: true` for long builds (poll with `job_output`, stop with `job_kill`). |

Every scaffolding tool takes a `write` flag (default `true`). When `true` it writes files directly under the resolved base directory (confined to the workspace root for safety) and returns the list of paths. When `false` it returns the file contents instead, and the model writes them through the normal approved file path.

## Skills

Four `SKILL.md` files under `skills/`, surfaced through the `skill-filesystem` provider configured in the overlay. The model loads them on demand.

| Skill | When to load |
|---|---|
| `mixin-development` | Before writing or modifying any mixin. Injection point selection, compatibility safe practices, mixin config files, crash fixes. |
| `compat-troubleshooting` | When investigating a crash or behavior that may involve another mod. Locating the conflict point, reading sibling mod source, verifying a fix. |
| `mod-ecosystem-overview` | Before deciding how to ship a mod. Loader choice, publishing platforms, CI publishing, docs, license. |
| `stonecutter-multiversion` | When a mod must support more than one Minecraft version from one source tree. Stonecutter conditional compilation, version switching, multi version builds. |

## Install and run

This plugin is loaded as TypeScript source by the harness loader, so it needs the `@deepseek-ai/dsh` package installed globally (or a local checkout) whose `node_modules` can resolve `@deepseek-ai/cordis`, `@deepseek-ai/dsh-tools`, and `@deepseek-ai/schemastery`.

1. Install the harness globally (if not already installed):
   ```sh
   npm install -g @deepseek-ai/dsh
   ```
2. Edit `cordis.yml` in this folder so `name:` is a `file://` URL pointing at `src/index.ts` on your machine. On Windows the loader passes `name` to ESM `import()`, so a bare drive path (`A:/...`) is rejected as an unsupported `a:` scheme; use `file:///A:/...` with spaces percent-encoded as `%20`.
3. Link the harness's bundled dependencies into the plugin's `node_modules` so Node's ESM resolver can find `@deepseek-ai/cordis`, `@deepseek-ai/dsh-tools`, and `@deepseek-ai/schemastery`. On Windows, directory junctions (no admin needed) pointing at the harness install work:
   ```powershell
   $plugin = "A:\MrPinoys Mods\dsh-fabric-modding\node_modules\@deepseek-ai"
   $harness = "A:\dev\dsh\npm-prefix\node_modules\@deepseek-ai\dsh\node_modules\@deepseek-ai"
   New-Item -ItemType Directory $plugin -Force
   foreach ($p in 'cordis','dsh-tools','schemastery') { cmd /c mklink /J "$plugin\$p" "$harness\$p" }
   ```
   For typechecking only, also junction `@types/node`:
   ```powershell
   $types = "A:\MrPinoys Mods\dsh-fabric-modding\node_modules\@types"
   $nodeTypes = "A:\dev\dsh\npm-prefix\node_modules\@deepseek-ai\dsh\node_modules\@types\node"
   New-Item -ItemType Directory $types -Force
   if (-not (Test-Path "$types\node")) { cmd /c mklink /J "$types\node" $nodeTypes }
   ```
4. **For DSH Desktop (persistent, automatic):** Add the plugin and skills to the web profile's patch layer so they load every time DSH Desktop starts, with no command line flags needed. DSH Desktop uses its own `DSH_HOME` at `C:\Users\Kriss\AppData\Roaming\dsh-desktop\harness`, which is separate from the CLI's `C:\Users\Kriss\.dsh`. Edit `C:\Users\Kriss\AppData\Roaming\dsh-desktop\harness\profiles\web\cordis.patch.yml`:
   ```yaml
   - insert:
       - id: fabric-modding
         name: 'file:///A:/MrPinoys%20Mods/dsh-fabric-modding/src/index.ts'
         config:
           workspaceRoot: 'A:/MrPinoys Mods'
   - id: skill-filesystem
     disabled: false
     config:
       providerName: fabric-modding-skills
       includeDefaultRoots: false
       customSkillDirs:
         - 'A:/MrPinoys Mods/dsh-fabric-modding/skills'
   ```
   `workspaceRoot` MUST be set explicitly when running under DSH Desktop, because the process cwd is the desktop launch root (`C:\Users\Kriss\AppData\Roaming\dsh-desktop\launch-root`), not your mods workspace. Without it, `build_mod` and the scaffolding tools resolve mod paths against the wrong root and fail with "path escapes workspace root" or "no gradle wrapper". The `skill-filesystem` override re-enables the host row (disabled by the web profile) as a deployment-level provider that contributes ONLY the plugin's bundled skills, without double-discovering project/user roots the active preset already scans. Then restart DSH Desktop and start a new conversation.

5. **For CLI `dsh web` (persistent, automatic):** Same idea, but the CLI uses `C:\Users\Kriss\.dsh` as `DSH_HOME`. Edit `C:\Users\Kriss\.dsh\profiles\web\cordis.patch.yml` with the same YAML (including `workspaceRoot`).

6. **For command line (ad hoc):** Start the Web UI with the overlay, using this mods workspace as the working directory:
   ```sh
   cd "A:/MrPinoys Mods"
   dsh web --patch "A:/MrPinoys Mods/dsh-fabric-modding/cordis.yml"
   ```
   The `cordis.yml` in this folder already includes the plugin insert (with `workspaceRoot`), and the `skill-filesystem` override. When launched with `cd "A:/MrPinoys Mods"` first, the process cwd matches `workspaceRoot`, so the explicit config is redundant but harmless for the CLI path; it is required for DSH Desktop.

## Configuration

The plugin accepts a Schemastery `Config` (set in a preset's `agent.cordis.yml` or the overlay). All fields optional:

| Field | Default | Effect |
|---|---|---|
| `workspaceRoot` | `process.cwd()` | Root the pattern scanner walks and writes are confined to. |
| `enableFileWrites` | `true` | Lets scaffolding tools write files directly. Set `false` to force `write: false` behavior and route all writes through the approved file path. |
| `enableBuildRuns` | `true` | Lets the `build_mod` tool run gradle. Set `false` to disable builds entirely. |
| `domainSectionOrder` | `8800` | Sort order for the domain-knowledge system-prompt section. |
| `workspaceContextOrder` | `8900` | Sort order for the dynamic workspace-pattern context. |

## Layout

```
src/
  index.ts       plugin entry: wires the prompt section, context, and tools
  prompt.ts      domain-knowledge text + house conventions
  patterns.ts    workspace scanner (mods, versions, mappings, entrypoints)
  scaffold.ts    scaffolding tools and file templates
  reference.ts   read_mod_reference tool
  build.ts       build_mod tool: runs gradle, parses diagnostics
  fsutil.ts      path-confined file IO helpers
skills/
  mixin-development/SKILL.md          injection points, compat, crash fixes
  compat-troubleshooting/SKILL.md     locating conflicts, verifying fixes
  mod-ecosystem-overview/SKILL.md     loader, publishing, CI, license
  stonecutter-multiversion/SKILL.md   multi version conditional compilation
```

## Notes

- The domain section encodes the house convention from `CLAUDE.md`: no em dashes and no `--` as punctuation in any player- or operator-facing text. The plugin's own strings follow the same rule.
- Mappings: this workspace runs Minecraft 26.2, which ships unobfuscated, so the plugin assumes Mojang/official names (`net.minecraft.core.registries.Registries`, `Identifier.fromNamespaceAndPath`, etc.) and never emits Yarn names. The scanner confirms this per mod from `gradle.properties` / `build.gradle.kts`.
