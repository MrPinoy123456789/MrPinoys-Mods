# Vanilla Dialogs — implementation spec

Adopt Minecraft 26.2's native dialog system in **Ballot** and **QuizEngine**.

Two agents, one mod each. The mods are separate Gradle builds with no shared module, so
there is **zero file overlap** between the two work streams. Do not create a shared
module and do not touch the other mod's tree.

| Agent | Mod | Tree |
|---|---|---|
| A | Ballot | `ballot/fabric/` |
| B | QuizEngine | `quizengine/fabric/` |

Each agent implements its own copy of the plumbing in Part 1. That duplication is
deliberate: each mod ships as one self-contained jar, and a shared module would couple
two independently releasable things to save about 120 lines.

---

## Part 0 — Verified API reference

**Read this before writing any code, and prefer it over anything you find online.**

Every signature below was read out of the real jar at
`~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar` with `javap`. Minecraft 26.2
ships unobfuscated, so these are the actual names. If you want to confirm something not
listed here, run `javap` against that jar rather than guessing or searching.

> **Status: QuizEngine (Part 3) is implemented and building.** See
> [quizengine/DIALOGS.md](quizengine/DIALOGS.md). Two corrections found during that work
> are folded into this reference below, marked **[confirmed in build]**. The
> `CustomAll` payload shape in "Receiving the submission" is now verified rather than
> assumed.

### Three ways 26.2 differs from the 1.21.6 docs you will find online

These will burn you if you follow a wiki:

1. **There is no `simple_input_form` or `multi_action_input_form` dialog type.** Those
   were consolidated. In 26.2, inputs live on `CommonDialogData.inputs()` and work with
   *any* dialog type. A one-field form is a `NoticeDialog` whose common data carries one
   `Input`. Do not go looking for a `SimpleInputForm` class — it does not exist.
2. **`Identifier`, not `ResourceLocation`.** The class is `net.minecraft.resources.Identifier`.
3. **Fabric API 0.156.0 has no event for custom click actions.** Verified by scanning the
   API jar — nothing matches. You must mixin. See Part 1.
4. **[confirmed in build] `ServerPlayer.getServer()` does not exist.** It compiles in
   your head and nowhere else. In a mixin on `ServerCommonPacketListenerImpl`, shadow the
   server it already holds:
   `@Shadow @Final protected MinecraftServer server;`
5. **[confirmed in build] `CompoundTag` getters return `Optional`.** `getString(k)` is
   `Optional<String>`. Use the `getStringOr(k, default)` / `getIntOr(k, default)` /
   `getBooleanOr(k, default)` variants — they are exactly the defensive parsing Part 1.4
   asks for.

### Dialog types (`net.minecraft.server.dialog`)

```java
interface Dialog {
    CommonDialogData common();
    MapCodec<? extends Dialog> codec();
    Optional<Action> onCancel();
}

record CommonDialogData(
    Component title,
    Optional<Component> externalTitle,
    boolean canCloseWithEscape,
    boolean pause,
    DialogAction afterAction,      // CLOSE | NONE | WAIT_FOR_RESPONSE
    List<DialogBody> body,
    List<Input> inputs)

record NoticeDialog(CommonDialogData common, ActionButton action)
record ConfirmationDialog(CommonDialogData common, ActionButton yesButton, ActionButton noButton)
record MultiActionDialog(CommonDialogData common, List<ActionButton> actions,
                         Optional<ActionButton> exitAction, int columns)
record DialogListDialog(...)   // javap it if you use it

record ActionButton(CommonButtonData button, Optional<Action> action)
record CommonButtonData(Component label, Optional<Component> tooltip, int width)
// also: CommonButtonData(Component label, int width)
// Dialogs.BIG_BUTTON_WIDTH is a sane constant; CommonButtonData.DEFAULT_WIDTH exists too
```

### Body (`net.minecraft.server.dialog.body`)

```java
record PlainMessage(Component contents, int width)   // PlainMessage.DEFAULT_WIDTH
record ItemBody(...)                                 // javap if needed
```

### Inputs (`net.minecraft.server.dialog.input`)

Each is wrapped in `new Input(String key, InputControl control)` — the `key` is what
identifies the value in the submitted payload.

```java
record TextInput(int width, Component label, boolean labelVisible,
                 String initial, int maxLength,
                 Optional<TextInput.MultilineOptions> multiline)
record TextInput.MultilineOptions(Optional<Integer> maxLines, Optional<Integer> height)

record BooleanInput(Component label, boolean initial, String onTrue, String onFalse)
    // onTrue/onFalse are the literal strings submitted. Use "true"/"false".

record SingleOptionInput(int width, List<SingleOptionInput.Entry> entries,
                         Component label, boolean labelVisible)
record SingleOptionInput.Entry(String id, Optional<Component> display, boolean initial)

record NumberRangeInput(int width, Component label, String labelFormat,
                        NumberRangeInput.RangeInfo rangeInfo)
record NumberRangeInput.RangeInfo(float start, float end,
                                  Optional<Float> initial, Optional<Float> step)
```

### Actions (`net.minecraft.server.dialog.action`)

```java
interface Action { Optional<ClickEvent> createAction(Map<String, Action.ValueGetter>); }

record CustomAll(Identifier id, Optional<CompoundTag> additions)   // "dynamic/custom"
record CommandTemplate(ParsedTemplate template)                    // "dynamic/run_command"
record StaticAction(ClickEvent value)                              // wraps a plain ClickEvent
```

`CustomAll` is the one you want for nearly everything. It submits **every input value**
plus your `additions` as one `CompoundTag`, straight to the server, with no command
involved. `additions` is where you put context the player did not type — a poll key, a
round id, an entry number.

### Showing and clearing

```java
// Push to a player:
player.connection.send(new ClientboundShowDialogPacket(Holder.direct(dialog)));
new ClientboundClearDialogPacket()

// Or attach to a chat component — no command, no packet code:
Component.literal("[ Answer ]").withStyle(s -> s
        .withClickEvent(new ClickEvent.ShowDialog(Holder.direct(dialog))));
```

`Holder.direct(T)` means dialogs are built at runtime from live state. **No datapack JSON
and no registry entries are needed anywhere in this spec.**

`ClickEvent.ShowDialog` is the preferred entry point throughout — see the "never push"
rule below.

### Receiving the submission

Vanilla routes it to `MinecraftServer.handleCustomClickAction(Identifier, Optional<Tag>)`,
whose body — confirmed by disassembly — is a single `LOGGER.debug` call. It is a
deliberate no-op extension point, and it does **not** tell you which player clicked. So
mixin one level up, at `ServerCommonPacketListenerImpl#handleCustomClickAction(ServerboundCustomClickActionPacket)`,
where `this` can be narrowed to `ServerGamePacketListenerImpl` to reach `.player`.

```java
record ServerboundCustomClickActionPacket(Identifier id, Optional<Tag> payload)
```

---

## Part 1 — Plumbing (both agents, one copy each)

Package: `ballot.mc.dialog` / `quizengine.mc.dialog`.

### 1.1 `Dialogs.java` — a small builder facade

Final class, private constructor, static methods. Keep it thin; it exists so the feature
code reads as intent rather than as nested record constructors.

Suggested surface (adapt as your call sites want):

```java
static void show(ServerPlayer player, Dialog dialog);
static void clear(ServerPlayer player);
static ClickEvent open(Dialog dialog);                        // ClickEvent.ShowDialog
static CommonDialogData common(String title, List<DialogBody> body, List<Input> inputs);
static DialogBody text(String line);
static ActionButton button(String label, String tooltip, Action action);
static Action submit(String id, Consumer<CompoundTag> context);   // CustomAll
```

### 1.2 The mixin

`ballot/fabric/src/main/java/ballot/mc/mixin/CustomClickMixin.java` (Ballot already has a
`mixin` package and a registered `ballot.mixins.json` — add the class name to the
`mixins` array).

QuizEngine has **no mixin setup at all**. Agent B must create
`quizengine/fabric/src/main/resources/quizengine.mixins.json` (copy Ballot's shape —
`"compatibilityLevel": "JAVA_25"`, package `quizengine.mc.mixin`) and add
`"mixins": ["quizengine.mixins.json"]` to `fabric.mod.json`. No `build.gradle.kts` change
is needed; Loom picks it up from `fabric.mod.json`.

```java
@Mixin(ServerCommonPacketListenerImpl.class)
public class CustomClickMixin {
    @Inject(method = "handleCustomClickAction", at = @At("HEAD"), cancellable = true)
    private void onCustomClick(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
        // namespace check, resolve player, dispatch, ci.cancel()
    }
}
```

Rules:

- **Only handle your own namespace** (`ballot:` / `quizengine:`). Anything else falls
  through to vanilla untouched — other mods use this same channel.
- `ci.cancel()` only on ids you actually handled.
- Resolve the player with a pattern check on `this`; a common listener is not always a
  game listener (configuration phase). If it is not a `ServerGamePacketListenerImpl`,
  ignore the packet.

### 1.3 Thread safety — do not skip this

**Packet handlers run on the netty IO thread.** Reading or mutating poll/round state, or
touching storage, from there is a data race that will corrupt saves under load.

Hop to the server thread before doing anything:

```java
server.execute(() -> Router.handle(player, id, payload));
```

Everything downstream of that call runs on the main thread and may use the existing
core/engine APIs freely.

### 1.4 Payload parsing — verify, don't assume

**[confirmed in build]** `CustomAll.createAction` disassembles to: copy `additions` into a
`CompoundTag`, then merge each input value in at the **top level of that same compound**
via `ValueGetter.asTag()`. So the payload is one flat compound, and **input keys overwrite
context keys on a collision** — name your context keys so they cannot clash with your
input keys.

Text inputs arrive as `StringTag`. Boolean and number-range encodings are still
**unconfirmed at runtime** — Ballot is the first user of both. QuizEngine's
`DialogRouter` logs the whole received tag at debug on every action; item 3 of its test
checklist captures the real shapes.

So: parse defensively. Missing key, wrong tag type and unparseable number must all
produce a friendly chat message, never an exception into the netty thread. Log the whole
received tag at debug on first handling so the real shapes can be confirmed in play
testing, and note anything surprising in your report.

---

## Part 2 — Ballot (Agent A)

Read [ballot/README.md](ballot/README.md) first. It is unusually good and states the
design intent you are working inside.

The governing principle is already written down in
[Menus.java:30-35](ballot/fabric/src/main/java/ballot/mc/Menus.java#L30-L35): the GUI
layer "owns no rules", every action calls the same core methods the chat buttons call,
and it stays "additive rather than load-bearing". **Dialogs join on exactly those terms.**

### Hard constraints

- **Do not touch `ballot/core/`.** It has no Minecraft on its classpath and 148 tests that
  must still pass unchanged.
- **Do not remove SGUI, the chest menus, or any chat screen.** Everything that works today
  keeps working. This is additive.
- **Do not remove the `/ballot _` command node.** Chat buttons still need it. New dialog
  buttons should use `CustomAll` rather than commands, but that is a reason not to *grow*
  the node, not a reason to delete it.
- Every action goes through the existing `Poll` methods and returns `Outcome`. Rejections
  are return values, not exceptions — match that.

### A2.1 — Text entry (highest value; do this first)

[Menus.java:324-346](ballot/fabric/src/main/java/ballot/mc/Menus.java#L324-L346) fakes a
text field with an anvil rename screen. Its own comment concedes it is a workaround. It
caps at ~50 characters, which the description field genuinely needs to exceed.

Replace `askText` with a `NoticeDialog` carrying one `TextInput` and a `CustomAll` submit
action. Keep the method name and signature (`prompt`, `initial`, `Consumer<String> onDone`)
so all four call sites are unchanged.

- Name: `maxLength` 64, single line.
- Description: `maxLength` 512, `MultilineOptions` with a height. **This is the field that
  most needed fixing.**
- Preserve the current cancel semantics: backing out commits nothing.
- Delete the `AnvilInputGui` path once the dialog version works.

### A2.2 — One settings dialog replacing per-toggle reopen

Today every toggle in [Menus.java:374-382](ballot/fabric/src/main/java/ballot/mc/Menus.java#L374-L382)
runs save → refresh signs → reopen the entire menu. Six round-trips to change six things.

Build a `MultiActionDialog` with:

- **Inputs:** name (`TextInput`), description (`TextInput` multiline), deadline
  (`SingleOptionInput` — `none` / 1 / 3 / 7 / 14 days; discrete choices read better here
  than a slider, and it replaces the odd cycling in `cycleDeadline`), and three
  `BooleanInput`s for `voteFromChat`, `allowVoteChange`, `blockSelfVote`.
- **Actions:** `[ Save ]` (`CustomAll` → `ballot:settings_save`, additions carry the poll
  key), `[ Preview ]`, the state-transition button, `[ Delete ]`.
- `afterAction = CLOSE`.

The handler applies all fields in one pass, saves once, refreshes signs once. If any
`Outcome` fails, report it in chat and apply nothing — partial application of a settings
save is worse than none.

Reachable from the wand, alongside the existing chest menu. Do not delete the chest menu.

### A2.3 — Real confirmations

`ConfirmationDialog` for the two destructive one-way doors currently guarded by
shift-click ([Menus.java:131-145](ballot/fabric/src/main/java/ballot/mc/Menus.java#L131-L145)
delete, and `VOTING` → close). Put the consequence in the body: vote count for closing,
"removes it and its whole history" for delete.

### A2.4 — Chat voting via dialog

Gate on `rules().voteFromChat()`. Replace the per-row `[ Vote ]` chat button in
[Screens.java:106-108](ballot/fabric/src/main/java/ballot/mc/Screens.java#L106-L108) with
one `[ Vote ]` that carries `ClickEvent.ShowDialog` — a `MultiActionDialog` with the poll
description in the body and one button per entry.

Respect the existing rules: no tallies shown mid-vote (bandwagons — see
[Menus.java:224-227](ballot/fabric/src/main/java/ballot/mc/Menus.java#L224-L227)), and
self-vote blocking where `blockSelfVote` is set. Cap at 8 entries with a "…and N more"
body line falling back to the chat list, matching `Screens.MAX_ROWS`.

### A2.5 — Explicitly out of scope

Ballot box voting keeps its SGUI chest (27 options, item icons — dialogs are worse here).
`announceResults` stays chat. Signs are untouched. Do not attempt `DialogListDialog` for
the `/ballot` index — it is a nice idea but not worth the risk in this pass.

---

## Part 3 — QuizEngine (Agent B)

Read [quizengine/README.md](quizengine/README.md) first.

QuizEngine has no GUI layer and no SGUI dependency — presentation is entirely clickable
chat in [Orchestrator.java](quizengine/fabric/src/main/java/quizengine/mc/Orchestrator.java).
Dialogs give it a real UI with **no new dependency**. Do not add SGUI.

### The one rule that matters most

**Never push a dialog unprompted.** Dialogs are modal — they take over the player's screen.
Quiplash's submission window defaults to two hours; freezing everyone's screen for that is
unshippable.

So: `ClientboundShowDialogPacket` is used **only** in direct response to a player clicking
something. Every dialog in this mod is reached by `ClickEvent.ShowDialog` attached to a
chat line. Broadcasts stay exactly as they are, with a button added.

Set `canCloseWithEscape = true` and `pause = false` on every dialog.

### Hard constraints

- **Do not touch `quizengine/engine/`.** No Minecraft on its classpath, 68 tests that must
  still pass.
- **Every existing command keeps working.** `/quiz answer 3`, `/quiz submit <text>`,
  `/quiz vote 1` all remain. Dialogs are a second front door.
- All existing chat broadcasts stay. You are adding a button to them, not replacing them.

### B3.1 — Quiplash submission (do this first; it fixes a real leak)

[Orchestrator.java:400-407](quizengine/fabric/src/main/java/quizengine/mc/Orchestrator.java#L400-L407)
broadcasts "Write your answer with `/quiz submit`". A player who forgets the command
prefix broadcasts their secret answer to the entire server. That is the single worst
interaction in the suite.

Add a `[ Write your answer ]` button to that broadcast carrying `ClickEvent.ShowDialog`
for a `NoticeDialog` with the prompt in the body, one `TextInput`, and a `CustomAll` submit
to `quizengine:quiplash_submit`.

**The additions tag must carry the round identity.** A player can leave a dialog open
across a round transition and submit into a round that has moved on. The handler must
reject a stale round with a clear message rather than corrupting the current one. Apply
the same guard to B3.2 and B3.3.

`maxLength` should come from the existing config, not be hardcoded.

### B3.2 — Trivia answers

Same shape, `MultiActionDialog`: question in the body, one button per option, `CustomAll`
to `quizengine:trivia_answer` with `{round, option}` in additions.

Add the button to the round broadcast at
[Orchestrator.java:385-396](quizengine/fabric/src/main/java/quizengine/mc/Orchestrator.java#L385-L396)
and to the catch-up message at
[Orchestrator.java:487-494](quizengine/fabric/src/main/java/quizengine/mc/Orchestrator.java#L487-L494)
— the latter exists *only* because chat scrolls the question away, which is the problem
dialogs solve.

### B3.3 — Quiplash voting

Same again for the ballot at
[Orchestrator.java:420-423](quizengine/fabric/src/main/java/quizengine/mc/Orchestrator.java#L420-L423).
Honour whatever self-vote rule the engine already enforces — do not reimplement it in the
presentation layer, just route through the same call and surface the `Outcome`.

### B3.4 — Double-submit

A player can click the chat button twice and open two dialogs. Both submit. Make sure the
engine's existing rejection path produces a sensible message rather than a double score.
If the engine does not already guard this, guard it in the handler — **do not change the
engine to fix it**, since that would break its test suite's contract.

---

## Part 4 — Acceptance

Neither agent can run a Minecraft server, so the bar is:

1. `./gradlew build` passes from the mod's own directory. JDK 25.
2. The pure-Java test suite passes unchanged — 148 for Ballot's `core`, 68 for
   QuizEngine's `engine`. If either number moved, you changed something you should not
   have.
3. No new dependency in either `build.gradle.kts` (the dialog API is vanilla).
4. Every pre-existing command and screen still compiles and is still reachable.

### House style

Read a couple of existing files before writing. In short: comments explain *why*, not
*what*, and are written in prose; classes are `final` with private constructors; failures
are `Outcome` return values, not exceptions; and the player-facing copy is plain, calm and
lowercase-ish. `Screens.java` and `Menus.java` are the models — match their register.

### Report

Each agent writes `DIALOGS.md` in its own mod directory covering: what was implemented,
anything that turned out different from Part 0 (**especially the actual `CompoundTag`
shapes observed for each input type**), anything deliberately left out, and a numbered
manual test checklist for in-game verification.

Do not commit. Leave the work in the working tree for review.
