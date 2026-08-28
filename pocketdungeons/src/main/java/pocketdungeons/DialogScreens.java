package pocketdungeons;

import net.minecraft.ChatFormatting;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
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

    /** M16: which slot and tier the gamble picker's button chose. */
    static final String KEY_SLOT = "pd_slot";
    static final String KEY_TIER = "pd_tier";
    static final String ACTION_GAMBLE = "gamble";

    /** M17: which extracted power the Cube's imbue picker's button chose. */
    static final String KEY_POWER = "pd_power";
    static final String ACTION_IMBUE = "imbue";

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

    /**
     * Every unlocked slot/tier combination, one "Gamble" button each. Tier B,
     * like {@link #rerollPicker}: the button carries the slot name and tier
     * so {@link GambleStation#handleGamble} can re-validate both (and the
     * emerald count) against the player's live state, since the keystone
     * level that unlocked a tier and the emeralds to pay for it can both
     * change while the screen sits open.
     *
     * <p>{@code maxTier} is read off {@link KeystoneMath#lootTier}, the same
     * level-to-tier mapping a run's own loot already uses: a low-level
     * player sees only tier-1 gambles, the same "level gates access" rule
     * M10/M12/M14 already use.
     */
    static Dialog gamblePicker(UUID player, int maxTier, String notice) {
        List<DialogBody> body = new ArrayList<>();
        if (notice != null) {
            body.add(DialogKit.text(Component.literal(notice).withStyle(ChatFormatting.YELLOW)));
        }
        body.add(DialogKit.text("Pick a slot and a tier. No guarantee of quality, just of fit."));

        List<ActionButton> buttons = new ArrayList<>();
        for (String slot : LootTables.GEAR_SLOTS) {
            for (int tier = 1; tier <= maxTier; tier++) {
                int cost = GambleMath.cost(tier, slot, PocketDungeonsConfig.gambleEmeraldsPerTier(),
                        PocketDungeonsConfig.gambleSlotMultiplier(), PocketDungeonsConfig.gambleWeightedSlot());
                CompoundTag context = new CompoundTag();
                context.putString(KEY_OWNER, player.toString());
                context.putString(KEY_SLOT, slot);
                context.putInt(KEY_TIER, tier);
                buttons.add(DialogKit.button(capitalize(slot) + ", tier " + tier + " (" + cost + " emeralds)",
                        null, DialogKit.submit(ACTION_GAMBLE, context)));
            }
        }
        if (buttons.isEmpty()) {
            body.add(DialogKit.text("Your keystone does not clear tier 1 yet."));
            return DialogKit.notice("Gamble station", body);
        }
        return DialogKit.list("Gamble station", body, buttons, "Close");
    }

    private static String capitalize(String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

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
}
