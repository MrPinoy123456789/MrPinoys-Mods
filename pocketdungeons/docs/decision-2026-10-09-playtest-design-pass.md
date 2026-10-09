# Decision 2026-10-09: the playtest 2026-10-08-1 design pass

The full spec, with option tables, numbers, text and knobs, is `docs/design-2026-10-09-1-reply.md`
(Opus's reply to `docs/design-2026-10-09-1.md`). This record is the owner-approved summary and the build
order. If the two disagree, the reply wins until this file says otherwise.

## The decisions, in the player's words

1. **Choose your dungeon in the world, not from three random doors.** The first staging room is the
   Astrolabe Room: turn the astrolabe to cycle unlocked acts, open a 1 by 2 door to look at the dungeon's
   first room through the back glass, pull DESCEND to go. Special doors (Endless Mine) stand on the side walls.
2. **Levels cost a little more each time, and deeper floors pay more.**
   Level cost `4 + floor(compass / 3)`; a floor pays `step + (act - 1)`, plus 1 on a final floor; below your
   compass it pays half (at least 1).
3. **Sculk hears you.** One Heard meter per sculk room; full means darkness and a wave; crossing unheard pays
   +1 scrap. `sensor_gallery` is replaced by the Hush Gallery.
4. **The last floor ends in a fight.** Act 1: a finale wave. Act 2 and up: a wave with a named elite.
5. **Copper Works mobs wear copper** (a per-dungeon `mobUniform`; nothing drops).
6. **The hopper-key toll goes.** `barred_vault` becomes a real vanilla vault opened with the trial key;
   `ominous_bargain` loses its gold toll.
7. **A sidebar shows floor, lives, spawners, haul and compass.** Chat stays the log; critical lines also
   show on titles and the action bar.
8. **Feral retires for Restless.** New Act 1 dungeon The Kennels, a Hound Crypt floor in the Ossuary, a rare
   Lost Dog room. Pets stay, per trip.
9. **The Endless Mine hides ore** and gets richer with depth; `deep_shaft_landing` gets more ore.
10. **Small items:** finished dungeons say Leave (the payout is already banked); every finish gets its own
    title beat; effect caps generalise the poison cap.

## Build order (each wave is one reviewable commit, tests green before the next)

| Wave | Scope | Owed live before the next |
|---|---|---|
| A | sidebar, chat audit moves, Leave wording, finish title beat, effect caps | one trip: can he say his floor and haul unprompted |
| B | Astrolabe Room (Q1): BUILT 2026-10-09, see PD-181 in `BUGS.md` for the list of deviations from the reply | played before C |
| C | scrap curve (Q2): BUILT 2026-10-09, see PD-182 in `BUGS.md` | levels per trip |
| D | finales and uniforms (Q4, Q5) | Copper Works finish |
| E | sculk and toll rooms (Q3, Q6) | Hush Gallery, vault |
| F | wolves and Restless (Q8) | |
| G | ore (Q9) | |

## Supersedes

- `decision-2026-10-07-haul-and-blood-doors`: Earning (act and final bonus, half below compass), Knobs
  (`SCRAP_PER_CHART` becomes `levelCost`), Migration (bar out of a varying cost). The haul model is kept.
- `DUNGEON_STRUCTURE_DESIGN`: the three-door front offer, the forced capstone door, the Endless Mine on door 3,
  the Ancient City pulse rules.
- PD-181 to PD-190 answered; PD-178 generalised into `effectCaps`; PD-170 and PD-164 retired for the toll rooms.

## To verify at build time (the reply asserts, the code decides)

- Copper armour and copper tool item ids in 26.2.
- Whether `Locks.Kind.HOPPER_KEY` has other users before `SpurToll` is retired.
- Whether the finish vault repeats on a second finish (`Payout`), and whether the diary page repeats.
- Which chat lines are chat-only today (`sendSystemMessage` audit) before moving them.
- Whether `roomBias` is a hard filter anywhere besides Lemon's playtest bias.
