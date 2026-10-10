---
name: dialog-screens
description: Build or change Pocket Dungeons dialog screens (the lodestone menu, Manage Room, Manage Party, Inspect Compass, lobbies, diaries, shells, confirmations). Covers the rules that crashed or confused players live (no button-less list, Esc runs the exit button, found things first, owner key, no history stack) and the checks to run. Use when adding or editing anything in DialogScreens or DialogRouter.
---

# Dialog screens

House rule for everything you write: no em dashes and no spaced double hyphens as punctuation
(`A:\MrPinoys Mods\CLAUDE.md`). Wording follows the `player-text` skill (what would Steve Jobs put on it).
Code lives in `pocketdungeons/src/main/java/pocketdungeons`: `DialogScreens` (builds), `DialogRouter`
(handles clicks), `DialogKit` (helpers), and `LodestoneMenuTest` plus `PartyScreensGameTest` (checks).

## Rules learned the hard way

1. **Never send a `MultiActionDialog` with no buttons.** The packet cannot be encoded and the player is
   disconnected (the Invite and Banned screens did this on 2026-10-09). When a list can be empty, return
   `DialogKit.notice(title, body, backButton)` for that case. Add the screen to `PartyScreensGameTest` in its
   emptiest state.
2. **Esc runs the exit button.** The dialog's exit action is what Esc triggers, so it must be harmless
   (`DialogKit.closeButton("Close")` or a Back). Never make a destructive action, such as Leave, the exit
   button. Put it last in the list and ask first with a confirm (`leaveConfirm`).
3. **Body text sits above every button.** You cannot interleave text between buttons. Found things come first
   as buttons; things not found yet follow as grey buttons (`lockedButton`, label with the section sign and 7)
   that only re-open their own screen. Never list locked things in the body above found ones.
4. **Every routed button carries `KEY_OWNER`**, and `DialogRouter` rejects a click whose owner is not the
   clicker. Anything only the room owner may do (ban, build rights, reset) is therefore safe by construction;
   do not weaken that check.
5. **There is no history stack.** Back is the parent rebuilt from live state through a router action. Every
   click path ends by showing a screen, never by closing on a change that looks unmade.
6. **Warn before what cannot be undone or hands over power.** Reset Room, Reset Compass, granting building and
   banning each get a confirm with a plain consequence line and a gray reassurance line where one is true
   ("You can unban them any time").
7. **State, not verdict.** A toggle's label is its state ("Public", "Can build") and its tooltip is the next
   action. Colour carries grouping: green here now, gray before or locked, yellow for what you can act on.
8. **Names of offline players** come from `DialogScreens.displayName` (the server's name cache), never a raw
   UUID.

## Checks

`gradlew test runGameTest` (the menu shape test is pure; the screens test builds real dialogs). After a deploy,
open each changed screen once in game: the encoder only fails when the packet is actually sent.
