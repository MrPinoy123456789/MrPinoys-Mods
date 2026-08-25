# Room Layouts (ASCII)

Use this file to sketch room arrangements. One cell is 16x16x7 blocks
(y=0..6, walls at y=1..5). Coordinates below are relative to the cell origin
(0,0,0) at the floor corner, written 0-F.

## Legend

```
# = wall / solid block
. = floor / air (walkable)
d = selector door (1-wide, 2-tall)
M = sealed exit slot into the dungeon (2-wide, 3-tall)
e = sealed entrance slot from the finished dungeon (2-wide, 3-tall)
L = lodestone (leave-pad)
l = chiseled stone ring around the leave-pad
C = reward chest
S = player spawn / standing point
~ = bedrock envelope (one block outside the cell)
```

A cell is the full 16x16 outer ring; the straight wall segment between the two
corner blocks is 14 blocks wide. Door slots are always 2-wide and centred at
positions 7-8 on a wall.

These sketches are top-down at floor level (y=0 for pads, y=1..2 for doors and
chests) and match the implementation in `RoomTemplateGenerator` and
`Instances.lobbyLayout`.

---

## 1. Lobby / player room before a dungeon is chosen

`e e` is on the north wall and is sealed because there is no previous dungeon.
The leave-pad is a single lodestone tucked in the north-west corner with
chiseled stone around it so it sticks out from the wall; one more chiseled
block sits on the north wall face directly behind it at `(x=2, y=1, z=0)`,
which a top-down sketch cannot show. The player spawns at `(3, 1, 3)` as if
they just walked in through the pad.

The `d d d` selector doors stand on row `z=E`, one block *in front of* the
hidden `MM` slot rather than inside it -- the slot itself stays sealed solid
until a dungeon exists behind it, and a door is not a seal. The strip covers
`x=7..9`: the middle two sit in front of the slot, the third in front of plain
wall.

```
z/x 0 1 2 3 4 5 6 7 8 9 A B C D E F
0   # # # # # # # e e # # # # # # #   <- sealed entrance from the previous dungeon
1   # l L l . . . . . . . . . . . #   <- single lodestone leave-pad in the corner
2   # l l l . . . . . . . . . . . #   <- chiseled ring on the adjacent floor
3   # . . S . . . . . . . . . . . #   <- player spawn at (3, 1, 3)
4   # . . . . . . . . . . . . . . #
5   # . . . . . . . . . . . . . . #
6   # . . . . . . . . . . . . . . #
7   # . . . . . . . . . . . . . . #
8   # . . . . . . . . . . . . . . #
9   # . . . . . . . . . . . . . . #
A   # . . . . . . . . . . . . . . #
B   # . . . . . . . . . . . . . . #
C   # . . . . . . . . . . . . . . #
D   # . . . . . . . . . . . . . . #
E   # . . . . . . d d d . . . . . #   <- three selector doors cover x=7..9
F   # # # # # # # M M # # # # # # #   <- sealed 2-wide exit at x=7..8
```

## 2. After a selector door is chosen

The three `d` doors are removed and the sealed 2-wide `MM` slot at `x=7..8,
z=F` opens. A new dungeon is generated on the south side of the room. `e e`
stays sealed.

```
z/x 0 1 2 3 4 5 6 7 8 9 A B C D E F
0   # # # # # # # e e # # # # # # #
1   # l L l . . . . . . . . . . . #
2   # l l . . . . . . . . . . . . #
3   # . . S . . . . . . . . . . . #
... (empty room)
D   # . . . . . . . . . . . . . . #
E   # . . . . . . . . . . . . . . #
F   # # # # # # # . . # # # # # # #   <- slot now open into the new dungeon
        ^ adjacent dungeon cell starts here (z=10..25)
```

## 3. The dungeon's terminal cell at completion

Example: the player entered the terminal cell from the north. The 2x2
lodestone pad sits in the centre (`x=7..8, z=7..8`). The reward chests spawn
on the far side (`z=C`), between the pad and the sealed `e e` door. The
player's room is generated in the cell directly behind that sealed door
(south of the terminal cell).

```
                  previous dungeon
                         |
                         v
z/x 0 1 2 3 4 5 6 7 8 9 A B C D E F
0   # # # # # # # # # # # # # # # #   <- entrance from dungeon (north)
1   # . . . . . . . . . . . . . . #
2   # . . . . . . . . . . . . . . #
3   # . . . . . . . . . . . . . . #
4   # . . . . . . . . . . . . . . #
5   # . . . . . . . . . . . . . . #
6   # . . . . . . . . . . . . . . #
7   # . . . . . . L L . . . . . . #   <- 2x2 lodestone exit pad at x=7..8, z=7..8
8   # . . . . . . L L . . . . . . #
9   # . . . . . . . . . . . . . . #
A   # . . . . . . . . . . . . . . #
B   # . . . . . . . . . . . . . . #
C   # . . . C . . . C . . . C . . #   <- reward chests at x=4, 8, C
D   # . . . . . . . . . . . . . . #
E   # . . . . . . . . . . . . . . #
F   # # # # # # # e e # # # # # # #   <- sealed door into the player's room
        ^ player's room is in the cell below (z=10..25)
```

## 4. After completion: the room is behind the terminal cell

The saved room blob has been stamped in the cell south of the terminal cell.
Its north wall now carries `e e`, open to the terminal cell. Its south wall
carries `MM` sealed behind new selector doors. The leave-pad is in the
north-west corner again so the player spawns next to the entrance from the
finished dungeon.

```
        terminal cell (from section 3)
                  |
                  v
z/x 0 1 2 3 4 5 6 7 8 9 A B C D E F
0   # # # # # # # . . # # # # # # #   <- slot open to the finished dungeon
1   # l L l . . . . . . . . . . . #   <- single lodestone leave-pad
2   # l l . . . . . . . . . . . . #
3   # . . S . . . . . . . . . . . #   <- player now inside the room
4   # . . . . . . . . . . . . . . #
5   # . . . . . . . . . . . . . . #
6   # . . . . . . . . . . . . . . #
7   # . . . . . . . . . . . . . . #
8   # . . . . . . . . . . . . . . #
9   # . . . . . . . . . . . . . . #
A   # . . . . . . . . . . . . . . #
B   # . . . . . . . . . . . . . . #
C   # . . . . . . . . . . . . . . #
D   # . . . . . . . . . . . . . . #
E   # . . . . . . d d d . . . . . #   <- selector doors
F   # # # # # # # M M # # # # # # #   <- sealed exit into the next dungeon
```

## 5. Starting the next run

Before a new dungeon is generated:

1. Any party members still in the old dungeon are teleported into the room.
2. All cells of the old dungeon are cleared.
3. The room's `e e` wall is sealed.

```
z/x 0 1 2 3 4 5 6 7 8 9 A B C D E F
0   # # # # # # # e e # # # # # # #   <- sealed: the old dungeon is gone
1   # l L l . . . . . . . . . . . #
2   # l l . . . . . . . . . . . . #
3   # . . S . . . . . . . . . . . #
... (empty room)
D   # . . . . . . . . . . . . . . #
E   # . . . . . . d d d . . . . . #   <- selector doors still cover MM
F   # # # # # # # M M # # # # # # #
```

After a selector door is chosen the `MM` slot opens again and a new dungeon
extends to the south, exactly as in section 2.

## Notes

- The terminal-cell example uses a north entrance. If the dungeon enters the
  terminal cell from a different direction, chests and the sealed `e e` door
  are mirrored to the opposite wall. The room is always stamped in the cell
  directly behind that sealed door.
- Door slots (`e e` and `MM`) are 2-wide, 3-tall, centred at positions 7-8 on
  the wall. The `d d d` selector doors are a contiguous strip at `x=7..9`, one
  block inside the room from that wall, so the middle pair stands in front of
  the `MM` slot. `RoomTemplateGenerator.SELECTOR_DOORS` and
  `Instances.selectorDoorStep` both key off those three positions.
- The lobby is always stamped at rotation 0, so the leave-pad and spawn can
  live at fixed corner coordinates. Rooms the planner may rotate (the terminal
  cell included) keep the rotation-invariant 2x2 centre pad instead.
