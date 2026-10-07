package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a clicked dialog button lands when it could not be expressed as a fixed
 * command string -- "remove <i>this</i> entry", "add the name I typed".
 *
 * <p>Always entered on the server main thread. The packet arrives on a netty IO
 * thread and {@code CustomClickMixin} hops with {@code server.execute} before
 * calling in here; {@code RoomWhitelist} is a {@code SavedData} and every
 * {@code Instances} map is single-threaded by design, so that hop is load-bearing
 * rather than defensive.
 *
 * <p>Nothing here decides anything. It parses a payload, re-reads live state
 * instead of trusting what the screen was showing when it opened, and calls the
 * same methods {@code /dungeon room whitelist add|remove} call. Every read has a
 * default rather than a throw: a malformed payload is something a modified client
 * can send whenever it likes, and it must produce a sentence in chat, never an
 * exception on a network thread.
 */
public final class DialogRouter {

    private DialogRouter() {}

    /** Dispatches one {@code pocketdungeons:} custom-click payload from the packet mixin. */
    public static void handle(ServerPlayer player, Identifier id, Optional<Tag> payload) {
        // PD-21: the mixin defers this call through server.execute, a real gap
        // in which the player can disconnect. Every other check below is about
        // whether the payload is trustworthy; this one is about whether the
        // player it would act on is still here at all, since some actions
        // (unlockShell shrinking a held item, applyShell mutating the world,
        // startDungeon teleporting) are not safe to run on a departed player.
        if (player.hasDisconnected()) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        if (!(payload.orElse(null) instanceof CompoundTag tag)) {
            PocketDungeonsMod.LOG.warn("Dialog action {} arrived without a compound payload", id);
            return;
        }
        PocketDungeonsMod.LOG.debug("Dialog action {} payload {}", id, tag);

        // Every screen that reaches this router is one an owner opened on their own
        // room, so the owner in the payload must be the player who clicked. A
        // mismatch is not a stale screen, it is a forged one.
        UUID owner = uuid(tag.getStringOr(DialogScreens.KEY_OWNER, ""));
        if (owner == null || !owner.equals(player.getUUID())) {
            PocketDungeonsMod.LOG.warn("Dialog action {} from {} carried owner {}",
                    id, player.getName().getString(), tag.getStringOr(DialogScreens.KEY_OWNER, ""));
            return;
        }

        switch (id.getPath()) {
            case DialogScreens.ACTION_WHITELIST_REMOVE -> whitelistRemove(player, server, owner,
                    uuid(tag.getStringOr(DialogScreens.KEY_TARGET, "")));
            case DialogScreens.ACTION_WHITELIST_ADD -> whitelistAdd(player, server, owner,
                    tag.getStringOr(DialogScreens.KEY_NAME, "").trim());
            case DialogScreens.ACTION_REROLL -> RerollStation.handleReroll(player,
                    tag.getStringOr(DialogScreens.KEY_ENCHANT, ""));
            case DialogScreens.ACTION_IMBUE -> CubeStation.handleImbue(player,
                    tag.getStringOr(DialogScreens.KEY_POWER, ""));
            case DialogScreens.ACTION_VISIT_ROOM -> visitRoom(player, server,
                    uuid(tag.getStringOr(DialogScreens.KEY_TARGET, "")));
            // M75: the private visit channel. Same routing service as the
            // public lobby directory, separate admit check: the whitelist, not
            // publicListed, gates which rooms appear and which clicks succeed.
            case DialogScreens.ACTION_BROWSE_FRIENDS -> browseFriends(player, server);
            case DialogScreens.ACTION_VISIT_FRIEND -> visitFriend(player, server,
                    uuid(tag.getStringOr(DialogScreens.KEY_TARGET, "")));
            // M21: the wall-lodestone menu's options. The keystone is checked
            // here, on the click, never when the menu opened.
            case DialogScreens.ACTION_START_DUNGEON -> startDungeon(player);
            case DialogScreens.ACTION_BROWSE_LOBBIES -> browseLobbies(player, server);
            case DialogScreens.ACTION_MANAGE_ROOM -> manageRoom(player, server);
            case DialogScreens.ACTION_INSPECT_KEYSTONE -> inspectKeystone(player);
            case DialogScreens.ACTION_LEAVE_DUNGEON ->
                    RunLifecycle.exit(player, RunLifecycle.ExitReason.COMMAND);
            case DialogScreens.ACTION_QUIT_DUNGEON ->
                    DialogKit.show(player, DialogScreens.quitDoorConfirm());
            case DialogScreens.ACTION_RESET_KEY ->
                    DialogKit.show(player, DialogScreens.resetKeyConfirm());
            case DialogScreens.ACTION_SET_ROOM_NAME -> setRoomName(player, server,
                    tag.getStringOr(DialogScreens.KEY_NAME, "").trim());
            case DialogScreens.ACTION_TOGGLE_PUBLIC -> togglePublic(player, server);
            case DialogScreens.ACTION_CHANGE_SHELL -> changeShellMenu(player);
            case DialogScreens.ACTION_APPLY_SHELL -> applyShell(player,
                    tag.getStringOr(DialogScreens.KEY_SHELL, ""));
            case DialogScreens.ACTION_UNLOCK_SHELL -> unlockShell(player,
                    tag.getStringOr(DialogScreens.KEY_SHELL, ""));
            case DialogScreens.ACTION_DIARIES -> diaries(player, server);
            case DialogScreens.ACTION_READ_DIARY -> readDiary(player, server,
                    tag.getIntOr(DialogScreens.KEY_DIARY, 0));
            case DialogScreens.ACTION_STATIONS -> StationPicker.open(player);
            case DialogScreens.ACTION_BACK_MENU -> backToMenu(player);
            case DialogScreens.ACTION_BACK_WHITELIST -> reshow(player, server, owner, null);
            // M48: the bag picker. A picker button carries a bag id and opens
            // the confirm dialog; the confirm dialog's Back button re-opens the
            // picker (it carries no bag id, which is the signal to do so).
            case DialogScreens.ACTION_SELECT_BAG -> selectBag(player,
                    tag.getStringOr(DialogScreens.KEY_BAG_ID, ""));
            case DialogScreens.ACTION_CONFIRM_BAG -> confirmBag(player, server,
                    tag.getStringOr(DialogScreens.KEY_BAG_ID, ""));
            default -> PocketDungeonsMod.LOG.warn("Unknown dialog action {}", id);
        }
    }

    private static void whitelistRemove(ServerPlayer owner, MinecraftServer server,
                                        UUID ownerId, UUID target) {
        if (target == null) {
            reshow(owner, server, ownerId, "That entry could not be read.");
            return;
        }
        // The live whitelist, not the one the screen was built from: it can change
        // from another session, or be emptied by an admin resetroom, while a screen
        // sits open.
        boolean removed = RoomWhitelist.forServer(server).remove(ownerId, target);
        reshow(owner, server, ownerId, removed
                ? null
                : "They were not on the list any more.");
    }

    private static void whitelistAdd(ServerPlayer owner, MinecraftServer server,
                                     UUID ownerId, String name) {
        if (name.isEmpty()) {
            reshow(owner, server, ownerId, "Type a player name first.");
            return;
        }
        // Online-only, exactly like the command: /dungeon room whitelist add takes an
        // EntityArgument.player(). Resolving offline names would be a second lookup
        // path with its own failure modes, and a rule the command does not have.
        ServerPlayer target = server.getPlayerList().getPlayerByName(name);
        if (target == null) {
            reshow(owner, server, ownerId, name + " is not online.");
            return;
        }
        if (target.getUUID().equals(ownerId)) {
            reshow(owner, server, ownerId, "You already own your room.");
            return;
        }
        boolean added = RoomWhitelist.forServer(server).add(ownerId, target.getUUID());
        reshow(owner, server, ownerId, added
                ? null
                : target.getName().getString() + " was already whitelisted.");
    }

    /**
     * Rebuilds the list from current state and sends it back.
     *
     * <p>There is no history stack in this API, so a click that ends without this
     * ends on a closed screen with no way back in. Every path above -- success,
     * rejection, a stale entry -- lands here, which is what makes this a manager
     * rather than a one-shot form.
     */
    private static void reshow(ServerPlayer owner, MinecraftServer server, UUID ownerId,
                               String notice) {
        if (notice != null) {
            Chime.refused(owner); // a notice line on this screen is always a refusal
        }
        List<UUID> entries = new ArrayList<>(RoomWhitelist.forServer(server).get(ownerId));
        entries.sort(Comparator.comparing(UUID::toString));
        DialogKit.show(owner, DialogScreens.whitelist(server, ownerId, entries, notice));
    }

    /**
     * The menu's Start Dungeon option. The keystone is checked by
     * {@link RunLifecycle#enterWithKeystone}, on the click, never when the
     * menu opened: the menu opens with any item or an empty hand, so a player
     * who just wants to leave or browse never needs their compass first.
     * {@code enterWithKeystone} reads the keystone from the inventory, not the
     * hand, and handles free re-entry into a live owned instance before any
     * keystone check, so a returning owner is never asked to hold their key.
     */
    private static void startDungeon(ServerPlayer player) {
        if (RunLifecycle.enterWithKeystone(player)) {
            // After the entry, not before it. Played up front, the cue fired
            // on refusals too, so a run that never opened sounded exactly like
            // one that did. Sending it across the teleport is the pattern
            // VisitService already uses for visitStarts, and it carries.
            // M22: a per-player packet, not a room-wide broadcast.
            Chime.runStarts(player);
            FloorStartTitle.show(player.level().getServer(), InstanceRegistry.byMember.get(player.getUUID()));
            player.sendSystemMessage(Component.literal("The lodestone pulls you under.")
                    .withStyle(ChatFormatting.DARK_PURPLE));
            return;
        }
        Chime.refused(player);
        // enterWithKeystone shrinks the stack only after the run has actually
        // opened, so a player still holding nothing is one it turned away for
        // want of a keystone rather than for a full server or a failed stamp.
        // Those refusals say their own piece in chat; this one gets a screen
        // with the command on it, because the alternative is a closed dialog
        // and a line telling the player to go type something.
        if (Keystone.findHeld(player) == null) {
            DialogKit.show(player, DialogScreens.noKeystone(player.getUUID()));
        }
    }

    /** The menu's Browse Lobbies option: M20's directory, invoked verbatim. */
    private static void browseLobbies(ServerPlayer player, MinecraftServer server) {
        DialogKit.show(player, DialogScreens.lobbyBrowser(server, player.getUUID()));
        Chime.lobbyOpens(player);
    }

    /**
     * M75: the menu's Visit a Friend option, the private counterpart of
     * {@link #browseLobbies}. Lists rooms whose owner has whitelisted this
     * player, never rooms they were not explicitly invited to.
     */
    private static void browseFriends(ServerPlayer player, MinecraftServer server) {
        DialogKit.show(player, DialogScreens.friendBrowser(server, player.getUUID()));
        Chime.lobbyOpens(player);
    }

    /** The menu's Manage Room option: the whitelist plus name and visibility. */
    private static void manageRoom(ServerPlayer player, MinecraftServer server) {
        DialogKit.show(player, DialogScreens.manageRoom(server, player.getUUID()));
    }

    /** Opens an unlocked diary entry in the book screen; an entry not yet found re-shows the list. */
    private static void readDiary(ServerPlayer player, MinecraftServer server, int number) {
        Diaries.Entry entry = null;
        for (Diaries.Entry candidate : Diaries.current().entries()) {
            if (candidate.number() == number) {
                entry = candidate;
            }
        }
        boolean unlocked = entry != null
                && DungeonLog.forServer(server).get(player.getUUID()).diaryBandsSeen().contains(entry.band());
        if (!unlocked) {
            diaries(player, server);
            return;
        }
        DiaryReading.openBook(player, DiaryDelivery.book(entry, false));
    }

    /** The menu's Diaries option, and the reader's own Back button (both re-open the same list). */
    private static void diaries(ServerPlayer player, MinecraftServer server) {
        DialogKit.show(player, DialogScreens.diaryList(server, player.getUUID()));
    }

    /**
     * The menu's Inspect Keystone option, the same screen
     * {@code /dungeon key info} opens.
     */
    private static void inspectKeystone(ServerPlayer player) {
        if (Keystone.findHeld(player) == null) {
            Chime.refused(player);
            DialogKit.show(player, DialogScreens.noKeystone(player.getUUID()));
            return;
        }
        DialogKit.show(player, DialogScreens.inspectKeystone(player,
                DialogScreens.backToMenuButton(player.getUUID())));
    }

    /**
     * Every Back button in the menu's tree. There is no history stack in this
     * API, so back is the menu rebuilt from live context rather than a screen
     * popped off a stack: the player's dimension decides which menu they get,
     * exactly as it does when they right-click the wall lodestone.
     */
    private static void backToMenu(ServerPlayer player) {
        boolean inDungeon = player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL);
        DialogKit.show(player, DialogScreens.lodestoneMenu(player, inDungeon));
    }

    /** The manage-room screen's name entry; caps at 16 like the command. */
    private static void setRoomName(ServerPlayer owner, MinecraftServer server, String name) {
        // PD-37: same stripping the command path applies, so this routed
        // path cannot smuggle a formatting code past it.
        name = name.replace("§", "");
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }
        DungeonLog.forServer(server).setRoomName(owner.getUUID(), name);
        DialogKit.show(owner, DialogScreens.manageRoom(server, owner.getUUID()));
    }

    /** The manage-room screen's visibility toggle; flips and re-shows. */
    private static void togglePublic(ServerPlayer owner, MinecraftServer server) {
        DungeonLog log = DungeonLog.forServer(server);
        boolean listed = !log.get(owner.getUUID()).publicListed();
        log.setPublicListed(owner.getUUID(), listed);
        if (listed) {
            Chime.roomListed(owner);
        } else {
            Chime.roomUnlisted(owner);
        }
        DialogKit.show(owner, DialogScreens.manageRoom(server, owner.getUUID()));
    }

    /**
     * The in-dungeon menu's Change Shell option: opens the shell picker, which
     * needs the player's live room record to know where the frame stands. The
     * option is owner-only by construction (menuOptions), and this re-checks
     * it against live state the same way every other routed action does.
     */
    private static void changeShellMenu(ServerPlayer player) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !player.getUUID().equals(record.owner)
                || record.roomCellOrigin == null || record.visitInstance) {
            Chime.refused(player);
            DialogKit.show(player, DialogScreens.lodestoneMenu(player, true));
            return;
        }
        DialogKit.show(player, DialogScreens.shellPicker(player, record));
    }

    /**
     * A Change Shell picker Apply click: re-validates the unlock against live
     * state (the screen can sit open while things change, and the payload can
     * be forged, so neither is trusted) and swaps the room's frame through
     * {@code RoomBuilder.rebuildShell}, which is the whole of the mechanic:
     * capture the interior as the safety net, stamp the new frame, re-place.
     */
    private static void applyShell(ServerPlayer player, String shellName) {
        RoomBuilder.ShellPalette palette = RoomBuilder.palette(shellName);
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !player.getUUID().equals(record.owner)
                || record.roomCellOrigin == null || record.visitInstance) {
            Chime.refused(player);
            backToMenu(player);
            return;
        }
        DungeonLog log = DungeonLog.forServer(player.level().getServer());
        if (!RoomBuilder.isDefaultShell(shellName)
                && !log.get(player.getUUID()).unlockedShells().contains(shellName)) {
            Chime.refused(player);
            player.sendSystemMessage(Component.literal(
                    "You have not unlocked the " + palette.displayName() + " shell.")
                    .withStyle(ChatFormatting.YELLOW));
            reshowShellPicker(player);
            return;
        }
        MinecraftServer server = player.level().getServer();
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            Chime.refused(player);
            player.sendSystemMessage(Component.literal("The dungeon dimension is not loaded.")
                    .withStyle(ChatFormatting.RED));
            reshowShellPicker(player);
            return;
        }
        if (!RoomBuilder.rebuildShell(level, server, record, palette)) {
            Chime.refused(player);
            player.sendSystemMessage(Component.literal(
                    "Your room could not be saved, so its frame is unchanged. Tell an operator.")
                    .withStyle(ChatFormatting.RED));
            reshowShellPicker(player);
            return;
        }
        player.sendSystemMessage(Component.literal(
                "Your room's frame is now " + palette.displayName() + ".")
                .withStyle(ChatFormatting.GOLD));
        reshowShellPicker(player);
    }

    /**
     * A Change Shell picker Unlock click: consumes the held shell token for
     * {@code shellName} and adds the palette to the player's permanent set.
     * The held stack is re-read on the click, so a screen that sat open while
     * the player swapped hands degrades to a refusal, never a wrong unlock.
     */
    private static void unlockShell(ServerPlayer player, String shellName) {
        RoomBuilder.ShellPalette palette = RoomBuilder.palette(shellName);
        if (!shellName.equals(RoomBuilder.shellUnlockOf(player.getMainHandItem()))) {
            Chime.refused(player);
            player.sendSystemMessage(Component.literal(
                    "Hold the " + palette.displayName() + " shell token to unlock it.")
                    .withStyle(ChatFormatting.YELLOW));
            reshowShellPicker(player);
            return;
        }
        DungeonLog log = DungeonLog.forServer(player.level().getServer());
        if (log.get(player.getUUID()).unlockedShells().contains(shellName)) {
            Chime.refused(player);
            player.sendSystemMessage(Component.literal("You have already unlocked that shell.")
                    .withStyle(ChatFormatting.YELLOW));
            reshowShellPicker(player);
            return;
        }
        player.getMainHandItem().shrink(1);
        log.unlockShell(player.getUUID(), shellName);
        player.sendSystemMessage(Component.literal(
                "Unlocked the " + palette.displayName() + " shell. It is yours permanently.")
                .withStyle(ChatFormatting.GOLD));
        reshowShellPicker(player);
    }

    /** Rebuilds the Change Shell picker from live state after a click. */
    private static void reshowShellPicker(ServerPlayer player) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || record.roomCellOrigin == null) {
            backToMenu(player);
            return;
        }
        DialogKit.show(player, DialogScreens.shellPicker(player, record));
    }

    /**
     * A lobby-directory button was clicked: visit the listed room's owner.
     *
     * <p>The target is re-read as a UUID and the visit re-validated against
     * live state, never trusted from the snapshot the screen was built from
     * (the stale-state guard, same shape as the whitelist actions). A room
     * that went private, or an owner who logged off, while the screen sat
     * open is refused here, before {@link VisitService#visit} is reached. A
     * room still listed and an owner still online go on to
     * {@link VisitService#visit}, which re-resolves the instance (the owner's
     * live room, an existing visit copy, or a fresh read-only stamp) and says
     * why in chat when it refuses. Either way a rejected click ends on a
     * re-shown directory with a reason line, so the player can act from it,
     * per DIALOGS_SPEC section 7.
     */
    private static void visitRoom(ServerPlayer clicker, MinecraftServer server, UUID target) {
        if (target == null) {
            reshowLobby(clicker, server, "That room could not be read.");
            return;
        }
        if (!DungeonLog.forServer(server).get(target).publicListed()) {
            reshowLobby(clicker, server, "That room is no longer public.");
            return;
        }
        if (server.getPlayerList().getPlayer(target) == null) {
            reshowLobby(clicker, server, "The room owner is offline.");
            return;
        }
        if (VisitService.visit(clicker, target)) {
            return; // teleported; the visit's own chat line is the confirmation
        }
        reshowLobby(clicker, server, "That room is not open any more.");
    }

    /** Rebuilds the lobby directory from current state and sends it back, with a reason line. */
    private static void reshowLobby(ServerPlayer clicker, MinecraftServer server, String notice) {
        if (notice != null) {
            Chime.refused(clicker); // as in reshow: a notice here is a room that said no
        }
        DialogKit.show(clicker, DialogScreens.lobbyBrowser(server, clicker.getUUID(), notice));
    }

    /**
     * M75: a friend-visit button was clicked. The private counterpart of
     * {@link #visitRoom}: the target is re-read as a UUID and the visit
     * re-validated against live state, never trusted from the snapshot the
     * screen was built from. The admit check is the whitelist (not
     * {@code publicListed}), so an owner who removed the clicker while the
     * screen sat open is refused here, before {@link VisitService#visit} is
     * reached. A click that still passes goes on to the same
     * {@link VisitService#visit} call the public directory uses, so the two
     * channels can never disagree about which instance a visitor lands in.
     * A rejected click ends on a re-shown friend directory with a reason
     * line, the same shape as the public directory's stale-state guard.
     */
    private static void visitFriend(ServerPlayer clicker, MinecraftServer server, UUID target) {
        if (target == null) {
            reshowFriends(clicker, server, "That room could not be read.");
            return;
        }
        if (!RoomWhitelist.forServer(server).isPermitted(target, clicker.getUUID())) {
            reshowFriends(clicker, server, "You are no longer invited to that room.");
            return;
        }
        if (server.getPlayerList().getPlayer(target) == null) {
            reshowFriends(clicker, server, "The room owner is offline.");
            return;
        }
        if (VisitService.visit(clicker, target)) {
            return; // teleported; the visit's own chat line is the confirmation
        }
        reshowFriends(clicker, server, "That room is not open any more.");
    }

    /** Rebuilds the friend-visit directory from current state and sends it back, with a reason line. */
    private static void reshowFriends(ServerPlayer clicker, MinecraftServer server, String notice) {
        if (notice != null) {
            Chime.refused(clicker);
        }
        DialogKit.show(clicker, DialogScreens.friendBrowser(server, clicker.getUUID(), notice));
    }

    // ---- M48: bag selection -------------------------------------------------

    /**
     * A bag-picker button click. A non-empty bag id opens the confirm dialog
     * for that bag; an empty one (the confirm dialog's Back button, which
     * carries only {@link DialogScreens#KEY_OWNER}) re-opens the picker, since
     * this API has no client-side history stack.
     */
    private static void selectBag(ServerPlayer player, String bagId) {
        if (bagId.isEmpty()) {
            DialogKit.show(player, DialogScreens.bagPicker(player));
            return;
        }
        BagDefinition picked = Bags.byId(bagId);
        if (picked == null || picked.hidden) {
            Chime.refused(player);
            return;
        }
        DialogKit.show(player, DialogScreens.bagConfirm(player, bagId));
    }

    /**
     * A bag-confirm click: re-validates against live state (the player must
     * still be in the dungeon dimension, be a member of a non-visit instance,
     * and have no bag assigned), then records the bag on their
     * {@link DungeonLog} entry, rolls the bag's kit into their inventory (the
     * one time it is ever handed over; what does not fit waits in their
     * dungeon inventory). The chest stays standing (PD-131); it is cleared on
     * door-choice and save, so a member who skips it simply enters with the
     * keystone alone and gets the chest back next lobby.
     */
    private static void confirmBag(ServerPlayer player, MinecraftServer server, String bagId) {
        BagDefinition bag = Bags.byId(bagId);
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (bag == null || bag.hidden || record == null || record.visitInstance || record.roomCellOrigin == null
                || !player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            Chime.refused(player);
            return;
        }
        DungeonLog log = DungeonLog.forServer(server);
        if (!log.bagOf(player.getUUID()).isEmpty()) {
            // Already chosen, possibly on another screen that sat open. Do not
            // overwrite a chosen bag from a stale click.
            Chime.refused(player);
            player.sendSystemMessage(Component.literal("You have already chosen a bag.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        log.setBag(player.getUUID(), bagId);
        PlaytestJournal.bagChosen(player, bagId);
        FirstVisitTutorial.bagChosen(server, record, player);
        List<ItemStack> leftover = Bags.apply(player, bagId);
        if (leftover != null) {
            // The kit is granted once, here. Entering and leaving never hand
            // it out again, and nothing refills it (design D14).
            log.setKitGranted(player.getUUID(), true);
            InventorySwap.keepForNextEntry(log, player.getUUID(), leftover);
        }
        // PD-131: the chest is not cleared on a pick. It used to go once every
        // member present had chosen, so a companion who was between rejoins at
        // that moment came back to no chest. It stays until the door is chosen
        // or the room is saved, which clear it already, and a click from
        // someone who has a bag says so (RitualListener).
        player.sendSystemMessage(Component.literal(
                "You chose " + bag.label
                        + ". It is yours until you reset your compass.")
                .withStyle(ChatFormatting.GOLD));
    }

    private static UUID uuid(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
