# Prompt: two Lords for one kingdom

Paste everything below the line into the agent. It is self-contained.

---

You are working in the MrPinoys-Mods workspace, in the `charter/` mod (Fabric, Minecraft 26.3). Read `/CLAUDE.md`
first and follow it exactly: no em dashes and no double hyphens as punctuation anywhere (code comments, lang strings,
docs, commit messages). Then read `docs/KINGDOM_SERVER.md` (the live server's setup, especially "Locks on leaders").

## Goal

The owner's kingdom server has five kingdoms, each a Lordship whose single Lord is appointed by an operator. The
owner wants a kingdom to be able to have **two Lords** who rule together as equals. Operators choose them. Players
must see "two Lords", not "a council".

## What already exists (verified, read it yourself before changing anything)

- The ruler is the faction's decision office (`Organization.governance.deciders`), an `Office` named `Lord`
  (`Preset.LORD`), reserved, `vacateWhenAbsent`, with a number of seats and a `Deciding` mode, `ALONE` (any holder
  may act) or `TOGETHER` (a majority of seats must agree). `core/src/main/java/charter/gov/Office.java`.
- Every Lord power is a grant to that office (`Preset.addLordGrants`), so **every holder of the office already holds
  every Lord power**. A two seat office with `ALONE` is, in the engine, exactly "two Lords who may each act".
- `Standing.form` (`core/.../gov/Standing.java`) calls 0 seats a Commune, 1 a Lordship and 2 or more a COUNCIL. The
  book shows "A council of 2, any one may act" (`MemberPages.home`, `book.form.council_alone`). This is the main
  thing players would see wrong.
- `AppointLeader` (`core/.../org/actions/AppointLeader.java`), run by `/charter admin faction leader <faction>
  <player>` (`fabric/.../cmd/LandCommands.java`, `appointLeader`), calls `lord.unseatAll()` then seats one player.
  Run twice, it replaces; it can never produce two Lords.
- `SetSeats` changes seats and mode; `leadersChangeGovernance` is false on the live server, so players cannot, but
  operators can. There is no operator command that sets seats today; check `AdminCommands` and `AdminPages`.
- `Unseat` and `Vacate` on the decision office return `needsSeatMajority`, and `ActionEngine.council(...)` turns a
  lone holder's attempt into a proposal to the seats when the office has 2 or more holders. Confirm by test that one
  Lord can **not** remove the other, shrink the seats, or change the mode alone.
- Absent Lords: `ActionEngine` (around the `absentLordDays` loop) unseats each absent holder separately and opens an
  election for the office. With two seats, one absent Lord loses only their own seat.
- `StateKingdomView.lordOf` (public API in `core/.../api/`) returns only the first holder. Find every caller in the
  workspace (`grep -rn lordOf --include=*.java .`, including `chartercurrency/` and `pocketdungeons/`) before changing
  its meaning.
- The book: `LeaderPages.leads` already gives every office holder the Leader's Desk. `MemberPages.whoDecides` lists
  every holder. `StandingText` words office lines by holder count ("{holders}, any one of them as Lord").

## What to build

1. **Operator commands** (players never choose this; `leadersChangeGovernance` stays false):
   - `/charter admin faction leader <faction> <player>` keeps its meaning: one sole Lord. It sets the office back to 1
     seat, `ALONE`, and seats only that player. Say so in its help text.
   - `/charter admin faction coleader <faction> <player>` (name it to match the existing tree): makes the office 2
     seats, `ALONE`, keeps the sitting Lord, and seats this player as the second. The player joins the kingdom if they
     are in none, as `AppointLeader` does; a player in another kingdom is refused. Refuse a third Lord with a clear
     line. If the seat is empty it simply seats them.
   - `/charter admin faction unlord <faction> <player>` (or `leader remove`): removes one Lord, keeps the other, and
     sets the office back to 1 seat when one remains. Removing the last Lord leaves the seat empty, as today.
   - Implement these as operator-only actions in `core` (like `AppointLeader`), not as raw state edits in the command,
     so history, the Chronicle and Discord see them. Record `OfficeFilled` and `OfficeVacated` as the existing code does.
   - The same three as buttons in the operator menu (`fabric/.../book/AdminPages.java`), following its pattern
     (`AdminCommandRunner`, dropdowns for names where possible).
2. **Words players see.** Two holders of the Lord office deciding `ALONE` read as two Lords everywhere:
   - Home form line: "A lordship with two Lords. You are Lord." (or "...You are a member.")
   - Who decides: "Two Lords, either may act: Alice and Bob."
   - The Freedoms and Join pages and the hover tips (`book.tip.form_*`): a new tip for two Lords.
   - `StandingText` holder lines: "Alice or Bob, as Lord".
   - The newcomer join list ("Azrak (12 members, a lordship)") and the faction page.
   - Chronicle and notice templates that name "the Lord": check them and make them right for two.
   Prefer one place that decides the label: either a new `Standing.Form.CO_LORDSHIP` (seats 2, mode `ALONE`, office is
   the Lord office) or a display helper that every page calls. Pick one, say why, and do not let pages each work it out.
   A council made by a Commune or by `SetSeats` (other seat counts, or `TOGETHER`) must still read as a council.
3. **Rules that must hold, each with a test:**
   - Either Lord can do every routine Lord action alone (invite, admit, grant, spend up to `lordSpendLimitMarks`,
     declare war, move the Nexus, open the Leader's Desk).
   - Neither Lord can remove, replace or outvote the other alone, change the seats or the mode, or rename the faction
     alone. Such an attempt becomes a proposal both must accept, or is refused; check what `ActAs` does and make the
     book say it plainly ("Bob must agree").
   - One Lord leaving the kingdom, being banished, or being absent past `absentLordDays` empties only their seat. Decide
     whether the office then drops back to 1 seat (recommended: yes, the remaining Lord rules alone, and no election
     opens while a Lord still sits) and make the absent-Lord loop and `Leave` agree.
   - `AppointLeader` on a two Lord kingdom leaves exactly one Lord.
   - Members still cannot vote either Lord out (as today for one Lord).
4. **The API.** Keep `KingdomView.lordOf` working (the first seated Lord) and add `lordsOf` returning all of them.
   Update callers that should know about both (for example payments to a kingdom's ruler, or a PD party check). Do not
   break `PaymentPortTest`.
5. **Docs.** Update `docs/KINGDOM_SERVER.md` (a "Two Lords" section under "Locks on leaders": the commands, what each
   Lord can do alone, what needs both) and the command list in `charter/README.md` if it lists admin commands.

## Out of scope

- Players choosing two Lords themselves, elections for a second Lord, more than two Lords, `TOGETHER` co-rule.
- Any other mod's behaviour beyond adapting a `lordOf` caller.
- Deploying. Do not deploy, restart or touch any server. Deployment is the owner's call through the
  `deploy-shipped-server` skill.

## Tests and checks

- Unit tests in `charter/core/src/test` for the new actions and the rules in item 3 (follow `InvariantTest` and the
  existing action tests). Gametests in `charter/fabric/src/gametest` for the commands and the book wording (the book
  records the last dialog in `BookDialogs.LAST`; see `BookGameTest`).
- `cd charter && ./gradlew build`. Note: an earlier change on this branch (book travel buttons, Leader's Desk) was
  written without a working Gradle; fix any compile error the first build finds there too.
- `grep -rn -e $'—' -e ' -- ' --include='*.java' --include='*.md' --include='*.json' charter` over what you wrote.
- Check each file's line endings and BOM before editing and keep them.

## Report

Per item above: done, partly done, or skipped with the reason. The exact operator commands to run on the live server
to give one kingdom two Lords. Every `lordOf` caller you found and what you did with it. Anything only a live test can
confirm.

Work on branch `claude/relaxed-pascal-us1zqd` (or a branch the owner names). Commit in small steps. Do not open a pull
request unless asked.
