---
name: wall-boards
description: Build or change a Pocket Dungeons wall board, the in-world screens made of display entities on a room wall (the staging room terminal, the door board over the selector doors, the go-home panel, any new board). Covers viewer coordinates, the depth each display type sits at, yaw per wall, text anchoring and font-width wrapping, the scale 1.0 floor, icons with right-click inspect glyphs, click boxes that stand on their bottom centre, tagging and clearing, and the gametests that pin a board. Use whenever you place text, item or block displays or glyph click boxes on a wall.
---

# Wall boards

House rule for everything you write: no em dashes and no spaced double hyphens as punctuation
(`A:\MrPinoys Mods\CLAUDE.md`). The words on a board follow the `player-text` skill; a new board or a layout change
goes through `design-brief` first. Code lives in `pocketdungeons/src/main/java/pocketdungeons`: `Terminal` (the
12 by 5 terminal), `DungeonScreen` (door board, go-home panel), `DoorIcons`, `BoardText` (wrapping and words),
`Glyph` and `GlyphHandlers` (click boxes). The terminal's full layout is `docs/devin/01-SPEC.md` section 7.

## What a board is

Blocks on the wall (black concrete, a polished blackstone tab row, sea lanterns behind lit glass) stamped with the
room, plus display entities and interaction boxes in front of them. The entities are **transient**: summoned at
every room stamp, never captured (`RoomStore.capture` skips `DungeonScreen.TAG`), and redrawn whenever the content
changes. Every path that re-stamps a room must summon its boards again.

## Coordinates: work in the viewer's frame

Lay a board out as the player standing in the room sees it, then convert once:

- `u` (or along) runs left to right **as the viewer sees it**; `v` (or y) is blocks above the room floor `o.y`.
- Convert with `RoomGeometry.mirrorsAlong(wall)` / `RoomGeometry.viewerAlong` (see `Terminal.absAlong`,
  `DungeonScreen.absAlong`). A symmetric board hides a missing mirror; an asymmetric one (the terminal) shows it on
  two of the four walls. Test or look at a north and an east wall.
- The ceiling is `v = 6`. The door board's backdrop is rows `y 4..5` (v 4 to 6), along 3 to 12.

## Depth in front of the wall face (from the cell edge: wall block 0 to 1)

| Entity | Depth | Why |
|---|---|---|
| text display | 1.10 (0.10 in front) | clears the face without floating |
| item display, block display face | 1.06 | sits flush; block displays are 0.04 deep |
| interaction (click box) | 1.5 | must stand clear of the wall to be hit |

`DungeonScreen.wallAnchorAt(origin, wall, along, y, depth)` and `Terminal.anchor(..., depth)` take the depth.

## Facing: yaw per wall, and it fails silently

Fixed billboard displays carry a `Rotation` so their face points into the room: **NORTH 0, SOUTH 180, EAST 90,
WEST -90** (270 is the same). Text renders on **one side only**, so a wrong yaw makes the text vanish while item
displays (two sided) still show. That was PD-223: the terminal's EAST and WEST were swapped and players saw "blank
concrete, there's items along the bottom". A gametest cannot see this; the server-side entity looks fine. Copy an
existing `yawFor`, never retype it.

## Text: anchoring, size and wrapping

- A text display's position is the **bottom centre** of its text block. To place a left edge at `u`, set
  `alignment: left`, set `line_width` in pixels and centre the entity at `u + width/2` (`Terminal.textBox`).
- One text pixel is `0.025 * scale` blocks; one line is about `0.25 * scale` blocks tall (9 px plus a gap).
- `line_width` in pixels for a box `w` blocks wide is `w / (0.025 * scale)`.
- **Nothing a player must read goes below scale 1.0.** The floor history rows were enlarged three times after
  playtests (PD-117) before landing at 1.03. Shrink layout, cut words or split the board instead.
- **Measure wrapping, never guess it.** `BoardText.wrapRows(text, lineWidth)` uses the vanilla font's advance
  widths and the client's word wrap; `charWidth` holds the table. The old `5.6 px per character` estimate and a
  "longer than 30 characters wraps" rule both put lines on top of each other. A line below a wrapping line moves
  down one row per extra row.
- Encode text with `DungeonScreen.encodeText` (an NBT object through `ComponentSerialization.CODEC`, not a JSON
  string), `see_through` false (MC-277982 renders see-through glyphs black), `shadow` and `default_background` on,
  brightness forced to 15.
- The uniform font (`FloorHistory`) is the only way to line columns up; a proportional font cannot.

## Icons instead of words

The lesson of PD-151, PD-207 and PD-211 ("too much text on the screen above the selector doors"): a board names the
numbers that decide a choice in words, and shows everything else as items.

- An icon is an `item_display` at item depth, scale about 0.5 (half a block), brightness 15, with an inspect glyph
  over it whose `text` is the component JSON the `inspect` handler prints. **Every icon prints its words on a right
  click**, so cutting text loses nothing. `DoorIcons.Icon(item, count, inspect)` is the shape to reuse.
- A count goes **beside** the icon at scale 1.0, only above one. Never a tiny stack count in the corner.
- A row that overruns its sheet shrinks its icons and gaps by one factor (never below half) and keeps its counts
  (`DungeonScreen.iconRowWidths`).
- A mapped item must be a real item: guard unknown ids (`DoorIcons.known`) and test the maps against the registry,
  or the board shows air.
- A shared board cannot tell viewers apart. Per-player state (reward ticks, personal haul) goes on a row per player
  (the terminal's Crew tab), never as a tick on a shared icon.

## Click boxes

- Spawn with `Glyph.spawn(level, at, width, height, handler, data, tags)` and a handler registered by name in
  `GlyphHandlers` (`inspect`, `terminal_tab`, `terminal_page`, or your own `register` call from the mod init).
- **An interaction stands on its bottom centre.** For a box over an icon centred at `v`, spawn it at
  `v - height/2`; spawning at `v` puts the box half an icon too high (the terminal's reward icons had this until
  2026-10-23). Text is bottom anchored too, so a box over a text label goes at the label's `v`.
- Keep boxes off the space a player needs for other clicks (PD-224: the old history board's map click spanned the
  whole terminal and kept opening the map). A dark or disabled control keeps its box and refuses with
  `Chime.refused`, so a click never does nothing.
- State a box changes lives on the `InstanceRecord` and is shared by the party; any member may click.

## Tagging, clearing and redrawing

- Tag every entity of a board with the board's tag (`Terminal.TAG`, `DungeonScreen.TAG`), plus a role tag for parts
  that clear or repaint alone (`SHEET_TAG`, `ICON_TAG`, `Terminal.AWAY_TAG`).
- A redraw clears **only its own tag** in the cell, then summons fresh. An update that runs often (a countdown)
  repaints its one display, not the whole board (`Terminal.tickAway`).
- Do not draw into a cell that is being torn down: check `record.tearingDown` and that the board's blocks still
  stand before a membership or timer driven redraw (`Terminal.standing`).
- A newly stamped room may choose what it shows first (`Terminal.openingTab`); after that only a click changes it.

## Tests

- Gametests build a record at `helper.absolutePos(new BlockPos(0, 1, 0))` and call the summon directly
  (`TerminalGameTest.recordAt`, `BoardGameTest`). Count entities by tag and class, filter `!e.isRemoved()`.
- Pin: the wall blocks stamp and clear whole; an update replaces displays, never doubles them; every icon and box
  sits inside the backdrop's blocks and rows; a click changes the shared state; a refused click changes nothing;
  long content still fits.
- Colour families in this Minecraft version are grouped (`Blocks.CONCRETE.black()`); compare stained glass and
  similar by registry id.
- Register a new gametest class in `src/gametest/resources/fabric.mod.json` and check the test count rises.
- What tests cannot prove: that text faces the room, reads from the middle of the room, and does not overlap
  in the client. Add a live check to `docs/playtests/LIVE_CHECKS.md` for every board change and look at a north and
  an east wall.

## Checklist before you ship a board change

- Laid out in viewer coordinates, mirrored once, checked on two wall orientations?
- Yaw copied from an existing `yawFor`?
- Every readable line at scale 1.0 or more, wrapping measured with `BoardText.wrapRows`?
- Words only for the numbers that decide; everything else an icon with an inspect line?
- Click boxes dropped to their bottom centre, clear of other controls?
- Own tag, cleared on redraw, no drawing into a torn down cell?
- Gametests for placement and fit, a live check for the look?
