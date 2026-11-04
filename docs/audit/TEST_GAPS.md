# Test gaps, live checks owed, and a playtest script

Baseline (2026-11-04, master 478cce26): every suite passes. Charter 2681 core, 569 fabric, 244 gametests; Charter Currency 416 core, no gametests; Fortune 134 core, 9 gametests; Foundry 597 core, 116 gametests; Excellent Enchants 18 gametests; Instant Restock 3; Lemon 2 pure; Pocket Dungeons 458 gametests and pure tests, SUITE GREEN in 5 minutes; Node 3 of 3. Every gametest class present is registered. Details in `BASELINE.md`.

## 1. What is untested (by mod)

### Charter core
- A personal grant surviving `Leave`, `Expel` or secession (CC-1); today it survives and no test says so.
- Protection scoped by asker during an open war (CC-2); `WarTest.captureCannotBeginUntilTheNoticeIsOver` checks the claim's flag only.
- Intermittent attacker presence, several attackers, defenders in a non-banner claim (CC-3, CC-4).
- A Lord withdrawing the whole treasury to a non member with default knobs; `PaymentPortTest.aLordSpendsWithoutALimitByDefault` pins the unlimited default as correct (CC-5).
- Appointing a never-seen Lord (`TwoLordsTest` calls `seen` first) (CC-6); both Lords absent; an election seating a second Lord (CC-7).
- The target-vanishes branch at `SiegeSim.java:196-200` (CC-8); vote growth, proposal spam, an election with an empty electorate (CC-9).
- `InvariantTest` never draws `DeclareWar`, `Secede`, `BreakAway`, `SetSeats`, `Apply`/`Admit`, `AppointCoLeader`, rank actions or `ChooseMercenaryRace`; `afterStep` checks nothing siege specific; INVARIANTS.md items 15, 16, 17 are marked tested and are not; item 9 checks the dead item ledger (CC-10).
- Test honesty: the suite is `main()` style with a shared tally; a class left out of `CoreSuite.run` never runs. Today every class is listed (verified by name).

### Charter fabric
- Projectile, potion, dispenser or wind charge damage to protected non-vehicle entities in kingdom land (CF-1, CF-10); mob griefing in kingdoms; hopper or dropper inserts across a kingdom border (the hopper test proves the gate is called, not that anything refuses).
- The real Floodgate adapter is never loaded (`compileOnly`); tests use a fake `Sender`. Nothing proves Cumulus 1.1.2 against the server's Floodgate, dropdown indices with labels, or a form closed with the X on a real client.
- Discord gateway and REST against a socket: reconnect, heartbeat timeout, 429, the command-registration PUT.
- A crash between an engine action and the next autosave (CF-2); a full disk; a `formatVersion` migration (none exist yet).
- Any dimension other than the overworld; the world border; a player offline across a sentence or a Nexus lift.
- Tick cost: the README's `LoadGameTest` does not exist; `LoadMeter` is read nowhere (CF-16).
- `Text` (the server override merge) has no test at all (CF-12).
- `HardeningGameTest` pins the permission outcomes but the phase ordering would pass without the phase change; live tests T2 and T8 are the real check.
- `BookLayoutGameTest` and `BookScreenGameTest` prove shape (8 buttons, 24 characters, 3 body lines), not readability on a TV.

### Charter Currency
- No gametest source set. Proven only by reading: `CoinStacks.markerOf` (a marked nugget recognised, a plain or renamed one not), `ItemBank` reading slots 0 to 35 only, `RecipeMeltMixin` applying on 26.3 (EC-7), a Mint strike with a full inventory banking rather than dropping, `BankStation` recording a placement once the vault stands, Charter's `bind` arriving before the first strike, analytics lines landing in the configured zone, a shop purchase delivering a stamped item.
- `persistence()` and `security()` sections exist in `EconomyTest`; nothing bounds a crash between debit and flush (EC-6).

### Fortune
- Every interaction with real Charter Currency: `FortuneKit.FakeBank` stands in; the payout command is replaced by a tag command. Nothing proves `earn` accepts the `fortune` source (EC-2), that Bedrock `form` mode charges and reveals, `firstSpinMinutes`, `/fortune admin reset` with a remembered stake, the jackpot keepsake, or what the Odds screen shows when `earn.json` disagrees with `valorBase`.

### Foundry and the small mods
- Every gametest uses mock players and an injected `permissionCheck` (`TestPerms`); nothing proves Charter's `RaceNodes` or LuckPerms answer `race:<kingdom>` through the Fabric permission API on 26.3.
- The race lock while mining (there is none, FO-2); a wrongly typed content field (FO-3); `keep_on_death` across a restart or a cancelled death (FO-6); area damage on villagers or tamed mobs (FO-4); Weakness stacking in a duel (FO-5).
- The generator versus the committed JSON (FO-1); `WeaponsGameTest.theWarPickMines...` tests the committed file, not the script.
- `RestockGameTest.pricesAndDemandAreLeftAlone` asserts demand after one trade, not after a restock cycle, so the demand collapse passes (IR-1).
- Bedrock paths use `Floodgate.treatAsBedrock` on a mock player: tap to cast is covered; the stand-in elytra packet, skin geometry, Geyser's recipe book and whether Bedrock reports jump while gliding are not.
- Fairy glide: entering water while gliding, `canGlide` with Levitation removed mid flight.
- Network cost (FO-8) has no measurement. GoldGate has no test and no recorded diff against the 26.3 vanilla table.

### Pocket Dungeons (wild mode)
- Real overworld placement on terrain (the gametest world has no terrain, `docs/DECISIONS.md:1420`); the Charter `areas.json` against the live file (PD-W2); the time a plan plus stamp takes (no assertion, no log, PD-W3); the chunk generation cost of `worldRejection` (PD-W1); Geyser rendering of `Dialog` screens and of the bedrock marker (PD-W7); a server stop with items inside (PD-W4); two dozen concurrent dungeons under 60 players; PvP and vanilla death drops inside; the `/tpa` adoption path with real teleport commands; the message a rejoining player sees.
- No flaky tests are documented anywhere in `pocketdungeons/docs`; the project memory names `HauntFloorGameTest` and Heist as occasionally flaky. This run was green.

### Lemon
- Command permissions are pinned by `CommandGameTest.driverTargetsRequireOperatorPermission`. Untested: the solo chat rule's effect on a spread-out server (LM-4), journal growth and the write cost (LM-2).

### Operations
- `shipped-deploy.mjs` has no test of a failed upload after `stop` (OP-3); the three node tests cover the chat cursor only.
- No restore has ever been done end to end (OP-4). The staged LuckPerms sub-node names (T5) are unconfirmed live.

## 2. Live checks still owed (nothing below has been seen by a player)

From `docs/HANDOFF_2026-11-02.md` item 2 and this audit, grouped by client.

Java:
- Fairy takeoff leap and wingbeats; Fae wing dash range; Human slide; Merfolk swim passives and sea floor mining.
- Weapon 3D models, bolt models and projectiles; the three magic enchantments at a table; melee weapons taking Excellent Enchants.
- The Admin Book and operators' menu for a LuckPerms-only admin (CF-6 says the book will be taken).
- Lord discipline from the Leader's Desk; the one hour Ledger grant.
- Hardening T2 (race node phase order), T3 (AntiXray plus Polymer), T4 and T5 (LuckPerms groups and sub-nodes), T8 (CarryOn versus keep protection).
- The deployed Pocket Dungeons jar's commit (does it carry 8e19e296); a w6 deep_quarry entry under Blackout.
- An older client through ViaVersion opening the Charter book (XM-6).

Bedrock (phone and Xbox):
- The Charter book as native forms: icon paths, dropdown indices with labels, the X button, body colour.
- Charter Currency's purchase confirm dialog and the shop editor through Geyser; Pocket Dungeons' two-button entry dialog; the written book Chronicle.
- Fairy flight and wingbeats (jump while gliding), the stand-in elytra in the chest slot, Fae wing dash with sneak as a toggle, Human slide, Merfolk `swim:speed#1` stand-in (does Speed change Bedrock swim speed at all).
- Race looks on classic skins, and the silent skip on persona skins (FO-10).
- The Bedrock recipe book after a race unlock; the upgrade recipe naming the base item.
- Shift-click bulk buy (expected impossible), the Fortune chest animation at 3 ticks a frame, container glint, the `▮▯` meter glyphs, the coin emoji heading, name tag prefixes and colours.
- Whether a kingdomless player can open the hub Bank and Mint (EC-12).
- The Floodgate `replace-spaces` setting and a gamertag with a space earning Valor (EC-11).

## 3. Proposed playtest script (two evenings, one Java and one Bedrock tester each, the owner watching the log)

Before: deploy batch A (Charter plus Currency) with the `DO_NOW.md` week-one fixes; set `wildClaimsSource`, `trimBonusDungeonOnly true`, the economy knobs; delete the text override. Start a spark profile (`/spark profiler start --timeout 600`) at the busiest hour and keep `/charter admin health` or `LoadMeter` output if built.

Evening 1, the door and the money (90 minutes):
1. A brand new account on each client joins. Record every line they see in the first five minutes, in order (Charter title, Currency welcome, Fortune prompt). Expected after the fixes: a Charter title naming the lectern; no "open your Charter book"; the Fortune prompt only once they have a kingdom.
2. Walk to the lectern without help. Time it. Open the chooser: smallest kingdom first, counts shown, the kingdom page shows the ruler and one trait line. Ask to join Azrak with no Azrak Lord online. Expected: can break, place and eat in the wild; refused in a keep; the body names Azrak's Lords.
3. An operator admits them. Expected: "Azrak took you in", the recipe unlock chat line, and on Bedrock the recipe in the book (check). Craft the rank 1 weapon in the 2 by 2 grid. Try each ability on each client; on Bedrock, a tap casts after the charge.
4. Mint: hold 20 gold ingots, tap the hub Mint as a kingdomless player (EC-12) and as a member. Expected: the look-at line says the tax before the strike; 200 Sov struck, 40 to the kingdom; overflow banks with a nearly full inventory. `/baltop` and the login board show Sovereigns, not Marks.
5. Shop: buy one item, then try a 200 Sov purchase on Bedrock (the confirm dialog). Try shift-click on Java and a long press on Bedrock. Buy a gold ingot for 10 Sov and strike it again (neutral loop).
6. Treasury: as a Lord, `/treasury withdraw` above the new limit. Expected: refused with Charter's limit named. Through the Leader's Desk, withdraw below the limit. Check `transactions.log` and the analytics event file for both.
7. Fortune (if deployed in batch B, move to evening 2): spin as a 4 minute old account (expected: told to wait with the reason), then after 5 minutes; stake 5 Sov once and pull again tomorrow (expected: the stake is not re-charged); check `/balance` for the Valor and the transaction log for the stake.
8. Crash test on the test server 714a0605 only: buy a 200 Sov item, kill the server within 10 seconds, restart. Expected after the fix: balance and item agree.

Evening 2, land, war and the wild (2 hours, after the grace ends, on the test server first if possible):
1. Two kingdoms plant touching outposts. An outsider (third kingdom and a mercenary) shoots a kingdom's villager and horse with a bow from outside the border, throws a splash potion, places a dispenser facing in, uses a wind charge on a cow by the wall. Expected after task 5: all refused with the deny cue; Ledger search finds nothing to roll back because nothing happened.
2. A Dwarf uses Ground Slam next to the kingdom's villagers and a tamed wolf (expected after task 12: unhurt); a Merfolk's Undertow beside villagers; a Fae's Bramble Burst with a Human ally of a mixed kingdom in range.
3. Declare war. Expected: the stake is charged; the Chronicle line; before the notice ends the outpost is protected to everyone; after it opens, protected to the third kingdom and the mercenary, open to the attacker's members only. One attacker stands in the banner chunk alone for 10 minutes: expected no capture (minimum two). Two attackers with a defender anywhere in the outpost's claims: no progress. Two attackers, no defender, leave after 3 minutes, come back an hour later: progress decayed.
4. A non Dwarf crafts a War Pick and tries to mine with it (expected: refused); a Dwarf mines gold ore with a Fortune III pick and the drops are checked against the stated cap.
5. The wild: watch the log for `WildTopUp` placements and the plan plus stamp duration line. Find a ruin on each client: what does a Bedrock player see (bedrock cube, sign, text display); tap to enter on Bedrock (the dialog renders, or does not: this decides PD-W7). Enter, drop an item, leave it in the dungeon, stop the server cleanly, start, and look for the lost and found chest beside the ruin. Check the clear's barrel contents against the gold table and write the ingot count down.
6. Trims: wear quartz trimmed armour outside a dungeon and read the attribute (expected: no bonus with `trimBonusDungeonOnly true`).
7. A prisoner: arrest a volunteer, put them in a cell, have them `/charter hub` (expected after CF-5: refused).
8. Read the spark profile: the top ten handlers by self time. Record `Foundry` senses, `WornDisplays`, `CarryGuard`, `ShopkeeperAnchor`, `WildTopUp` against each other. Cut the sense radius in `race_traits.json` live if it leads.

After each evening: file every mismatch as a numbered bug (the project's rule: harness and wait faults count as bugs too), note which UNVERIFIED items above are now verified, and update `BASELINE.md` with what was seen.

## 4. The cheapest tests to add first (in order)

1. Charter Currency gametest module, four tests (`DO_NOW.md` task 9). The money has never run in a server.
2. `AuthorityTest`: grant, leave, use refused (CC-1). Ten lines; the current behaviour is wrong and untested.
3. A Foundry core check that regenerates the weapons in memory and diffs the committed content (FO-1). Catches the drift that already exists.
4. A real arrow from an outsider at a villager in a keep (CF-1), modelled on the existing cart test.
5. `WarTest` for scoped protection, minimum attackers and decay (CC-2 to CC-4), once the owner sets the numbers.
6. `WildTopUp` rejects an unloaded chunk; a stop with an item inside leaves it in the sweep chest (PD-W1, PD-W4).
7. `RestockGameTest` trading past `maxUses` and reading demand (IR-1); the current test passes for the wrong reason.
8. A `FabricSuite` test for `CharterConfig` with one bad knob keeping the rest (OP-10).
9. `InvariantTest` generator additions and the rewritten siege invariants (CC-10), so INVARIANTS.md stops overstating.
10. A node test for `shipped-deploy.mjs` with a failed second upload (OP-3).
