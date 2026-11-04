# Do now: small, safe, high value

Each task is self contained for a builder session: what to change, where, how to prove it, and what it must not touch. Ordered by value for effort. None deploys anything; deploys and config edits on d212ecae are the owner's call through the master session. Coin is in Gold Sovereigns.

Standing rules for every task: branch `feature/<topic>` off master; author commits as MrPinoy123456789; no em dashes or double hyphens in anything written; run the mod's own suite (`charter`: `./gradlew.bat build`; `foundry`, `chartercurrency`, `fortune`: `./gradlew.bat build`; Pocket Dungeons: `bash tools/ship.sh --quick --no-dist`), one build at a time with `JAVA_TOOL_OPTIONS=-Xmx1200m` (2200m for PD), temp on A:; new gametest classes go into the gametest `fabric.mod.json`.

## Owner actions, no build (do these first)

1. **Push master.** `git push origin master` from the main checkout. Master is 45 commits ahead of GitHub. Minutes. (OP-2)
2. **Delete the stale text override on the server.** Back up `config/charter/en_us.json` on d212ecae, delete it, restart when convenient; Charter rewrites a fresh copy. Then check one reworded line in game. 1 h. (OP-11, CF-12)
3. **Check three live config values** (read only): `wildClaimsSource` in `config/pocketdungeons.json` (must be `charter/areas.json`, not empty), `trimBonusDungeonOnly` (set true), `chatTalk` in `config/lemon/lemon.json` (must be false), and whether `earn.json` has a `fortune` source if Fortune is live. 15 min. (PD-W2, PD-W9, LM-4, EC-2)
4. **Rewrite the Chronicle voice.** `config/charter/templates.json` is live config: three crier lines for Joined-with-from, OutpostCaptured and Muted, from `UX_BEDROCK_JAVA.md` section 2.10. Restart or reload per the Chronicle docs. 1 h. (UX-32)
5. **Put the two admins in LuckPerms** (`lp user ... parent add admin` for both owners) and run live tests T4 and T5 from `docs/SERVER_HARDENING.md`. 30 min. (OP-18)
6. **Name a co-owner with a Pterodactyl sub-user** (power, console, files, backups) and write the three pre-approved emergency actions in `docs/KINGDOM_SERVER.md`: restart on crash, roll a jar back from `/mods_backup`, re-arm a stuck schedule. 1 h. (OP-1)

## Builder tasks

### 1. Charter: the newcomer's first ten minutes (UX-1, UX-2, UX-3, UX-4, GP-10 part) 
- Files: `charter/fabric/src/main/resources/data/charter/lang/en_us.json`, `charter/fabric/src/main/java/charter/mc/book/NewcomerPages.java`, `charter/fabric/src/main/java/charter/mc/CharterServer.java` (join).
- Change: `land.drifter` to "Pick a kingdom first. Walk to the sparkling lectern in spawn and tap it."; `pad.join_hint` to "Tap the sparkling lectern in spawn to pick a kingdom."; every player visible `faction`, `organization`, `nation` to `kingdom` (list in `UX_BEDROCK_JAVA.md` UX-2). On join, a player with no kingdom gets a title "Kingdoms of Fantasy" and subtitle "Pick your kingdom at the sparkling lectern" once per session. Kingdom chooser: sort smallest first, label "Azrak, the Dwarf (5)" within 24 characters; kingdom page body: "Azrak, the Dwarf. Emperor krqth rules. 5 members." / one trait line per kingdom from a new lang key `race.traits.<slug>` / "Joining makes you a Dwarf for good." Drop the keep count.
- Prove: `BookLayoutGameTest` and `BookScreenGameTest` still hold the screen rules; a new gametest that a kingdomless join sends a title and a member join does not; dash check on the lang file.
- Do not touch: `requireAffiliation` behaviour (task 2), the stale server override (owner action 2).
- Size: 0.5 d.

### 2. Charter: applicants may play while they wait (GP-1)
- Files: `charter/fabric/src/main/java/charter/mc/land/Protection.java` or wherever `requireAffiliation` refuses a drifter; `Knobs`.
- Change: a knob `applicantsActInWild` (default true): a player with a pending join request may break, place, use and eat outside held land and outside spawn. Refusal text inside held land stays. Also `book.join.asked` body adds the kingdom's Lords by name: "A Lord of Azrak must say yes: krqth."
- Prove: a gametest where a drifter is refused in the wild, asks to join, and is then allowed in the wild and still refused in a keep.
- Size: 0.5 d.

### 3. Charter: config that cannot silently unlock the kingdom (OP-10, CF-3)
- Files: `charter/fabric/src/main/java/charter/mc/CharterConfig.java`, `charter/core/src/main/java/charter/config/Knobs.java` (`problems()`).
- Change: when `problems()` is non empty, keep the parsed `Knobs` and reset only the named fields to their defaults, logging each; compare the file's top level key set with the `Knobs` fields and warn on unknown keys; add `/charter admin config check` that prints both lists without a restart.
- Prove: a `FabricSuite` test: a file with one bad `simStepTicks` keeps `leadersChangeGovernance false`; a misspelt key is reported.
- Size: 0.5 d.

### 4. Charter: save after anything that cost money or moved land (CF-2, CF-7, CF-8)
- Files: `charter/fabric/src/main/java/charter/mc/CharterRuntime.java`, `CharterServer.java`, `CharterStore.java`, `RaceCombatPassives.java`.
- Change: a dirty flag set by the engine listener on any `Charged`, land or membership event, flushed at most once a tick; three consecutive save failures tell online `charter.admin` holders. `RaceCombatPassives` checks `CharterServer.running()` before `runtime()`. The `.bak` copy is skipped when the last load did not come from `state.json`.
- Prove: `StoreGameTest` extended: an action, then a store read without a vanilla save, shows the change.
- Size: 0.5 d.

### 5. Charter: protect living things, not only blocks (CF-1, CF-10)
- Files: `charter/fabric/src/main/java/charter/mc/land/Guard.java`, `Hazards.java`, a new mixin for `BlockAttachedEntity`, `ArmorStand`, `ItemFrame` `hurtServer` under kingdom rules (reuse `SpawnHangingEntityMixin`), `charter.mixins.json`, README mixin table.
- Change: `ServerLivingEntityEvents.ALLOW_DAMAGE` applies `Guard.isProtectedKind` plus `Protection.mayAct(source.getEntity(), ...)` in guarded claims (mirror `Hazards.vehicleMayBeHurt`); wind charge pushes in guarded land treated like spawn.
- Prove: a gametest with a real arrow from an outsider at a villager in a keep (as `aRealArrowFromAnOutsiderCannotBreakACartInAKeep` does for carts), and one for a member's arrow passing.
- Size: 1 d.

### 6. Charter core: grants end when membership ends, and the Lord's limit (CC-1, CC-5, CC-6, CC-13)
- Files: `charter/core/src/main/java/charter/grant/AuthorityResolver.java`, `charter/state/CharterState.java` (`leave`), `engine/ActionEngine.java` (absent Lord), `org/actions/BreakAway.java`, `config/Knobs.java`.
- Change: `reaches` requires `state.isMember(p, subject tree)` for `OfPlayer`; `leave` revokes the player's personal grants in the tree left with a `GrantEvents.Revoked`; absent check uses `max(lastSeen, seat.since)`; `lordSpendLimitMarks` default 6400 (100 Sov); `Withdraw.to` must be a member unless the actor is a vote or operator; `BreakAway` checks `playerFounding`.
- Prove: `AuthorityTest`: grant, leave, use refused; grant, expel, use refused. `TwoLordsTest` without the `seen` workaround. `PaymentPortTest` updated for the new default. A fuzz invariant: a working personal grant reaches a member.
- Size: 1 d.

### 7. Charter core: war that cannot be won while the enemy sleeps (CC-2, CC-3, CC-4, CC-8)
- Files: `charter/core/src/main/java/charter/siege/SiegeRules.java`, `SiegeSim.java`, `siege/actions/DeclareWar.java`, `WarBook.java`, `WarInputs`, `Knobs`, the fabric protection call sites that ask `protectedClaim`.
- Change: protection loss scoped to members of the attacking nation (`protectedClaimFor(claim, player)`); a declaration stakes half the target's deposit, forfeited on a lapse; capture needs `warMinAttackers` (default 2) present and counts defenders anywhere in the outpost's claims; progress decays by half per unattended siege hour; both silent endings record an event and a truce; an owner cannot lift an outpost named by an open goal.
- Prove: `WarTest` cases for each rule; `InvariantTest` generator draws `DeclareWar`.
- Size: 2 d. Owner decides the numbers first (see `ROADMAP.md`).

### 8. Charter Currency: close the treasury back door and wire Fortune (EC-3, EC-2, EC-4, EC-6)
- Files: `chartercurrency/fabric/src/main/java/chartercurrency/TreasuryCommands.java`, `core/Treasury.java`, `EarnConfigFile.java`, `JsonAccountStore.java`, `EconomyApi`; `fortune/fabric/src/main/java/fortune/mc/CurrencyBank.java`, `Payout.java`.
- Change: `/treasury withdraw` honours Charter's spend right and `lordSpendLimitMarks` through the kingdom view (or refuses when Charter is bound and names Charter's Withdraw); `fortune` source shipped in defaults (`base 4, perLevel 2, softCap 60, renownMultiplier 0.5`) and missing default sources merged into an existing `earn.json`; an `EconomyApi.chargeExternal(player, marks, reason)` that writes the transaction log and an analytics line, used by Fortune; the account store flushes synchronously after withdraw, shop delivery, treasury give and stake; Fortune warns once when `earn` pays 0.
- Prove: `EconomyTest` sections for the limit and the merge; a Fortune gametest asserting the log line.
- Size: 1 d.

### 9. Charter Currency: four gametests that run the money in a server (EC-7)
- Files: new `chartercurrency/fabric/src/gametest/...` source set and `fabric.mod.json`, `chartercurrency/fabric/build.gradle.kts` (copy Fortune's harness block).
- Tests: a coin in a crafting grid yields nothing (`RecipeMeltMixin` applies on 26.3); `/bank all` on a mixed inventory with a plain nugget banks only the marked ones; a Mint strike with a nearly full inventory banks the overflow; a shop purchase of an enchanted book delivers the stored enchantment.
- Size: 1 d.

### 10. Economy tuning: config values (EC-1, EC-5, EC-10, EC-13)
- Files: `chartercurrency/fabric/src/main/java/chartercurrency/EconomySettings.java` defaults and the example configs; `charter/core/src/main/java/charter/config/Knobs.java` defaults; `fortune/fabric/.../FortuneService.java` (`pull`).
- Change: `mint.softCapIngots` 64, `mint.falloffEvery` 64; `keepPriceMarks` 20480, `outpostDepositMarks` 2560 / step 1280, `custodyFineCapMarks` 5120, `bailMarksPerHour` 320, `npcTreasuryMarks` 2560 / 5120 / 10240, `shop.confirmAt.coin` 12800; Fortune `stakeTier` reset to none after each pull and shown in the Bedrock form text; `timezone` documented as the players' zone. Full table in `ECONOMY.md`.
- Prove: `EconomyTest` and `CoreSuite` updated; the knob comments say why.
- Size: 3 h. The live values are the owner's to set.

### 11. Foundry: the War Pick, four fixes (FO-1, FO-2, FO-12, FO-11, FO-5)
- Files: `foundry/tools/gen_weapons.py` (`azrak`, `aurelia`), `foundry/examples/content/kingdoms/items/*.json` (regenerated), `foundry/src/main/java/foundry/mc/Swings.java`, `Gate.java`, `FoundryItem` (`modifyBasePolymerItemStack`), `Bonuses.java`, `bonuses/azrak_fortune.json`.
- Change: generator emits `tool`, the `minecraft:pickaxes` tag and the "Mines as" lore for the pick (then regenerate; the diff must be empty except CRLF); `AttackBlockCallback` returns FAIL when `Gate.check` refuses the main hand, and the `TOOL` component is stripped from the client copy for non holders; `nether_gold_ore` removed from the Dwarf Fortune list; the Dwarf bonus computed from an unenchanted drop count; Oathblade Weakness at `chance 0.25` (or parry counter only).
- Prove: a core check that regenerates in memory and compares with the committed content; a gametest that a non Dwarf cannot break a block with the pick; `WeaponsGameTest` still passes.
- Size: 0.5 d.

### 12. Foundry: area abilities spare villagers, pets and allies; bad content cannot stop the boot (FO-4, FO-3)
- Files: `foundry/src/main/java/foundry/core/MechanicTypes.java`, `ItemParser.java`, `ContentLoader.java`; `foundry/src/main/java/foundry/mc/MechanicHost.java` (`around`, `nearImpact`), `Quickdraw.java`; `gen_weapons.py`.
- Change: a closed `exclude` list on `damage`, `knockback`, `apply_effect`, `dash_strike` (`villagers`, `tamed`, `passive`, `players`), honoured in all three selectors; every area action in the generator gets `["villagers", "tamed"]`; `ItemParser` wraps each `getAs*` in the field helpers and `ContentLoader` turns any `RuntimeException` per file into a `Problem`.
- Prove: CoreSuite checks for an object in `lore`, a string in `enchantable.value`; a gametest that Ground Slam beside a villager leaves it unhurt.
- Size: 1 d.

### 13. Pocket Dungeons wild: no stutter, no erased builds, no lost gear (PD-W1, PD-W2, PD-W4, PD-W9, PD-W10, PD-W3 first step)
- Files: `pocketdungeons/src/main/java/pocketdungeons/WildTopUp.java`, `WildLodestones.java`, `Instances.java` (stopping), `WildRun.java`, `LemonBridge.java`, `config/pocketdungeons.default.json`.
- Change: `worldRejection` rejects a candidate whose chunks are not loaded (`getChunkNow == null`) instead of generating them; in wild mode the claims check uses `charter.api.CharterAreas` when Charter is present and only falls back to the file knob otherwise, and any non natural block in the 5 by 5 by 5 column rejects the point; the stopping hook runs `WildSweep.sweep` for every wild record before `purge`; wild mode forces `trimBonusDungeonOnly`; the compass summon is gated on `!WildMode.active()`; `WildRun.build` logs plan and stamp duration at INFO.
- Prove: gametests for the unloaded chunk rejection, for a stop with an item inside (sweep chest holds it), and for a claimed point rejected through the API.
- Size: 1 d.

### 14. Pocket Dungeons wild: a dungeon you can see (PD-W7, UX-30, PD-W12)
- Files: `WildLodestones.java`, `WildMarkerDisplays.java`, `WildText.java`, Charter `MapMarkers` hook.
- Change: a text display above the marker for all clients ("A dungeon. Tap the dark stone."), an oak sign on the ruin, a chat line on first approach within 8 blocks, the hint reworded to "the dark stone in a ruin"; each lodestone registered with `charter.api.MapMarkers` when Charter is present.
- Prove: `WildLodestoneGameTest` extended for the sign and display; live check Geyser dialog rendering before anything else (see `TEST_GAPS.md`).
- Size: 0.5 d.

### 15. Lemon: hide her until she can talk (LM-3, LM-4, LM-1, LM-2)
- Files: `lemon/src/main/java/lemon/LemonCommands.java`, `LemonConfig.java`, `LemonSpeech.java`, `LemonJournal.java`.
- Change: a `playerCommands` knob (default false) that registers the player facing nodes only when on; the solo rule removed from `addressed` (prefix always required); journal only while an agent lease is active, with 7 day retention; 2 s cooldown and 256 character cap on `/lemon <text>` and `/lemon report`; writes through a background executor.
- Prove: `LemonSpeechTest` for the prefix rule; `CommandGameTest` for the knob.
- Size: 0.5 d.

### 16. Ops: the deploy script that cannot strand the server (OP-3, OP-5, OP-6, OP-7)
- Files: `tools/playtest/shipped-deploy.mjs`, `tools/playtest/*.test.mjs`, `docs/KINGDOM_SERVER.md`.
- Change: each upload retried three times and verified; on failure the `before-<stamp>` jars are restored and the server started; `--rollback before-<stamp>`; the version check reads each jar's `fabric.mod.json`; `--allow` strips the `[Tag] ` prefix; `--prune` or `--remove` for retired content; the flag list in the docs regenerated from the script header.
- Prove: node tests with a fake panel that fails the second upload.
- Size: 1 d.

### 17. Ops: one entry point and one truth (OP-19, OP-12, XM-9, FO-7)
- Files: new `docs/README.md`, `docs/CONFIG_INVENTORY.md`, `docs/UPGRADE_PLAYBOOK.md`; one line each in `docs/KINGDOM_SERVER.md:6` and `docs/SERVER_HARDENING.md:26` (Axiom); `foundry/docs/DECISIONS.md` D6; move `docs/SUITE_AUDIT.md`, `CODE_AUDIT_PHASE2.md`, `ROADMAP.md` to `docs/archive/`.
- Content: read order, historical list, live facts table (mods, operators, Axiom state, last deploy, backups); the config table from the ops review; the upgrade order and the mixin audit command.
- Size: 0.5 d.

### 18. Small ones, one commit each
- `AdminBook.allowed` uses `CharterPerms.admin` (CF-6, 15 min).
- `discord.example.json` ships `enabled: false` (CF-13, 10 min).
- Jail: teleports and pads refuse when held; `CommandRule` allowlist (CF-5, 2 h).
- `Retired` sweep at join only (EE-3, 15 min); `VillagerMixin` keeps demand (IR-1, 1 h); one `minecraft` pin style across the small mods (IR-2, 5 min).
- Fortune: `Annals: this week's biggest wins` to `This week's big wins`; milestone broadcast reordered; jackpot symbol "Sun" on the real Sovereign item (UX-23, UX-16, 1 h).
- Currency welcome: three lines, no command, no click link; the one-in-five tax in the Mint look-at line (UX-15, UX-18, 1 h).
- Weapon lore template: one ability per line, the Bedrock tap note on every hold ability (UX-26, 2 h).
- Race movement tips for Dwarf, Human, Merfolk, Fairy (UX-29, 2 h).
- Foundry `permissionCheck` refuses `race:*` from the vanilla fallback (XM-1, 1 h).
