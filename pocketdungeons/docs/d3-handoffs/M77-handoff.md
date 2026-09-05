# M77 - Release the format through real hosts - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `docs/reference/VISION.md` sections 6 to 9. Adoption, authorship and release promises.
- `docs/INTEGRATION.md`, compatibility and operating envelope.
- `docs/reference/LIVE_TEST_PASS.md`, closure plus new content and social rows. Do not re-read the whole file.
- `src/main/resources/fabric.mod.json`, release metadata.

## Dependencies

```bash
grep -rn "packValidationTest\|dungeonIntegrationTest\|dungeonLoadTest" pocketdungeons/build.gradle.kts
grep -rn "CallingCard\|RunMemento\|RecipeDiscovery" pocketdungeons/src/main/java/pocketdungeons/
```

First confirms M72, M76 and M75 test tasks are registered. Second confirms social and discovery features are in source. Verify all required evidence IDs, not only milestone headings. If M72 external-author acceptance, M75 social proof or M76 measured cap is missing, stop.

## Goal

A host can adopt Pocket Dungeons by name without the author in their console.

## Implementation plan

Run `build_mod` after each step. No opportunistic source refactor in this milestone.

1. **Pilot hosts.** Owner recruits two independent pilot hosts, ideally a vanilla-client server and an existing compatible modpack population. Ship install, update, backup, recovery and author quickstarts grounded in tested paths. Run upgrade fixtures from pre-M20, pre-M46 and M61 saves; preserve rooms, listing privacy, skins, bag choices and item custody. Test a pack being removed while paid recipes are pending. Freeze public schema major version only after the authors' upgrade exercise works.
2. **Release jar verification.** Produce the candidate and verify release jar contents: server environment, no client assets, no test classes or entrypoints, only intended mixin surface. Record actual compatibility limits for server-side inventory managers and client-required content mods.
3. **Observe retention.** Observe several weekly bounty cycles before declaring retention. Collect opt-in, aggregate host observations about repeat home visits, experiments taught, abandonment and admin rescues. Do not claim causation from two servers; fix barriers before buying content volume with more milestones.

## Constraints

- No launch date commitment, monetisation layer or forced public listing.
- A pilot host must consent before receiving artifacts or operational changes.
- A safety regression blocks release regardless of engagement.
- Release is an owner action, not an automatic milestone side effect.

## Verification

`build_mod` with tasks `build`, `runGameTest`, `dungeonIntegrationTest`, `packValidationTest`, representative `dungeonLoadTest`. Owner and human: installation, update, recovery and gameplay acceptance on the release artifact. Done when candidate disposition and pilot findings are appended.

## Completion

- Append candidate disposition and pilot findings to `plans/COMPLETED-MILESTONES.md`. Do not re-read the whole file.
- Update `README.md` and `docs/INTEGRATION.md` with install, update, backup, recovery and author quickstarts.
- Rename this file `M77-handoff-completed.md`.
- Record any 26.2 API surface that could not be confirmed in `docs/DISCOVERIES.md` as a numbered trap with `UNVERIFIED`.
