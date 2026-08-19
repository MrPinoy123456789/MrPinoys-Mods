---
title: SPEC.md section 15.1 -- Rehome coexistence guard, test plan
---

# Why this exists

There's no automated multi-mod game-test harness in this repo (no gametest
dependency, no CI). This is the manual/integration test plan for the
`interaction.InteractionHandler` HOME guard added in SPEC.md section 15.1.

# What's under test

`InteractionHandler.onUseEntity` must never return `PASS` once a villager is
established as a resident (`MemoryModuleType.HOME` claimed). The two
vanilla-trade branches (hand-item right-click, sneak+empty-hand right-click)
open the trade screen directly and return `SUCCESS`, so the interaction can
never reach a later `UseEntityCallback` listener -- specifically Rehome's
`GiftHandler`, which intercepts based on its own follower attachment
(`follower == null`), not on `HOME`.

# Setup

1. Build both mods and place both jars in the same `mods/` folder:
   `smalltalk` and `rehome`.
2. Start a fresh single-player (or dedicated) world.
3. In `smalltalk.json`, leave `requestsEnabled` at its default (`false`) --
   this test is about the gift/trade dispatch guard, not Fetch tasks.
4. In Rehome's config, confirm at least one gift item is configured for the
   `"*"` profession wildcard (or the profession of the test villager), so a
   gift item you're holding would normally be accepted by Rehome's
   `GiftHandler.isGift`.

# Test 1 -- resident + gift item never reaches Rehome

1. Find or build a bed-having (resident) villager. Confirm residency: right
   click empty-handed and get Small Talk's dialogue menu, not vanilla
   trading UI directly.
2. Hold a Rehome-configured gift item (e.g. whatever's in
   `rehome`'s gift table for that villager's profession).
3. Right-click the resident.
4. **Expected:** the vanilla trading screen opens. Rehome's "gift accepted /
   gift declined" particle and chat feedback (`Feedback.giftAccepted` /
   `Feedback.giftDeclined`) never fire. The held item is **not** consumed
   (Rehome's `offerGift` shrinks the stack by 1 on interception -- if the
   stack count is unchanged, Rehome never ran).
5. **Fail condition:** trading screen doesn't open, or the item shrinks by
   one, or Rehome's befriend/decline feedback appears.

# Test 2 -- resident + sneak, empty hand

1. Same resident as above. Sneak + right-click with an empty hand.
2. **Expected:** vanilla trading screen opens directly (fast path).
3. This path doesn't touch Rehome regardless (Rehome's `GiftHandler` only
   acts on a non-empty gift-matching stack, or an emerald against an
   existing follower), but confirms the guard doesn't regress the fast path.

# Test 3 -- stranger (no HOME) is untouched, sanity check

1. Find or spawn a villager with **no** claimed bed (a stranger).
2. Hold the same gift item from Test 1 and right-click it.
3. **Expected:** Rehome's normal gift-offer flow runs (befriend chance
   applies, particles/feedback fire per Rehome's own config) -- Small Talk's
   `InteractionHandler` must `PASS` immediately via `isResident()` and never
   intervene. This confirms the guard is resident-scoped, not global.

# Test 4 -- follower edge case (regression for the bug this guard fixes)

This is the scenario the guard specifically closes: a resident who Rehome
would otherwise treat as giftable because `FollowerAttachment.followerOf`
returns `null` for it (Rehome's befriend state is independent of Small
Talk's residency).

1. Confirm the resident in Test 1 has never been given to Rehome's
   `FollowerAttachment` (fresh villager, never followed by anyone).
2. Repeat Test 1. Before this guard existed, listener registration order
   between Small Talk and Rehome was unspecified, so Rehome's `GiftHandler`
   could run first and intercept (since `follower == null && isGift(...)`
   both hold). With the guard, Small Talk consumes the interaction itself
   (`SUCCESS`) whenever it's a resident, so Rehome's listener is never
   reached -- order-independent.
3. **Expected:** identical to Test 1, regardless of mod load order.

# Result

Record pass/fail per test and the two mods' versions/build hashes here when
run, since there's no automated record otherwise.
