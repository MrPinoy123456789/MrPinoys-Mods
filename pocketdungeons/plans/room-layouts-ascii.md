# Room Layouts (ASCII)

Use this file to sketch room arrangements. One cell is 16x16x7 blocks
(y=0..6, walls at y=1..5). Coordinates below are relative to the cell origin
(0,0,0) at the floor corner.

## Legend

```
# = wall / solid block
. = floor / air (walkable)
D = door (lower block)
d = door (upper block)
L = lodestone (exit pad)
l = chiseled stone ring around pad
C = chest
S = spawn / standing point
~ = bedrock envelope (outside the cell)
```

## Lobby / Player Room (16x16, south-wall doors)

Top-down view of the floor (y=1). The connecting door to the dungeon is on the
south wall, in the middle (x=7..8, z=15).

A cell is 16×16 (indices `0..15`). The wall is the full outer ring, so each edge
is 16 blocks long including the two corner blocks. If you draw only the straight
wall segment between corners, it is 14 blocks wide. Either way, the door slot at
`7..8` is centred.

```
z/x 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15
0   # # # # # # # # # # # # # # # # # # #
1   # . . . . . . . . . . . . . . . . . #
2   # . . . . . . . . . . . . . . . . . #
3   # . . . . . . . . S . . . . . . . . #   <- player spawn / entrance
4   # . . . . . . . . . . . . . . . . . #
5   # . . . . . . . . . . . . . . . . . #
6   # . . . . . . . . . . . . . . . . . #
7   # . . . . . . . . . . . . . . . . . #
8   # . . . . . . . . . . . . . . . . . #
9   # . . . . . . . . . . . . . . . . . #
10  # . . . . . . . . . . . . . . . . . #
11  # . . . . . . . . . . . . . . . . . #
12  # . . . L L . . . . . . . . . . . . #   <- 2x2 lodestone leave-pad
13  # . . . l l . . . . . . . . . . . . #
14  # . . . . . . . . . . . . . . . . . #
15  # . . . D . . . . D . . . . D . . . #   <- three selector doors (x=4,8,12)
    # # # # # # # # # # # # # # # # # # #
```

Cross-section through the middle (x=7..8, looking east):

```
y=6 # # # # # # # # # # # # # # # #
y=5 # # # # # # # # # # # # # # # #
y=4 # # # # # # # # # # # # # # # #
y=3 # . . . . . . . . . . . . . . #
y=2 # . . . . . . . . . . . . . . #
y=1 # . . . . . . . . . . . . . . #
y=0 # # # # # # # # # # # # # # # #
    z=0                         z=15
```

Same room drawn with only the straight wall segments (corners removed) to make
the 14-wide wall clear:

```
z/x  1 2 3 4 5 6 7 8 9 10 11 12 13 14
1   # . . . . . . . . . . . . . . #   <- north wall (14 wide)
2   # . . . . . . . . . . . . . . #
...
14  # . . . . . . . . . . . . . . #
15  # . D . . . . D . . . . D . #   <- south wall with doors
    # # # # # # # # # # # # # # # # #
```

## After a door is chosen

The three selector doors on the south wall disappear and the sealed 2-wide
slot at x=7..8, z=15 opens into the dungeon. The side doors at x=4 and x=12
leave empty 1x2 openings.

```
z=15:  # . . D . . . . . . . . D . . #
            ^                 ^
            |                 |
            x=4               x=12

        (middle section x=7..8 is now air connecting to the dungeon)
```

## Completion: terminal cell becomes the player's room

The terminal cell is stamped with the saved room blob. Reward chests appear at
(x=4, z=4), (x=8, z=4), (x=12, z=4). The door back to the dungeon is on
whichever wall the terminal cell's one open side faces.

```
z/x  0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15
0   # # # # # # # # # # # # # # # # # # #
1   # . . . . . . . . . . . . . . . . . #
2   # . . . . . . . . . . . . . . . . . #
3   # . . . . . . . . . . . . . . . . . #
4   # . . . C . . . . C . . . . C . . . #   <- reward chests
5   # . . . . . . . . . . . . . . . . . #
6   # . . . . . . . . . . . . . . . . . #
7   # . . . . . . . . . . . . . . . . . #
8   # . . . . . . . . . . . . . . . . . #
9   # . . . . . . . . . . . . . . . . . #
10  # . . . . . . . . . . . . . . . . . #
11  # . . . . . . . . . . . . . . . . . #
12  # . . . . . . . . . . . . . . . . . #
13  # . . . . . . . . . . . . . . . . . #
14  # . . . . . . . . . . . . . . . . . #
15  # # # # # # # D D # # # # # # # # # #   <- closed door back to dungeon
    # # # # # # # # # # # # # # # # # # #
```

## Bedrock envelope (one block outside the cell)

```
   ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~
   ~ # # # # # # # # # # # # # # # # # ~
   ~ # . . . . . . . . . . . . . . . # ~
   ~ # . . . . . . . . . . . . . . . # ~
   ...
   ~ # . . . . . . . . . . . . . . . # ~
   ~ # # # # # # # # # # # # # # # # # ~
   ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~
```

## Notes

- Door blocks are two tall; mark both `D` (lower y=1) and `d` (upper y=2) if
  you want to show the full column.
- The leave-pad lodestone is at y=0, so a side view of that corner would show
  `L` at y=0 and `.` above it.
- Reward chests are placed by overwriting whatever is at those coordinates in
  the saved room blob.
