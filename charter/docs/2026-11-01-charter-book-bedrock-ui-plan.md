# Charter book: Bedrock first UI plan

Audience: whoever builds it next. Written 2026-11-01 after a read of every page in
`charter/fabric/src/main/java/charter/mc/book/`. Revised the same day to aim for the best possible Bedrock
experience; every rule here also makes the book better on Java.

## The player this is for

An Xbox player on Bedrock, joining through Geyser and Floodgate, holding a controller. Design for all of this at once:

- **No commands.** No slash commands, and typing anything means an on-screen keyboard. Treat typing as a failure.
- **No hover.** Tooltips and hover text do not exist on a controller.
- **No clickable chat.** Every `Chat.act` and `Chat.run` link is dead to them.
- **No chat, mostly.** Chat is hidden by default on console. Anything said only in chat is lost.
- **Every press costs.** Each screen is press, wait, read, press. Count presses; fewer always wins.
- **B is Back.** B (and Esc on Java) must always go one step back, never lose work, never do something.
- **Small, wide screens.** A TV seen from a couch, sometimes split screen. Short labels, short bodies, big targets.
- **The book is the only door.** It is always in the inventory, exactly one of it, and easy to reach.

Half the server plays like this. If a screen works for them it works for Java players too, who simply get there faster.

## Done already (Phase 1, code)

| Item | What changed |
|---|---|
| One book only | `BookMod.removeExtras` deletes every book after the first, once a second for everyone online, and on join and respawn. It runs even when `replaceLostBook` is off. |
| Death drop | `BookMod.vanish` deletes the book just before death loot drops (`ALLOW_DEATH`), so it never lies on the ground. Respawn hands out a fresh one. |
| Travel at the top | Home (bed), Keep, Outpost, Spawn, Hub are the first buttons of the member Home; a newcomer gets Home, Spawn and Hub. |
| Leader's Desk | One button on the member Home, shown only to officers (`LeaderPages.leads`): requests, invite, grant, treasury, who decides, votes, war, move the Nexus. |
| Borders | A button on Home replaces `/charter borders`. |
| Accept an invitation | A button on the newcomer Home. Before it, an invite could only be accepted through a chat link. |

Phase 1 was written without a working Gradle in the sandbox. The first job of the next phase is `./gradlew build` and
fixing whatever it finds.

## The big decision: native Bedrock forms

Today every screen is a Java dialog (`net.minecraft.server.dialog`), and Geyser translates it for Bedrock. That
works, but a translation is the floor, not the ceiling: no button icons, body text flattened into one block, and
every press travels as a typed `/charter act <token>` command through Geyser.

The shipped server runs Floodgate in the same JVM, and Floodgate exposes Bedrock's own forms (the Cumulus API:
`SimpleForm`, `ModalForm`, `CustomForm`, sent with `FloodgateApi.getInstance().sendForm(uuid, form)`).

**Recommendation: one screen model, two renderers.**

```
BookScreen (title, status lines, buttons with icon, inputs, exit)
   ├── JavaDialogRenderer    -> the vanilla dialog, as today (Java players, and Bedrock when Floodgate is absent)
   └── BedrockFormRenderer   -> a Floodgate Cumulus form (Bedrock players when Floodgate is present)
```

- Pages stop building `Dialog` objects and build a `BookScreen` instead. `BookDialogs.show` picks the renderer:
  Floodgate loaded and `FloodgateApi.isFloodgatePlayer(uuid)` true means a native form; anything else means a dialog.
- Floodgate is a soft dependency: `compileOnly` in Gradle, and every call behind
  `FabricLoader.getInstance().isModLoaded("floodgate")` in one adapter class, so Charter still runs without it and its
  tests never need it.
- Mapping:
  - A list of buttons becomes a `SimpleForm`. Each button can carry an icon (`FormImage.Type.PATH`, a vanilla
    texture such as `textures/items/bed_red` or `textures/items/compass_item`).
  - A confirmation becomes a `ModalForm` (exactly two buttons, which is all a confirm needs).
  - A form with inputs becomes a `CustomForm` with a dropdown, slider, step slider or toggle.
  - Closing the form (B, or the X) runs the screen's exit action. Floodgate reports it as a closed response.
- The response arrives as a Java callback in the server, so the button runs the page's `Consumer<ServerPlayer>`
  directly: no token command, no chance of a stale token, no command echo. The callback must still re-check
  everything (live state, the player's powers), exactly as the token path does today.
- `BookDialogs.LAST` keeps recording the `BookScreen`, so gametests stay renderer-agnostic.
- A knob `bedrockNativeForms` (default true) turns the native path off without a release, in case a Floodgate
  update breaks it.

Why it is worth it: icons on every button turn a wall of words into something a player reads at a glance from the
couch; a `ModalForm` makes a confirm unmistakable; sliders replace typed numbers; and the B button is native. Java
gains too: the screen model forces every page into the same small shape, and it is easy to test.

Fallback if the native path is rejected: keep dialogs, apply every other rule below. They still hold.

## Rules for every screen

Write these into `BookScreen` so a page cannot break them.

1. **At most 8 buttons** on a screen, the exit not counted. More than 8 items means paging (6 per page, then More).
2. **Labels at most 24 characters.** Verb first: "Go to your keep", "Admit Bob". No coordinates in a label.
3. **Body at most 3 short lines.** One status line is better. Detail goes behind a button.
4. **Things that need the player come first,** right under travel. A screen with nothing to do says so in one line.
5. **No hover, anywhere.** If a word needs explaining, explain it in the body or behind "What does this mean?".
6. **No typing** except a free text name, and there is no such field for players today. Players are chosen from
   buttons (8 or fewer) or a dropdown (more); numbers come from presets or a slider.
7. **Exit is always Back,** except on Home where it is Close. B runs it. It never changes anything.
8. **After a press, land on the page it came from** with the result on top: green for done, red for refused. Never
   leave a player on a blank screen.
9. **Confirm only what cannot be undone** (leave, declare war, reject, break away, move the Nexus). The confirm
   says the consequence in one line and its button is the verb: "Leave Azrak", not "Yes".
10. **Grey, do not hide, a temporary no.** "Go to your keep (12s)" during a cooldown. Hide what can never apply
    (no outpost button for a faction with no outpost).
11. **Icons on Bedrock buttons,** one per button, chosen from vanilla textures so no resource pack is needed.

## Feedback outside the book

Chat is the worst channel for a console player. Use, in this order:

- **Action bar** for short results and prompts ("Hold still: 3", "You are home").
- **Boss bar** for anything that counts down: teleport warm-up, a war capture timer, a vote closing. Bedrock draws
  boss bars well and they need no reading time.
- **Title and subtitle** only for rare, big moments (you joined a faction, war declared on you).
- **Sounds** as a cue: a soft chime when something needs you, the UI click on a result.
- **The book itself** (below).
- **Chat** last, and only as a copy for Java players, with its clickable links kept for them.

## The book item

- **Hotbar slot.** Give the book into the last hotbar slot (9) when it is free, otherwise the first free hotbar
  slot, otherwise the inventory. A book in the hotbar is one press away.
- **It tells you when you are needed.** When something waits for the player (a vote, a join request, an
  invitation, a war on your outpost), set the glint (`ENCHANTMENT_GLINT_OVERRIDE`) and add the count to the name:
  "Charter (2 need you)". Lore lists what: "1 vote, 1 request". Refresh on a slow tick, only for players whose
  counts changed, and only touch the one stack.
- **Opens from anywhere.** Use in the main hand opens it today, and on a block that has no use of its own. Keep that.
- Confirm on a real Bedrock client that the paper drawn as a writable book reads as a book in the hotbar, and that
  the glint shows.

## Information architecture

Depth at most two from Home. Every leaf has Back to its parent.

### Member Home

Body: one line. "Azrak, a lordship. You are a member." (Bedrock icon on the form title is not possible; the body line
carries it.)

| # | Button | Icon (Bedrock) | Shown when |
|---|---|---|---|
| 1 | Go home (your bed) | `textures/items/bed_red` | always (greyed with reason if no bed) |
| 2 | Go to your keep | `textures/blocks/lodestone_top` | the nation has a keep |
| 3 | Go to your outpost | `textures/items/banner_pattern` | the faction has an outpost |
| 4 | Go to spawn | `textures/items/compass_item` | always |
| 5 | Go to the hub | `textures/items/ender_pearl` | a hub exists |
| 6 | Needs you (N) | `textures/items/bell` or `book_writable` | N above zero |
| 7 | Leader's Desk | `textures/items/gold_ingot` | officers |
| 8 | Where I stand | `textures/items/book_normal` | always |
| 9 | My faction | `textures/items/banner` | always |
| 10 | More | `textures/items/paper` | always |

That is more than 8 for an officer with everything. Fold rule: travel stays as one block at the top (the owner asked
for it), and when the total passes 8, "My faction" moves under More. Check the final count in a gametest.

**More:** War, Chronicle, Calendar, Show or hide borders, What do these words mean?, Leave or break away (last).

### Needs you

One list, newest first, each a button straight to the thing: "Vote: raise taxes", "Bob asks to join", "Invite
from Azrak", "War on your outpost". Pressing one opens it; acting on it returns here; when the list is empty it says
"Nothing needs you." and Back.

### Where I stand

Replaces Rights, Freedoms and Situation (three read only pages, three round trips). Three short sections with
headings: What you may do, Who decides for you, Your status (time in faction, held, banished, owed, respawn delay).
Each section capped at 4 lines with "See all" behind a button when longer.

### My faction

Read only summary: members (first 12, "and N more" opens a paged list), empty offices, treasury, keep, elections
running. Officers see one extra button: Leader's Desk.

### Leader's Desk

| Button | What it opens |
|---|---|
| Requests to join (N) | One button per applicant; each opens Admit or Turn away (ModalForm). |
| Invite a player | Online players with no faction as buttons (8 or fewer) or a dropdown. |
| Give a power | Step 1: power group buttons (Members, Money, Land and war, Justice). Step 2: the power. Step 3: who. |
| Treasury | Balance, then Take: presets 1, 10, 64 and a slider up to the balance. |
| Justice | Sentence, bail, cells, safe deposit, pardon: each a short flow of buttons, no typing. |
| Who decides | As today. |
| War | Targets first (named by direction and distance: "Faeloria outpost, 300 north"), rules behind How war works. |
| Move the Nexus here | ModalForm; the body says whether you stand inside the keep right now. |

### Newcomer Home

Body: one line ("You belong to no kingdom." or "Waiting for Azrak to answer.").

Buttons: travel (home, spawn, hub), then any invitations ("Join Azrak (invited)"), then Choose a kingdom, Become a
mercenary, Found your own (only if founding is on), More (Realm Chronicle, Calendar, words).

**Choose a kingdom:** one button per kingdom, labelled with its race ("Azrak, the Dwarves"); the kingdom page says in
three lines what joining gives (race, keep, who rules) and has Ask to join.

**Mercenary:** race buttons first, one line of rules, then a ModalForm: "Become a Fae mercenary? You cannot choose
again." with buttons "Become a Fae" and "Back".

### Votes

The vote page: the question, time left, your ballot. Buttons Yes, No, Abstain, or one per candidate (paged). After
casting, back to Needs you with "Your vote is cast." in green.

### Chronicle

Opens as a written book today (`BookReading`, a packet trick). Whether Geyser shows it on Bedrock is unverified.
Build the Chronicle as paged screens (6 entries per page, Next and Previous) for Bedrock players regardless; Java
keeps the written book, which reads better there.

### Map

Replaces `/charter map` for the book: a 9 by 9 grid drawn as text rows in the body (one coloured glyph per chunk,
you in the middle), with North, South, East, West buttons. Check it fits a console screen; if not, 7 by 7.

## Travel, done right

- A press closes the screen and shows a boss bar: "Going to your keep. Hold still." filling over the warm-up.
  Moving or taking damage empties it with "Moved, so you stay." on the action bar.
- Arrival: action bar line and a sound.
- Before the press: the label already says why it cannot work ("Go to your outpost (no food)") through a public
  `Teleports.refusal(player, kind)`; pressing a greyed button shows the full reason on top of Home.
- Outpost costs food: say it on the button only when it matters ("Go to your outpost (4 food)").

## Text

All player text follows the house dash rule and the `player-text` skill's spirit: few words, the next action, no
jargon. Specific cuts already found:

- Delete every "Hover over..." sentence (`book.rights.intro`, `book.freedoms.intro`, `book.home.form_hint`,
  `book.tip.hint`).
- `book.borders.on` style lines that say "Say /charter ..." must not reach a book screen; book screens get their own
  keys without commands in them.
- `BookDialogs.withNotice` paints every notice red, including successes. Replace with a result that knows ok or
  refused.

## Still typed only

After this plan nothing a player needs is typed only. Left as commands on purpose:

- `/charter found <name> <preset>`: off on the live server (`playerFounding` false). If it is turned on, founding
  needs a name, which is the one place a Bedrock keyboard is acceptable.
- `/charter discord link|unlink|notify`: add to More only if Bedrock players use Discord linking.
- Operator tools (`/charter admin ...`): operators play on Java; `AdminPages` already covers them.

## Build order

1. **Phase 1, done:** one book, death vanish, travel, Leader's Desk, borders, accept invite.
2. **Phase 2, the frame:** `BookScreen` model and the rules enforced in it; `JavaDialogRenderer` producing exactly
   today's dialogs; green and red results; port every page to `BookScreen` without changing what it shows. Gametests
   still pass. This is the risky refactor; do it alone.
3. **Phase 3, Bedrock native:** `BedrockFormRenderer` behind the soft Floodgate dependency and the knob; icons; B
   runs exit; callbacks re-check state.
4. **Phase 4, the new layout:** Home and Newcomer Home as specified, Needs you, Where I stand, More, My faction,
   Leader's Desk complete (Justice, grouped powers, treasury presets), confirms as verbs, paging.
5. **Phase 5, outside the book:** boss bar travel, action bar results, book glint and counts, hotbar slot, sounds.
6. **Phase 6, needs a live Bedrock client:** Chronicle and map as paged screens tuned on a real console; icon choices
   checked on screen.

## How to check

- **Gametests** (`BookGameTest`): every screen has at most 8 buttons and an exit; labels at most 24 characters; Home
  order (travel first, Needs you next); one-book rule; death vanish; leader gating; a refused press lands on the same
  page with a red line, a successful one with a green line.
- **Press budget,** measured on a real controller from the book in hand:

  | Task | Presses, at most |
  |---|---|
  | Go to your keep | 2 (use book, press) |
  | Vote on something waiting | 3 |
  | Accept an invitation | 2 |
  | Admit a player (leader) | 4 |
  | Take Marks from the treasury (leader) | 4 |

- **Live,** on an Xbox or a Bedrock client through Geyser: every screen with a controller only; B always backs out;
  nothing needs a hover, a chat click, or the keyboard; icons show; the boss bar shows during travel.
- Deploy only through the `deploy-shipped-server` skill and its approvals. This work does not authorize a deploy.

## Risks and open questions

- **Floodgate API version.** Pin the Cumulus and Floodgate API versions that match the Geyser and Floodgate on the
  server; check `docs/KINGDOM_SERVER.md` and the server's mods folder before choosing.
- **Geyser dialog fidelity.** If native forms are turned off, Bedrock sees Geyser's translation. Check on a real
  client how it renders body text, long lists and inputs, and that Esc maps to the exit.
- **Texture paths.** Bedrock texture names differ from Java ids (`bed_red`, `compass_item`). Each icon must be checked
  once on a client; a wrong path shows a blank square, not an error.
- **Glint on paper.** Verify Geyser carries `ENCHANTMENT_GLINT_OVERRIDE` to Bedrock; if not, the name count alone
  carries the signal.
