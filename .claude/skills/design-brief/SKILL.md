---
name: design-brief
description: Run a Pocket Dungeons design decision the way the owner likes it. Researches comparable games when asked, proposes two or three options with one recommendation and waits for approval before coding, writes a self-contained prompt for another model when asked, and records the approved result as a dated decision doc with a supersedes list and open questions. Use for scrap or economy models, risk systems, room or ordeal redesigns, board and screen text, and any "propose before you build" request.
---

# Design before building

House rule for everything you write: no em dashes and no spaced double hyphens as punctuation
(`A:\MrPinoys Mods\CLAUDE.md`). Work in `A:\MrPinoys Mods\pocketdungeons`.

## The loop the owner uses

1. **Frame** the problem in the player's words (quote them from the playtest report).
2. **Research** (only when asked, or when the question is "how do other games solve this"): look up
   comparable systems with WebSearch/WebFetch (load them with ToolSearch). Report what each does and the lesson,
   not a link dump. The scrap model research drew on Darkest Dungeon, Slay the Spire, Hades and similar; the
   owner's reaction (it had "no risk") shows what to check: **what is actually wagered**.
3. **Propose** two or three options. For each: what the player does, why it is interesting, how it scales with
   party size and ominous, what it costs in items (see scarcity below), how hard it is to build here. Then
   **one recommendation** with the reason. Do not survey exhaustively; a recommendation is expected.
4. **Stop and wait** for the owner. Do not code any option. Do not treat enthusiasm as approval of one option
   when they said "I like your ideas" about several; ask which, or pick the one that matches their stated
   constraints and say you did.
5. **Build** only what was approved. Record the decision (below). Hand back a list of what is unverified until
   played.

If the owner says an option "is not feasible currently", take it at its word and propose the nearest feasible
version (the Plate Relay replaced the climbing room that way).

## Constraints that shape every option

- **Scarcity:** resources are meant to be finite (wood, food, sand, ore, gold). A mechanic should cost time,
  position, lives or risk, not stacks of items the player may not have. Do not require crafted or keyed items to
  finish a floor (soft-lock risk, see the ominous-key toll, PD-170).
- **Risk is real:** the owner wants Darkest Dungeon style loss, where failing costs progress but not everything.
  A system where only emeralds are wagered was rejected.
- **Legibility:** a rule the player cannot see "feels like it doesn't exist". Say where the number is shown (item
  lore, chat line, board, title, bar) and how the player learns it. Boards read like Steve Jobs wrote them:
  few words, no jargon, no labels that position can carry, colour does the grouping. Offer a short and a clear
  version of any text and say which you prefer.
- **Past leanings:** mechanics rescued, not skipped; verticality and geometry that demand movement; rooms that
  tell a story; one clear idea per room; the minecraft-SCP anomaly tone.
- **Naming is settled law:** the player-facing words are compass (level), scrap, haul, lives, charts (check the
  current decision doc before using older words like "chart scrap" or "keystone"). Internal names may differ.
- Things the owner has ruled out or deferred stay that way unless they reopen them (read `docs/reference/BUGS.md`
  notes and the latest `docs/decision-*.md`).

## Writing a prompt for another model

When asked to "write a prompt to get Opus to find the best system": produce one self-contained markdown
document the model can act on with no other context. Include: the project in two sentences; the house rules
(dash rule); the problem and the player quotes; what exists today with file paths; the hard constraints above;
the question to answer; the required output format (options, recommendation, spec table, migration, knobs,
open questions); and an explicit "do not write code". Save it under `docs/` (for example
`docs/playtests/<date>-claude-prompt.md` for fix prompts, or `docs/design-<date>-<n>.md` for a design brief) and
give the owner the path. The reply from the other model is pasted back by the owner; then build it.

## The decision record

After approval write `docs/decision-<yyyy-mm-dd>-<topic>.md`. The shape that worked (see
`docs/decision-2026-10-07-haul-and-blood-doors.md`):

- one-sentence statement of the model in the player's words;
- a table of the numbers, where each lives, and what is at risk;
- the rules (earning, banking, failing, costs), concise and numbered;
- **Migration** for existing saves (what a current player keeps, the one-time message);
- **Supersedes:** list the earlier decisions, plans and bugs this replaces or amends, by id;
- **Knobs:** config keys, JSON keys and constants a tuner can change;
- **Open until played:** the questions only live play can answer.

Update the files a decision touches: `BUGS.md` statuses, the docs it supersedes, `docs/playtests/LIVE_CHECKS.md`
(a new live check for the open questions), and the config/JSON docs. Note the Lemon knowledge pack and
`INTEGRATION.md` if they still describe the old rule.

## Design pass over a playtest (two phases)

For a larger brief ("design pass, then implement"): Phase 1 is design only with no code changes and ends in
a document and questions; Phase 2 starts only after the owner approves or revises. Read the report's "Design
decisions" section first, then the doc the model lives in (`docs/DUNGEON_STRUCTURE_DESIGN.md` is the current
reference), then the relevant source. `docs/design-2026-10-06-1.md` is the template: numbered sections per topic,
"Phase 2 notes", "Open questions".
