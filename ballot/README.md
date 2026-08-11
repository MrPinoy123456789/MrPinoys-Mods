# Ballot

Server-side voting for Minecraft Fabric 26.2. Vanilla clients need nothing installed.

**All five milestones.** The model, the chat side, ballot boxes, plot signs and
custom head skins.

## Layout

    core/     The poll model. No Minecraft, no Gson, no logging. 81 tests.
    fabric/   Persistence, commands, chat screens.

The split is enforced by the build: `core` has nothing but the JDK on its classpath, so
a stray Minecraft import fails compilation rather than quietly coupling the two.

## Run the model tests without Gradle

    javac -d build $(find core/src -name '*.java')
    java -cp build ballot.PollTest

Expect `148 passed, 0 failed`.

## Build

Needs JDK 25. Wrapper included.

    ./gradlew build

Jar at `fabric/build/libs/ballot-0.1.0.jar`.

## Try it

    /ballot new Art_show plots     or omit "plots" and click an answer
    /ballot describe A monument to something that killed you.
    /ballot entry add Plot 1
    /ballot entry add Plot 2
    /ballot deadline 7             days from now
    /ballot preview                everything restated before the point of no return
    /ballot open
    /ballot claim 1                as a player
    /ballot name The Drowning Man
    /ballot voting
    /ballot vote 2
    /ballot close                  or let the deadline do it

`/ballot` lists everything running, with clickable names and whether *you* have acted.
`/ballot setup` is the organiser checklist. `/ballot settings` toggles the switches.
`/ballot results` reprints a finished vote.

## Commands

Four. Everything else is walking up to a block.

| Command | Who | What |
|---|---|---|
| `/ballot` | all | The vote's page |
| `/ballot wand` | op | The organiser stick |
| `/ballot create poll` | op | Noticeboard + ballot box |
| `/ballot create buildoff` | op | Noticeboard (build competition) |

`/ballot _ ...` holds every button target. They cannot be hidden — Brigadier suggests
whatever is runnable, and buttons must be runnable — so they sit under one opaque node.

## Two kinds, chosen at creation

**Poll** — you write the options, everyone picks one. One ballot box holds every option.

**Buildoff** (build competition) — players claim plots and build, then vote. One
ballot box per plot, plus a sign to claim at.

The kind comes from which command created the vote, not from a setting. There is
nothing to leave unset and no way to open a buildoff that is secretly a poll.

## The pieces

Everything binds the moment it is placed. There is no wand step during setup.

| Item | Block | Does |
|---|---|---|
| Noticeboard | Lectern | Right-click: the vote. Wand: settings |
| Ballot box | Jukebox | A poll's single voting box, all options inside |
| Plot ballot box | Jukebox | Placing it *creates the plot*. Vote for that build here |
| Plot sign | Sign | Claim, name and release. Binds to the nearest plot box |

Plot boxes and signs are handed over as a pair, because they are always placed as one.
Signs write themselves and should never be edited by hand.

Breaking a bound block unbinds it. Before voting, breaking a plot box removes its plot
— and if somebody had claimed it, both the breaker and the owner are told, because
silently deleting a claimed plot is how organisers lose trust. Once voting starts the
plot survives (votes exist for it) and only the binding goes.

## Files

`config/ballot/polls/` — one file per poll, and **the filename is the id**. Underscores
render as spaces, so `Art_show.json` is shown to players as "Art show". There is no
index file, because an index is a thing that can disagree with the folder: adding a
poll means adding a file, removing one means deleting it.

Each file holds current state *and* the full event history. Keeping both means boot is
a plain read rather than a replay, and no snapshot machinery is needed. The history is
there because votes change — when the prize is a permanent town monument, somebody will
eventually dispute the result, and "who flipped on the last day" is worth answering.

`config/ballot/archive.json` — closed polls, appended whole. Never read at runtime.

## Notes

Admin commands use the real op check: `PlayerList.isOp(player.nameAndId())`. 26.2
replaced integer permission levels with `PermissionSet`, so the usual
`hasPermission(2)` no longer compiles.

Rejections are return values, not exceptions. A player clicking a ballot box they have
already used is completely ordinary, and the message they see is the point.
