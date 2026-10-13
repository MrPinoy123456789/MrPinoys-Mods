# Next playtest brief (2026-10-13 build)

Deployed to Kinetic test server `714a0605` on 2026-10-13.

- Commit: `d71769bf` (master), the a3dbc2cf roadmap integration plus the
  HostileWolvesGameTest hardening.
- Jar: `MrPinoys_Pocket_Dungeons-0.1.0.jar`, 2,603,836 bytes, built fresh from
  that commit.
- Verified before deploy: full pure suite green, 323/323 gametests green,
  181 config knobs, server boot clean, all new knobs defaulted into the
  server's pocketdungeons.json.

## What is new on this server

The previous jar predates the whole roadmap build, so everything from
`docs/roadmap-build-2026-10-11.md` is new here: the affix batch, the per
dungeon finale scripts, haunts, the wager system, the Endless Mine per act,
and the six sub-agent tasks (Cow Pits, Copper Works, Lush Caves rooms, both
acts of mine guardians, the Spawner Dungeon capstone).

## Feature to live check map

| Feature | Live check | Quickest route |
|---|---|---|
| PD-204 to 206 fixes | L66 | Kennels run, a rising_lava room, right click a shell token |
| Affix quick changes (T10) | L82 | any affixed run; Restless needs compass 5+ |
| Overclocked, Loaded (T11) | L83, L84 | doors offering the affix |
| Restless widened, Blackout (T12) | L85 | affix doors |
| Deepslate depth, hidden ore (T13) | L86 | Deepslate run; dig in Lush Caves and Copper Works |
| Mineshaft dark phase (T20) | L87 | Mineshaft final floor |
| Ossuary coffins (T21) | L88 | Ossuary final floor |
| Kennels handler (T22) | L89 | Kennels final floor |
| Potion drip, giant slime (T23) | L90 | Lush Caves floors 3+ and finale |
| Creeping bricks, The Queen (T24) | L91 | Infestation |
| Silent Deep Hush (T31) | L92 | Deepslate final floor |
| Big Freeze, braziers (T33) | L93 | Frostworks final floor; the elite is now named Jack Frost |
| Haunts, The Counted (T34) | L94 | Ossuary floors with a haunt |
| Endless Mine per act (T40) | L95 | Cube recipe mine or a hall door offering the Mine |
| Wager, Blood affix (T50) | L96 | door board wager prompt |
| Cow Pits parlour, Specimen 0 (T30) | L67 | Act 2 Cow Pits, all three floors |
| Copper Works clock, vents, vats (T32) | L68 | Act 2 Copper Works; set `copperClockMinutes` low to see the blown clock |
| Lush swamp rooms (T25) | L69 | Lush Caves floors 1 and 2; `dungeon admin bias <room> <n>` steers picks |
| Act 1 mine guardians (T41) | L70 to L74 | Act 1 mine, floors 3, 6, 9 (lap two: 12, 15, 18) |
| Act 2 mine guardians (T42) | L75 to L78 | Deep Mine, floors 3, 6, 9 |
| Brood Chamber, Brood Mother (T43) | L79 to L81 | Spawner Dungeon final floor |

## Useful admin commands (via `pdserver.mjs cmd ...`)

- `dungeon admin compass <player> set <level> [progress]`: opens compass gated
  doors. Set high (45+) when a check is not about gating.
- `dungeon admin haul <player> set <n>`: scrap carried on the trip.
- `dungeon admin bias <room> <multiplier>` plus `dungeon admin bias hold
  <player> [seconds]`: steers room picks toward a room under test (zero_bay,
  witch_haven).
- `dungeon admin validate`: pack health check.

Act 2 has no admin unlock. The only road is the real one: finish every Act 1
dungeon once, reach the Act 1 mine's floor 3 guardian, then clear the Spawner
Dungeon capstone, which conveniently exercises L79 to L81 on the way to L67,
L68 and L75 to L78.

## Known risks and tuning questions

- `broodMobCap` (24) is a first guess; watch a full chamber at cap (L81).
- Brood cage order is the player's choice; some orders may be much harder
  (drip under darkness, coffins last). Note which order the party picked.
- `mineGuardianMobCap` (16) trims guardian waves; the Deep Sentry's whelp
  answer waits at cap rather than spawning over it (L74, L78).
- The Foreman's vat kiting is a playtest question: can he actually be pushed
  in (L76)?
- One Warden rule: the Frostworks elite is Jack Frost and the Act 2 guardian
  is The Frostbite; neither should ever be named a Warden (L78).
- `dungeon admin validate` reports one expected finding:
  `pocketdungeons:endless_mine_deep / adventure: node is not reachable from
  any entry theme; no door ever offers it`. The Deep Mine is offered by the
  act gated mine door at runtime, not by the static door graph, so the check
  cannot see it. Flag if it ever appears alongside other findings.
- The Brood Chamber reuses the shipped room template; if its real caged
  spawner count ever differs from four, the phase order still holds but the
  count needs a data check.

## How the session is recorded

- The in game journal writes
  `world/pocketdungeons/playtest/<yyyy-mm-dd>/<player-uuid>.jsonl` on the
  server (config `playtestJournal`, on by default). Check-ins are off until
  Lemon's dialog lands.
- After the session, pull the day's `.jsonl` from the panel (file API under
  `/world/pocketdungeons/playtest/`) or point
  `node .claude/skills/playtest/summarize.mjs --root <folder>` at it.
- The interview follows `.claude/skills/playtest/SKILL.md`: findings go to
  `docs/playtests/<yyyy-mm-dd>-<n>.md`, `AGENDA.md` and the touched
  `LIVE_CHECKS.md` rows get updated, new bugs append to
  `docs/reference/BUGS.md` under the next `PD-n`, and one row per floor goes
  to `docs/playtests/BALANCE.md`.
