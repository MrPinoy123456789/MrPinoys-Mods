# Handoff prompt — paste this whole file as your first message

You are implementing the Wondrous mod expansion in this repository. Everything
you need is already written down. Do not skip reading it.

## Read these two files first, in this order

1. `wondrous/BUILD-PLAN.md` — **this is your actual instructions.** Task-by-task,
   in order, with exact files, exact code shapes, and exact stop conditions.
2. `wondrous/SPEC-EXPANSION.md` — the design reasoning behind each task, for when
   you need to understand *why* a rule exists. `BUILD-PLAN.md` wins on anything
   the two disagree about.

Do not start writing code before you've read `BUILD-PLAN.md` §0–§2 (the working
method and the full task table). It tells you the order and why the order
matters — some tasks silently break a feature that already ships if done in the
wrong sequence (T9 in particular).

## The five rules that matter more than anything else in this document

**1. Never invent a Minecraft API.** If you expect a method to exist and can't
find it, do not guess a plausible name and do not write code around a method you
haven't confirmed exists. Stop, write down what you were looking for and what you
found instead in `wondrous/NOTES.md` under a "Signatures" heading, and move to
the next task. A skipped task is fine. A wrong method call that compiles against
the wrong overload, or worse, doesn't compile and gets "fixed" by guessing again,
is not fine.

**2. Compile after every single task. Never batch multiple tasks before
compiling.**

```bash
cd "a:/MrPinoys Mods/wondrous"
./gradlew.bat :fabric:compileJava --console=plain -q
```

No output means it passed. If it fails, that's your task to fix before touching
anything else — do not start the next task with a broken compile.

**3. Server-side only, no Mixins, no new registry entries, no new blocks/items/
entity types.** Every item in this mod is a vanilla item stack carrying
`{wondrous: "<id>"}` in `minecraft:custom_data`. If your plan for a task involves
registering something new, you've misunderstood the task — stop and re-read the
relevant section of `SPEC-EXPANSION.md`. The one narrow exception (vanilla
`block_display` entities, with a particle fallback) is called out explicitly
where it applies.

**4. Match the existing code, don't reinvent its shape.**
`wondrous/fabric/src/main/java/wondrous/AreaBreak.java` and `FlyingBoots.java`
are the reference implementations — read one of them before writing your first
new behaviour class. Every behaviour class in this mod is `public final`, has a
private constructor, exposes `public static void register(...)`, reads its own
id via `WondrousTag.is(stack, ID)`, and every event handler starts with:

```java
if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
    return InteractionResult.PASS;
}
```

**5. Start at T0. Do not skip it, do not reorder around it.** `BUILD-PLAN.md`
§3 is a signature-verification pass against the real merged jar already sitting
in this repo's Gradle cache — it runs `javap` against the actual 26.2 jar and
records real signatures in `NOTES.md`, because most of what `SPEC-EXPANSION.md`
says about non-trivial APIs is stated from memory and flagged 🔍, not verified.
Skipping this step is the single most likely way this build goes sideways: you
will hit a 🔍'd claim, not know it's unverified, and either invent a signature
(rule 1) or burn an hour rediscovering what T0 would have told you in five
minutes.

## Working method for the rest of the session

Go through `BUILD-PLAN.md` §2's task table top to bottom: **T0 → T0.5 → T1 → T2
→ T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T12 → T12.5 → T13 → T14 → T15
→ T16 → T17 → T18.**

For each task:
1. Read that task's section in `BUILD-PLAN.md` in full — Files / Do / Don't /
   Wire / Done when.
2. Implement exactly the files it names. Touch nothing else.
3. Compile.
4. Check the task's "Done when" against what you actually built — not against
   whether it compiled. Compiling is necessary, not sufficient.
5. Mark the task's checkbox `☑` in the §2 table in `BUILD-PLAN.md` itself, so
   progress survives a context reset.
6. Move to the next task.

**Natural stopping points**, if you need to pause and report back rather than
running the whole plan in one sitting: after T0.5, after T4, after T8, after
T12.5. Each of those leaves the mod in a shippable state — say so explicitly
when you stop there, and say which task you'd resume at.

## If you get stuck

- A task whose "Done when" you can't verify (no way to test in this
  environment): implement it, say clearly what you could not verify and why,
  and move on. Don't claim it's done if you didn't check it.
- A 🔍'd API that turns out not to exist as described: this is exactly what T0
  and rule 1 exist for. Write it in `NOTES.md`, and if `BUILD-PLAN.md` names a
  fallback for that specific case (T4's fuel question, T13's particle fallback,
  T2's "cut if no write path"), take the fallback rather than improvising one.
- Anything not covered by the above: stop and describe the specific blocker
  rather than guessing past it.
