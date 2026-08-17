# Vanilla dialogs in Quiz Engine

Implements Parts 1 and 3 of [../DIALOGS_SPEC.md](../DIALOGS_SPEC.md).

Players can now write a Quiplash answer, pick a trivia option and vote from a real
vanilla dialog instead of typing a command into the chat box. No new dependency — the
dialog API is vanilla, and no SGUI was added.

## What was built

| File | Does |
|---|---|
| `mc/dialog/DialogKit.java` | The vanilla dialog API narrowed to the shapes used here |
| `mc/dialog/QuizDialogs.java` | The three screens: write, answer, ballot |
| `mc/dialog/DialogRouter.java` | Where a clicked button lands, on the main thread |
| `mc/mixin/CustomClickMixin.java` | Catches the packet; hops threads |
| `quizengine.mixins.json` | New — the mod had no mixin setup at all |

`Orchestrator` changed in four places only, all of them presentational: the trivia
announce, the Quiplash announce, the ballot listing, and the mid-round greeting each grew
one button. Every existing broadcast, every existing clickable option line and every
existing command is untouched and still works.

`engine/` was not touched. 68/68 tests still pass.

## The fixed bug

`/quiz submit <your answer>` was the only way to enter a Quiplash answer. Forget the
command prefix and your secret answer goes to the whole server as a chat message —
the round is spoiled and it cannot be taken back. The dialog removes the chat box from
the path entirely. The command still works for anyone who prefers it.

## Design notes

**Nothing is ever pushed.** `ClientboundShowDialogPacket` is not sent anywhere in this
mod. Every dialog is carried inside a chat component via `ClickEvent.ShowDialog`, so a
dialog only ever opens because a player clicked it. Dialogs are modal and Quiplash's
submission window defaults to two hours; pushing one would freeze everybody's screen for
that long. `DialogKit.show()` exists for completeness and is currently unused.

**Every dialog carries its round id.** A dialog is a window and a window can be left
open across a phase change, so a submission from a screen opened two phases ago is
ordinary. `DialogRouter` compares the payload's round id against the live round and
rejects a mismatch with "That round has moved on." before touching anything.

**Single column.** Quiplash options are written by players and run to a sentence; a
two-column grid truncates them into nonsense.

**Double-submit needed no new code.** The engine already returns `ALREADY_SUBMITTED` /
`ALREADY_VOTED`, which `Orchestrator.explain` turns into a readable message. Opening the
dialog twice and submitting twice produces "You've already answered", not a double score.
Self-vote is likewise still the engine's decision — the router only routes.

## Verified against the real jar

Everything below was read out of
`~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar` with `javap`, not from
documentation. Three findings worth recording:

**The payload is one flat compound.** Disassembling `CustomAll.createAction` confirms it
copies `additions` into a `CompoundTag`, then merges each input value in at the top level
of that same compound via `ValueGetter.asTag()`. Input keys overwrite context keys on a
collision, which is why the context keys (`round`, `option`) are named to avoid the input
key (`answer`). This was the spec's main open assumption and it holds.

**The thread hop is required.** `ServerCommonPacketListenerImpl.handleCustomClickAction`
calls `PacketUtils.ensureRunningOnSameThread` as its first instruction — *after* an
`@At("HEAD")` injection. So the mixin genuinely runs on the netty thread, and the
`server.execute(...)` in it is load-bearing, not defensive. Cancelling at HEAD means
vanilla's own reschedule never fires, so the handler runs exactly once.

**`ServerGamePacketListenerImpl` does not override the method**, so injecting into the
common parent does catch game-phase clicks.

Two corrections to the spec's API reference, both found at compile time:

- `ServerPlayer.getServer()` **does not exist** in 26.2. The mixin shadows
  `ServerCommonPacketListenerImpl.server` instead, which it has anyway.
- `CompoundTag` getters return `Optional`; the `getStringOr` / `getIntOr` variants are
  what you want for defensive parsing. Used throughout `DialogRouter`, so a malformed
  payload from a modified client produces a friendly message rather than an exception on
  a netty thread.

### Still unconfirmed

The exact tag type the client returns per input kind is confirmed *for text inputs only*
(`StringTag`, by inspection of the value-getter path). Quiz Engine uses no boolean or
number-range inputs, so nothing else here depends on it — but **Ballot's settings dialog
will**, since it uses three `BooleanInput`s and a `SingleOptionInput`. `DialogRouter`
logs the whole received tag at debug level on every action; run item 3 below with debug
logging on and the real shapes will be in the log for Ballot to rely on.

## Manual test checklist

Needs a 26.2 Fabric server and a vanilla 26.2 client. Nothing below has been run — the
build and the engine suite are the only automated evidence.

1. **Mixin loads.** Start the server. No mixin apply error in the log, and
   `Quiz Engine initialised` still appears.
2. **Trivia.** `/quiz start trivia` → the broadcast ends with `[ Open the question ]`.
   Click it: a dialog with the question and one button per option. Click an option → it
   closes and you get "Answer locked in."
3. **Payload shapes.** With debug logging on, confirm the `Dialog action ... payload ...`
   line and record the tag types. This is the item Ballot is waiting on.
4. **Quiplash, the important one.** `/quiz start quiplash` → `[ Write your answer ]`
   opens a dialog with a text field. Type an answer, submit. **Confirm it does not appear
   in chat.** Only "Answer submitted. Nobody can see it until voting opens." should show,
   and only to you.
5. **Length cap.** Try to type more than 100 characters — the field should stop you,
   rather than the engine rejecting it after the fact.
6. **Voting.** `/quiz advance` twice → `[ Open the ballot ]` appears with the ballot,
   *not* on the first advance where submissions have only just closed. Vote from it.
7. **Stale round.** Open the write dialog, then `/quiz advance` past it in another
   session, then submit. Expect "That round has moved on. Nothing was submitted." and no
   state change.
8. **Double-submit.** Open the dialog twice, submit both. Expect the second to be refused.
9. **Escape.** Every dialog closes on escape with nothing submitted.
10. **Commands unchanged.** `/quiz answer 3`, `/quiz submit <text>`, `/quiz vote 1` and
    the numbered clickable chat lines all still work.
11. **Mid-round join.** Join during a round; the greeting carries the right button for
    the round type.

## Not done

Nothing from Part 3 was left out. Part 2 (Ballot) is untouched, as intended.

Not committed — the work is in the working tree.
