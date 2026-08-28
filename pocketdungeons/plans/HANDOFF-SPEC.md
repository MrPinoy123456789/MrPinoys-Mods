# Handoff and planning doc spec

> Paste this whole file into a fresh chat when planning a new round of
> milestones. It tells you how to write every planning doc so that each
> generated handoff is lean, self-contained, and efficient when pasted into a
> DSH implementation session.

## What you are producing

When the user asks you to plan a round of milestones, you produce or update
these documents:

| Document | Purpose | When to write |
|---|---|---|
| `docs/reference/ROADMAP.md` | Milestone order, not status | Update when adding milestones to the sequence |
| `docs/reference/{plan-doc}.md` | Authoritative scope per milestone: goal, dependencies, touch points, done-when | Write the section for each new milestone |
| `docs/d3-handoffs/M{n}-handoff.md` | The paste-into-chat handoff for one milestone | Write one per milestone |
| `{mod}/CONVENTIONS.md` | Mod-specific rules that apply to every milestone in that mod | Create once per mod, update when rules change |
| `docs/d3-handoffs/_shared_preamble.md` | Maintainer reference for the handoff series | Update when the handoff format or dependency graph changes |

You do NOT write these (the implementation agent updates them on completion):

| Document | Who updates it |
|---|---|
| `plans/COMPLETED-MILESTONES.md` | Implementation agent, on milestone completion (append) |
| `docs/reference/LIVE_TEST_PASS.md` | Implementation agent, after live testing (append) |
| `docs/reference/BUGS.md` | Implementation agent or user, when a bug is found |

## What the DSH plugin already covers

The `dsh-fabric-modding` plugin injects a system prompt section that covers
these topics. Do NOT repeat them in handoffs, CONVENTIONS.md, or reading
lists:

- Toolchain: Java 25, Fabric Loom, Kotlin DSL gradle, MC 26.x unobfuscated,
  Mojang/official names (no Yarn)
- Project layout: `src/main/java/<group>/...`, `fabric.mod.json`, mixins json,
  datapack dirs
- Entrypoints and registries: `ModInitializer`, `Registry.register`,
  `CommandRegistrationCallback`
- Mixin format: package, json fields, `@Mixin`/`@Inject`/`@ModifyArg`
- Jar verification: verify against the 26.2 jar, not memory
- Build verification: `build_mod` tool, `JavaExec` test tasks,
  `run_in_background` for long builds
- Server-side constraint: no `net.minecraft.client.*` in server code
- Commit style: one commit per logical change, message explains why
- House style: no em dashes or `--` as punctuation, compact code, do not
  add or remove comments unless asked
- Context discipline: read only the handoff and relevant Java files, do not
  read big reference docs unless the handoff names a section
- Skills: `mixin-development`, `compat-troubleshooting`,
  `mod-ecosystem-overview`, `stonecutter-multiversion`
- Workspace scan: all mods, their versions, entrypoints, mixins, and whether
  they have a `CONVENTIONS.md`

If a handoff needs to reference one of these rules (e.g. "remember to verify
against the jar for this API"), say "the system prompt covers jar verification"
in one line. Do not re-explain the rule.

## Document hierarchy

```
{mod}/
  CONVENTIONS.md              mod-specific rules, read once per session
  docs/
    DISCOVERIES.md            verified API findings (top level, always scannable)
    DIALOGS_SPEC.md           menu/dialog spec (top level, always scannable)
    INTEGRATION.md            datapack schema (top level, always scannable)
    reference/                big docs, NOT in the agent's default scan path
      ROADMAP.md              milestone order
      ROOM_UX_PLAN.md         authoritative scope per milestone
      DOOR_LADDER_BRAINSTORM.md  design rationale
      D3_PROGRESSION_PLAN.md  master plan
      LIVE_TEST_PASS.md       live verification items
      BUGS.md                 bug log
      VISION.md               long-term vision
      PLAN.md                 original technical spec
      LORE.md, LORE-STORY.md, LORE-DIARIES.md  fiction
      DIALOGS.md              what is actually built
      MYTHIC_PLUS_RECONCILIATION.md  affix design reasoning
    d3-handoffs/
      M{n}-handoff.md         the active handoff (paste into chat)
      _shared_preamble.md     maintainer reference
      archive/                completed handoffs
  plans/
    COMPLETED-MILESTONES.md   architectural summaries (append only)
    room-layouts-ascii.md     reference layouts
```

The key principle: `docs/` at the top level stays small (under 50 KB total).
`docs/reference/` holds the big stuff. The implementation agent does not scan
`docs/reference/` by default; a handoff points it at specific sections.

## CONVENTIONS.md

One per mod, at the mod root (e.g. `pocketdungeons/CONVENTIONS.md`). Contains
mod-specific rules that apply to every milestone in that mod. The plugin's
workspace scan detects it and tells the agent to read it once.

What goes in CONVENTIONS.md:
- Mod-specific constraints (e.g. one-mixin budget, no custom assets)
- Mod-specific doc conventions (e.g. where status lives, what not to
  recreate)
- Mod-specific code patterns (e.g. codec migration discipline)

What does NOT go in CONVENTIONS.md:
- Anything the plugin already covers (see the list above)
- Anything milestone-specific (that goes in the handoff)
- General Fabric/Minecraft knowledge (that goes in skills)

Keep it under 2 KB. If it grows beyond that, split it or trim it.

## Handoff format

Each handoff is a self-contained markdown file designed to be pasted into a
fresh DSH chat. It should be under 3 KB if possible, never over 5 KB. Every
line is in the agent's context for every turn of the session, so waste
compounds.

### Required sections, in order

```
# M{n} - {title} - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

{2 to 4 items, section-specific, see reading list rules below}

## Dependencies

{grep-based checks, see dependency check rules below}

## Goal

{one to three lines}

## Implementation plan

{numbered steps with exact file/class/method references}

## Constraints

{only milestone-specific constraints, see constraints rules below}

## Verification

{what to run, what to check}

## Completion

{what docs to update, append-only, see completion rules below}
```

### Sections that are NOT in the format

Do NOT include these sections. They are redundant with the plugin or the
CONVENTIONS.md:

- "Where this is" (repo path, branch, mod name): the plugin's workspace scan
  lists all mods and their paths.
- "Standing rules": the plugin covers workspace-wide rules; CONVENTIONS.md
  covers mod-specific rules. A handoff says nothing about either unless a
  rule needs a milestone-specific emphasis.
- "Start here" with jar verification instructions: the plugin covers jar
  verification. If the milestone has a specific API to verify, fold that into
  the implementation plan's first step.
- "What you must not do" with workspace-wide prohibitions: fold
  milestone-specific prohibitions into "Constraints". Workspace-wide
  prohibitions are already in the plugin or CONVENTIONS.md.
- "If you get stuck": if a milestone has a specific gotcha, fold it into the
  implementation plan step where it would occur. General troubleshooting is
  the agent's job.

### Reading list rules

The reading list is the single biggest source of context bloat. Follow these
rules exactly:

1. **List only files the plugin does not cover.** The plugin covers
   toolchain, conventions, jar verification, build workflow, and house style.
   Do not list `CLAUDE.md`, `D3_PROGRESSION_PLAN.md`'s implementation context,
   or any file whose content is already in the system prompt.

2. **Name the section, not the file.** Write `ROOM_UX_PLAN.md`'s `## M22:
   Sound pass` section ONLY, not `ROOM_UX_PLAN.md`. Reference files are
   large; the agent should read one section, not scroll through the whole
   file.

3. **Do not list a file whose content is already inlined in the handoff.** If
   the handoff contains the cue table, do not also list the brainstorm
   section that the cue table came from.

4. **Maximum 4 items.** If you need more than 4 reads, inline the content
   in the handoff instead. A handoff with inlined content is cheaper than a
   handoff that triggers 5 file reads.

5. **Always include `{mod}/CONVENTIONS.md`** if the mod has one and the
   handoff is the first milestone in a new round (the agent may have read it
   in a previous session). For subsequent milestones in the same round, the
   agent has already read it; do not list it again.

6. **Include `docs/DISCOVERIES.md`** only when the milestone touches
   Minecraft APIs that have known traps. If the milestone is pure logic
   (e.g. a math refactor), skip it.

### Dependency check rules

Do NOT tell the agent to read `plans/COMPLETED-MILESTONES.md` (71 KB) to check
dependencies. Instead, tell it to grep for the specific class or method that
proves the dependency has landed.

Good:
```
## Dependencies

Grep for `RitualListener` in `src/main/java/`. If the lever handler and
lodestone menu exist, M19 and M21 are in place. If not, ship the
run-lifecycle cues only and leave `// M22: add when M19 lands` comments at
the door-selection call sites.
```

Bad:
```
## Dependencies

Check `plans/COMPLETED-MILESTONES.md` for entries on M19 and M21.
```

If a dependency is on a specific file existing, name the file. If it is on a
specific method existing, name the method and the class. Give the agent a
grep target, not a doc to read.

### Implementation plan rules

1. **Number the steps.** Each step is one commit. Say "run `build_mod` after
   each step" once at the top of the plan, not after every step.

2. **Name exact files, classes, and methods.** The agent should not need to
   search for where to make a change. Write `RunLifecycle.completeRun` (line
   709), not "the run completion method."

3. **One step per logical change.** If a step touches 3 files, list all 3.
   If a step has a precondition (e.g. "only if M19 has landed"), say so at
   the top of the step.

4. **Inline the content the agent needs.** If the step references a cue
   table, a mapping, or a set of constants, put them in the step. Do not
   point at a reference file for content the agent will need on every turn.

5. **Do not re-explain the toolchain.** Do not say "run `./gradlew build`".
   Say "run `build_mod`". The plugin handles the gradle wrapper invocation.

### Constraints rules

Only include constraints that are specific to THIS milestone. Examples:

Good:
```
## Constraints

- No custom sound files. Everything is vanilla `SoundEvents` sent via packet.
- Do not gate events on the chime. The chime is a side effect; if the packet
  fails, the event still succeeded.
- Do not add a config toggle for sounds in this milestone.
```

Bad (these are workspace-wide, already covered):
```
## Constraints

- No em dashes in any output.  <- plugin covers this
- Verify against the 26.2 jar.  <- plugin covers this
- `./gradlew build` green after every commit.  <- plugin covers this
- No client mod.  <- CONVENTIONS.md covers this
- One mixin only.  <- CONVENTIONS.md covers this
```

If a workspace-wide rule needs emphasis for this milestone (e.g. "this
milestone touches a tricky API, verify the constructor signature against the
jar"), say it in one line inside the implementation plan step where it
matters, not in Constraints.

### Verification rules

1. **Name the gradle task.** If there is a specific `JavaExec` test task,
   name it: "run `build_mod` with task `doorMaskTest`". If not, say "run
   `build_mod` with default `build`".

2. **Distinguish headless from live.** Say what is verifiable without a
   client (compiles, jar signature matches, test passes) vs. what needs a
   live client. The implementation agent can only do headless verification.

3. **Do not repeat the done-when from the plan doc.** The reading list
   already points at the plan section. Summarize it in one line: "Done when:
   every event in the cue table produces its cue, heard only by the relevant
   player."

4. **Do not duplicate the cue table or spec here.** If the implementation
   plan already inlined the content, the verification section just says "all
   cues from the implementation plan fire correctly."

### Completion rules

1. **Append, do not re-read.** For `COMPLETED-MILESTONES.md` (71 KB) and
   `LIVE_TEST_PASS.md` (54 KB), say "append a new section" and "do not
   re-read the whole file."

2. **Name the exact doc and what to add.** "Append this milestone's
   architectural summary to `plans/COMPLETED-MILESTONES.md` in the same
   style as M0-M17's entries."

3. **Rename the handoff.** "Rename `M{n}-handoff.md` to
   `M{n}-handoff-completed.md` once the milestone is landed and its
   `COMPLETED-MILESTONES.md` entry exists."

4. **Update the roadmap if needed.** If the milestone's scope diverged from
   the plan, fix the plan first. The roadmap carries order, not status.

5. **Do not list docs the plugin already knows about.** The agent knows
   where `COMPLETED-MILESTONES.md` and `LIVE_TEST_PASS.md` are; just name
   them and say "append".

## Roadmap update rules

When adding milestones to the roadmap:

1. Add them in order. No checkboxes. The roadmap carries order and reasoning
   for the order, not status.
2. One line per milestone: `M{n}: {title} (needs M{dep})`.
3. Do not repeat the milestone's scope. The plan doc has the scope; the
   roadmap has the order.
4. If a milestone is cut or reordered, update the reasoning paragraph that
   explains the sequence.

## Plan doc update rules

When writing the authoritative scope for a new milestone in the plan doc
(e.g. `ROOM_UX_PLAN.md`):

1. One `## M{n}: {title}` section per milestone.
2. Include: goal, dependencies, scope, touch points, done-when, verification
   notes.
3. Do not include implementation steps. Those go in the handoff.
4. Do not include reading lists or dependency checks. Those go in the
   handoff.
5. The plan doc is the source of truth for scope. The handoff is the
   session-bootstrapping layer that tells the agent where to find the scope
   and how to execute it.

## Context budget

A handoff is pasted into the chat and stays in context for every turn. At
94% cache hit, cost is low, but latency and agent focus suffer when the
handoff is bloated.

| Component | Target | Hard limit |
|---|---|---|
| Handoff file | under 3 KB (~750 tokens) | 5 KB (~1,250 tokens) |
| Reading list (total file size of listed sections) | under 50 KB | 100 KB |
| CONVENTIONS.md | under 2 KB | 3 KB |

If a handoff exceeds the hard limit, cut content by:
1. Removing any section that duplicates the plugin or CONVENTIONS.md.
2. Inlining reference content instead of listing the reference file (saves a
   file read but may add tokens; judge per case).
3. Trimming the implementation plan to exact file/class/method references
   without prose explanations.
4. Removing the "If you get stuck" section and folding gotchas into the
   relevant implementation step.

## Prose style: telegraphic

Write handoffs in telegraphic prose: drop articles, auxiliary verbs, and
filler words where meaning stays clear. Never drop a qualifier that changes
what the agent does.

Good:
```
Chime.java: one static method per event, each sends ClientboundSoundPacket
via player.connection.send(). Vanilla SoundEvents only, no custom files,
server-side.
```

Bad (verbose, no extra information):
```
A Chime.java class with one static method per event, each sending a
ClientboundSoundPacket to the player's connection. All vanilla
SoundEvents, no custom sound files, server-side only.
```

Bad (too terse, ambiguous):
```
Chime.java. One method per event. Send packet to player. Vanilla sounds.
```

The third example is ambiguous: "send packet to player" does not say which
method to use (`connection.send` vs `level.playSound`), and "vanilla sounds"
does not say `SoundEvents` constants vs custom registrations. Ambiguity
costs more tokens than it saves, because the agent makes extra tool calls to
resolve it.

Rules:
- Drop "a", "an", "the", "that", "which" when the noun is clear without them.
- Drop "is", "are", "was", "will be", "has been" when the statement is
  clear as a colon or comma join.
- Keep every class name, method name, field name, and file path in full.
- Keep every conditional ("only if M19 landed", "unless the handoff
  references a section").
- Keep every constraint ("no custom files", "server-side only").
- Prefer colon joins over full sentences: `Chime.java: one method per
  event` over `Chime.java is a class with one method per event`.
- Prefer comma lists over prose: `vanilla SoundEvents, no custom files,
  server-side` over `using vanilla SoundEvents and not creating any custom
  files and being server-side only`.

The savings are modest (~14% vs verbose prose), but the discipline keeps
handoffs under the context budget and forces precision over padding.

## Worked example

See `docs/d3-handoffs/archive/M22-handoff-completed.md` for a handoff that
was trimmed to follow this spec. Compare it against the archived M18-M21
handoffs to see the difference: the older handoffs include "Where this is",
full standing rules, and reading lists that point at whole files. The
trimmed M22 handoff drops all of those and only contains milestone-specific
content.

## Checklist for each handoff before you save it

- [ ] No "Where this is" section (plugin covers workspace info)
- [ ] No "Standing rules" section (plugin and CONVENTIONS.md cover rules)
- [ ] Reading list has 2 to 4 items, each naming a specific section
- [ ] Reading list does not include `CLAUDE.md` or `D3_PROGRESSION_PLAN.md`'s
      implementation context
- [ ] Dependency checks use grep, not file reads
- [ ] Constraints are milestone-specific only
- [ ] Implementation plan names exact files, classes, and methods
- [ ] No content is duplicated between sections (e.g. cue table appears once)
- [ ] Verification names the gradle task, not just "run the build"
- [ ] Completion instructions say "append" for large files
- [ ] File is under 5 KB
- [ ] Prose is telegraphic: no filler words, but no ambiguity
- [ ] No em dashes or `--` as punctuation in the handoff text
