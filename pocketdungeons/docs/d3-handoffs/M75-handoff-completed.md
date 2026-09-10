# M75 - Give discoveries somewhere to live - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `docs/reference/VISION.md` sections 2.1, 3.1.1, 3.6.1 and 5.4. Room ownership, visiting and discovery display.
- `src/main/java/pocketdungeons/VisitService.java`, methods `visit`, `statusOf`, `createVisitInstance`.
- `docs/DIALOGS_SPEC.md` section 7 and stale-state rules.
- `docs/DISCOVERIES.md` traps 3, 14 to 17. Trap 16: a placed block does not retain item provenance.

## Dependencies

```bash
grep -rn "HOME" pocketdungeons/src/main/java/pocketdungeons/VisitService.java
grep -rn "RecipeDiscovery" pocketdungeons/src/main/java/pocketdungeons/
grep -n "publicListed\|recentVisitors" pocketdungeons/src/main/java/pocketdungeons/DungeonLog.java
```

First confirms M65 HOME guard is in visit routing. Second confirms M71 recipe discovery is in personal records. Third confirms listing and visitor fields in `DungeonLog`. If M73 or M74 not landed, stop. Owner approval of the two-channel privacy model is mandatory.

## Goal

Players can show a physical record, pass a private address and learn from each other without a browse-only popularity contest.

## Implementation plan

Run `build_mod` after each step.

1. **CallingCard.** Add `src/main/java/pocketdungeons/CallingCard.java`. Restore owner-UUID cards as a separate private capability with revocation epoch and no position. Retain public directory opt-in, paginate using existing server-side UI, use one authorisation and routing service. Support hand-to-hand card trade inside safe rooms without exposing stored items or opening survival inventory. Card possession allows visiting only, not whitelisting, party membership or reward eligibility. When owner is running, offer shared read-only saved-room visit, never staging admission; when owner returns, converge visitors safely onto one authoritative home. Test stale pages, copied and revoked cards, offline owners, guest departure, owner start and return, cross-player custody. No room name or UUID enumeration for private rooms.
2. **RunMemento.** Add `src/main/java/pocketdungeons/RunMemento.java`. Make signature drops placeable vanilla decor, banners, books and shell tokens. Provide an optional written memento with theme, key band, affixes and discovery ID so the owner can place it beside their own display. Keep evidence in a server-side run record. No auto-furnished trophy wall, compulsory museum slots or tradable progression credit.
3. **Retune bounties.** Retune weekly bounties toward exploring, clearing spurs and low-omen safe visits, with solo-achievable choices and actual participating-member credit. Cosmetics and materials only for new rewards, no streak punishment, public ranks or escalating mandatory grind. Replicable routine crafting is not an exclusive achievement certificate.

## Constraints

- Owner approval of the two-channel privacy model is mandatory.
- Room permissions and run membership stay separate.
- Lore remains optional; no Herobrine finale is needed to justify a room or reward.
- No room name or UUID enumeration for private rooms.
- No popularity metrics or global browsing of private owners.

## Verification

`build_mod` with tasks `lobbyBrowserTest`, `bountyTrackerTest`, `dungeonLogTest`, `runGameTest`, `dungeonIntegrationTest`, then `build`. Human session includes a public encounter, private card trade, visit and a player explaining a displayed object. Done when privacy and revocation semantics and observed social use are appended, with population and tester counts.

## Completion

- Append privacy and revocation semantics and observed social use to `plans/COMPLETED-MILESTONES.md`, with population and tester counts. Do not re-read the whole file.
- Append social rows to `docs/reference/LIVE_TEST_PASS.md`. Do not re-read the whole file.
- Rename this file `M75-handoff-completed.md`.
- Record any 26.2 API surface that could not be confirmed in `docs/DISCOVERIES.md` as a numbered trap with `UNVERIFIED`.
