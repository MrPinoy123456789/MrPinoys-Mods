# Lemon puzzle hint ladders

One entry per puzzle room, keyed by room id (as in `context.room`). Lemon gives
the nudge on the first ask and the clue on a repeat or when the player is stuck
a while. There is deliberately no solution line. Rooms without an entry get a
generic nudge. Fill these in by hand; do not let a model invent them.

Format:

    ## <room_id>
    - nudge: <where to look or what to notice>
    - clue: <which part matters, not the order>
