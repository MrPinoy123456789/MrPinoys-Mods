# Charter book: UI plan for a controller player on Bedrock

Audience: whoever builds it next. Written 2026-11-01 after a read of every page in `charter/fabric/src/main/java/charter/mc/book/`.

## The player this is for

An Xbox player on Bedrock (through Geyser and Floodgate). Assume all of this at once:

- No slash commands, no typing a command, and typing anything at all means an on-screen keyboard. Avoid it.
- No hover. Tooltips and hover text do not exist on a controller.
- No clickable chat. Every `Chat.act` and `Chat.run` link in chat is dead to them.
- Chat is hidden by default on console. Anything important said only in chat is lost.
- Every screen is a round trip: press, wait, read, press Back. Fewer screens and fewer buttons is better.
- B closes or goes back. The book already makes the exit button run on Esc, so B works. Keep that rule.
- The book item is the only door. It must always be in the inventory, and exactly one of it.

## Done in this change (code)

| Item | What changed |
|---|---|
| One book only | `BookMod.removeExtras` deletes every book after the first, once a second for everyone online, and on join and respawn. It runs even when `replaceLostBook` is off. A looted or old death-drop book is deleted from whoever already has one. |
| Death drop | `BookMod.vanish` deletes the book just before death loot drops (`ALLOW_DEATH`), so it never lies on the ground. Respawn hands out a fresh one. |
| Travel at the top | Home (bed), Keep, Outpost, Spawn, Hub are the first buttons of the member Home and of the newcomer Home (a newcomer has no keep or outpost). A refusal shows above the same page; a start closes the screen so the player can hold still. |
| Leader's Desk | One button on the member Home, shown only to officers (`LeaderPages.leads`). Holds: requests to join, invite a player, give a power, take Marks from the treasury, who decides, votes and elections, war, move the Nexus. Names are picked from dropdowns, never typed. |
| Borders | A button on Home replaces `/charter borders`. |
| Accept an invitation | A button on the newcomer Home. Before this, an invite could only be accepted through a chat link, so a Bedrock player could never join by invitation. |

## Still typed only (not in the book yet)

Everything a player or leader can still only do with a slash command:

- Justice for leaders: `/charter justice sentence`, `bail`, `cell mark|remove|list`, `safe deposit`, and the pardon, confine, bond and fine links (all chat links today). Plan: a Justice section on the Leader's Desk, same dropdown pattern.
- `/charter map`: draws in chat. Plan: a map dialog (see Phase 3).
- `/charter vote`, `/charter vault`: covered by Situation and the new treasury screen; the commands can stay.
- `/charter discord link|unlink|notify`: needs a screen if Discord matters to Bedrock players.
- `/charter found <name> <preset>`: the book teaches founding but cannot do it. Off while `playerFounding` is false on the live server, so low priority.
- Race-weapon or other-mod commands: out of scope here.

## Findings, page by page

Priority: P1 broken or confusing for a controller player now, P2 clumsy, P3 polish.

### All pages

1. **P1 Hover is load bearing.** The form line on Home, "Hover over a name" on Rights, Freedoms and the faction page, and every `book.tip.*` explain jargon only on hover. Console players never see it. Fix: a "What do these words mean?" page (Glossary) reached by one button, and a one line plain explanation written into the page where it matters. Delete "Hover over..." sentences.
2. **P1 Success looks like failure.** `BookDialogs.withNotice` paints every notice red, including "You invited Bob". Fix: a `Notice(ok, text)` so success is green and refusal is red. Callers pass a string today, so add `okNotice` helpers and migrate page by page.
3. **P1 Chat links.** `Pages.java` and `CharterServer` send join request, vote and invite prompts as clickable chat lines. Fix: on every such event also (a) set the book's lore to show a count ("2 waiting"), (b) send an action bar line "Open your Charter book", and (c) keep the chat line for Java players.
4. **P2 Too much text above the buttons.** Home shows four paragraphs (form line, hint, seasons, days) before any button. On a console screen the buttons start below the fold. Fix: one status line on Home, calendar behind a Calendar button.
5. **P2 Long lists.** Joining lists up to 20 factions, votes up to 20 candidates, a Commune's "who decides" up to 9 forms. Fix: pages of 6 with Next and Previous, and the selected item first.
6. **P2 Labels that say nothing.** "Yes" and "No" on confirmations (Reject, Break away, Leave). Fix: verbs, "Turn Bob away" and "Keep Bob waiting"; "Leave Azrak" and "Stay".
7. **P3 Titles repeat the body.** Several pages open with a title and a body line that says the same thing. Cut the body line.

### Home (member)

- Order now: Travel, Leader's Desk (officers), Rights, Freedoms, Situation, faction page, War, Chronicle, Borders.
- P2 Rights, Freedoms and Situation are three read only pages. Merge into one "Where I stand" page with three headed sections (My powers, What others do for me, My status and votes). Saves two round trips.
- P2 The faction page button is the faction's own name; players do not know that it is a button. Label it "My faction: Azrak".
- P2 Move Leave out of Freedoms (it sits next to a joke "Break away" label). Put Leave and Break away on one "Leave or break away" page at the bottom of Home, clearly last, with the cooldown said before the confirm.

### Newcomer home

- Now: Travel, invitation buttons, Join, Mercenary, Found (if allowed), Realm Chronicle.
- P2 The body shows the calendar too. Same cut as Home.
- P2 Join lists "Azrak (12 members, a lordship)". The form of rule means little; lead with the race and one plain sentence of what joining gives.
- P2 Found your own is a checklist with no buttons except Back. Where founding is allowed, add "Check again" so a player does not leave and reopen to refresh it.

### Faction page

- P1 It mixes information (members, treasury, keep) with leader actions (admit, turn away, elect, propose). The actions now live on the Leader's Desk; strip the buttons from the faction page for non officers and keep it a read only summary with one "Open Leader's Desk" button for officers.
- P2 The member list is one long comma run. Cap at 12 names and add "and N more", with a members page behind a button.

### Votes and elections

- P2 Reaching a vote needs Situation then the vote. Show a count on Home ("Votes waiting: 2") and put it near the top when above zero.
- P3 A closed vote's page has only Back. Fine.

### War

- P2 The page explains the rules in three long paragraphs before the target list. Put targets first, the rules behind "How war works".
- P2 Target buttons read "Name the outpost of X at -240, 880". Coordinates mean little on a console. Add distance and direction ("about 300 blocks north") and keep coordinates secondary.
- P3 Confirm screen is four paragraphs. Keep the three facts that matter: how long before capture opens, how long to hold the chunk, what a lapse costs.

### Chronicle

- P1 Opens a real written book through a packet (`BookReading`). Whether Geyser translates `ClientboundOpenBookPacket` for Bedrock is unverified. Test on a real Xbox. If it fails: render the Chronicle as dialog pages, six entries per page, with Next and Previous.
- P2 Realm Chronicle is on the newcomer Home only; members cannot reach it. Add it to the Chronicle page as a choice between faction and realm.

### Mercenary

- P2 Three paragraphs, then one button per race. Put the buttons first and the rules in one short line, then a confirm that names the race ("You cannot choose again").

### Break away

- P2 Eight paragraphs before the single button. A controller player will not read them. Reduce to: who comes with you, what it costs, how long, then the button. Move the rest behind "More".

### Travel feedback

- P1 The hold still message and the "you are home" line go to chat. Send both to the action bar as well, with a visible countdown.
- P2 Buttons fail after the press ("no outpost"). Show the reason before the press: grey the label ("Go to your outpost (still being raised)") by asking `Teleports` for the refusal when building the page. Needs a public `Teleports.refusal(player, kind)`.
- P2 `/outpost` costs food from the outpost store. Say so on the button ("costs 4 food") so it is not a surprise.

### Leader's Desk (new, to refine)

- P2 Grant lists 15 powers by their internal names. Group them (Members, Money, Land and war, Justice) and use the same plain words as the Rights page.
- P2 Invite lists only online players with no faction. Add "Invite someone who is offline" only if a dropdown of known names is acceptable (no typing).
- P3 Move the Nexus has no preview. Add a line saying whether the player is standing inside the keep.

### The item

- P2 Name and lore are fixed. Make the lore dynamic: "Votes: 2. Requests: 1." (updated on a slow tick for players whose counts changed). It is the only badge a console player can see without opening anything.
- P3 The book is paper drawn as a writable book. Bedrock shows the Java model name through the Geyser mapping; confirm it reads as a book in the hotbar.

## Build order

1. Phase 1, done: one book, travel, Leader's Desk, borders, accept invite.
2. Phase 2, small and safe: green and red notices; verb labels on confirms; remove "Hover over" text; action bar for travel; dynamic lore with counts; cut the Home body to one line.
3. Phase 3, medium: merge Rights, Freedoms and Situation into "Where I stand"; paging helper (`BookDialogs.paged`) for the long lists; Leave and Break away page; Justice section on the Leader's Desk; map dialog.
4. Phase 4, needs a live Bedrock check: Chronicle as dialog pages if the written book fails.

## How to check each phase

- Gametests in `BookGameTest`: button order of each Home, one-book rule, every page ends with an exit button (rule already enforced by `BookDialogs.list`).
- Live, on an Xbox or Bedrock client through Geyser: open every screen with a controller only; count presses to reach Home, a teleport, an invite and a vote; confirm Esc/B always goes back; confirm nothing needed a hover or a chat click.
- Deploy only through the `deploy-shipped-server` skill and its approvals. This work does not authorize a deploy.
