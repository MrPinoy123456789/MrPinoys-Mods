# Known issues and traps

- Shell heredocs: a long `cat <<'EOF'` write failed silently on this Windows setup and wrote nothing. Use the editor
  tool for files; confirm with `ls` that they exist.
- Working directory resets: the shell may return to another worktree between commands. Use absolute paths or `cd`
  into `foundry/` in the same command.
- Mojang names in 26.x: `Identifier` replaces `ResourceLocation`; `Item.Properties` needs `setId(ResourceKey)` before
  an item is built; `Registry.register` is used as shown in `FoundryItems`. Copy patterns from `lemon/` and
  `pocketdungeons/` rather than older tutorials.
- Polymer API surface is large and version specific. The item API was checked with `javap` on
  `polymer-core-0.17.5+26.2.jar` (in `~/.gradle/caches/modules-2/files-2.1/eu.pb4/`). Do the same for anything else.
- `SimplePolymerItem` fixes the vanilla item shown to clients at construction. A definition whose `base_item` changes
  needs a restart; this is documented, do not try to hot swap it.
- `PolymerResourcePackUtils.markAsRequired()` is called in `FoundryMod.onInitialize`; check it still fits the owner's
  pack acceptance policy when you do F1.
- `run/` and `build/` are git-ignored; example content lives in `examples/`, never only in `run/`.
- The Kinetic test server is `714a0605`. `365f9682` is not a test target. You do not deploy; this is here so you
  never copy a server id into code or docs by accident.
- The first Gradle run downloads dependencies and can take several minutes; the Fabric Loom plugin needs the
  Fabric maven (`settings.gradle.kts`) and Polymer needs `https://maven.nucleoid.xyz`.
