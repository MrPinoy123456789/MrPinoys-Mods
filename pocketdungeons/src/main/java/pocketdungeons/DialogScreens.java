package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Every screen this mod can put in front of a player, built from live state at
 * the moment of the click that asked for it.
 *
 * <p>One file rather than one class per screen: they are all short, they all
 * read the same handful of {@code Instances}/{@code RoomWhitelist} accessors,
 * and keeping them together makes it obvious at a glance that none of them
 * decides anything. A screen here formats state and names a command; the rules
 * stay where the commands already put them.
 *
 * <p>There is <b>no dialog history stack in this API</b>. A screen never returns
 * anywhere on its own -- every "back to the list" a player expects is this mod
 * explicitly rebuilding the list from current state and sending it as the last
 * step of handling their click. That is still a response to a click, not an
 * unprompted push, which is the line the sibling mods drew and this one keeps.
 */
final class DialogScreens {

    private DialogScreens() {}

    // Context keys carried in a CustomAll payload. Prefixed because input keys
    // overwrite context keys on collision (root spec Part 1.4) and a player types
    // into the input, not into these.
    static final String KEY_OWNER = "pd_owner";
    static final String KEY_TARGET = "pd_target";
    /** The one key a player's typing lands under; deliberately unprefixed. */
    static final String KEY_NAME = "name";

    static final String ACTION_WHITELIST_ADD = "room_whitelist_add";
    static final String ACTION_WHITELIST_REMOVE = "room_whitelist_remove";

    /** M14: which enchantment (a namespaced id) the reroll picker's button chose. */
    static final String KEY_ENCHANT = "pd_enchant";
    static final String ACTION_REROLL = "reroll";

    /** M17: which extracted power the Cube's imbue picker's button chose. */
    static final String KEY_POWER = "pd_power";
    static final String ACTION_IMBUE = "imbue";

    /** M20: which public room the lobby browser's button chose to visit. */
    static final String ACTION_VISIT_ROOM = "pd_visit_room";

    /** M21: which wall-lodestone menu option the button chose. */
    static final String ACTION_START_DUNGEON = "start_dungeon";
    static final String ACTION_BROWSE_LOBBIES = "browse_lobbies";
    static final String ACTION_MANAGE_ROOM = "manage_room";
    static final String ACTION_INSPECT_KEYSTONE = "inspect_keystone";
    static final String ACTION_LEAVE_DUNGEON = "leave_dungeon";
    static final String ACTION_SET_ROOM_NAME = "set_room_name";
    static final String ACTION_TOGGLE_PUBLIC = "toggle_public";

    /** M24: which shell palette a shell action's button chose. */
    static final String KEY_SHELL = "pd_shell";
    /** M24: the menu's Change Shell option opens the picker; the picker's Apply buttons dispatch this. */
    static final String ACTION_CHANGE_SHELL = "change_shell";
    /** M24: a picker Apply button: swap the room's frame to {@link #KEY_SHELL}. */
    static final String ACTION_APPLY_SHELL = "apply_shell";
    /** M24: a picker Unlock button: consume the held token for {@link #KEY_SHELL}. */
    static final String ACTION_UNLOCK_SHELL = "unlock_shell";
    /** (M26) The lodestone menu's Diaries option, and the reader's own Back button. */
    static final String ACTION_DIARIES = "diaries";
    /** The lodestone menu's Stations option: opens the SGUI station picker (owner-only). */
    static final String ACTION_STATIONS = "stations";
    /**
     * The Back buttons. This API has no history stack, so going back is the
     * parent screen rebuilt from live state, which means a round trip through
     * {@link DialogRouter} rather than a client-side screen swap. Manage Room
     * and the lobby directory are their own parents already
     * ({@link #ACTION_MANAGE_ROOM}, {@link #ACTION_BROWSE_LOBBIES}); these two
     * cover the parents that had no action of their own.
     */
    static final String ACTION_BACK_MENU = "back_menu";
    static final String ACTION_BACK_WHITELIST = "back_whitelist";

    // ---- section 2: party roster and kick confirmation ----------------------

    /**
     * The leader's pre-registered companions, one "Kick" button each.
     *
     * <p>New surface, not a retrofit: before this there was no way to <i>browse</i>
     * a party at all, only to name someone you already remembered. The buttons
     * still run {@code /dungeon party kick <name>}, so the leader check that
     * matters is {@code stageKick}'s own -- this screen does not re-implement it.
     */
    static Dialog partyRoster(MinecraftServer server, List<UUID> companions) {
        if (companions.isEmpty()) {
            // Never build a zero-button MultiActionDialog: an empty list reads as a
            // broken screen, and the leader deserves a plain sentence instead.
            return DialogKit.notice("Your party",
                    List.of(DialogKit.text("Nobody is pre-registered for your next dungeon."),
                            DialogKit.text("Use /dungeon party <player> to add somebody.")));
        }

        List<ActionButton> buttons = new ArrayList<>();
        for (UUID id : companions) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online == null) {
                // kick takes an EntityArgument.player(), so an offline companion has
                // no command to run. Say so rather than offering a button that fails.
                continue;
            }
            String name = online.getName().getString();
            buttons.add(DialogKit.command("Kick " + name, "Asks you to confirm first",
                    "/dungeon party kick " + name));
        }
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogKit.text(companions.size() + " companion"
                + (companions.size() == 1 ? "" : "s") + " will come with you."));
        if (buttons.size() < companions.size()) {
            body.add(DialogKit.text(Component.literal(
                    (companions.size() - buttons.size())
                            + " of them are offline and cannot be removed by name right now.")
                    .withStyle(ChatFormatting.GRAY)));
        }
        if (buttons.size() > 1) {
            buttons.add(DialogKit.command("Kick everyone", null, "/dungeon party kick all"));
        }
        if (buttons.isEmpty()) {
            return DialogKit.notice("Your party", body);
        }
        return DialogKit.list("Your party", body, buttons, "Close");
    }

    /** The confirmation {@code stageKick} stages -- same wording the chat prompt used. */
    static Dialog kickConfirm(String what) {
        return DialogKit.confirm("Remove from party",
                List.of(DialogKit.text("Remove " + what + " from your party?")),
                DialogKit.command("Confirm", null, "/dungeon party kickconfirm"),
                DialogKit.closeButton("Cancel"));
    }

    // ---- section 3: party invite -------------------------------------------

    /**
     * The accept/decline an invitee gets. {@code join} already re-checks the
     * invite's expiry and its leader, so a dialog left open past the two-minute
     * window degrades exactly like a stale chat command: it fails and says why.
     */
    static Dialog inviteOffer(String inviterName) {
        return DialogKit.confirm("Dungeon invitation",
                List.of(DialogKit.text(inviterName + " invites you into their dungeon."),
                        DialogKit.text(Component.literal("The invitation lasts two minutes.")
                                .withStyle(ChatFormatting.GRAY))),
                DialogKit.command("Join", null, "/dungeon join " + inviterName),
                DialogKit.closeButton("Decline"));
    }

    // ---- section 4: keystone inspection -------------------------------------

    /**
     * Read-only: the held keystone's level and affix, plus the same run statistics
     * {@code /dungeon log} prints. Nothing here is unavailable elsewhere -- it is a
     * convenience pass over lore plus a command, not a new source of truth.
     */
    static Dialog keystoneInfo(ItemStack held, DungeonLog.Entry entry) {
        return keystoneInfo(held, entry, DialogKit.closeButton("Close"));
    }

    /** The same screen with its one button supplied, so the menu can send a Back. */
    static Dialog keystoneInfo(ItemStack held, DungeonLog.Entry entry, ActionButton exit) {
        List<DialogBody> body = new ArrayList<>();
        int level = Keystone.levelOf(held).orElse(0);
        java.util.EnumSet<Affix> affixes = Keystone.affixOf(held);
        java.util.List<Affix> ordered = AffixMath.ordered(affixes);
        Affix title = ordered.isEmpty() ? null : ordered.get(0);
        body.add(DialogKit.text(Component.literal(AffixMath.name(level, affixes))
                .withStyle(Keystone.colourOf(title))));
        // One blurb per affix, the same "curse and kiss" sentence the item's lore
        // carries -- see the design rule at Affix's class note.
        if (ordered.isEmpty()) {
            body.add(DialogKit.text(Component.literal("Beat the clock to trade up.")
                    .withStyle(ChatFormatting.GRAY)));
        } else {
            for (Affix affix : ordered) {
                body.add(DialogKit.text(Component.literal(affix.blurb).withStyle(ChatFormatting.GRAY)));
            }
        }
        body.add(DialogKit.text(""));
        if (entry.runsCompleted() == 0) {
            body.add(DialogKit.text("You have not finished a dungeon yet."));
        } else {
            body.add(DialogKit.text(entry.runsCompleted() + " run"
                    + (entry.runsCompleted() == 1 ? "" : "s") + " completed."));
            body.add(DialogKit.text("Best keystone [" + entry.bestKeystoneLevel() + "]."));
            body.add(DialogKit.text("Longest dungeon cleared " + entry.bestPathLength()
                    + " rooms deep."));
        }
        return DialogKit.notice("Your keystone", body, exit);
    }

    /**
     * M21: the inspect-keystone screen shared by {@code /dungeon key info} and
     * the wall-lodestone menu's Inspect Keystone option. The callers check the
     * player is carrying a keystone and refuse in chat when they are not; this
     * is the dialog itself, built from the live held stack and log entry.
     */
    static Dialog inspectKeystone(ServerPlayer player) {
        return inspectKeystone(player, DialogKit.closeButton("Close"));
    }

    /** The same screen with its one button supplied, so the menu can send a Back. */
    static Dialog inspectKeystone(ServerPlayer player, ActionButton exit) {
        ItemStack held = Keystone.findHeld(player);
        DungeonLog.Entry entry = DungeonLog.forServer(player.level().getServer())
                .get(player.getUUID());
        return keystoneInfo(held, entry, exit);
    }

    /** The Back-to-the-menu button, for the screens the menu itself opens. */
    static ActionButton backToMenuButton(UUID owner) {
        return backButton("Back", ACTION_BACK_MENU, owner);
    }

    // ---- section 5: the room whitelist manager ------------------------------

    /**
     * The owner's guest list, one "Remove" button per entry plus a way in to the
     * add screen.
     *
     * <p>Tier B, unlike everything above: which entry was clicked cannot be baked
     * into a fixed command string when the entry may be an offline UUID with no
     * name for {@code EntityArgument.player()} to resolve. The buttons carry the
     * target's UUID in their payload and {@link DialogRouter} re-reads the live
     * whitelist before acting, so a list that went stale while the screen was open
     * cannot remove the wrong person.
     */
    static Dialog whitelist(MinecraftServer server, UUID owner, List<UUID> entries,
                            String notice) {
        List<DialogBody> body = new ArrayList<>();
        if (notice != null) {
            body.add(DialogKit.text(Component.literal(notice).withStyle(ChatFormatting.YELLOW)));
        }
        body.add(DialogKit.text(entries.isEmpty()
                ? "Nobody may enter your room but you."
                : entries.size() + " player" + (entries.size() == 1 ? "" : "s")
                        + " may enter your room."));

        List<ActionButton> buttons = new ArrayList<>();
        for (UUID id : entries) {
            CompoundTag context = new CompoundTag();
            context.putString(KEY_OWNER, owner.toString());
            context.putString(KEY_TARGET, id.toString());
            buttons.add(DialogKit.button("Remove " + displayName(server, id), null,
                    DialogKit.submit(ACTION_WHITELIST_REMOVE, context)));
        }
        // Opening the add screen is a plain ShowDialog: the client swaps screens
        // with no server round trip, and nothing is committed until that screen's
        // own "Add" is pressed.
        buttons.add(new ActionButton(
                new net.minecraft.server.dialog.CommonButtonData(
                        Component.literal("Add a player..."), Optional.empty(), DialogKit.WIDE),
                Optional.of(new StaticAction(
                        new ClickEvent.ShowDialog(net.minecraft.core.Holder.direct(
                                whitelistAdd(owner, ACTION_BACK_WHITELIST)))))));

        return DialogKit.list("Room whitelist", body, buttons, "Close");
    }

    /**
     * The add-a-player screen: one text field, "Add" and "Cancel".
     *
     * <p>A {@code ConfirmationDialog} and not a {@code NoticeDialog} because a
     * notice has exactly one button, which would leave Escape as the only way to
     * back out of a form somebody opened by accident.
     *
     * <p>{@code backAction} names the screen Cancel returns to, because this
     * form is reachable from two: the standalone whitelist manager and Manage
     * Room. Cancelling a form should undo the step that opened it, not close
     * everything the player had walked through to get here.
     */
    static Dialog whitelistAdd(UUID owner, String backAction) {
        CompoundTag context = new CompoundTag();
        context.putString(KEY_OWNER, owner.toString());
        Input name = new Input(KEY_NAME, new TextInput(
                DialogKit.WIDE, Component.literal("Player name"), true, "", 16,
                Optional.empty()));
        return new net.minecraft.server.dialog.ConfirmationDialog(
                DialogKit.common("Whitelist a player",
                        List.of(DialogKit.text("They must be online, the same as "
                                + "/dungeon room whitelist add requires.")),
                        List.of(name)),
                DialogKit.button("Add", null, DialogKit.submit(ACTION_WHITELIST_ADD, context)),
                backButton("Cancel", backAction, owner));
    }

    /**
     * A whitelist entry's label. Matches what {@code /dungeon room whitelist list}
     * already prints for an offline entry -- a shortened UUID -- rather than
     * inventing a second name-resolution path this mod does not otherwise have.
     */
    private static String displayName(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        return online != null ? online.getName().getString() : id.toString().substring(0, 8);
    }

    // ---- section 6: admin baserestore confirmation --------------------------

    /**
     * The guard on an operator command that overwrites player-authored content.
     * A room blob is somebody's build, not disposable run state, and there is
     * exactly one backup generation to restore from -- so the confirm is a fixed
     * command string and this stays tier A.
     */
    static Dialog baseRestoreConfirm(String targetName, String when) {
        return DialogKit.confirm("Restore a room",
                List.of(DialogKit.text("Restore " + targetName + "'s room from the backup"),
                        DialogKit.text("made " + when + "?"),
                        DialogKit.text(Component.literal("This overwrites their current room.")
                                .withStyle(ChatFormatting.RED))),
                DialogKit.command("Restore", null,
                        "/dungeon admin baserestore " + targetName + " confirm"),
                DialogKit.closeButton("Cancel"));
    }

    // ---- section 7: the gear reroll station (M14) ---------------------------

    /**
     * The held item's current enchantments, one "Reroll" button each.
     *
     * <p>Tier B, like {@link #whitelist}: which enchantment was clicked cannot
     * be a fixed command string, so the button carries the enchantment's
     * registered id and {@link RerollStation#handleReroll} re-reads the
     * player's live main-hand item rather than trusting this screen's
     * snapshot: the item, and the lapis to pay for the swap, can both change
     * while the screen sits open.
     */
    static Dialog rerollPicker(UUID player, ItemStack held, String notice) {
        List<DialogBody> body = new ArrayList<>();
        if (notice != null) {
            body.add(DialogKit.text(Component.literal(notice).withStyle(ChatFormatting.YELLOW)));
        }
        int tier = RerollStation.tierOf(held);
        int cost = PocketDungeonsConfig.rerollLapisPerTier() * Math.max(1, tier);
        body.add(DialogKit.text("Tier " + tier + " gear. One reroll costs " + cost + " lapis lazuli."));

        List<ActionButton> buttons = new ArrayList<>();
        net.minecraft.world.item.enchantment.ItemEnchantments enchantments = held.getEnchantments();
        for (Holder<Enchantment> holder : enchantments.keySet()) {
            int level = enchantments.getLevel(holder);
            CompoundTag context = new CompoundTag();
            context.putString(KEY_OWNER, player.toString());
            context.putString(KEY_ENCHANT, holder.getRegisteredName());
            buttons.add(DialogKit.button("Reroll " + Enchantment.getFullname(holder, level).getString(),
                    "Replaces just this one enchantment", DialogKit.submit(ACTION_REROLL, context)));
        }
        if (buttons.isEmpty()) {
            body.add(DialogKit.text("This item has no enchantments to reroll."));
            return DialogKit.notice("Reroll station", body);
        }
        return DialogKit.list("Reroll station", body, buttons, "Close");
    }

    // ---- section 8: the gear gamble station (M16) ---------------------------
    // The gamble station now uses SGUI's MerchantGui (villager trading screen)
    // rather than a vanilla dialog. The villager UI fits emerald-as-currency
    // naturally, and onTrade intercepts each trade to run the real random loot
    // draw. See GambleStation for the implementation.

    // ---- section 9: the Herobrine Cube's imbue picker (M17) -----------------

    /**
     * Every power the player has extracted, one "Imbue" button each. Tier B,
     * like {@link #rerollPicker}: the button carries the power id so
     * {@link CubeStation#handleImbue} can re-validate the held item, the
     * player's live extracted-power set, and the material cost, since all
     * three can change while the picker sits open.
     */
    static Dialog imbuePicker(UUID player, ItemStack held, Set<String> extractedPowers, String notice) {
        List<DialogBody> body = new ArrayList<>();
        if (notice != null) {
            body.add(DialogKit.text(Component.literal(notice).withStyle(ChatFormatting.YELLOW)));
        }
        int cost = PocketDungeonsConfig.imbueCost();
        body.add(DialogKit.text("Imbuing " + held.getHoverName().getString() + ". Costs "
                + cost + " " + PocketDungeonsConfig.imbueMaterial() + "."));

        List<ActionButton> buttons = new ArrayList<>();
        for (String power : CubeStation.sortedUnlocked(extractedPowers)) {
            CompoundTag context = new CompoundTag();
            context.putString(KEY_OWNER, player.toString());
            context.putString(KEY_POWER, power);
            buttons.add(DialogKit.button("Imbue " + power, null, DialogKit.submit(ACTION_IMBUE, context)));
        }
        if (buttons.isEmpty()) {
            body.add(DialogKit.text("You have not extracted any powers yet."));
            return DialogKit.notice("Herobrine Cube", body);
        }
        return DialogKit.list("Herobrine Cube", body, buttons, "Close");
    }

    // ---- section 10: the lobby directory (M20) ------------------------------

    /** One row of the lobby directory before rendering, kept pure for the headless test. */
    record LobbyRow(UUID owner, String ownerName, String roomName, String status, int occupancy) {
        /** The button label: room name (or owner name when unset) plus occupancy. */
        String label() {
            String name = roomName.isBlank() ? ownerName : roomName;
            return name + " (" + occupancy + ")";
        }

        /** The button's body line: the owner's player name and their live status. */
        String body() {
            return ownerName + ": " + status;
        }
    }

    /** An online player reduced to identity, so {@link #lobbyRows} needs no server. */
    record OnlinePlayer(UUID id, String name) {}

    /**
     * The rows the lobby directory shows right now: every online player whose
     * entry opts in ({@code publicListed}). Pure function of the log and the
     * online set, so the listing rule is testable headless; status and
     * occupancy read the same live state {@link VisitService} routes visits by,
     * so the directory can never show a room as visitable that a click could
     * not enter.
     */
    static List<LobbyRow> lobbyRows(DungeonLog log, List<OnlinePlayer> online) {
        List<LobbyRow> rows = new ArrayList<>();
        for (OnlinePlayer player : online) {
            DungeonLog.Entry entry = log.get(player.id());
            if (!entry.publicListed()) {
                continue;
            }
            rows.add(new LobbyRow(player.id(), player.name(), entry.roomName(),
                    VisitService.statusOf(player.id()), VisitService.occupancyOf(player.id())));
        }
        rows.sort(Comparator.comparing(LobbyRow::label));
        return rows;
    }

    /**
     * The lobby directory: one button per public room, each carrying the
     * owner's UUID in its payload. Same shape as {@link #partyRoster}: build a
     * list of buttons from live state, send it as one dialog, and let
     * {@link DialogRouter} re-read the live room before acting, so a room that
     * went private (or an owner who logged off) while the screen sat open
     * degrades to a re-shown directory, never to a wrong entry.
     */
    static Dialog lobbyBrowser(MinecraftServer server, UUID clicker) {
        return lobbyBrowser(server, clicker, null);
    }

    /** Same as the two-arg form, with a yellow reason line for a stale-click re-show. */
    static Dialog lobbyBrowser(MinecraftServer server, UUID clicker, String notice) {
        List<OnlinePlayer> online = server.getPlayerList().getPlayers().stream()
                .map(p -> new OnlinePlayer(p.getUUID(), p.getName().getString()))
                .toList();
        return lobbyBrowserDialog(lobbyRows(DungeonLog.forServer(server), online), clicker, notice);
    }

    /**
     * The dialog from prebuilt rows plus an optional reason line; the
     * headless-testable half of {@link #lobbyBrowser}. {@code notice} is the
     * yellow line the router shows when a stale click re-opens the directory
     * ("That room is not open any more."); {@code null} for a fresh open.
     */
    static Dialog lobbyBrowserDialog(List<LobbyRow> rows, UUID clicker, String notice) {
        List<DialogBody> body = new ArrayList<>();
        if (notice != null) {
            body.add(DialogKit.text(Component.literal(notice).withStyle(ChatFormatting.YELLOW)));
        }
        if (rows.isEmpty()) {
            // Never build a zero-button MultiActionDialog; a fresh server with
            // nobody opted in deserves a sentence, not an empty grid.
            body.add(DialogKit.text("No public rooms right now."));
            body.add(DialogKit.text(Component.literal(
                    "List your room with /dungeon room public.")
                    .withStyle(ChatFormatting.GRAY)));
            return DialogKit.notice("Lobby directory", body, backToMenuButton(clicker));
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (LobbyRow row : rows) {
            CompoundTag context = new CompoundTag();
            context.putString(KEY_OWNER, clicker.toString());
            context.putString(KEY_TARGET, row.owner().toString());
            buttons.add(DialogKit.button(row.label(), row.body(),
                    DialogKit.submit(ACTION_VISIT_ROOM, context)));
        }
        body.add(DialogKit.text(rows.size() + " public room" + (rows.size() == 1 ? "" : "s")
                + " right now."));
        return DialogKit.list("Lobby directory", body, buttons, backToMenuButton(clicker));
    }

    // ---- section 11: the wall-lodestone menu (M21) -------------------------

    /** One row of the wall-lodestone menu before rendering, kept pure for the headless test. */
    record MenuOption(String label, String tooltip, String action) {}

    /**
     * The wall-lodestone menu's button list for a context: the overworld menu
     * (Start Dungeon, Browse Lobbies, Manage Room, Inspect Keystone) or the
     * in-dungeon menu (Leave, plus Manage Room only when the player is their
     * own room's owner, plus Inspect Keystone). Pure function of the context
     * so the menu's shape is testable headless; the keystone is deliberately
     * not checked here, because Start Dungeon checks it when clicked and a
     * player who just wants to leave or browse never needs their compass
     * first.
     */
    static List<MenuOption> menuOptions(boolean inDungeon, boolean roomOwner) {
        if (inDungeon) {
            List<MenuOption> options = new ArrayList<>();
            options.add(new MenuOption("Leave", "Exit the dungeon", ACTION_LEAVE_DUNGEON));
            if (roomOwner) {
                options.add(new MenuOption("Manage Room", null, ACTION_MANAGE_ROOM));
                // M24: the shell swap needs the room's live cell, so it is an
                // in-dungeon, owner-only option: the frame only exists while
                // the room is stamped, and only its owner gets to reframe it.
                options.add(new MenuOption("Change Shell", "Swap your room's frame",
                        ACTION_CHANGE_SHELL));
                // Stations: owner-only, same gate as Change Shell. A party
                // member visiting another player's room does not see this.
                options.add(new MenuOption("Stations", "Take a station block for your room",
                        ACTION_STATIONS));
            }
            options.add(new MenuOption("Inspect Keystone", null, ACTION_INSPECT_KEYSTONE));
            options.add(new MenuOption("Diaries", null, ACTION_DIARIES));
            return options;
        }
        return List.of(
                new MenuOption("Start Dungeon", "Requires a keystone in your main hand",
                        ACTION_START_DUNGEON),
                new MenuOption("Browse Lobbies", null, ACTION_BROWSE_LOBBIES),
                new MenuOption("Manage Room", null, ACTION_MANAGE_ROOM),
                new MenuOption("Stations", "Take a station block for your room",
                        ACTION_STATIONS),
                new MenuOption("Inspect Keystone", null, ACTION_INSPECT_KEYSTONE),
                new MenuOption("Diaries", null, ACTION_DIARIES));
    }

    /**
     * The wall-lodestone navigation menu, context-dependent: the overworld
     * menu (Start, Browse, Manage, Inspect) or the in-dungeon menu (Leave,
     * Manage for the room's own owner, Inspect). The keystone is checked by
     * Start Dungeon's dispatch, never here, so the menu opens with any item
     * or an empty hand. In the dungeon the menu is the room's wall terminal,
     * not a stand-on pad; {@code RitualListener} enforces that position gate
     * before this is ever called.
     */
    static Dialog lodestoneMenu(ServerPlayer player, boolean inDungeon) {
        boolean roomOwner = false;
        if (inDungeon) {
            InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
            roomOwner = record != null && record.owner.equals(player.getUUID());
        }
        return lodestoneMenuDialog(menuOptions(inDungeon, roomOwner), player.getUUID(), inDungeon);
    }

    /**
     * The menu dialog from prebuilt options plus the clicking player's UUID;
     * the headless-testable half of {@link #lodestoneMenu}. Every button
     * carries {@link #KEY_OWNER} so {@link DialogRouter}'s owner check passes.
     */
    static Dialog lodestoneMenuDialog(List<MenuOption> options, UUID owner, boolean inDungeon) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogKit.text(inDungeon
                ? "Leave the dungeon, manage your room, or inspect your keystone."
                : "Start a dungeon, visit a lobby, or manage your room."));
        List<ActionButton> buttons = new ArrayList<>();
        for (MenuOption option : options) {
            CompoundTag context = new CompoundTag();
            context.putString(KEY_OWNER, owner.toString());
            buttons.add(DialogKit.button(option.label(), option.tooltip(),
                    DialogKit.submit(option.action(), context)));
        }
        return DialogKit.list(inDungeon ? "Dungeon" : "Pocket Dungeons", body, buttons, "Close");
    }

    /**
     * What Start Dungeon and Inspect Keystone show a player carrying no
     * keystone. The refusal used to be a chat line naming {@code /dungeon key},
     * which a player reads after the screen has already closed and then has to
     * retype; here the command is the button, and Back returns to the menu they
     * came from. {@code /dungeon key} mints a free level 1 keystone for anyone
     * holding none, so the button needs no permission and cannot be farmed.
     */
    static Dialog noKeystone(UUID owner) {
        return DialogKit.list("No keystone",
                List.of(DialogKit.text("You are not carrying a keystone."),
                        DialogKit.text("Your first one is free, and a lost one is replaced "
                                + "at the level you had earned.")),
                List.of(DialogKit.command("Get a keystone", "Runs /dungeon key", "/dungeon key")),
                backToMenuButton(owner));
    }

    // ---- section 12: room management (M21) ---------------------------------

    /**
     * The room management screen behind the menu's Manage Room option: the
     * whitelist's remove buttons, an add-a-player entry (reusing
     * {@link #whitelistAdd}), a room-name entry, and the public/private toggle
     * showing the current listing state. The name entry and the toggle
     * dispatch through {@link DialogRouter} and re-show this screen, so a
     * click never ends on a closed dialog with the change unmade.
     */
    static Dialog manageRoom(MinecraftServer server, UUID owner) {
        List<DialogBody> body = new ArrayList<>();
        DungeonLog.Entry entry = DungeonLog.forServer(server).get(owner);
        String name = entry.roomName().isBlank() ? "Unnamed" : entry.roomName();
        body.add(DialogKit.text("Room " + name + " is "
                + (entry.publicListed() ? "public" : "private") + "."));
        List<UUID> entries = new ArrayList<>(RoomWhitelist.forServer(server).get(owner));
        entries.sort(Comparator.comparing(UUID::toString));
        body.add(DialogKit.text(entries.isEmpty()
                ? "Nobody may enter your room but you."
                : entries.size() + " player" + (entries.size() == 1 ? "" : "s")
                        + " may enter your room."));

        List<ActionButton> buttons = new ArrayList<>();
        for (UUID id : entries) {
            CompoundTag context = new CompoundTag();
            context.putString(KEY_OWNER, owner.toString());
            context.putString(KEY_TARGET, id.toString());
            buttons.add(DialogKit.button("Remove " + displayName(server, id), null,
                    DialogKit.submit(ACTION_WHITELIST_REMOVE, context)));
        }
        buttons.add(showDialogButton("Add a player...", whitelistAdd(owner, ACTION_MANAGE_ROOM)));
        buttons.add(showDialogButton("Set room name...", roomNameInput(owner)));
        CompoundTag toggle = new CompoundTag();
        toggle.putString(KEY_OWNER, owner.toString());
        buttons.add(DialogKit.button(entry.publicListed() ? "Room is public" : "Room is private",
                "Click to flip the lobby listing", DialogKit.submit(ACTION_TOGGLE_PUBLIC, toggle)));
        // M27 27.2: host-visible only, so this lives behind Manage Room rather
        // than the plain lodestone menu; a visitor never opens this screen at
        // all, since Manage Room itself is owner-only.
        buttons.add(showDialogButton("Recent visitors...", recentVisitors(server, owner)));

        return DialogKit.list("Manage room", body, buttons, backToMenuButton(owner));
    }

    /**
     * (M27 27.2) The last {@link DungeonLog#MAX_RECENT_VISITORS} people who
     * visited this room via the lobby directory, newest first: name, how long
     * ago, and whether they are still inside right now. Host-visible only:
     * reached only through the owner-only Manage Room screen, and read-only,
     * since there is nothing on this screen to click but Back.
     */
    static Dialog recentVisitors(MinecraftServer server, UUID owner) {
        List<DungeonLog.VisitorEntry> visitors = DungeonLog.forServer(server).get(owner).recentVisitors();
        List<DialogBody> body = new ArrayList<>();
        if (visitors.isEmpty()) {
            body.add(DialogKit.text("Nobody has visited this room yet."));
        } else {
            long now = System.currentTimeMillis();
            for (DungeonLog.VisitorEntry visitor : visitors) {
                boolean stillInside = isVisitorStillInside(server, owner, visitor.name());
                body.add(DialogKit.text(visitor.name() + " - " + agoText(now - visitor.timestamp())
                        + (stillInside ? " (still inside)" : "")));
            }
        }
        return DialogKit.notice("Recent visitors", body, backButton("Back", ACTION_MANAGE_ROOM, owner));
    }

    /**
     * A logged visitor is only findable by name (the log stores no UUID; see
     * {@link DungeonLog.VisitorEntry}), and "still inside" is only meaningful
     * for someone online right now, so this resolves the name against the
     * online player list before asking {@link VisitService#isStillInside}.
     */
    private static boolean isVisitorStillInside(MinecraftServer server, UUID owner, String visitorName) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(visitorName);
        return online != null && VisitService.isStillInside(owner, online.getUUID());
    }

    /** A rough "how long ago" for the recent-visitors screen: minutes, hours or days. */
    private static String agoText(long millisAgo) {
        long minutes = millisAgo / 60_000L;
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h ago";
        }
        return (hours / 24) + "d ago";
    }

    /**
     * The room-name form behind Manage Room's Set room name: one text field,
     * "Set" and "Cancel", the same shape as {@link #whitelistAdd}.
     */
    static Dialog roomNameInput(UUID owner) {
        CompoundTag context = new CompoundTag();
        context.putString(KEY_OWNER, owner.toString());
        Input name = new Input(KEY_NAME, new TextInput(
                DialogKit.WIDE, Component.literal("Room name"), true, "", 16,
                Optional.empty()));
        return new net.minecraft.server.dialog.ConfirmationDialog(
                DialogKit.common("Set room name",
                        List.of(DialogKit.text("Shown in the lobby directory.")),
                        List.of(name)),
                DialogKit.button("Set", null, DialogKit.submit(ACTION_SET_ROOM_NAME, context)),
                backButton("Cancel", ACTION_MANAGE_ROOM, owner));
    }

    /**
     * (M24) The Change Shell screen behind the in-dungeon menu's option: the
     * current frame, one Apply button per shell the player can use (the
     * always-available default plus every unlock), grayed-out hint lines for
     * the locked ones (the menu option is the tutorial: it names what there is
     * to find), and, when the player is holding a shell token, an Unlock button
     * that consumes it. Selecting an Apply dispatches
     * {@code RoomBuilder.rebuildShell} through {@link DialogRouter}, which
     * re-validates the unlock against live state: nothing here decides
     * anything, the same shape as every other screen in this file.
     */
    static Dialog shellPicker(ServerPlayer player, InstanceRecord record) {
        DungeonLog.Entry entry = DungeonLog.forServer(player.level().getServer())
                .get(player.getUUID());
        Set<String> unlocked = entry.unlockedShells();
        List<DialogBody> body = new ArrayList<>();
        BlockPos o = record.roomCellOrigin;
        RoomBuilder.ShellPalette current = o == null ? null
                : RoomBuilder.shellAt((ServerLevel) player.level(), o);
        body.add(DialogKit.text("Current shell: "
                + (current == null ? "the default" : current.displayName()) + "."));
        body.add(DialogKit.text("A swap replaces only the frame; everything inside stays."));

        String heldUnlock = RoomBuilder.shellUnlockOf(player.getMainHandItem());
        List<ActionButton> buttons = new ArrayList<>();
        for (RoomBuilder.ShellPalette palette : RoomBuilder.shellOrder()) {
            String name = palette.name();
            if (RoomBuilder.isDefaultShell(name) || unlocked.contains(name)) {
                buttons.add(shellButton(player.getUUID(), palette, "Apply ",
                        "Swap your room's frame to " + palette.displayName(),
                        ACTION_APPLY_SHELL));
            } else if (name.equals(heldUnlock)) {
                buttons.add(shellButton(player.getUUID(), palette, "Unlock ",
                        "Consumes the token you are holding", ACTION_UNLOCK_SHELL));
            } else {
                body.add(DialogKit.text(Component.literal(
                        palette.displayName() + ": " + palette.unlockHint())
                        .withStyle(ChatFormatting.GRAY)));
            }
        }
        return DialogKit.list("Change shell", body, buttons, backToMenuButton(player.getUUID()));
    }

    /** One shell button: Apply for an available palette, Unlock for a held token. */
    private static ActionButton shellButton(UUID owner, RoomBuilder.ShellPalette palette,
                                            String verb, String tooltip, String action) {
        CompoundTag context = new CompoundTag();
        context.putString(KEY_OWNER, owner.toString());
        context.putString(KEY_SHELL, palette.name());
        return DialogKit.button(verb + palette.displayName(), tooltip,
                DialogKit.submit(action, context));
    }

    /**
     * A screen's way back to the one that opened it: a button that asks
     * {@link DialogRouter} to rebuild the parent from current state. Carries
     * {@link #KEY_OWNER} like every other routed button, so the router's owner
     * check passes.
     */
    private static ActionButton backButton(String label, String action, UUID owner) {
        CompoundTag context = new CompoundTag();
        context.putString(KEY_OWNER, owner.toString());
        return DialogKit.button(label, null, DialogKit.submit(action, context));
    }

    // ---- section 14: diaries (M26) -----------------------------------------

    /**
     * The lodestone menu's Diaries option: all seven of Alex's entries, in
     * their canonical numeric order. A discovered entry is a button that
     * opens {@link #diaryReader}; an undiscovered one is a plain grey
     * "Entry N: ???" line, the same locked-content shape {@link
     * #shellPicker} already uses for a shell the player has not unlocked --
     * never a clickable dead end.
     */
    static Dialog diaryList(MinecraftServer server, UUID owner) {
        Set<Integer> seen = DungeonLog.forServer(server).get(owner).diaryBandsSeen();
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogKit.text("Alex's diaries. Found out of order, on purpose."));
        List<ActionButton> buttons = new ArrayList<>();
        for (Diaries.Entry diaryEntry : Diaries.current().entries()) {
            if (seen.contains(diaryEntry.band())) {
                buttons.add(showDialogButton("Entry " + diaryEntry.number() + ": " + diaryEntry.title(),
                        diaryReader(diaryEntry, owner)));
            } else {
                body.add(DialogKit.text(Component.literal("Entry " + diaryEntry.number() + ": ???")
                        .withStyle(ChatFormatting.GRAY)));
            }
        }
        // MultiActionDialog rejects an empty actions list ("List must have
        // contents"), so before any entry is discovered the screen is a notice
        // with the Back button as its one action, the same shape the lobby
        // directory uses when nobody is listing a room.
        if (buttons.isEmpty()) {
            return DialogKit.notice("Diaries", body, backToMenuButton(owner));
        }
        return DialogKit.list("Diaries", body, buttons, backToMenuButton(owner));
    }

    /**
     * (M26) One diary's reader: its pages in their canonical, unshuffled
     * order -- the source {@link DiaryDelivery} itself shuffles for the
     * physical book, never this screen, so a player who lost the book can
     * still read the real thing here. Back re-opens the list through
     * {@link DialogRouter} rather than a stored reference to it, so a diary
     * found while this screen was open shows up discovered the moment the
     * player backs out.
     */
    static Dialog diaryReader(Diaries.Entry entry, UUID owner) {
        List<DialogBody> body = new ArrayList<>();
        for (String page : entry.pages()) {
            body.add(DialogKit.text(page));
        }
        return DialogKit.notice("Entry " + entry.number() + ": " + entry.title(), body,
                backButton("Back", ACTION_DIARIES, owner));
    }

    /** A button that swaps to another screen on the client, committing nothing. */
    private static ActionButton showDialogButton(String label, Dialog dialog) {
        return new ActionButton(
                new net.minecraft.server.dialog.CommonButtonData(
                        Component.literal(label), Optional.empty(), DialogKit.WIDE),
                Optional.of(new StaticAction(
                        new ClickEvent.ShowDialog(net.minecraft.core.Holder.direct(dialog)))));
    }
}
