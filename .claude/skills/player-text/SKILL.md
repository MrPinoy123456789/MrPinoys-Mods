---
name: player-text
description: Write the words a Pocket Dungeons player reads (signs, titles, subtitles, the door board, the sidebar, action bar lines, item lore, dialogs, chat lines) the way the owner wants them: few words, the next action instead of a verdict, no jargon, colour doing the grouping. Use whenever you write or change any string a player will see in game.
---

# Player-facing text

House rule for everything you write: no em dashes and no spaced double hyphens as punctuation
(`A:\MrPinoys Mods\CLAUDE.md`).

The test the owner uses: **what would Steve Jobs put on it?** Fewer words, the thing the player does next,
nothing that explains the system. Players do not read chat live (playtest 2026-10-08-1), so anything that must
be seen goes on a title, the sidebar, the action bar, a sign or a board; chat is only the log.

## The rules

1. **Say what to do, not what happened.** A verdict ("Finished", "Cleared", "Locked") closes a door in the
   player's head. Say the invitation or the reason: "Play again", "Compass 12", "Finish the act".
2. **One idea per line, as few words as carry it.** If a line needs "and", it is two lines. A sign line is
   15 characters, a sidebar line 40, a title 25.
3. **No jargon and no system words.** Not "haul at risk", "interval", "keystone", "node". The settled
   player words are compass, scrap, haul, lives, charts.
4. **Position and colour carry meaning, not labels.** Do not write "Status:" or "Cost:"; put the number where
   it belongs and colour it (aqua for what you carry, yellow for compass, green for good, red for danger,
   gray for the rest).
5. **Name the thing the player can see.** "4 chests in your reward barrel", not "4 chests".
6. **A number needs its unit and its owner.** "Banked 7 scrap", never "Haul 0" after the haul was paid.
7. **Make the state and the sign agree.** If a bulb, a door or a colour already says "done", the words say
   what is next.
8. **Short and clear versions.** For anything new, write both and say which you prefer; ship the short one
   unless the player could misread it.

## Examples from this project (before, after)

| Before | After | Why |
|---|---|---|
| Finished (door sign) | Play again | A verdict read as "nothing left"; the door is open to replay |
| Haul 0 scrap (after a finish) | Banked 7 scrap | The haul was paid already; say what it paid |
| Home 4 chests | Banked 7 scrap. 4 chests in your reward barrel. | Units, and where they are |
| GO HOME (after a finish) | LEAVE | The payout is already banked; the lever only leaves |
| Dungeon cleared (title) | Infestation finished / Banked 9 scrap | A finish is its own beat, with the pay |
| Right-click a door to preview | Right-click a door to look inside | The player's words, not the system's |
| Needs level gate | Compass 12 | The reason, as one number |

## Checklist before you ship a string

- Is it on a surface a player actually looks at (not only chat)?
- Could any word be cut? Could it be a number or a colour instead?
- Does it say the next action, not a verdict?
- Does it use only settled words, and no dash punctuation?
- Does it agree with the blocks, bulbs and colours around it?
