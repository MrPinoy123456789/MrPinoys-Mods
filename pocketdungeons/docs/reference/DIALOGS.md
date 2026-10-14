# Vanilla dialogs in Pocket Dungeons

All player menus use the vanilla 26.2 `Holder.direct` dialog API (`DialogKit`, `DialogScreens`, `DialogRouter`, `mixin/CustomClickMixin`). No client mod or SGUI is required. Rules for adding or editing a screen live in `.claude/skills/dialog-screens/SKILL.md`.

## Current screens (`DialogScreens.menuOptions`)

- **Home (wall lodestone):** Start Dungeon, Manage Room, Inspect Compass, Manage Party, View Lobbies. Esc closes.
- **In a dungeon:** Manage Room, Inspect Compass, Manage Party and View Lobbies for the room's owner (View Lobbies only before a door is chosen), Quit Door for the owner once a door is chosen (fails the dungeon for the haul share and returns the party to the Home room; leaves the compass alone), and Leave **last**, which asks first (`leaveConfirm`). Esc only closes.
- **Manage Room:** Name, Public or Private, Shell, Visitors (recent visitors list), Reset Room (confirm).
- **Inspect Compass:** Compass lore, Diaries (found entries first, uncollected grey; clicking a found entry opens the vanilla book screen via a client-only slot packet without touching server inventory), Reset Compass (room owner, confirm).
- **Manage Party:** Invite (online players not banned), Banned (tap to unban), then everyone in the party and everyone who has played with you (green in party now, grey played before, yellow star for builders). Selecting a player opens Can build (confirm before granting) and Ban (confirm). A ban removes them from the party and building list and blocks invites, joining and visits (`RoomBans`).
- **View Lobbies:** Public rooms and friends' rooms (`VisitService.visit`).

## Architecture and invariants

| Class | Role |
|---|---|
| `DialogKit.java` | Narrow wrapper over the vanilla dialog API (`NoticeDialog`, `ConfirmationDialog`, `MultiActionDialog`, `ServerLinksDialog`) |
| `DialogScreens.java` | Builds each screen from live state on click |
| `DialogRouter.java` | Routes payload-carrying button clicks on the server thread |
| `mixin/CustomClickMixin.java` | Intercepts custom click packets (`pocketdungeons:` namespace) and hops to the server thread |

Key rules:
1. **No button-less list:** `MultiActionDialog` requires at least one action button (`List.of()` throws `IllegalArgumentException` in `ExtraCodecs.nonEmptyList`). Empty states use a one-button notice or a disabled placeholder row.
2. **Esc runs `exitAction`:** On `MultiActionDialog`, pressing Esc executes `exitAction`. Never wire a destructive command (`leave`, `quit`, `reset`) to `exitAction`; use a plain Close button.
3. **Every list rebuilds after an action:** Vanilla dialogs have no client-side history stack, so `DialogRouter` re-sends the updated parent screen after every add/remove/toggle.
4. **One code path with commands:** Buttons either run the `/dungeon ...` command as the player or call the exact service method the command calls (`RoomWhitelist`, `RoomBans`, `PartyService`, `VisitService`), never a duplicate permission check.
