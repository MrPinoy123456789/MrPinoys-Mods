# Roadmap: this week, this month, this quarter

Written 2026-11-04. The server opened 2026-11-03; the 72 hour grace ends 2026-11-06; the first real war cannot start before then. Everything below is for the owner to accept, reorder or cut. Sizes are builder time; deploys are extra and the owner's call. Task numbers refer to `DO_NOW.md`.

## This week (by 2026-11-11): stop the bleeding before the first war

Goal: a newcomer gets through the door, the first war cannot be won offline, the economy's scale is sane, and the owner is not the only person who can touch the server.

| Order | Work | Depends on | Risk if skipped |
|---|---|---|---|
| 1 | Owner actions 1 to 6: push master, delete the text override, check the four live config values, Chronicle voice, LuckPerms admins, co-owner sub-user | nothing | Unpushed work; stale text; a possible empty `wildClaimsSource` stamping ruins into keeps; one person deep |
| 2 | Task 1 newcomer path and task 2 applicants act while waiting | nothing | New players quit in spawn; Azrak and Aurelia stay small |
| 3 | Task 7 war rules (owner decides numbers first: stake, minimum attackers, decay) | design decision | Offline captures from 2026-11-06; griefers at unprotected outposts |
| 4 | Task 10 economy tuning values and task 8 treasury back door plus Fortune wiring | nothing | Inflation in week one; a Lord drains a treasury; Fortune pays no Valor |
| 5 | Task 11 War Pick fixes and task 5 entity protection | nothing | Free diamond pickaxes; villagers shot through walls |
| 6 | Task 13 Pocket Dungeons stutter, claims, lost gear, trims | owner action 3 | Lag blamed on the server; erased builds; "my gear vanished on restart" |
| 7 | Task 3 config safety and task 4 save after charges | nothing | A typo unlocks governance; a crash eats a paid keep |
| 8 | Deploy in two batches with a live check list each (see `TEST_GAPS.md`): batch A Charter plus Currency, batch B Foundry plus PD plus Fortune | all above built and green | One big restart with 81 unplayed commits |

Dependencies: the war redesign (3) needs the owner's numbers before code; everything else is independent and can run in parallel sessions, one per mod folder. Memory on the build machine allows one Gradle build at a time; coordinate suites.

What to cut if time is short: task 4 (save after charges) and task 3 (config safety) can slip a week if the owner promises not to edit `charter.json` by hand; task 13's claims check can be replaced by owner action 3 (set the knob) for now. Do not cut 1, 2, 3 or the treasury fix.

## This month (by 2026-12-04): make the systems meet each other

Goal: coin, gear, dungeons and the Chronicle form one loop; staff can run the server without the owner; the money mod is tested in a server.

| Order | Work | Depends on | Risk |
|---|---|---|---|
| 1 | Task 9 Charter Currency gametests; task 12 Foundry area exclusions and parser hardening; task 15 Lemon hidden | week 1 | A content typo takes the server down; weapons hurt villagers; a dead `/lemon` |
| 2 | Task 16 deploy script hardening and task 17 docs entry point, config inventory, upgrade playbook | nothing | A stranded server during a deploy; a new ops person acting on wrong facts |
| 3 | Monitoring: a scheduled task posting to Discord on offline, stuck backup or new crash report; `/charter admin health` (OP-16); one restore drill timed and written down (OP-4) | co-owner exists | The owner learns of a crash from players |
| 4 | Gear meets coin (GP-4): a Sovereign or Renown cost in the tier 2 and 3 upgrade path, a Renown button on the Leader's Desk, six to ten Valor listings (EC-8), Fortune First Week tips and muster (UX-24, GP-7) | economy tuning live for two weeks of `snapshots.jsonl` | Coin and Valor are numbers; Renown is a typed command |
| 5 | Dungeons you can see and share (task 14, PD-W12): sign and text display, map markers, a Chronicle line per clear, per-clear analytics and a daily clear count (PD-W5) | week 1 PD fixes | The whole wild mode stays dark |
| 6 | Chronicle events players already tell stories about (UX-32): defection as The Betrayal, first tier 3, first clear, 10 members, a jackpot; Justice announced once through the Chronicle and the Herald (GP-6) | templates work from week 1 | The RP happens in Discord and the Chronicle misses it |
| 7 | Pocket Dungeons stamp spread over ticks (PD-W3 full) | the duration log from week 1 shows it matters | Evening freezes with 30 lodestones cycling |
| 8 | Charter core governance edges (CC-7, CC-9, CC-10, CC-11), Foundry `keep_on_death` persistence (FO-6), network load trim (FO-8), Bedrock race fallback identity (FO-10) | nothing | Players elect Lords; unbounded votes; a lost weapon on restart; phone frame cost |
| 9 | Privacy and moderation (OP-15, OP-17, LM-1): a privacy note, an analytics purge command, an appeal line, Lord action digest | nothing | No answer to a deletion request; a Lord abusing power unseen |

Cut if short: 7 (stamp spread) if the duration log shows under 500 ms; 8's FO-8 and FO-10; 9 can be a docs-only pass.

## This quarter (by 2027-02-04): the long game and the next version

Goal: reasons to come back after week three; a server that survives a Minecraft update and the owner's absence.

| Work | Why | Risk |
|---|---|---|
| The ladder between peace and war (CC-16): signed truce extension, alliance and tribute using the existing `contract` model; ransom to stop a capture; war state in the book with a countdown and who is standing there | Today war is a 12 hour countdown and ten minutes of standing still; kingdoms have no reason to talk | Design heavy; needs two or three real wars first |
| Trade has a place (GP-8): a Market block that holds a kingdom's offers, priced in Sovereigns, readable from any keep | The gathering passives exist to drive trade and nothing lets it happen | A new block, a new screen, Bedrock form path |
| A kingdom view for members: who is online, where the Bank is, what the Lord asked for; kingdom chat | The Discord does all of this; the game should echo it | Scope creep; keep it to one book page |
| Save migration and history bounds (CC-12, CF-4): drop the legacy treasury types, rotate `history.jsonl`, render the Chronicle newest first | Months of play will make start-up and the 10 s refresh slow | A migration bug on live data; test on a world copy |
| Load profiling and the tick budget (CF-16, XM-10): a restored load gametest, spark at peak, `LoadMeter` exposed | Nothing has been measured above a handful of players | Unknown headroom at 60 players |
| The next Minecraft version: `UPGRADE_PLAYBOOK.md` followed once on a branch, third party table checked, Trinkets and GoldGate removed first (XM-3, XM-5), one pin style and a root versions file (XM-7), a released Floodgate API instead of the SNAPSHOT (AU-1) | 59 required mixins across six mods; a port is all or nothing | Geyser and Floodgate lag releases; plan the port for after they ship |
| Branch hygiene (AU-2): decide the five branches ahead of master, prune 96 worktrees | The hints and primer work the UX review asks for may already exist on `feature/hints-and-primer` | Lost work |
| Lemon guide mode, or Lemon removed | A companion that says "I do not know" is worse than none | Owner's call |

## Risks across all horizons

- **The build machine.** 16 GB, 27 out-of-memory crash logs, C: nearly full. Two Gradle builds at once will fail. Every parallel session plan must serialise suites.
- **Live checks nobody has done.** Fairy flight on Bedrock, Geyser rendering of Currency and Pocket Dungeons dialogs, the Bedrock recipe book, race looks on persona skins, ViaVersion with older clients. `TEST_GAPS.md` lists them; until they are done, Bedrock parity is a claim.
- **The owner's time.** Every deploy, restart and config edit funnels through one person. Weeks one and two above are about ten builder days plus two deploy evenings and owner decisions on the war numbers and the economy table. If that is too much, week one's rows 1 to 4 are the irreducible set.
