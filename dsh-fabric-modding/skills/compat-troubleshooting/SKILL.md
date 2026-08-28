---
name: compat-troubleshooting
description: Use when investigating a mod compatibility problem or crash that may involve another mod. Covers locating the conflict point, reading a sibling mod's source, verifying a fix, and deciding whether to fail closed. Load this when a crash log or behavior points at an interaction between mods.
---

# Compatibility troubleshooting

A compatibility problem is almost always two mods wanting to change the same thing. The fix is to find the exact shared target, see what each mod does to it, and make your change coexist or fail closed. This skill is about finding that point fast and verifying the fix, not about guessing.

## Step 1: read the crash log for the real target

- Find the first frame that names a mixin, a `@Redirect`, an `@Overwrite`, or a `target ... method not found` line. That is the conflict point, not the top of the stack.
- A `Mixinsub> target ... method not found` means a signature drifted between Minecraft versions, not necessarily a mod conflict. Check the target's real signature in this workspace's MC version first.
- A `MixinApplyError` or two mods both redirecting the same call shows up as one mod's redirect never running, or a `ClassCastException` from an unexpected type at the call site.

## Step 2: locate what each mod does to that target

- In this workspace, use `read_mod_reference` with concrete terms (the target class name, the method name, the registry key) to pull how a sibling mod already targets the same point.
- For mods outside the workspace, read their published source on their GitHub or, if installed, their decompiled jar. Look for `@Mixin(TargetClass.class)` and the injection annotation on the same method you target.
- Note the injection type each mod uses. If both use `@Redirect` on the same call, that is the conflict. If one uses `@Inject` and the other `@Redirect`, the redirect may swallow the inject.

## Step 3: decide the fix by conflict surface

- If your injection is more invasive than needed, downgrade it: `@Redirect` to `@WrapOperation`, `@WrapOperation` to `@Inject` or `@ModifyArg`. See the `mixin-development` skill for the surface table.
- If you genuinely must own the call and another mod also does, prefer to fail closed: detect the other mod at load time and skip your conflicting injection. An `IMixinConfigPlugin.shouldApplyMixin` that checks `FabricLoader.getInstance().isModLoaded("othermod")` and returns false is safer than a runtime crash.
- If the conflict is a data structure both mods replace, namespace your additions instead of replacing the whole structure, or register into a Fabric API extension point rather than overwriting the vanilla field.

## Step 4: verify the fix

- Reproduce the original crash with both mods present before changing anything, so you know the failure mode.
- Apply the fix and run `build_mod` with the mod's `test` task. Compile success is necessary but not enough: a mixin conflict only shows at runtime.
- Run the game (or the live server) to the trigger point with both mods loaded and confirm the original crash is gone and your intended behavior still works.
- Add or update a regression test (`*Test` JavaExec task) for the logic you changed, so a future drift is caught by `build_mod` rather than by a user crash.

## Common failure shapes in this workspace

- Two mods `@Redirect` the same vanilla call: one silently wins. Fix by switching one to `@WrapOperation` or `@Inject`.
- A mixin targets a method renamed or resigatured in MC 26.x: startup fails with `target not found`. Fix the descriptor against the unobfuscated source.
- A `@ModifyArg` changes a value another mod also modifies: the order of application decides the final value. If order matters, use `@WrapOperation` so both mods' logic composes.
- A mod overwrites a vanilla method a Fabric API callback also targets: the overwrite can skip the callback. Use the callback instead of the overwrite.

## When to escalate

If the conflict is with a mod you cannot read the source of, and the crash is in their mixin, file an issue on their repo with the crash log and the exact target point. Do not patch around a closed source mod by guessing its internals; the guess will break on their next release.
