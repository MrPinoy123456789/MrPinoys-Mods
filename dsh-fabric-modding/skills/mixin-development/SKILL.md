---
name: mixin-development
description: Use when writing or modifying Mixins in a Minecraft Fabric mod. Covers injection point selection, compatibility-safe practices, mixin config files, and fixing mixin crashes. Load this before adding any @Inject, @ModifyArg, @WrapOperation, @Redirect, or @Overwrite.
---

# Mixin development

Mixin is the main tool for changing vanilla behavior in a Fabric mod. Mixin used badly conflicts with other mods or crashes at startup. Every rule below follows from one fact: when several mods target the same class, the one that takes over the most semantics is the one most likely to overwrite the others.

This workspace runs Minecraft 26.x unobfuscated with Mojang/official mappings, so target descriptors use official names directly. There is no Yarn refmap to fall back on.

## Core principle: conflict surface equals how much semantics the injection takes over

- `@Redirect` takes over an entire call: it replaces the call wholesale. Two mods redirecting the same call site collide. Largest conflict surface.
- `@Inject`, `@ModifyVariable`, `@ModifyArg`, `@ModifyExpressionValue`, `@WrapOperation` change only a local piece: the injected code coexists with the original call, and several mods can stack. Small, composable conflict surface.
- Rule: prefer the least invasive injection that solves the problem. If `@Inject` works, do not reach for `@WrapOperation`. If `@WrapOperation` works, do not reach for `@Redirect`.

| Injection | What it takes over | Why safe or dangerous |
|---|---|---|
| `@Inject` (HEAD / RETURN / a specific anchor) | Inserts code, does not change the return value; coexists with original logic | Safe, composable |
| `@ModifyVariable` | Changes one method argument or local variable value | Local, composable |
| `@ModifyArg` | Changes one argument of one call | Local, composable |
| `@ModifyExpressionValue` (MixinExtras) | Changes an expression result, does not replace the call; chains across mods | Smallest surface |
| `@WrapOperation` (MixinExtras) | Keeps the original call (`original.call(...)`), adds logic before/after; two wraps can nest | Composable |
| `@Local` (MixinExtras) | Sugar to read a local variable; changes no injection semantics | Safe |
| `@Redirect` | Replaces an entire call; two mods redirecting the same point always conflict | Dangerous; only when you must fully own the call |
| `@Overwrite` | Replaces an entire method body; two mods overwriting the same method silently last-write-wins | Most dangerous; avoid |

`@WrapOperation`, `@ModifyExpressionValue`, and `@Local` come from MixinExtras, not native Mixin. Fabric Loader 0.15.x+ bundles `mixinextras-fabric`, so no explicit dependency is needed in this workspace.

## Compatibility details that each have a reason

- `remap = false` for non Minecraft targets: refmaps only remap Minecraft classes. `java.*`, `com.google.*`, `org.joml.*` are not remapped. If a Mixin treats such a call site as a Minecraft class, the target string maps wrong and startup fails with "target not found". Put `remap = false` on the `@At` target for any non MC call site.
- Method name form: the target method name follows the mappings and version. In this workspace (Mojang names, MC 26.x) write the official name. When a method is overloaded, write the full descriptor to disambiguate, e.g. `"collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"`.
- `require = N`: when `defaultRequire = 0`, a missed injection point silently skips. The mixin "does not work" and nobody knows. `require = N` turns "must match N points" into a loud error, so a mappings or version drift fails fast instead of silently.
- `@Overwrite` is the largest conflict surface: two mods overwriting the same method means the later one wins silently. If you truly must, add `@author` and `@reason` Javadoc as an audit record for maintainers and reviewers.
- `@Unique` + `modid_` prefix: every identifier a mixin injects into a target class shares one global namespace. An unprefixed invented member can collide with another mod's. Prefix interface methods too (`fabric_`, your modid, or an `IE*` duck-typed interface).
- Pick injection points that are called rarely and are semantically stable: per frame or hot path injections both hurt performance and raise the chance of interacting with another mod.

## Mixin config file (`<mod>.mixins.json`)

This workspace's shape (see any existing mod, e.g. pocketdungeons):

```json
{
  "required": true,
  "package": "<maven_group>.mixin",
  "compatibilityLevel": "JAVA_25",
  "mixins": [],
  "injectors": { "defaultRequire": 1 }
}
```

- `package`: where the mixin classes live.
- `required: true` + `compatibilityLevel` (`JAVA_25` here): baseline.
- `injectors.defaultRequire: 1`: injection points must match or startup errors. When debugging "my mixin did not apply", check this first.
- `mixins` / `client` / `server`: split by side. Client only mixins go in `client`, server in `server`, common in `mixins`. Most mods in this workspace are server side, so `mixins` is the usual bucket.
- Add every new mixin class simple name to the `mixins` array, or it will not apply. The `scaffold_mixin` tool does this for you.

## Useful patterns

- Interface mixin: when the target is an interface, write the mixin as an `interface`, `@Shadow` methods as abstract, and put injection logic in `default` methods. `default` methods are composable: several mixins can each provide a different `default` without overwriting. This also avoids `(Target)(Object)this` casts.
- Replace a static data structure: `@Shadow @Final @Mutable` field plus an `@Inject` at `<clinit>` `@At("RETURN")` that rewrites it. Static data has one copy, so the rewrite must happen after class init completes; `<clinit>` runs exactly once and is semantically stable.
- Target an anonymous inner class: nest a `static class` inside the outer mixin with `@Mixin(targets = "fully.qualified.Name$1")`. Anonymous classes have no source name, so this is inherently fragile; use only when necessary.
- Anchor a lambda generated method: `@Shadow(aliases = "lambda$...")`. Lambda method names are compiler decided and may change between versions; `aliases` gives several candidate names to raise the hit rate.
- Precise anchor: "after a field write" = `@At(value = "FIELD", target = "Lnet/...;fieldName:Z", opcode = Opcodes.PUTFIELD, ordinal = 0, shift = At.Shift.AFTER)`. The more precise the point, the more deterministic the target state.
- `priority` is "jump the queue": several injections at the same point apply in priority order. Raising priority overrides other mods but also couples you to them. Reserve for cases where you must own the point.

## Writing steps

1. Confirm the target class's real name, method name, and signature in the target Minecraft version. In this workspace, read the unobfuscated source or use `read_mod_reference` to pull how a sibling mod already targets it.
2. Pick the least invasive injection: `@Inject` before `@WrapOperation` before `@Redirect`.
3. Specify a precise `@At`. For `RETURN`, only enable `cancellable` on `CallbackInfo` when you actually need to cancel.
4. Register the mixin class simple name in the mod's mixins json `mixins` array.

## Verification

- Run the game (or `build_mod`) to the point where the injection actually fires, not just startup, and confirm the behavior changed with no errors.
- Add `-Dmixin.debug.export=true` to the run config to export the decompiled target method and verify the injection point landed where expected. This is a debug tool, not a convention.
- When an injection "did not apply", check in order: `injectors.defaultRequire` is set, `require` is satisfied, `remap` is correct for non MC targets, the target method name has not changed in this MC version.
- A crash log line like `Mixin ... target ... method not found` usually means a signature mismatch from a version difference. Fix the descriptor against that version's source.
- In this workspace, finish by calling `build_mod` with the mod's `test` task (or the specific `*Test` JavaExec task) to prove nothing regressed.
