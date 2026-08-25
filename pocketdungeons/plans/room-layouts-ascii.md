# Room Layouts (ASCII)

Use this file to sketch room arrangements. One cell is 16x16x7 blocks
(y=0..6, walls at y=1..5). Coordinates below are relative to the cell origin
(0,0,0) at the floor corner.

## Legend

```
# = wall / solid block
. = floor / air (walkable)
d = selector door
L = lodestone (exit pad)
l = chiseled stone ring around pad
C = chest
S = spawn / standing point
~ = bedrock envelope (outside the cell)
e = entrance into the lobby room
M = exit into the dungeon
```

## Lobby / Player Room (16x16, south-wall doors)

Top-down view of the floor (y=1). The connecting door to the dungeon is on the
south wall, in the middle (x=7..8, z=15).

A cell is 16×16 (indices `0..15`). The wall is the full outer ring, so each edge
is 16 blocks long including the two corner blocks. If you draw only the straight
wall segment between corners, it is 14 blocks wide. Either way, the door slot at
`7..8` is centred.

```
z/x 0 1 2 3 4 5 6 7 8 9 A B C D E F
0   # # # # # # # e e # # # # # # #
1   # L l . . . . . . . . . . . . #  <- lodestone leave-pad
2   # l l . . . . . . . . . . . . # 
3   # . . S . . . . . . . . . . . #  <- player spawn / entrance  
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
E   # . . . . . d d d . . . . . . #   <- three selector doors (x=4,8,12)
F   # # # # # # # M M # # # # # # #
```

## After a door is chosen

The `d` selector doors on the south wall disappear and the sealed 2-wide `MM`
slot at x=7..8, z=F opens into the new dungeon.

```
z/x 0 1 2 3 4 5 6 7 8 9 A B C D E F
0   # # # # # # # e e # # # # # # #
1   # L l . . . . . . . . . . . . #
2   # l l . . . . . . . . . . . . # 
3   # . . S . . . . . . . . . . . #
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
E   # . . . . . . . . . . . . . . #
F   # # # # # # # M M # # # # # # #   <- 2-wide exit into new dungeon
```

## Completion: the room becomes the terminal cell

The player's saved room blob is stamped into the terminal cell. Reward chests
appear at (x=4, z=4), (x=8, z=4), (x=12, z=4). The `e e` side is the door the
player arrives through from the just-finished dungeon.

```
z/x 0 1 2 3 4 5 6 7 8 9 A B C D E F
0   # # # # # # # # # # # # # # # #
1   # . . . . . . . . . . . . . . #
2   # . . . . . . . . . . . . . . #
3   # . . . . . . . . . . . . . . #
4   # . . . C . . . . C . . . . C . #   <- reward chests
5   # . . . . . . . . . . . . . . #
6   # . . . . . . . . . . . . . . #
7   # . . . . . . . . . . . . . . #
8   # . . . . . . . . . . . . . . #
9   # . . . . . . . . . . . . . . #
A   # . . . . . . . . . . . . . . #
B   # . . . . . . . . . . . . . . #
C   # . . . . . . . . . . . . . . #
D   # . . . . . . . . . . . . . . #
E   # . . . . . . . . . . . . . . #
F   # # # # # # # e e # # # # # # #   <- entrance from finished dungeon
```

## Notes

- Door blocks are two tall; the `d`, `M`, and `e` markers in the top-down view
  represent the 2-wide, 3-tall door slot.
- `e e` is sealed with wall when no previous dungeon connects to it; `MM` is
  sealed and covered by selector doors `d` until a choice is made.
