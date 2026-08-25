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

---

## 1. Lobby / player room before a dungeon is chosen

`e e` is on the north wall and is sealed because there is no previous dungeon.
`d` selector doors sit on the south wall in front of the hidden 2-wide `MM` slot.
The 2x2 lodestone leave-pad is at `x=7..8, z=12..13`; the player spawns at
`x=8, z=3`.

```
z/x 0 1 2 3 4 5 6 7 8 9 A B C D E F
0   # # # # # # # e e # # # # # # #
1   # . . . . . . . . . . . . . . #
2   # . . . . . . . . . . . . . . #
3   # . . . . . . . . S . . . . . #   <- player spawn
4   # . . . . . . . . . . . . . . #
5   # . . . . . . . . . . . . . . #
6   # . . . . . . . . . . . . . . #
7   # . . . . . . . . . . . . . . #
8   # . . . . . . . . . . . . . . #
9   # . . . . . . . . . . . . . . #
A   # . . . . . . . . . . . . . . #
B   # . . . . . . . . . . . . . . #
C   # . . . . . . . l l . . . . . #   <- leave-pad (4x4 footprint)
D   # . . . . . . . l l . . . . . #
E   # . . . . . . . . . . . . . . #
F   # # # # d # # # M M # # # d # #   <- selector doors at x=4,8,12; MM sealed
```

## 2. After a selector door is chosen

The three `d` doors disappear and the 2-wide `MM` slot opens. A new dungeon is
generated on the south side of the room. `e e` stays sealed.

```
z/x 0 1 2 3 4 5 6 7 8 9 A B C D E F
0   # # # # # # # e e # # # # # # #
1   # . . . . . . . . . . . . . . #
2   # . . . . . . . . . . . . . . #
3   # . . . . . . . . S . . . . . #
... (empty room)
C   # . . . . . . . l l . . . . . #
D   # . . . . . . . l l . . . . . #
E   # . . . . . . . . . . . . . . #
F   # # # # # # # M M # # # # # # #   <- exit into new dungeon
        ^ adjacent dungeon cell starts here (z=10..25)
```

## 3. The dungeon's terminal cell at completion

Example: the player entered the terminal cell from the north. The 2x2
lodestone pad sits in the centre (`x=7..8, z=7..8`). The reward chests spawn
on the far side (`z=C`), between the pad and the sealed `e e` door. The player's
room is generated in the cell directly behind that sealed door (south of the
terminal cell).

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
6   # . . . . . . L L . . . . . . #   <- 2x2 lodestone exit pad
7   # . . . . . . L L . . . . . . #
8   # . . . . . . . . . . . . . . #
9   # . . . . . . . . . . . . . . #
A   # . . . . . . . . . . . . . . #
B   # . . . . . . . . . . . . . . #
C   # . . C . . . . C . . . . C . #   <- reward chests
D   # . . . . . . . . . . . . . . #
E   # . . . . . . . . . . . . . . #
F   # # # # # # # e e # # # # # # #   <- sealed door into the player's room
        ^ player's room is in the cell below (z=10..25)
```

## 4. After completion: the room is behind the terminal cell

The saved room blob has been stamped in the cell south of the terminal cell.
Its north wall now carries `e e`, open to the terminal cell. Its south wall
carries `MM` sealed behind new selector doors. The player stands inside the
room (teleported to `x=8, z=8`).

```
        terminal cell (from section 3)
                  |
                  v
z/x 0 1 2 3 4 5 6 7 8 9 A B C D E F
0   # # # # # # # e e # # # # # # #   <- entrance from finished dungeon
1   # . . . . . . . . . . . . . . #
2   # . . . . . . . . . . . . . . #
3   # . . . . . . . . . . . . . . #
4   # . . . . . . . . . . . . . . #
5   # . . . . . . . . . . . . . . #
6   # . . . . . . . . . . . . . . #
7   # . . . . . . . . . . . . . . #
8   # . . . . . . . . S . . . . . #   <- player now inside the room
9   # . . . . . . . . . . . . . . #
A   # . . . . . . . . . . . . . . #
B   # . . . . . . . . . . . . . . #
C   # . . . . . . . l l . . . . . #
D   # . . . . . . . l l . . . . . #
E   # . . . . . . . . . . . . . . #
F   # # # # d # # # M M # # # d # #   <- selector doors; MM sealed
```

## 5. Starting the next run

Before a new dungeon is generated:

1. Any party members still in the old dungeon are teleported into the room.
2. All cells of the old dungeon are cleared.
3. The room's `e e` wall is sealed.

```
z/x 0 1 2 3 4 5 6 7 8 9 A B C D E F
0   # # # # # # # e e # # # # # # #   <- sealed: old dungeon is gone
1   # . . . . . . . . . . . . . . #
2   # . . . . . . . . . . . . . . #
3   # . . . . . . . . . . . . . . #
...
C   # . . . . . . . l l . . . . . #
D   # . . . . . . . l l . . . . . #
E   # . . . . . . . . . . . . . . #
F   # # # # d # # # M M # # # d # #   <- selector doors still cover MM
```

After a selector door is chosen the `MM` slot opens again and a new dungeon
extends to the south, exactly as in section 2.

## Notes

- The terminal-cell example uses a north entrance. If the dungeon enters the
  terminal cell from a different direction, chests and the sealed `e e` door
  are mirrored to the opposite wall. The room is always stamped in the cell
  directly behind that sealed door.
- Selector doors are one block wide at `x=4, 8, 12` on the wall. The middle
  door overlaps the right half of the eventual 2-wide `MM` opening at `x=7..8`;
  clearing all three exposes the full slot.
- The leave-pad footprint is 4x4 (`x=6..9, z=11..14`) so the `l` ring is not
  walkable.
