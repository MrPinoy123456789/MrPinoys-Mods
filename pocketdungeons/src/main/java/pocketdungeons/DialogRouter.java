package pocketdungeons;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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
            case DialogScreens.ACTION_GAMBLE -> GambleStation.handleGamble(player,
                    tag.getStringOr(DialogScreens.KEY_SLOT, ""), tag.getIntOr(DialogScreens.KEY_TIER, 0));
            case DialogScreens.ACTION_IMBUE -> CubeStation.handleImbue(player,
                    tag.getStringOr(DialogScreens.KEY_POWER, ""));
            case DialogScreens.ACTION_VISIT_ROOM -> visitRoom(player, server,
                    uuid(tag.getStringOr(DialogScreens.KEY_TARGET, "")));
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
        List<UUID> entries = new ArrayList<>(RoomWhitelist.forServer(server).get(ownerId));
        entries.sort(Comparator.comparing(UUID::toString));
        DialogKit.show(owner, DialogScreens.whitelist(server, ownerId, entries, notice));
    }

    /**
     * A lobby-directory button was clicked: visit the listed room's owner.
     *
     * <p>The target is re-read as a UUID and the visit re-resolved against live
     * state, never trusted from the snapshot the screen was built from (the
     * stale-state guard, same shape as the whitelist actions): an owner who
     * logged off, went private, or started a run while the screen sat open
     * must not receive a visitor on stale faith. {@link VisitService#visit}
     * already says why in chat when it refuses; the directory is re-shown with
     * a reason line so a rejected click still ends on a screen the player can
     * act from, per DIALOGS_SPEC section 7.
     */
    private static void visitRoom(ServerPlayer clicker, MinecraftServer server, UUID target) {
        if (target == null) {
            reshowLobby(clicker, server, "That room could not be read.");
            return;
        }
        if (VisitService.visit(clicker, target)) {
            return; // teleported; the visit's own chat line is the confirmation
        }
        reshowLobby(clicker, server, "That room is not open any more.");
    }

    /** Rebuilds the lobby directory from current state and sends it back, with a reason line. */
    private static void reshowLobby(ServerPlayer clicker, MinecraftServer server, String notice) {
        DialogKit.show(clicker, DialogScreens.lobbyBrowser(server, clicker.getUUID(), notice));
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
