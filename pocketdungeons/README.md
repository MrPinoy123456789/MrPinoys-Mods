# Pocket Dungeons

A server-side Fabric mod for MC 26.2. `/dungeon` to go in.

## Where to start

Read `plans/COMPLETED-MILESTONES.md` for what was built, `docs/DECISIONS.md` for
the settled design and workshop decisions, `docs/playtests/LIVE_CHECKS.md` for
what is built but not yet seen in play, and `docs/reference/BUGS.md` for the
numbered bug and design ledger (PD-n).

## Key features

- **Private dungeon instances** in a void dimension, seeded per player.
- **The compass, the haul and lives**: floors pay scrap into a haul; going
  home or finishing a dungeon banks it into your compass level; a failed
  dungeon keeps half. The party shares five lives, and side doors cost lives.
- **The Astrolabe Room**: choose your act and dungeon in the world, look
  through the window, pull the DESCEND lever.
- **Dungeons by act**: story dungeons (Mineshaft, Infestation, Ossuary, the
  Kennels, Copper Works, Deepslate, Frostworks and on), resource dungeons (Cow
  Pits, Lush Caves, the Endless Mine), and capstone bosses. Most end in a
  finale on the last floor. Affixes, sculk that listens, hostile wolves and more.
- **Room stations**: an enchanting table rerolls enchantments (lapis), a
  grindstone scraps gear, and a librarian and merchants buy and sell for emeralds.
- **Trip sidebar**: floor, lives, spawners, haul and compass while you play
  (`/dungeon display off` hides it).
- **Trial spawners and vaults**: dungeon rooms contain trial spawners that
  eject vault keys or emeralds; a key lasts the dungeon and turns into
  emeralds if unused when the haul banks.
- **Lodestone menu**: Start Dungeon, Manage Room, Inspect Compass, Manage Party
  (invites, bans, building rights) and View Lobbies.

## Active documents

| Document | What it is |
|---|---|
| `docs/DECISIONS.md` | Owner decisions, model changes and workshop ledgers |
| `docs/CODEMAP.md` | Generated class index and task starting points |
| `docs/DISCOVERIES.md` | Verified 26.2 API findings, so nobody re-derives them the hard way |
| `docs/DUNGEON_STRUCTURE_DESIGN.md` | Acts, dungeon graphs, the Astrolabe Room, themes and resource nodes |
| `docs/ZONES_SPEC.md` | Endless Mine per act, Woodland Mansion design and economic roles |
| `docs/INTEGRATION.md` | The published datapack schema (`dungeon_room`, `dungeon_theme`, `dungeon_adventure`, `dungeon`, `anomaly_room`, `diary`, affixes, bags, roles, cube recipes), for pack authors |
| `tools/lemon/GUIDE.md` | The game in plain words, as Lemon explains it; the quickest accurate summary of the rules |
| `.claude/skills/` (workspace root) | How to build content, text, screens and tests for this mod |
| `plans/COMPLETED-MILESTONES.md` | Architectural summary of every completed milestone |

## Reference vault (`docs/reference/`)

| Document | What it is |
|---|---|
| `docs/reference/BUGS.md` | Numbered bug and design ledger (`PD-n`) |
| `docs/reference/VISION.md` | Why this mod exists: the hook, the pillars, the platform thesis |
| `docs/reference/DIALOGS.md` | Vanilla dialog screens and invariants |
| `docs/reference/FINAL_FLOOR_ROOMS.md` | Final-floor room specs and gaps |
| `docs/reference/ORDEALS.md` | Ordeal rooms and mechanics |
| `docs/reference/THEME_TABLE.md` | Theme and spawner table |
| `docs/reference/THEMED_MERCHANTS.md` | Merchant currencies per theme |
| `docs/reference/LORE.md` | The fiction, and where it couples to the mechanics |
| `docs/reference/LORE-STORY.md` | Story content for the lore pass |
| `docs/reference/LORE-DIARIES.md` | Diary content for the lore pass |
| `docs/reference/ROOM_AUTHORING_SPEC.md` | In-world room editor (`buildroom` / `saveroom`) |
| `docs/reference/SALVAGE_PROPOSAL.md` | Salvage station rules |


## House conventions

Read `A:\MrPinoys Mods\CLAUDE.md` before writing anything a person will
read: no em dashes, no double hyphens, as punctuation.
