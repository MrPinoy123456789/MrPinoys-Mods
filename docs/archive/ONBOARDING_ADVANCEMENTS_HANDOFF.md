# Handoff prompt — onboarding-advancements brainstorm

> Copy the block below into a fresh session to continue this brainstorm.

---

```
We're designing a "mod discovery via advancements" system for a Minecraft
Fabric server mod suite: use vanilla advancement toasts (server-only,
datapack-defined) to teach players that specific small server-side mods
exist, triggered on the vanilla-adjacent action closest to that mod's
function.

REPO: a:\MrPinoys Mods

READ FIRST: ONBOARDING_ADVANCEMENTS_BRAINSTORM.md — it's the full state of
this design as of the last session: what's actually implemented (four mods,
each with a two-step advancement chain: spiritwolves, kamutotems, rehome,
cobbleeconomy), what was tried and fixed (a translate-key bug — read that
section before using `{"translate": ...}` anywhere in this suite), and what's
still just an idea (shop CTA, wayfarers coupon, backfill grants, hidden
progressive disclosure, curiosity-gap titles, swapping the spiritwolves
tick-poll for a native vanilla criterion).

Also skim DESIGN.md §2 and §4 (the coupling rule: no mod may depend on
another, in Java or fabric.mod.json) and PHASE1_PLAN.md §0/§1 if you're
going to touch code, not just brainstorm.

STATUS: nothing from the advancement work is committed yet. `git status`
also shows unrelated in-progress work in cobbleeconomy (a dialog/mixin
system) and an uncommitted archived/ move for cobblebending and
dailyquests — none of that is part of this thread, don't touch or revert it.

Continue the brainstorm from the "Open questions / next steps" section of
ONBOARDING_ADVANCEMENTS_BRAINSTORM.md. Don't restart the design — build on
what's there. If you start implementing, verify any Minecraft API guess
against the actual mapped jar in each mod's
`.gradle/loom-cache/minecraftMaven/...` (via `unzip`+`javap`) before writing
code — this codebase runs a fictional/future Minecraft version (26.2) with
its own renames (`ResourceLocation` → `Identifier`), so real-Minecraft
knowledge from training data does not reliably apply here.
```
