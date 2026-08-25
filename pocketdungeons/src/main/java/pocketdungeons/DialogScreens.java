package pocketdungeons;

import net.minecraft.ChatFormatting;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    // ---- section 1: the door offer ------------------------------------------

    /**
     * The offer behind one selector door. Deliberately per-door and never a
     * combined three-door picker: {@code VISION.md} section 1's hook is that you
     * walk to a door and spend your keystone on it, and that walk is the mechanic,
     * not a UI limitation waiting to be tidied away.
     *
     * <p>No stale-offer guard here, because there is already one that cannot be
     * bypassed: {@code chooseOffer} re-validates against {@code DungeonLog}'s live
     * pending offer, so a dialog left open across a state change fails exactly the
     * way a stale chat link already does.
     */
    static Dialog doorOffer(Keystone.Offer offer, int step, String heading) {
        List<DialogBody> body = new ArrayList<>();
        java.util.List<Affix> ordered = AffixMath.ordered(offer.affixes());
        Affix doorAffix = ordered.isEmpty() ? null : ordered.get(0);
        body.add(DialogKit.text(Component.literal(heading).withStyle(Keystone.colourOf(doorAffix))));
        if (doorAffix != null) {
            body.add(DialogKit.text(Component.literal(doorAffix.blurb).withStyle(ChatFormatting.GRAY)));
        }
        // A NoticeDialog has exactly one button by construction, so there is no
        // "Decline" to add here. Walking away from the door -- or Escape -- is the
        // no, and nothing has been spent until this button is pressed.
        return DialogKit.notice("Door " + step, body,
                DialogKit.command("Take this key", "Spends your run on this door",
                        "/dungeon choose " + step));
    }

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
        return DialogKit.notice("Your keystone", body);
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
                                whitelistAdd(owner)))))));

        return DialogKit.list("Room whitelist", body, buttons, "Close");
    }

    /**
     * The add-a-player screen: one text field, "Add" and "Cancel".
     *
     * <p>A {@code ConfirmationDialog} and not a {@code NoticeDialog} because a
     * notice has exactly one button, which would leave Escape as the only way to
     * back out of a form somebody opened by accident.
     */
    static Dialog whitelistAdd(UUID owner) {
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
                DialogKit.closeButton("Cancel"));
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
}
