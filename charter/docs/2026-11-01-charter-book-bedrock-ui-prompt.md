# Prompt: build the Charter book Bedrock UI plan

Paste everything below the line into the agent. It is self-contained.

---

You are working in the MrPinoys-Mods workspace, in the `charter/` mod (Fabric, Minecraft 26.3). Read `/CLAUDE.md` first and follow it exactly: no em dashes and no double hyphens as punctuation anywhere (code comments, lang strings, docs, commit messages).

## Goal

Half the players on the owner's live server are on Bedrock (Xbox, controller, through Geyser). They cannot type slash commands, cannot hover, cannot click chat links, and mostly cannot see chat. The "Charter of Rights and Freedoms" book is their only way into Charter. Make the book work well for them.

## Read first

1. `charter/docs/2026-11-01-charter-book-bedrock-ui-plan.md`: the plan, with findings per page and a build order. It is the spec.
2. `charter/fabric/src/main/java/charter/mc/book/`: all the pages. Start with `BookDialogs` (dialog helpers), `MemberPages`, `NewcomerPages`, `LeaderPages`, `TravelButtons`, `BookMod`.
3. `.claude/skills` entries `dialog-screens` and `player-text` apply in spirit (few words, the next action, no jargon, every dialog has an exit button that Esc and B run). They are written for Pocket Dungeons; the rules transfer, the code does not.

Phase 1 is already done and pushed (one book per player, book vanishes on death, travel buttons at the top, Leader's Desk, borders button, accept invitation). Do not redo it.

## What to build, in order

Do Phase 2 fully, then Phase 3. Stop before Phase 4 (it needs a live Bedrock client).

**Phase 2**
- Green success and red refusal notices. Replace the string notice with a small type that carries ok or not; migrate every page; keep `withNotice` working until the last caller moves.
- Confirm screens use verbs, not Yes and No (Reject, Break away, Leave).
- Remove every "Hover over..." sentence. Where a hover explained jargon, put one plain sentence on the page, and add a Glossary page behind one button.
- Travel feedback on the action bar (hold still, countdown, arrival). Build a public `Teleports.refusal(player, kind)` and use it so a button that cannot work says why in its label before it is pressed. Say the outpost food cost on its button.
- Dynamic book lore with counts (votes waiting, requests waiting, invitations), refreshed on a slow tick only for players whose counts changed. Also send an action bar line "Open your Charter book" whenever a chat link would be sent today (join request, vote, invite) so Bedrock players notice. Keep the chat links for Java players.
- Cut the Home body to one status line; move the calendar behind a Calendar button. Same on the newcomer Home.

**Phase 3**
- Merge Rights, Freedoms and Situation into one "Where I stand" page with headed sections.
- A paging helper in `BookDialogs` (six items per page, Next and Previous, exit still present) and use it for the faction list, vote candidates, the Commune "who decides" list and the member list.
- A "Leave or break away" page at the bottom of Home, separate from Freedoms, with the cooldown shown before the confirm. Shorten Break away to who comes, cost, time, then the button; the rest behind "More".
- War: targets first, rules behind "How war works", add distance and direction to target labels.
- Faction page: read only for non officers; officer actions only on the Leader's Desk.
- A Justice section on the Leader's Desk covering `/charter justice sentence`, `bail`, `cell mark|remove|list`, `safe deposit` and the pardon, confine, bond and fine links in `justice/JusticeCommands.java`. Dropdowns only, no typing. Reuse the actions those commands run.
- A map dialog replacing `/charter map` chat output for the book.
- Group the powers on the Grant screen (Members, Money, Land and war, Justice) using the same words as the Rights page.

## Rules

- Every dialog must have an exit button (`BookDialogs.list` and `form` enforce it). Esc and B run it.
- No typing where a dropdown works. Where typing is unavoidable (an amount), keep the default valid.
- Build screens from live state on every open; every button re-checks its power when pressed. The engine decides, the page never does.
- Do not change Pocket Dungeons, Lemon or any other mod. Do not deploy, restart a server, or touch the shipped server. Deployment needs the `deploy-shipped-server` skill and the owner's approval.
- CRLF and BOM: check each file's line endings before editing and keep them.
- Every behaviour change gets a gametest in `charter/fabric/src/gametest/java/charter/gametest/BookGameTest.java` (button order, notice colours, paging, one-book rule, leader gating). Pure logic gets a normal unit test.

## Check

- `cd charter && ./gradlew build` (run it; the earlier phase was written without a working Gradle, so fix any compile error it finds in Phase 1 files too).
- `grep -rn -e $'—' -e ' -- ' --include='*.java' --include='*.md' --include='*.json' charter` over what you wrote.
- Report per plan item: done, partly done, or skipped with the reason. List anything that only a live Bedrock client can confirm (Chronicle as a written book, Geyser dialog rendering, controller feel).

Work on branch `claude/relaxed-pascal-us1zqd` (or a branch the owner names). Commit in small steps. Do not open a pull request unless asked.
