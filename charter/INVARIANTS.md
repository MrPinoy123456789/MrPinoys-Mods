# Charter invariants

Rules that must hold after every action, every vote, every siege step and every
load, whatever players do. `DESIGN.md` says why each exists; this file is the
short list a reviewer and a test can check against.

`core/src/test/java/charter/InvariantTest.java` checks the ones marked **(tested)**
after each of thousands of random actions, most of them invalid, by players who
mostly should not be allowed to do them. When a new feature adds a rule that must
never break, add it here and to that test.

## Refusal

1. **A refused action changes nothing.** Not the state, not the history, not the
   id counter. A refusal is a sentence for the player and nothing else. (tested)
2. **History only grows.** Nothing is edited or removed from it. (tested)

## Organizations and membership

3. **The hierarchy is a tree.** Every organization's parent exists, and following
   parents always reaches a top-level organization. (tested)
4. **A player belongs to at most one organization**, and that organization exists. (tested)
5. **An office is vacant or held by a member of its own organization.** (tested)

## Authority

6. **No power without a chain.** Every grant that works traces, link by link, back
   to a root grant whose grantor is the organization the power belongs to. Each
   link is unrevoked, unexpired, passable on, no wider than its parent, and given
   by its parent's recipient. (tested)
7. **Revocation is immediate.** A revoked grant, and everything below it, stops
   working at once. Nothing is cached on the recipient. (tested through 6)
8. **Reserved acts only come from a vote** of the organization itself: amending
   the charter, filling or emptying the Lord's seat, issuing a root grant.
   (acceptance tests B and C)

## Treasury

9. **A treasury is never negative.** (tested)

## Land

10. **One claim per chunk.** (tested)
11. **Every claim is either assigned to a core of the same owner, or contested
    with a grace time.** Never both, never neither. (tested)
12. **A core's own chunks exist:** a keep's nine, a camp's banner chunk. (tested)
13. **No core holds more claims than its capacity, or any claim beyond its
    range.** (tested)

## Siege

14. **Stores never hold negative counts**, and allowances never allow negative
    amounts. (tested)
15. **A besieged keep with nothing left to eat has fallen.** After any siege
    step, no keep is besieged or encircled with an empty granary unless it is
    waiting on the besieger's choice. (tested)
16. **Siege records only name things that exist:** stores, falls and occupations
    belong to existing cores, holds to existing claims. (tested)
17. **Time scale does not change outcomes.** The same siege run by `advance`, at
    time scale 24 and in real 5 second steps ends at the same hour. (SiegeTest)

## Justice (M6)

18. **Punishment never exceeds its caps:** confinement and bonds 1 day,
    banishment 1 day, respawn delay 1 hour, a custody fine 16 diamonds, and the
    weekly total per player across all factions. (tested; the random worlds use a
    2 day weekly cap so the per-sentence caps can be told apart from it)
19. **Resisting an imposed contract is never breach.** (tested, and ContractTest)

## Persistence (M7)

20. **Save then load gives back the same state**, field by field, including the
    contracts block and every kind of term. (StoreTest)
21. **The same state always writes the same snapshot.** Every saved collection
    keeps a stable order: never `Set.copyOf` or `Map.copyOf` in a saved record or a
    getter the store reads, since their iteration order depends on how they were
    built. Insertion order, a sorted map, or an `EnumMap` instead. (StoreGameTest,
    which compares a reloaded state's snapshot with the live one)

## The granary (M9)

22. **A loaded granary's mirror equals its chests** after every step: core's provisions for
    a store are the food the real chests hold. (GranaryGameTest)
23. **What the sim removed from an unloaded store is taken from the real items before the
    chest can be opened.** The queue is empty afterwards. (GranaryGameTest)
