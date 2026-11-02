# Prompt: build the Charter book Bedrock first UI

Paste everything below the line into the agent. It is self-contained.

---

You are working in the MrPinoys-Mods workspace, in the `charter/` mod (Fabric, Minecraft 26.3). Read `/CLAUDE.md`
first and follow it exactly: no em dashes and no double hyphens as punctuation anywhere (code comments, lang strings,
docs, commit messages).

## Goal

Half the players on the owner's live kingdom server are on Bedrock: Xbox, controller, joining through Geyser and
Floodgate. They cannot type commands, cannot hover, cannot click chat links, and mostly cannot see chat. The
"Charter of Rights and Freedoms" book is their only way into Charter. Give them the best experience Minecraft can
offer, and let Java players benefit from the same design.

## Read first

1. `charter/docs/2026-11-01-charter-book-bedrock-ui-plan.md`. It is the spec: the player, the native forms
   decision, the eleven screen rules, the screen tree, the press budget, the build order and the risks. When this
   prompt and the plan differ, the plan wins.
2. `charter/fabric/src/main/java/charter/mc/book/`: every page. Start with `BookDialogs`, `MemberPages`,
   `NewcomerPages`, `LeaderPages`, `TravelButtons`, `BookMod`, then `WarPages`, `BreakawayPages`.
3. `charter/fabric/src/main/java/charter/mc/cmd/Teleports.java`, `CharterCommands.java`, `Pages.java` and
   `justice/JusticeCommands.java`: the commands the book must replace.
4. `docs/KINGDOM_SERVER.md` for the server (Geyser, Floodgate, Polymer) and the versions it runs.
5. The `dialog-screens` and `player-text` skills apply in spirit (they are written for Pocket Dungeons; the rules
   transfer, the code does not).

Phase 1 of the plan is already on the branch. Do not redo it.

## What to build

Phases 2 to 5 of the plan, in order, each finished and green before the next:

- **Phase 2, the frame.** A `BookScreen` model that enforces the screen rules (at most 8 buttons, label length,
  an exit always, ok or refused result). A Java dialog renderer that produces what players see today. Port every
  page to it without changing content. All existing gametests pass. Commit this on its own.
- **Phase 3, Bedrock native.** A Floodgate Cumulus renderer (`SimpleForm`, `ModalForm`, `CustomForm`) behind a
  soft dependency (`compileOnly`, `isModLoaded("floodgate")`, one adapter class) and a `bedrockNativeForms` knob,
  default true. Icons from vanilla Bedrock texture paths. Closing a form runs the exit. Callbacks run the page's
  action directly and re-check live state and powers. Pick the Floodgate API version that matches the server; say
  which and why.
- **Phase 4, the new layout.** Member Home, Newcomer Home, Needs you, Where I stand (replacing Rights, Freedoms and
  Situation), My faction, More, the complete Leader's Desk (requests, invite, grouped powers, treasury with presets
  and slider, Justice, who decides, war with targets first, move the Nexus), verb confirms, paging, the Glossary.
  Travel stays at the top of both Homes.
- **Phase 5, outside the book.** Boss bar for the teleport warm-up, action bar for results, book glint and need
  count in its name and lore, the book given into the hotbar, sounds as cues. Chat stays as a copy for Java players.

Stop before Phase 6 (it needs a live Bedrock client) and list what it should check.

## Rules

- Every behaviour change gets a gametest in `charter/fabric/src/gametest/java/charter/gametest/` (screen rules,
  Home order, leader gating, result colours, paging, one-book rule, death vanish). Pure logic gets a unit test.
  Gametests must not need Floodgate.
- Screens are built from live state on every open, and every press re-checks it. The engine decides, never the page.
- No typing where a button, dropdown, slider or preset works.
- Do not change Pocket Dungeons, Lemon, Foundry or any other mod. Do not deploy, restart, or touch any server.
  Deployment is the owner's call through the `deploy-shipped-server` skill.
- Check each file's line endings and BOM before editing and keep them.

## Check

- `cd charter && ./gradlew build` at the end of each phase. Phase 1 was written without a working Gradle, so fix any
  compile error the first build finds there too.
- `grep -rn -e $'—' -e ' -- ' --include='*.java' --include='*.md' --include='*.json' charter` over what you wrote.
- Report per plan item: done, partly done, or skipped with the reason. Give the expected press count for each task
  in the plan's press budget. List everything only a live Bedrock client can confirm (native form look, icon paths,
  glint, boss bar, Chronicle).

Work on branch `claude/relaxed-pascal-us1zqd` (or a branch the owner names). Commit per phase, at least. Do not open a
pull request unless asked.
