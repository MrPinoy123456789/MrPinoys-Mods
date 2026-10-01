# Playtest research agenda

What we want to learn from playtests, most important first. The `/playtest`
interviewer reads this before every session, covers the open items that the
session gives it a way to ask about, and updates each item afterwards. The owner
can add, reorder or retire items at any time.

Status: `open` (no evidence yet), `partial` (some evidence, keep asking),
`answered` (enough to act on), `retired` (no longer relevant). Evidence cites
the playtest file (`docs/playtests/<date>-<n>.md`).

## Format

Each item: an id, the question, **why we care**, **what would change our
mind** (the answer that would make us change the design), status, and evidence.

---

## A1. Do players read and use the omen bar?
- **Why:** omen is the loop's pressure (chests and keystone level at a bank). If
  it is not read, banking decisions are blind and the band feels arbitrary.
- **Would change our mind:** players cannot say what their omen was or why it
  rose, or say the bar is noise. Then the bar or the rise cues need rework.
- **Status:** partial
- **Evidence:** 2026-09-26-1.md: bar title cluttered; the spawner count is what they track. 2026-09-27-1.md: no model of what raises omen; the band name "calm" is opaque; calm on all 10 floors, so omen never bit (medium).
- **Changed 2026-09-27:** the bar now leads with the spawner gate (`Spawners 2/3 | Omen 1/4, 3 chests`), a floor clear shows a big "Floor 2 of 3 cleared" title, and the dwell cue says "in an unsolved room". Retest: can they say what raised the omen?
- **2026-09-27-3.md:** retest failed: omen bit for the first time (sensor rises, 2 chests on F1 and F2) and the player saw the bar fill but not why; guessed "I took a long time". Still reads as noise to a new player; wants spawners as a countdown, omen hidden at 0, a flavour line on each rise. Then rejected omen as a reward penalty: wants it to raise danger, like a wanted level (high).
- **2026-09-29-1.md:** omen stayed 0 across all 5 cleared floors and the interval; one death rescue did not visibly change the band (still calm at the bank). The danger model had nothing to show this session (low).
- **2026-09-29-2.md:** rescues now tick floor omen to 1 (seen twice); the player never mentioned the bar unprompted and banks still happen at band calm. Omen is changing state, but there is still no sign he reads it (low).
- **2026-09-29-3.md:** first observed run failure at max omen: an ominous floor 2 ended `run_failed` at floor/interval omen 4 after an arrow death. The danger model finally bit. He never commented on the fail, so whether the bar telegraphed it is still unknown (medium).

## A2. What makes a player go home, and when?
- **Why:** bank-anywhere replaced the forced safe room. The depth bonus and the
  omen head start past floor 3 are meant to make "one more floor?" a real
  gamble.
- **Would change our mind:** players always go home at the same point for the
  same reason (no real decision), or never feel pulled either way. Then the
  depth bonus or head start numbers need tuning.
- **Status:** partial
- **Evidence:** 2026-09-27-1.md: "I don't know what home offers so I never want to go"; went home for a full inventory and chest storage, always after floor 2, never reached the floor 3 head start (high on the current state: the payoff is invisible, not mistuned).
- **Changed 2026-09-27:** the go-home screen lists what home pays (reward chests, key progress, kit refill) and the floor-end line names what each lever pays. Retest: does the go-home point move, and is the reason ever the omen or depth?
- **2026-09-27-3.md:** moved from floor 2 to floor 3, at the green "TIME TO GO HOME" screen, but the player asked why it was time and what descending would do: a traffic light, not a weighed gamble. Descended again one minute after going home (medium).
- **2026-09-29-1.md:** the floor 2 bank was an accidental GO HOME pull, not a decision (see A5); the floor 3 bank followed a rescue at calm band. No weighed go-home moment observed (medium).
- **2026-09-29-2.md:** two voluntary banks, both after floor 3 via the HOME lever (key 4 then 5). Floor 3 is becoming his routine exit; still never weighs the door 2+ gamble because the gate hides it (low).

## A3. Does the strict resource economy feel tense or tedious?
- **Why:** scarcity of blocks and durability is a core strength (owner). It
  should create decisions, not chores.
- **Would change our mind:** players describe running dry as unfair, or grind
  outside the loop to avoid it. Compare with blocks and durability spent per
  floor and the kit top-ups in the digest.
- **Status:** partial
- **Evidence:** 2026-09-27-1.md: wood is the bottleneck (about 4% oak log per tier 1 chest); ingots and diamonds unused for lack of sticks; early loot reads as useless; mob-dropped tools keep full durability (PD-69) (high).
- **Changed 2026-09-27:** wood early, fewer bones and emeralds, trims later game, more and shorter-lived weapons and armour (owner decisions 2026-09-27 in `AUDIT_2026-09.md`). Retest over three runs.
- **2026-09-27-3.md:** no wood complaint this session; proposed the Grove as a small capped wood source (its logs are all unbreakable wall). Only one interval played, so the three-run retest is still open (low).
- **2026-09-29-1.md:** the complaint flipped to surplus: "accumulating too much gear and vault keys", wants a scrap or redemption outlet. Two full intervals, no scarcity friction mentioned (medium).
- **Changed 2026-09-29:** the salvage bench (a grindstone station, `docs/reference/SALVAGE_PROPOSAL.md`): tagged gear pays 1 emerald per tier, vault keys 1 (ominous 3), mob gear XP only. Retest: does the surplus complaint go away, and do `salvage` journal events show it used every interval without emeralds piling up at the gamble?

## A4. Does the kit top-up feel fair and understood?
- **Why:** the top-up by band ties the economy to omen. It only works if players
  notice it and connect it to how the stretch went.
- **Would change our mind:** players do not notice it, or think it is random.
- **Status:** partial
- **Evidence:** 2026-09-27-1.md: not noticed until asked right after a go-home; the one restock seen was a spyglass the player called useless, delivered invisibly to the kept dungeon inventory (medium).
- **Changed 2026-09-27:** the restock line says where the items went and flashes on the action bar. Retest: noticed unprompted?
- **2026-09-27-3.md:** noticed unprompted, but as absent: "I don't think my bag refilled" after a top-up of 6 arrows and a spyglass. "Kit refilled" on the screen implies a full refill (medium).

## A5. Is the HOME lever and staging room readable without help?
- **Why:** the staging room carries three doors, a commit lever, the HOME lever
  and screens. A new player must understand it unaided.
- **Would change our mind:** hesitation, wrong lever pulled, or "I did not know
  I could go home".
- **Status:** partial
- **Evidence:** 2026-09-27-1.md: levers read correctly unaided, wants HOME as a verb; the home screen and "banks what you carry" were fully opaque; levers swap sides between floors (PD-70) (high).
- **Changed 2026-09-27:** the lever sign reads GO HOME, the levers no longer swap sides (PD-70), and the screen is in plain words. Retest with a fresh player if one is available.
- **2026-09-27-3.md:** owner role-played a first login: levers used without hesitation; nothing on join says to type `/dungeon`; the green title prompts "why now?" (medium).
- **2026-09-29-1.md:** regression evidence: the floor 2 bank was an accidental GO HOME pull while reaching for door select ("I was trying to change my door selection"). The commit/home controls are still confusable after the PD-70 fix (high).
- **2026-09-29-2.md:** PD-84 verified in game: two deliberate `home_lever` banks with the confirm dialog in the pull path, no misclick all session. New readability issue surfaced instead: the preview glass sits behind the doors (PD-85) (medium).

## A6. Do door choices feel meaningful?
- **Why:** each floor now banks its own door step (averaged). Door 1 is free,
  door 2 is ominous, door 3 costs fuel.
- **Would change our mind:** players always pick the same door, or cannot say
  why they picked one.
- **Status:** partial
- **Evidence:** 2026-09-27-3.md: the free door on all four descents, committed 1 to 8 seconds after the preview; reason not asked yet (low). 2026-09-29-1.md: the free door again, committed 18 s after preview on a check-in run that was quit at once; reason still unasked (very low).
- **2026-09-29-1.md:** asked directly: "I would take deeper doors, but my keystone level is too low I think so they're unavailable." Door 3 needs level 15 while he sat at keystone 1-3; the free door is forced, not chosen. His suggestions: hide unavailable doors so a newly unlocked door is itself the signal; put door 2 at level 7 or 8 (high).
- **2026-09-29-2.md:** gating verified in game: at keystone ~4-5 the selector showed exactly one door ("it correctly showed 1"). He reached key 5 by session end, still under the door 2 gate of 7; no real door choice observed yet (medium).

## A7. What pulls a player into another session?
- **Why:** the game has to stand on its own and hold people for hours.
- **Would change our mind:** no clear pull (no goal they are chasing), or the
  pull is outside the mod entirely.
- **Status:** partial
- **Evidence:** 2026-09-27-1.md: the imagined pull is a stocked home and feeling "kitted out" (tree farm, many chests) (low).

## A8. Where does a session drag, and where does it spike?
- **Why:** pacing across a floor and an interval. Long floors, repeated rooms and
  dead time are the enemies of "hours".
- **Would change our mind:** consistent low points in the same room types, zones
  or phases (the digest's per-floor seconds and check-ins point at them).
- **Status:** partial
- **Evidence:** 2026-09-27-1.md: "doesn't feel like it's getting too risky"; only the pillager floor spiked; floor 2 took 4 to 6.5 min (low).

## A9. Does the room (home) matter to the player?
- **Why:** the room is the long-term product: decorating, trophies, showing it
  off. Room saves now keep paintings, frames, stands and pets.
- **Would change our mind:** players never decorate, or treat the room as a
  corridor.
- **Status:** partial
- **Evidence:** 2026-09-27-1.md: the home room gives no sign it is yours or buildable; the player began furnishing it with reward chests unprompted (medium).
- **Changed 2026-09-27:** going home prints a line saying the room is theirs to build in and that chests there are safe storage. Retest: do they decorate?
- **2026-09-29-2.md:** yes, but blocked: he tried to grow a tree at home and could not (interior ~6 blocks, trees need 7+). The "productive home" pull keeps resurfacing; home headroom is now the obstacle (medium).

---

## Emerging themes

Themes the interviewer noticed that are not yet agenda items. Promote one to an
item when it recurs or the owner says it matters.

- **Early loot with no immediate use** (emeralds, armor trims, diamond and gold): raised unprompted in both sessions (2026-09-26-1.md, 2026-09-27-1.md). Candidate for promotion.
- **"Bank" is read as item storage**, and the inventory swap on going home is silent (2026-09-27-1.md).
- **A productive home** (plant a sapling for wood) as the pull back home, versus the strict economy (2026-09-27-1.md).
- **Interviewer reliability affects sessions:** the player quit when the interviewer dropped (2026-09-27-1.md). Again in 2026-09-27-3.md: a two minute silence while Lemon looked something up (and was held by a fight) read as being ignored; the player asked Lemon to say it is looking, vanish, and come back with the answer. Third time in 2026-09-27-4.md: server wait dropped the player's question (PD-79), it timed out, and the player stopped the session until Lemon is fixed. Recommend promoting (harness, not game).
- **Omen should raise danger, not cut rewards** (2026-09-27-3.md): a wanted level with waves of lootless mobs, and a reason to court high omen. Strong opinion from the owner; tied to A1.
- **Onboarding on first join:** nothing says how to start (`/dungeon`) or what the session is (2026-09-27-3.md, role-played new player).
- **Lemon placement:** three sessions of refinement (2026-09-27-3, -4, 2026-09-29-1). Converged spec: appear at a good spot, stay anchored (bubble included), rotate only to face the player, relocate only for a new point of interest. Implemented in `Lemon.java` on 2026-09-29; verify in play.
- **Big-title delivery is liked:** floor-clear title praised unprompted and suggested for the go-home moment too (2026-09-29-1.md). Candidate: more big-text moments where they fit.
- **Locked doors shown but unreachable** (2026-09-29-1.md): door 3 wanted keystone 15 while the player sat at 1-3; he would rather locked doors be hidden entirely. Tied to A6.
- **Surplus needs a sink** (2026-09-29-1.md): "too much gear and vault keys", wants scrap or redemption. Tied to A3.
- **Interviewer reliability resolved once:** 2026-09-29-1.md is the first session since the theme emerged where nothing was missed; both questions answered in 10 to 13 s after the PD-79 fix. Keep the theme until PD-80 also proves out. 2026-09-29-2.md: two more misses, both agent-side (a shell quoting error aborted the reply; pausing the wait loop silently lapsed llm mode and one question hit the fallback at wait_s 0). PD-80 false-down also reproduced twice.
- **Dead mechanics vs dead time** (2026-09-29-2.md): the hold-the-plate early-complete fix removed dead time but he called the result "pointless"; his counter-proposal is waves that spawn off the plate itself, escalating with remaining time. A mechanic should be rescued, not skipped.
- **Flat rooms leave systems stranded** (2026-09-29-2.md): building blocks are dead loot because nothing is vertical; trap blocks telegraph instead of catch. Same root cause: geometry never demands climbing or hides mechanisms.
- **Lemon steers the test** (2026-09-29-2.md): player wants Lemon to bias dungeon generation toward rooms needing coverage. Harness feature, ties to verification pain (thicket could not be inspected before teardown).
