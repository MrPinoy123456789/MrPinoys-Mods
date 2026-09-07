package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The game loop: entry, the door choice out of the lobby, completion, exit,
 * and what ends a run early. Sixth and last of {@code Instances}' six M9 C3
 * extractions -- every mechanic in {@code docs/DOOR_LADDER_BRAINSTORM.md} edits
 * here, which is exactly why it should not share a file with slot arithmetic.
 *
 * <p>Revised during implementation from the plan's original scope
 * (plans/COMPLETED-MILESTONES.md M9's PartyService/RunLifecycle rows): {@code dropMember}
 * and {@code leadershipChanged} were briefly scoped to {@link PartyService}
 * instead, then moved here once it was clear both call {@link InstanceTeardown#purge}
 * and {@link #saveRoomIfOwner}, run-teardown machinery, not party bookkeeping.
 *
 * <p>Calls back into {@code Instances} for the lobby-stamping and admission
 * plumbing that stays there because the tick watcher and the admin commands
 * need it too: {@code buildLayout}, {@code admit}, {@code applyTrialOmen},
 * {@code clearTrialOmen}, {@code enterLobby}, {@code generateBehindLobby},
 * {@code mcDirection}, {@code eject}, {@code announce},
 * {@code purgeIfAbandonedLobby}, {@code roomOwnerAt}, {@code teleport},
 * {@code clearCellSync} and {@code sendToWorldSpawn} -- all opened from
 * {@code private} to package-visible for exactly this, the same pattern
 * every earlier C3 extraction used.
 */
final class RunLifecycle {

    private RunLifecycle() {}

    // ---- the void inventory, on the way out (M46, spec 11.6 and 11.9) ------

    /**
     * Spec 11.9's belt and braces. Anything in the void inventory that is
     * neither bag loot nor the keystone got there without this mod's
     * involvement, which is the "another mod put a netherite sword in my
     * inventory mid-run" case.
     *
     * <p>It is a warning, not a confiscation and not a divert into the survival
     * backup. The stacks still go to the room with everything else: they might
     * be legitimate room items the player was holding, and the backup is
     * restored whole either way. The one thing this must never do is put an
     * untagged stack anywhere near the survival restore.
     *
     * <p>Package-private so {@link InventorySwap}'s leaving branch can call
     * it, since the delivery logic lives there.
     */
    static void warnAboutUntagged(ServerPlayer player, List<ItemStack> carried) {
        List<ItemStack> strays = InventorySwap.untagged(carried, ItemStack::isEmpty, InventorySwap::isOurs);
        if (strays.isEmpty()) {
            return;
        }
        List<String> untagged = new ArrayList<>();
        for (ItemStack stack : strays) {
            untagged.add(stack.getCount() + "x " + stack.getItem());
        }
        PocketDungeonsMod.LOG.warn(
                "{} left the dungeon carrying {} untagged stacks; they were not bag loot and "
                        + "not a keystone, and have been delivered to the room rather than "
                        + "restored with survival: {}",
                player.getName().getString(), untagged.size(), String.join(", ", untagged));
        // Said out loud as well as logged. A player who finds an item missing
        // should be told where it went at the moment it moves, not left to
        // discover it and file a bug about lost gear.
        player.sendSystemMessage(Component.literal(untagged.size()
                        + (untagged.size() == 1 ? " item was" : " items were")
                        + " left in your room: they came into the dungeon from outside the run.")
                .withStyle(ChatFormatting.YELLOW));
    }

    /**
     * Fills the room's own containers first, then returns whatever did not
     * fit so the caller can hold it for the player's next entry rather than
     * dropping it into the dungeon dimension where it can be lost to a
     * teardown.
     *
     * <p>The scan is the same shape {@code BlacksmithNPC.findSmithingTable}
     * uses: at most ~1500 block reads, once, when somebody walks out of a
     * dungeon.
     *
     * <p>PD-65: this method used to drop overflow at the room's pad position
     * inside the dungeon dimension via {@code Block.popResource}. If the room
     * was being torn down at the same time (an abandoned lobby purge firing
     * after "Leave Dungeon"), the floor under that pad position could be
     * cleared before the item landed, and the item fell into the void and
     * was destroyed. Returning the leftovers to the caller instead lets
     * {@link InventorySwap#leaveVoid} hold them as an
     * {@link InventorySwap.OrphanRecord} and hand them back on the player's
     * next entry into any dungeon, the same guarantee the overworld
     * inventory already has through {@link InventorySwap.StashRecord}.
     */
    static List<ItemStack> deliverToRoom(ServerLevel level, BlockPos roomOrigin, List<ItemStack> carried) {
        List<Container> containers = new ArrayList<>();
        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            for (int y = 1; y <= RoomGeometry.CEILING_Y; y++) {
                for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                    BlockEntity be = level.getBlockEntity(roomOrigin.offset(x, y, z));
                    if (be instanceof Container container) {
                        containers.add(container);
                    }
                }
            }
        }
        List<ItemStack> leftover = new ArrayList<>();
        for (ItemStack stack : carried) {
            ItemStack remainder = insertInto(containers, stack);
            if (!remainder.isEmpty()) {
                leftover.add(remainder);
            }
        }
        return leftover;
    }

    /**
     * Puts as much of {@code stack} into {@code containers} as fits, and hands
     * back whatever did not.
     *
     * <p>Only empty slots are used. Merging into a partially filled slot would
     * mean re-implementing vanilla's stacking rules over an arbitrary
     * container, and a room chest with a few free slots is the normal case
     * anyway.
     */
    private static ItemStack insertInto(List<Container> containers, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
                if (!container.getItem(slot).isEmpty()) {
                    continue;
                }
                int take = Math.min(remaining.getCount(),
                        Math.min(remaining.getMaxStackSize(), container.getMaxStackSize()));
                container.setItem(slot, remaining.copyWithCount(take));
                remaining.shrink(take);
            }
            container.setChanged();
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }
        return remaining;
    }

    // ---- entry ----------------------------------------------------------

    /**
     * Spends a keystone from this player's inventory and opens the run it buys,
     * or -- if this player already owns a live instance -- teleports them back
     * into it for free (U8 Stage 1: free re-entry).
     *
     * <p>The stack is shrunk only after {@code enter} has actually succeeded --
     * U4's rule, and it matters more here than it did for an echo shard, because a
     * keystone carries a level somebody has spent several runs earning. The stack
     * reference survives the cross-dimension teleport inside {@code enter}:
     * {@code ServerPlayer.teleport} is {@code aload_0 ... areturn} throughout and
     * never recreates the player, which U4's shipped notes confirmed at bytecode
     * level.
     *
     * @return whether the player went in (fresh or re-entered)
     */
    static boolean enterWithKeystone(ServerPlayer player) {
        if (reenterOwnedInstance(player)) {
            return true;
        }

        ItemStack keystone = Keystone.findHeld(player);
        if (keystone == null) {
            player.sendSystemMessage(Component.literal(
                    "You need a keystone to open a dungeon. ")
                    .withStyle(ChatFormatting.RED)
                    .append(Component.literal("[Get one]").withStyle(style -> style
                            .withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent.RunCommand("/dungeon key"))
                            .withHoverEvent(new HoverEvent.ShowText(
                                    Component.literal("Runs /dungeon key"))))));
            return false;
        }
        int level = Keystone.levelOf(keystone).orElse(1);
        // The elective half comes off the stack; the seeded half is re-derived
        // from the level rather than trusted, so a remote the watcher has not
        // caught up with yet still opens the run its level actually earns.
        EnumSet<Affix> affixes = AffixMath.effective(player.getUUID(), level,
                AffixMath.elective(Keystone.affixOf(keystone)));

        if (!enter(player, level, affixes)) {
            return false;
        }
        return true;
    }

    /**
     * The free-re-entry branch of U8 Stage 1: if this player owns a live instance
     * they are not currently standing in, teleport them back rather than opening a
     * new one. Must run <strong>before</strong> any keystone is spent -- re-entering
     * an existing run costs nothing and must not re-roll its layout.
     *
     * @return true if this call was handled here, whatever the outcome
     */
    static boolean reenterOwnedInstance(ServerPlayer player) {
        if (InstanceRegistry.hasInstance(player)) {
            // Already physically inside something -- their own run, or (in
            // practice, never) someone else's. Let the normal "already in a
            // dungeon" refusal in enter() handle it.
            return false;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        InstanceRecord existing = reenterableInstance(player.getUUID());
        if (existing == null) {
            return false;
        }
        Instances.admit(server, existing, player);
        player.sendSystemMessage(Component.literal("You step back into your dungeon.")
                .withStyle(ChatFormatting.GOLD));
        return true;
    }

    /**
     * The live instance this owner could step back into for free, or {@code null}.
     *
     * <p>Extracted from {@link #reenterOwnedInstance} in M6 so
     * {@link #ownsReenterableInstance} can ask the question without answering it.
     * The filter is subtle enough that a second copy of it would go wrong.
     */
    private static InstanceRecord reenterableInstance(UUID owner) {
        for (InstanceRecord candidate : InstanceRegistry.bySlot.values()) {
            if (owner.equals(candidate.owner) && isReenterable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Whether {@code record} is a run its owner could step back into for
     * free right now, ownership aside. Extracted from
     * {@link #reenterableInstance} so the filter has one home, used by
     * {@link #reenterableInstance} (free re-entry search) and
     * {@code DungeonCommands.abandon} (find the run to abandon).
     *
     * <p>A lingering quarry (T2.5) is not re-entered for free: it is done,
     * and opening a new run purges it. See {@link #enter}'s lingering-quarry
     * check.
     *
     * <p>Nor is an instance that has already completed (T2.4): once the room
     * has moved to the terminal cell, "re-entering" this instance means
     * dropping the player at a cleared entrance cell with their room sitting
     * at the far end, which reads as broken even though it is exactly what a
     * finished run looks like now. A run that is done is done; there is
     * nothing left to continue, and the reward room stays reachable through
     * its own grace window/lingering-quarry path regardless (T2.5), not
     * through free re-entry.
     *
     * <p>Nor is an instance that is tearing down: a purge or retirement
     * sets {@link InstanceRecord#tearingDown} before ejecting the first
     * member, so a re-entry search that runs in the same tick does not
     * offer the owner a way back into an instance that is seconds away
     * from being removed from {@code InstanceRegistry.bySlot}.
     */
    static boolean isReenterable(InstanceRecord record) {
        return !record.tearingDown && !record.lingering && !record.visitInstance
                && record.completed.isEmpty();
    }

    /**
     * Whether {@code /dungeon} would put this player back into a run they already
     * own rather than open a new one (M6 T6.5).
     *
     * <p>The command asks before deciding whether to hand out a first keystone: a
     * player standing outside a run they are in the middle of holds no keystone,
     * because they spent it going in, and minting them another one for walking
     * back through their own door would be a free key every time.
     */
    static boolean ownsReenterableInstance(ServerPlayer player) {
        return !InstanceRegistry.hasInstance(player) && reenterableInstance(player.getUUID()) != null;
    }

    static boolean enter(ServerPlayer player, int keystoneLevel, Set<Affix> affixes) {
        return enter(player, keystoneLevel, affixes, false);
    }

    /**
     * Opens a dungeon with no clock, for {@code /dungeon admin untimed}.
     *
     * <p>The one run that never ends on its own. Spends no keystone, so an
     * operator can walk a level 12 layout without owning a level 12 key, and both
     * its creation and its teardown are logged so it cannot be quietly forgotten.
     */
    static boolean enterUntimed(ServerPlayer player, int keystoneLevel, boolean ominous,
                                String theme) {
        int level = Math.max(1, keystoneLevel);
        // The admin path takes the thresholds too: the whole point of it is to
        // walk a level 16 run without owning a level 16 key, and a level 16 run
        // is two seeded affixes whether or not a keystone paid for it.
        return enter(player, level, AffixMath.effective(player.getUUID(), level,
                ominous ? EnumSet.of(Affix.OMINOUS) : EnumSet.noneOf(Affix.class)), true, theme);
    }

    static boolean enter(ServerPlayer player, int keystoneLevel, Set<Affix> affixes,
                         boolean untimed) {
        return enter(player, keystoneLevel, affixes, untimed, null);
    }

    static boolean enter(ServerPlayer player, int keystoneLevel, Set<Affix> affixes,
                         boolean untimed, String theme) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        if (InstanceRegistry.hasInstance(player)) {
            player.sendSystemMessage(Component.literal(
                    "You are already in a dungeon. Use /dungeon exit first.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        // T2.5: opening a new run is the lingering quarry's purge trigger. Also
        // catches an already-*completed* instance that has not reached
        // rewardRoomGraceSeconds yet -- reenterOwnedInstance no longer offers
        // free re-entry into one of those (see its note), so without this a
        // completed-but-not-yet-lingering instance would just sit there
        // orphaned, still holding its slot, while a second one gets built.
        // retireOrPurge turns it into a proper lingering quarry if its room
        // already moved, or purges it outright if it never got that far.
        for (InstanceRecord candidate : new ArrayList<>(InstanceRegistry.bySlot.values())) {
            if ((candidate.lingering || !candidate.completed.isEmpty())
                    && player.getUUID().equals(candidate.owner)) {
                InstanceTeardown.retireOrPurge(server, candidate, "new run started");
                break;
            }
        }

        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            player.sendSystemMessage(Component.literal(
                    "The dungeon dimension is not loaded. This world needs the "
                            + "Pocket Dungeons data pack enabled.")
                    .withStyle(ChatFormatting.RED));
            PocketDungeonsMod.LOG.error("Dimension {} is missing",
                    PocketDungeonsMod.DUNGEON_LEVEL.identifier());
            return false;
        }

        List<ServerPlayer> companions = PartyService.resolveParty(server, player);
        int partySize = 1 + companions.size();

        // M2/M3 §3.2.3: an ordinary keystone run no longer builds a full
        // dungeon on entry at all -- it opens into the owner's lobby (their
        // persistent room, cell 0), and generation is deferred to whichever of
        // its three doors gets chosen (chooseOffer -> generateBehindLobby).
        // /dungeon admin untimed keeps the old immediate-full-build path below:
        // it is a debug tool, not the player-facing loop, and changing it earns
        // nothing.
        if (!untimed) {
            return Instances.enterLobby(server, level, player, companions);
        }

        long seed = level.getRandom().nextLong();
        int slot = InstanceRegistry.allocateSlot();
        BlockPos origin = InstanceRegistry.originForSlot(slot);

        // U8 Stage 6: a run is ominous iff its keystone carries the ominous
        // affix -- one source, replacing U6/U7's depth ramp, off-hand bottle and
        // ominousFromLevel threshold.
        // M4 carries the whole affix set down this path instead of that one
        // boolean, and OMINOUS is read back out of it at the two leaves that
        // still care.

        // On failure buildLayout has already queued a clear for whatever partial
        // geometry it wrote and the slot's release happens when that clear
        // finishes -- it is deliberately not freed here. See buildLayout's note.
        InstanceLayout layout = Instances.buildLayout(server, level, slot, origin, seed, keystoneLevel,
                affixes, theme, player.getUUID());
        if (layout == null) {
            player.sendSystemMessage(Component.literal(
                    "The dungeon failed to build. You have not been moved.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        InstanceRecord record = new InstanceRecord(slot, origin, level.getGameTime(), layout, affixes,
                player.getUUID(), untimed);
        // M2 T2.1/T2.4: the entrance cell is the room, for every keystone run
        // that got a real (procedural) layout. Untimed/admin runs and the
        // StaticLayout fallback have no room concept and stay null.
        if (record.isKeystoneRun() && layout.procedural()) {
            PlanCell entranceCell = layout.geometry().cellAt(layout.entrance());
            if (entranceCell != null) {
                record.roomCellOrigin = layout.geometry().cellOrigin(entranceCell);
            }
        }
        InstanceRegistry.bySlot.put(slot, record);

        if (untimed) {
            PocketDungeonsMod.LOG.info(
                    "UNTIMED dungeon opened in slot {} by {} (level {}, {} rooms, seed {}). "
                            + "It will not expire on its own; /dungeon admin purge {} to close it.",
                    slot, player.getName().getString(), layout.keystoneLevel(),
                    layout.roomCount(), layout.seed(), slot);
        }

        // Always, for every keystone run. The clock is the only thing that ends an
        // ordinary dungeon now, so the sole way to opt out is an operator asking
        // for it explicitly by the command -- and that one is logged and listed.
        if (record.isKeystoneRun() && !record.untimed) {
            record.timer = new RunTimer(layout.keystoneLevel(),
                    KeystoneMath.timerSeconds(PocketDungeonsConfig.timerBaseSeconds(),
                            PocketDungeonsConfig.timerPerRoomSeconds(), layout.pathLength()),
                    layout.roomCount());
        }

        Instances.admit(server, record, player);
        for (ServerPlayer companion : companions) {
            Instances.admit(server, record, companion);
        }

        player.sendSystemMessage(Component.literal("You step into the dungeon.")
                .withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.literal(
                layout.roomCount() + " rooms. Nothing here can kill you; a killing "
                        + "blow throws you out with your inventory intact, but "
                        + "anything you drop inside is lost. Stand on a lodestone, "
                        + "or run /dungeon exit, to leave.")
                .withStyle(ChatFormatting.GRAY));
        if (!layout.procedural()) {
            player.sendSystemMessage(Component.literal(
                    "The dungeon collapsed into its oldest shape: four rooms, "
                            + "heading east. Tell an operator: the room library or "
                            + "the planner needs looking at.")
                    .withStyle(ChatFormatting.YELLOW));
        }
        if (companions.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "Bring someone along with /dungeon invite <player> once you're in, "
                            + "or /dungeon party <player> before you go in to scale the "
                            + "dungeon for your whole group.")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            StringBuilder names = new StringBuilder();
            for (ServerPlayer companion : companions) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(companion.getName().getString());
                companion.sendSystemMessage(Component.literal(
                        "You step into " + player.getName().getString() + "'s dungeon.")
                        .withStyle(ChatFormatting.GOLD));
            }
            player.sendSystemMessage(Component.literal(
                    names + " came in with you. The dungeon is scaled for your party.")
                    .withStyle(ChatFormatting.GRAY));
        }

        if (layout.ominous()) {
            player.sendSystemMessage(Component.literal(
                    "The run is ominous. Every spawner and every vault bites harder, "
                            + "and the payout is worth more for it.")
                    .withStyle(ChatFormatting.DARK_PURPLE));
        }
        if (record.isKeystoneRun()) {
            player.sendSystemMessage(Component.literal(
                    "Keystone [" + layout.keystoneLevel() + "] spent. Clear a trial spawner for a "
                            + "key, spend the key on a vault, and beat the clock for the best "
                            + "reward room. Anything a vault ejects onto the floor is lost when "
                            + "the dungeon closes; pick it up.")
                    .withStyle(ChatFormatting.GRAY));
        }

        PocketDungeonsMod.LOG.info(
                "Opened dungeon slot {} for {} ({} rooms, tier {}, party {}, keystone {}, "
                        + "ominous {}, seed {})",
                slot, player.getName().getString(), layout.roomCount(),
                layout.lootTier(), partySize, layout.keystoneLevel(), layout.ominous(),
                layout.seed());
        return true;
    }

    // ---- the door choice out of the lobby --------------------------------

    /**
     * Settles a door choice out of the lobby (M2/M3 §3.2.3): generates the rest
     * of the dungeon at the chosen offer's level and affix, connects it through
     * the lobby's already-sealed door, starts the timer, and admits every
     * member already standing in the lobby into the real run. The keystone
     * stays the authority throughout -- the level/affix are read off
     * {@link DungeonLog} at the moment of choosing, not carried on any item.
     *
     * @return whether a door was actually taken
     */
    /**
     * (M56) The preview phase of door selection. The host right-clicks a
     * selector door; this plans the dungeon and stamps only its entrance
     * cell beyond the door, replacing the door with an iron-bars window so
     * the party can see in. The plan is stored on the record for
     * {@link #commitDoor} to re-use. Switching doors purges the old preview
     * and stamps the new one.
     */
    static boolean previewDoor(ServerPlayer player, int step) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        if (step < 1 || step > 3) {
            return false;
        }
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !RunSession.canChooseDoor(record) || !player.getUUID().equals(record.owner)) {
            return false;
        }

        DungeonLog log = DungeonLog.forServer(server);
        DungeonLog.Entry entry = log.get(player.getUUID());
        Keystone.Offer[] offers = Keystone.offers(player.getUUID(), entry.keystoneLevel(),
                entry.currentTheme(), entry.depth());
        Keystone.Offer offer = offers[step - 1];

        // The Greater tier's refusals are checked at the door screen level
        // (doorRefusal) before this is called, but re-check here for safety.
        if (!offer.free()) {
            int minLevel = PocketDungeonsConfig.greaterDoorMinLevel();
            if (entry.keystoneLevel() < minLevel) {
                return false;
            }
            int cost = PocketDungeonsConfig.fuelCostPerGreaterDoor();
            if (Fuel.banked(player) < cost) {
                return false;
            }
        }

        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return false;
        }

        if (!Instances.previewDoor(server, level, record, offer, step)) {
            player.sendSystemMessage(Component.literal(
                    "The preview failed to build. Try another door.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        return true;
    }

    /**
     * (M56) The commit phase of door selection. The host pulls the lever;
     * this stamps the rest of the dungeon around the already-previewed
     * entrance cell, replaces the window with a walkable doorway, saves and
     * despawns the safe room (M55), and starts the run. The plan stored by
     * {@link #previewDoor} is re-used so the entrance cell is the exact one
     * the party previewed.
     */
    static boolean commitDoor(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !RunSession.canChooseDoor(record) || !player.getUUID().equals(record.owner)) {
            player.sendSystemMessage(Component.literal("There is no door here for you to choose.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (record.selectedStep == 0 || record.previewPlan == null) {
            player.sendSystemMessage(Component.literal("Select and preview a door first.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        int step = record.selectedStep;

        // M55/M65: the beginRun gate. Every party member must be standing
        // in the staging room before the host can commit, on every floor
        // advance, not only the first one. Solo players skip the wait.
        // M65: the gate now fires on every advance (HOME and FLOOR_CLEARED),
        // not only when the safe room is loaded (roomCellOrigin != null).
        // A party member who went back to loot a cell on a previous floor
        // must return to the staging room before the next floor commits.
        if (!record.visitInstance && record.stagingCellOrigin != null) {
            List<String> missing = missingStagingMembers(server, record);
            if (!missing.isEmpty()) {
                player.sendSystemMessage(Component.literal(
                        "Waiting in the staging room for: " + String.join(", ", missing))
                        .withStyle(ChatFormatting.YELLOW));
                return false;
            }
        }

        DungeonLog log = DungeonLog.forServer(server);
        DungeonLog.Entry entry = log.get(player.getUUID());
        Keystone.Offer[] offers = Keystone.offers(player.getUUID(), entry.keystoneLevel(),
                entry.currentTheme(), entry.depth());
        Keystone.Offer offer = offers[step - 1];

        // M12: re-check the Greater tier refusals at commit time, since the
        // player's state may have changed since the preview.
        if (!offer.free()) {
            int minLevel = PocketDungeonsConfig.greaterDoorMinLevel();
            if (entry.keystoneLevel() < minLevel) {
                player.sendSystemMessage(Component.literal(
                        "Door " + step + " needs keystone level " + minLevel + " or higher; yours is ["
                                + entry.keystoneLevel() + "].")
                        .withStyle(ChatFormatting.RED));
                return false;
            }
            int cost = PocketDungeonsConfig.fuelCostPerGreaterDoor();
            if (Fuel.banked(player) < cost) {
                player.sendSystemMessage(Component.literal(
                        "Door " + step + " costs " + cost + " fuel; the engine holds "
                                + Fuel.banked(player) + ". Feed it echo shards first.")
                        .withStyle(ChatFormatting.RED));
                return false;
            }
        }

        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return false;
        }

        // M55: save and despawn the safe room before committing the dungeon.
        if (record.roomCellOrigin != null && !record.visitInstance) {
            saveRoom(level, server, record);
            DoorMask.Direction dungeonDir = record.roomDungeonDoor;
            RoomBuilder.sealDoor(level, record.roomCellOrigin,
                    Instances.mcDirection(dungeonDir));
            BedrockEnvelope.applyToCell(level, record.roomCellOrigin, Set.of());
            saveRoom(level, server, record);
            Instances.despawnSafeRoom(level, record);
            DoorMask.Direction eeDir = CellGeometry.opposite(dungeonDir);
            RoomBuilder.sealDoor(level, record.stagingCellOrigin,
                    Instances.mcDirection(eeDir));
            BedrockEnvelope.applyToCell(level, record.stagingCellOrigin, Set.of(dungeonDir));
        }

        // M66: the recipe tags were read in previewDoor and resolved into
        // previewRecipePlan. The keystone still carries them (preview does
        // not clear them). Legacy BAG_OVERRIDE is no longer a bag swap; it
        // is a pending legacy operation for owner-approved refund. The new
        // BOUNDED_SUPPLY recipe does not touch the bag. Do NOT clear recipe
        // tags or the catalyst escrow yet: a failed commit must restore the
        // catalyst, not charge for nothing.
        ItemStack keystone = Keystone.findHeld(player);
        String legacyBagOverrideId = null;
        if (keystone != null) {
            // M66: detect a legacy BAG_OVERRIDE tag. It is a pending legacy
            // operation: notify the owner and offer a refund, but do not
            // silently reinterpret it as a bag swap.
            legacyBagOverrideId = CubeRecipe.bagOverrideId(keystone);
            if (legacyBagOverrideId != null) {
                player.sendSystemMessage(Component.literal(
                        "Legacy bag_override detected (bag: " + legacyBagOverrideId
                                + "). This recipe is retired. The run will proceed"
                                + " as a bounded supply run. Use /dungeon admin refund"
                                + " to recover the original catalyst.")
                        .withStyle(ChatFormatting.YELLOW));
            }
        }

        if (!Instances.commitDoor(server, level, record, offer, step)) {
            // M66: commit failed. Restore the catalyst so the player is not
            // charged for nothing.
            if (keystone != null) {
                CubeRecipe.restoreCatalyst(player, keystone);
                CubeRecipe.clearRecipes(keystone);
            }
            player.sendSystemMessage(Component.literal(
                    "The dungeon failed to build. Try another door.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        // M66: commit succeeded. The catalyst is permanently spent: clear
        // the recipe tags and the catalyst escrow from the keystone.
        if (keystone != null) {
            CubeRecipe.clearRecipes(keystone);
            CubeRecipe.clearCatalystEscrow(keystone);
        }

        // The spend happens only once the commit has actually succeeded.
        if (!offer.free()) {
            Fuel.spendBanked(player, PocketDungeonsConfig.fuelCostPerGreaterDoor());
        }

        EnumSet<Affix> granted = AffixMath.effective(player.getUUID(), offer.level(),
                offer.affixes());
        player.sendSystemMessage(Component.literal(
                AffixMath.name(offer.level(), granted) + ". The door opens.")
                .withStyle(Keystone.colourOf(
                        AffixMath.ordered(granted).stream().findFirst().orElse(null))));
        TaskTracker.progress(player, TaskTracker.Task.DESCEND, 1);
        if (step >= 2) {
            TaskTracker.progress(player, TaskTracker.Task.GREATER_DOOR, 1);
        }
        return true;
    }

    /**
     * (M55) Returns the names of party members who are not standing in the
     * staging room. Used by the {@code beginRun} gate to refuse a commit
     * until the whole party has left the safe room.
     */
    private static List<String> missingStagingMembers(MinecraftServer server, InstanceRecord record) {
        List<String> missing = new java.util.ArrayList<>();
        net.minecraft.world.phys.AABB stagingBounds = CellGeometry.cellBounds(record.stagingCellOrigin);
        for (UUID member : record.members.keySet()) {
            ServerPlayer memberPlayer = server.getPlayerList().getPlayer(member);
            if (memberPlayer == null) {
                missing.add("offline member");
                continue;
            }
            if (!memberPlayer.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)
                    || !stagingBounds.contains(net.minecraft.world.phys.Vec3.atCenterOf(memberPlayer.blockPosition()))) {
                missing.add(memberPlayer.getName().getString());
            }
        }
        return missing;
    }

    /**
     * Persists the owner's room exactly as it stands right now.
     *
     * <p>Called on every path where the owner stops looking at the room -- the
     * leave-pad, {@code /dungeon exit}, a disconnect, walking out of the
     * dimension, an admin teleport, a purge -- because a block they broke or
     * placed is theirs and must not depend on the run ending tidily. Saving the
     * same room twice is harmless (the second capture is the same blocks, and
     * {@link RoomStore} keeps a {@code .bak} of the previous write either way),
     * so the leave paths overlap on purpose rather than trying to be exclusive.
     *
     * <p>Skips the two cases that have no room of their own: a
     * {@code visitInstance} is a read-only copy of somebody else's, and a
     * record with no {@code roomCellOrigin} is an admin or untimed run.
     */
    static boolean saveRoom(ServerLevel level, MinecraftServer server, InstanceRecord record) {
        if (record.roomCellOrigin == null || record.visitInstance) {
            // Nothing to save is not a failed save: these two have no room of
            // their own by design, and a caller must not read this as a reason
            // to hold off a teardown.
            return true;
        }
        // M55: the safe room holds only the owner's blob and the bag chest.
        // Selector doors, furniture and screens live in the staging room now,
        // so capture hygiene no longer needs to clear and re-arm them here.
        // The bag chest is still transient: clear it before capture so it
        // never bakes into the blob, then re-arm it after.
        Instances.clearBagChest(level, record.roomCellOrigin);
        // The safe room is always stamped at rotation 0 (its ee and MM walls
        // are sealed by stampSafeRoom regardless of what the blob was saved
        // with), so the capture rotation is always 0.
        boolean saved = RoomStore.capture(level, server, record.owner, record.roomCellOrigin, 0);
        Instances.placeBagChestForParty(level, server, record);
        return saved;
    }

    /**
     * {@link #saveRoom} for callers that only have a server, and only if
     * {@code member} is the owner.
     *
     * <p>Routed through {@link MinecraftServer#execute}, not called directly:
     * {@code ServerPlayConnectionEvents.DISCONNECT} -- the source of most calls
     * here, via {@link #dropMember} -- fires on Netty's IO thread, not the
     * server thread, for an abrupt disconnect. {@link RoomStore#capture} does
     * real world work (entity discard, {@code StructureTemplate.fillFromWorld}),
     * and discarding an entity off-thread is exactly what a threading-safety mod
     * like c2me's async-unload guard exists to catch -- it throws rather than
     * let two threads race on the same chunk's entity list. {@code execute}
     * runs synchronously when already on the server thread (every other caller:
     * {@code Instances.eject}, {@link InstanceTeardown#retireOrPurge},
     * {@link InstanceTeardown#purge}) and defers to the next tick otherwise, so
     * this is a no-op behaviour change for all of them.
     */
    static void saveRoomIfOwner(MinecraftServer server, InstanceRecord record, UUID member) {
        // An unowned instance (/dungeon admin build) has no owner to save a room
        // for and never gets a roomCellOrigin, so there is nothing to capture.
        // The owner null check has to come first regardless: purge and
        // retireOrPurge both open by passing record.owner straight back in here,
        // and RoomStore.capture below would take the null the same way.
        if (record.owner == null || !record.owner.equals(member)
                || record.roomCellOrigin == null || record.visitInstance) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (level != null && !saveRoom(level, server, record)) {
                // Nothing here can hold off a teardown: by the time this
                // deferred body runs the caller has long since returned. The
                // recoverable record is the log line plus the untouched
                // previous blob on disk, which is what an operator restores
                // from with baserestore.
                PocketDungeonsMod.LOG.error("Deferred room save for {} failed; their previously "
                        + "saved room is still on disk and is what they will get on re-entry",
                        record.owner);
            }
        });
    }

    /**
     * Synchronous version of {@link #saveRoomIfOwner}, for paths where the
     * room cell is about to be cleared (purge, retireOrPurge). The deferred
     * {@code server.execute} in {@link #saveRoomIfOwner} can race with the
     * {@code PendingClear} those paths queue right after: if the clear
     * reaches the room cell before the deferred save captures it, the room
     * is written back as empty air (PD-8). Callers that are about to destroy
     * the room must save it now, before the clear is queued, not after.
     */
    static boolean saveRoomIfOwnerSync(ServerLevel level, MinecraftServer server,
                                       InstanceRecord record, UUID member) {
        if (record.owner == null || !record.owner.equals(member)
                || record.roomCellOrigin == null || record.visitInstance) {
            return true;
        }
        return saveRoom(level, server, record);
    }

    /**
     * (M55) Teleports party members still in the old dungeon back into the
     * staging room, clears the old dungeon cells, and seals the staging room's
     * {@code ee} wall so the void behind the previous dungeon cannot be
     * entered. The safe room is already despawned during dungeon play, so this
     * method no longer saves or seals the safe room.
     */
    static void resetForNextDungeon(MinecraftServer server, InstanceRecord record) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null || record.stagingCellOrigin == null) {
            return;
        }
        BlockPos stagingOrigin = record.stagingCellOrigin;
        DoorMask.Direction eeDir = CellGeometry.opposite(record.roomDungeonDoor);

        // Pull stragglers into the staging room.
        BlockPos stagingCentre = stagingOrigin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
        for (ServerPlayer straggler : server.getPlayerList().getPlayers()) {
            if (!record.members.containsKey(straggler.getUUID())) {
                continue;
            }
            if (straggler.level().dimension() != PocketDungeonsMod.DUNGEON_LEVEL) {
                continue;
            }
            if (Instances.roomOwnerAt(straggler.blockPosition()) != null) {
                continue; // already inside the staging room
            }
            Instances.teleport(server, straggler, PocketDungeonsMod.DUNGEON_LEVEL,
                    net.minecraft.world.phys.Vec3.atBottomCenterOf(stagingCentre), 0.0f, 0.0f);
        }

        // Clear every cell of the previous dungeon except the staging room.
        List<BlockPos> keepStaging = List.of(stagingOrigin);
        for (BlockPos cellOrigin : record.layout.geometry().cellOrigins()) {
            if (cellOrigin.equals(stagingOrigin)) {
                continue;
            }
            Instances.clearCellSync(level, cellOrigin, keepStaging);
        }

        // Seal the staging room's entrance from the previous dungeon.
        CellGeometry.sealDoorOnWall(level, stagingOrigin, eeDir);

        // ...and put the bedrock back behind that seal. Only the MM side stays
        // reserved -- that is where the run about to be generated connects.
        BedrockEnvelope.applyToCell(level, stagingOrigin, Set.of(record.roomDungeonDoor));
    }

    // ---- exit -------------------------------------------------------------

    /**
     * Why a member is leaving. U8 Stage 0 collapses every outcome down to "did
     * this cost anything", and the answer is now always no: {@link #EXIT_PAD}
     * used to be the one that paid, but paying now happens once, at
     * {@link #completeRun}, the moment a member first reaches a pad -- so by the
     * time either reason reaches {@code exit} there is nothing left to settle
     * beyond ejecting the player. Kept as two constants purely for the exit
     * message's wording (a retreat reads differently from walking out after
     * finishing).
     */
    enum ExitReason { EXIT_PAD, COMMAND }

    /**
     * @return whether the player actually left a dungeon. {@code false}
     * means the refusal message is the whole story (PD-38): the command
     * executor's brigadier result now agrees with it, for {@code execute if}.
     */
    static boolean exit(ServerPlayer player, ExitReason reason) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null) {
            player.sendSystemMessage(Component.literal("You are not in a dungeon.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        // A keystone run records its completion and hands over its door offer on
        // the *first* pad contact -- see completeRun. By the time exit() is
        // reached the completion, if any, has already happened.
        boolean completed = record.completed.contains(player.getUUID());

        // T2.6: a deliberate /dungeon exit is still a leadership change if the
        // owner is walking out on a party that is still in there.
        // InstanceTeardown.purge() ejects and settles the keystone for every
        // member -- this player included -- so there is nothing left to do
        // here but hand off to it.
        boolean othersRemain = record.members.keySet().stream()
                .anyMatch(m -> !m.equals(player.getUUID()));
        if (leadershipChanged(record, player.getUUID(), othersRemain)) {
            InstanceTeardown.purge(server, record, "party leader left", player.getUUID());
            return true;
        }

        Instances.eject(server, record, player);

        // M3: leaving a read-only visit instance gets its own return cue.
        if (record.visitInstance) {
            Chime.visitEnds(player);
        }

        // U8 Stage 1: leaving never costs anything. The clock is the only thing
        // that can deplete a keystone, and it does that on its own in onTick --
        // see expireTimedOut -- independently of anyone leaving or staying.
        returnKeystone(server, record, player.getUUID(), player, Keystones.Outcome.NO_CHANGE);

        if (!completed) {
            player.sendSystemMessage(Component.literal("You leave the dungeon behind.")
                    .withStyle(ChatFormatting.GOLD));
        }
        Instances.announce(server, record, player.getName().getString()
                        + (completed ? " walks out of the dungeon." : " leaves the dungeon."),
                player.getUUID());

        // M3 T3.3: a visit instance is read-only and has no timer; tear it down
        // as soon as its last visitor leaves.
        if (record.visitInstance && record.members.isEmpty()) {
            InstanceTeardown.purge(server, record, "visit ended", player.getUUID());
            return true;
        }

        Instances.purgeIfAbandonedLobby(server, record);
        return true;
    }

    /**
     * Hands this member's keystone back, once.
     *
     * <p>With depletion collapsed to a single cause (U8 Stage 1), every call site
     * but the timeout expiry passes {@link Keystones.Outcome#NO_CHANGE}, which
     * costs nothing -- the write still happens, so a stale remote in an offline
     * member's pocket still catches up. The "a completed run can never be
     * charged a failure" escalation U7 needed here is gone along with the
     * outcomes it used to escalate <em>to</em>: {@code COMPLETED_OVER_TIME} could
     * once cost {@code overtimeDepletion}, so a member who had already finished
     * needed protecting from a later death or disconnect being mis-costed as a
     * fresh failure. There is no such outcome left to protect against.
     */
    static void returnKeystone(MinecraftServer server, InstanceRecord record,
                                       UUID member, ServerPlayer player,
                                       Keystones.Outcome outcome) {
        if (!record.isKeystoneRun() || !record.keystoneReturned.add(member)) {
            return;
        }
        // Deplete from the member's own keystone level, not the run's layout
        // level. The layout level is the offer level (key + door step), which
        // is the difficulty the run was stamped at, not the level the member's
        // key actually sits at. A +2 door from a 14 key runs at level 16, but
        // the key is still 14 until a timed success banks the offer. Depleting
        // from 16 would cost the member levels they never had, and a party
        // member riding along at a lower level would deplete from a level
        // above their own. Reading from DungeonLog gives each member their
        // own true key level at the moment of failure.
        int memberLevel = DungeonLog.forServer(server).get(member).keystoneLevel();
        Keystones.returnTo(server, member, player, memberLevel, record.affixes, outcome);
    }

    /**
     * The first pad contact of a keystone run: record the completion, stamp the
     * reward room on the very first member to reach it, offer a door, and
     * teleport the player in. Deliberately <strong>does not eject</strong> --
     * the reward room's own lodestone is the second contact that leaves (U8
     * Stage 2).
     *
     * <p>M10: refused outright, with a chat message and no state change at all,
     * if this run has not cleared its spawner-clear threshold yet. The run stays
     * live and the pad stays contactable, since {@code onPad} in
     * {@code Instances}' watcher is an edge, so stepping off and back on tries
     * again once more spawners are down.
     */
    static void completeRun(MinecraftServer server, InstanceRecord record,
                                    ServerPlayer player) {
        // M65: the phase must be ACTIVE. A pad contact in any other phase
        // is a programming error (the pad should not be reachable).
        if (!RunSession.require(record, RunSession.Phase.ACTIVE)) {
            return;
        }
        if (record.isKeystoneRun()) {
            // M67: every trial spawner on the floor counts toward the
            // completion gate, not just gated encounter cells. The threshold
            // (default 0.75) lets the player skip some spawners without
            // letting them sprint past the entire floor.
            Set<BlockPos> spawners = TrialContent.activeSpawners(record.layout, player.level());
            int cleared = TrialContent.countCleared(player.level(), spawners);
            if (!DifficultyProfile.spawnersCleared(cleared, spawners.size(),
                    PocketDungeonsConfig.spawnerClearThreshold())) {
                player.sendSystemMessage(Component.literal(
                        "Not yet: " + cleared + "/" + spawners.size()
                                + " trial spawners cleared. Go finish the rest.")
                        .withStyle(ChatFormatting.YELLOW));
                return;
            }
            // M11: the boss is the ultimate gated completion for its one theme,
            // the same shape as the spawner gate above but for a single mob
            // instead of a fraction of many.
            AdventureGraph.Node themeNode = AdventureGraphs.current().graph().node(record.theme);
            if (themeNode != null && themeNode.kind() == AdventureGraph.Kind.BOSS
                    && BossContent.bossAlive(player.level(), record.layout.terminal())) {
                player.sendSystemMessage(Component.literal(
                        "The Drowned Warden still stands. Finish it first.")
                        .withStyle(ChatFormatting.YELLOW));
                return;
            }
        }

        boolean firstCompletion = record.completed.isEmpty();
        record.completed.add(player.getUUID());
        TaskTracker.progress(player, TaskTracker.Task.COMPLETE_RUN, 1);

        if (firstCompletion) {
            if (record.timer != null) {
                record.timer.markCompleted();
            }
            // M65: advanceFloor replaces completeDungeon. It does the
            // physical floor advance (increment floorIndex, bank omen,
            // place chests, stamp new staging room) and transitions the
            // phase to FLOOR_CLEARED. The interval-level settlement
            // (keystone, prestige, payout, bounty) happens at the safe
            // visit, not here.
            advanceFloor(server, record);
        }

        // M65: per-floor observations only. The completion count and theme
        // record are per-floor; the keystone level up, payout, prestige and
        // bounty hooks move to settleSafeVisit, called from returnToSafe.
        DungeonLog log = DungeonLog.forServer(server);
        log.recordCompletion(player.getUUID(), record.layout.pathLength(),
                record.layout.keystoneLevel());
        DungeonLog.Entry entry = log.recordTheme(player.getUUID(), record.theme);

        int chests = record.rewardChests;
        boolean isSafeStaging = record.safeStaging;
        if (isSafeStaging) {
            // M65: this is the last floor before the safe room. The
            // interval-level settlement happens when the player selects
            // the safe door (returnToSafe -> settleSafeVisit). The
            // completion message names the safe door rather than the next
            // floor.
            player.sendSystemMessage(Component.literal(
                    "You reach the end. " + chests + " chest" + (chests == 1 ? "" : "s")
                            + " wait beyond the door, and the safe room stands open beyond.")
                    .withStyle(ChatFormatting.AQUA));
        } else {
            player.sendSystemMessage(Component.literal(
                    "You reach the end of this floor. " + chests + " chest"
                            + (chests == 1 ? "" : "s")
                            + " wait beyond the door, and the next floor stands open beyond.")
                    .withStyle(ChatFormatting.AQUA));
        }
        // M66: the compass recipe promises a completion study list. The
        // list is the run's situations by name, emitted on the first
        // completion of the floor.
        if (firstCompletion && record.recipeTags != null
                && record.recipeTags.getBooleanOr("compass", false)
                && !record.situations.isEmpty()) {
            String studyList = String.join(", ", record.situations);
            player.sendSystemMessage(Component.literal(
                    "Completion study list: " + studyList + ".")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        PocketDungeonsMod.LOG.info("{} completed floor {} of slot {} (run #{}, chests {}, tier {})",
                player.getName().getString(), record.floorIndex, record.slot,
                entry.runsCompleted(), chests, record.layout.lootTier());
        Chime.runComplete(player);
    }

    /**
     * M65: extracted from the old {@code completeDungeon}. Does the
     * physical floor advance: increments the floor index, banks the
     * floor's omen, places completion chests in the terminal cell, clears
     * the old staging room to a liminal cell, stamps a new staging room
     * behind the terminal's far wall, and transitions the phase to
     * FLOOR_CLEARED.
     *
     * <p>Does not do keystone level up, payout, prestige, bounty, or diary
     * delivery. Those are interval-level settlements that happen at the
     * safe visit (spec 12.4: "Keystone per safe visit, not per floor"),
     * and are handled by {@link #settleSafeVisit}, called from
     * {@link #returnToSafe}.
     */
    private static void advanceFloor(MinecraftServer server, InstanceRecord record) {
        if (record.stagingCellOrigin == null) {
            return;
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return;
        }

        BlockPos terminalOrigin = record.layout.terminal();
        DoorMask.Direction entranceDir = CellGeometry.terminalEntranceDirection(record.layout.geometry(), terminalOrigin);
        DoorMask.Direction farWall = CellGeometry.opposite(entranceDir);

        // M57: increment the floor index. The staging room about to be stamped
        // is for the next floor (or the safe room return).
        record.floorIndex++;

        // M57: determine whether this is a safe staging room. Every
        // floorsPerSafeVisit floors, the staging room offers a safe door
        // instead of three dungeon doors.
        boolean isSafeStaging = record.floorIndex % PocketDungeonsConfig.floorsPerSafeVisit() == 0;
        record.safeStaging = isSafeStaging;

        // M48: the omen finish table replaces the clock (spec 5.2, 5.4).
        // OmenSources accrues the floor in progress into record.omen; close it
        // here and key the table off the sum since the last safe visit. M57:
        // the band denominator is floorsPerSafeVisit, not a hardcoded 1.
        record.floorOmens.add(Omen.clamp(record.omen));
        record.omen = 0;
        int omenSum = Omen.floorSum(record.floorOmens.stream().mapToInt(Integer::intValue).toArray());
        int band = Omen.band(omenSum, PocketDungeonsConfig.floorsPerSafeVisit());
        int chests = Omen.chestCount(band);
        record.rewardChests = chests;

        // Chests on the far side of the terminal cell, beyond the 2x2 lodestone
        // pad and in front of the sealed door.
        TrialContent.placeCompletionChests(level, terminalOrigin, entranceDir, chests,
                DifficultyProfile.of(record.layout.pathLength(), record.layout.keystoneLevel())
                        .lootTier(),
                record.affixes.contains(Affix.OMINOUS), record.layout.seed(),
                record.theme == null || ThemeManifest.current().byId(record.theme) == null ? null
                        : ThemeManifest.current().byId(record.theme).meta().lootSuffix);

        // Sealed door in the far wall, behind the chests.
        CellGeometry.sealDoorOnWall(level, terminalOrigin, farWall);

        // M55: the old staging room is cleared to a liminal cell. The safe
        // room is already despawned, so there is no blob to capture or move.
        // A new staging room is stamped behind the terminal's far wall for
        // the next door choice.
        BlockPos oldStagingOrigin = record.stagingCellOrigin;
        RoomTemplateGenerator.clearPostSelectionDoors(level, oldStagingOrigin, record.roomDungeonDoor);
        RoomTemplateGenerator.clearFurniture(level, oldStagingOrigin, record.roomDungeonDoor);
        for (Entity leftover : level.getEntitiesOfClass(Entity.class, CellGeometry.cellBounds(oldStagingOrigin),
                e -> !(e instanceof ServerPlayer))) {
            leftover.discard();
        }
        Set<net.minecraft.core.Direction> backDoors = new LinkedHashSet<>();
        for (DoorMask.Direction dir : CellGeometry.standingNeighbours(record.layout.geometry(), oldStagingOrigin)) {
            backDoors.add(Instances.mcDirection(dir));
        }
        RoomBuilder.buildLiminalCell(level, oldStagingOrigin, backDoors);

        // Stamp a new staging room in the cell behind the far wall.
        BlockPos newStagingOrigin = CellGeometry.offsetInDirection(terminalOrigin, farWall, RoomGeometry.CELL);
        level.setChunkForced(newStagingOrigin.getX() >> 4, newStagingOrigin.getZ() >> 4, true);
        Instances.stampStagingRoom(level, newStagingOrigin, farWall);
        record.stagingCellOrigin = newStagingOrigin;
        record.roomDungeonDoor = farWall;

        // M67: no sound for room movement. VISION.md §4: "No message, no
        // sound, no lore entry." The room relocation is the trick; the
        // silence is the effect. The previous Chime.roomRelocated call is
        // removed to honour both the vision and this milestone's constraint.

        // Summon fresh screens at the new staging room.
        record.selectedStep = 0;
        if (isSafeStaging) {
            // M57: safe staging room. The door screen shows a safe-return
            // prompt instead of a dungeon preview.
            DungeonScreen.summonDoor(level, newStagingOrigin, farWall,
                    Component.literal("Safe Room").withStyle(ChatFormatting.GREEN));
        } else {
            DungeonScreen.summonDoor(level, newStagingOrigin, farWall,
                    DungeonScreen.idleContent(level, record.owner));
        }
        DungeonScreen.summonEngine(level, newStagingOrigin, farWall, DungeonScreen.engineContent(null));
        DungeonScreen.summonTracker(level, newStagingOrigin, farWall,
                DungeonScreen.trackerContent(level.getServer(), record.owner));
        record.awaitingDoorChoice = true;

        // The bedrock envelope for the new staging room.
        BedrockEnvelope.applyToCell(level, newStagingOrigin, Set.of(farWall, CellGeometry.opposite(farWall)));

        // Open the sealed door into the staging room.
        CellGeometry.openDoorOnWall(level, terminalOrigin, farWall);

        // M65: transition to FLOOR_CLEARED. The party is now in the
        // staging room, awaiting the next door choice (or the safe door).
        RunSession.transition(record, RunSession.Phase.FLOOR_CLEARED);
    }

    /**
     * M65: the interval-level settlement that happens once per safe visit,
     * for each member who completed at least one floor. Replaces the
     * keystone level up, payout, prestige, bounty and diary delivery that
     * used to happen in {@code completeRun} on every floor.
     *
     * <p>Spec 12.4: "Keystone per safe visit, not per floor." The omen
     * finish table (spec 5.2, 5.4) keys off the sum of all floors' omens
     * since the last safe visit, and the band determines the keystone
     * level change (+1/+1/+0) and the chest count (3/2/1). This method
     * reads {@code record.floorOmens} and {@code record.rewardChests},
     * both of which were set by {@link #advanceFloor} on the last floor.
     *
     * <p>Bounty hooks fire once per visit (not per member), the same way
     * they used to fire on firstCompletion. SPEEDRUNNER is now "low-omen
     * completion" (band 0) rather than "finished before the clock ran
     * out," per the M65 handoff.
     */
    private static void settleSafeVisit(MinecraftServer server, InstanceRecord record) {
        if (!record.isKeystoneRun()) {
            return;
        }
        int chests = record.rewardChests;
        // M48/M65: the omen finish table. chests >= 2 means the low or
        // mid band (+1 level); chests == 1 means the high band (+0 level).
        boolean levelUp = chests >= 2;
        // M65: SPEEDRUNNER is now "low-omen completion" (band 0, 3 chests)
        // rather than "finished before the clock ran out." The clock no
        // longer depletes on ordinary floors (step 2), so "timed" is no
        // longer a meaningful distinction.
        boolean lowOmen = chests >= 3;

        // M34: weekly bounty hooks. Fire once per visit, not per member.
        Set<BlockPos> spawners = record.layout.trialSpawners();
        if (!spawners.isEmpty()) {
            int cleared = TrialContent.countCleared(
                    server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL), spawners);
            BountyTracker.progress(server, record.owner,
                    BountyTracker.Bounty.CLEAR_HALLS.id, cleared);
        }
        if (lowOmen) {
            BountyTracker.progress(server, record.owner,
                    BountyTracker.Bounty.SPEEDRUNNER.id, 1);
        }
        if (record.chosenStep >= 2) {
            BountyTracker.progress(server, record.owner,
                    BountyTracker.Bounty.SPELUNKER.id, 1);
        }
        if (record.members.size() >= 2) {
            BountyTracker.progress(server, record.owner,
                    BountyTracker.Bounty.PACK_HUNTER.id, 1);
        }

        // Per-member settlement: keystone level up, free-door fuel, payout,
        // prestige, diary.
        DungeonLog log = DungeonLog.forServer(server);
        for (UUID member : record.members.keySet()) {
            ServerPlayer memberPlayer = server.getPlayerList().getPlayer(member);
            if (memberPlayer == null) {
                continue;
            }
            // M2/M3: the door choice happened at the first staging room.
            // On a low/mid-band finish, record.chosenStep is which of
            // Keystone.offers this member's own current level banks at.
            if (levelUp && record.chosenStep > 0) {
                DungeonLog.Entry memberEntry = log.get(member);
                Keystone.Offer[] offers = Keystone.offers(member, memberEntry.keystoneLevel(),
                        memberEntry.currentTheme(), memberEntry.depth());
                Keystone.Offer banked = offers[record.chosenStep - 1];
                Keystones.grantOffer(server, member, memberPlayer, banked);
                record.keystoneReturned.add(member);
            }

            // M12: door 1's second job. Guaranteed, regardless of omen band.
            if (record.freeDoor) {
                Fuel.grant(memberPlayer, PocketDungeonsConfig.fuelPerFreeRun());
            }

            Payout.runPayoutCommand(memberPlayer, record.layout.keystoneLevel(), chests);

            // M26: reads log fresh, after every keystone-level change.
            DiaryDelivery.deliverIfEligible(log, memberPlayer);

            // M24: prestige counts completions while the owner holds the
            // same room without resetting it. Only the owner's completions
            // move the count.
            if (member.equals(record.owner)) {
                DungeonLog.Entry prestige = log.addRoomCompletion(member);
                if (prestige.roomCompletions() >= RoomBuilder.PRESTIGE_SHELL_THRESHOLD
                        && !prestige.unlockedShells().contains(RoomBuilder.PRESTIGE_SHELL)) {
                    log.unlockShell(member, RoomBuilder.PRESTIGE_SHELL);
                    memberPlayer.sendSystemMessage(Component.literal(
                            "Ten completions on the same room. The "
                                    + RoomBuilder.palette(RoomBuilder.PRESTIGE_SHELL).displayName()
                                    + " shell is yours; change it from your room's menu.")
                            .withStyle(ChatFormatting.GOLD));
                }
            }
        }
    }

    /**
     * (M57/M65) Returns the party to the safe room from a safe staging
     * room. M65: the return is now a silent homecoming. The saved room
     * is stamped behind the final staging door, the door opens, and the
     * party walks through physically. No teleport, no chime, no
     * explanation message. The old floor cells are released after all
     * members cross, handled by {@code onTick} via the
     * {@link InstanceRecord#pendingHomecomingCleanup} flag.
     *
     * <p>If the room stamping fails, the staging room is left usable
     * and the method falls back to the old teleport path so the party
     * is never stranded.
     */
    static boolean returnToSafe(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !RunSession.require(record, RunSession.Phase.FLOOR_CLEARED)
                || !player.getUUID().equals(record.owner) || !record.safeStaging) {
            return false;
        }
        // M65: transition to SAFE_RETURN for the duration of the return.
        if (!RunSession.transition(record, RunSession.Phase.SAFE_RETURN)) {
            return false;
        }

        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            RunSession.transition(record, RunSession.Phase.FLOOR_CLEARED);
            return false;
        }

        // M65: settle the safe visit before tearing down the dungeon.
        settleSafeVisit(server, record);

        // M65: silent homecoming. Stamp the saved room behind the final
        // staging door, open the door, and let the party walk through.
        // The cell beyond the staging room's dungeon door is where the
        // next dungeon preview would normally go; it is empty now.
        DoorMask.Direction dungeonDir = record.roomDungeonDoor;
        BlockPos safeOrigin = CellGeometry.offsetInDirection(
                record.stagingCellOrigin, dungeonDir, RoomGeometry.CELL);

        // Force-load the new room's chunk so the stamp lands.
        level.setChunkForced(safeOrigin.getX() >> 4, safeOrigin.getZ() >> 4, true);

        // Persist room state before clearing old floor state. The room
        // is already in RoomStore from the initial save; re-save here to
        // capture any changes the player made before entering the dungeon.
        // (The safe room was saved when the first floor was committed.)

        // Stamp the saved room while the door is still closed.
        boolean stamped = false;
        try {
            Instances.stampSafeRoom(level, server, record.owner, safeOrigin);
            stamped = true;
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Silent homecoming room stamp failed", e);
        }

        if (!stamped) {
            // M65: leave staging usable if stamping fails. Fall back to
            // the old teleport path so the party is never stranded.
            level.setChunkForced(safeOrigin.getX() >> 4, safeOrigin.getZ() >> 4, false);
            RunSession.transition(record, RunSession.Phase.FLOOR_CLEARED);
            return fallbackTeleportHomecoming(server, level, record, player);
        }

        // Validate the stamp: check the room's bedrock envelope is in place.
        // If validation fails, fall back.
        // (The stamp either placed the saved room or the entrance hall
        // template as a fallback, so the cell is always structurally
        // sound after stampSafeRoom returns.)

        // Open the staging door. Replace the selector door/window with
        // a walkable doorway so the party can walk through.
        RoomBuilder.openDoor(level, record.stagingCellOrigin, Instances.mcDirection(dungeonDir));
        BedrockEnvelope.clearFace(level, record.stagingCellOrigin, dungeonDir);

        // Update the record: the room is now at the new location, and
        // the staging room's dungeon door now leads into the room.
        record.roomCellOrigin = safeOrigin;
        record.roomDungeonDoor = CellGeometry.opposite(dungeonDir);

        // Re-arm the bag chest at the new room.
        Instances.clearBagChest(level, safeOrigin);
        Instances.placeBagChestForParty(level, server, record);

        // Close the timer and clear trial omen from every member.
        if (record.timer != null) {
            for (UUID member : record.members.keySet()) {
                ServerPlayer inside = server.getPlayerList().getPlayer(member);
                if (inside != null) {
                    record.timer.removePlayer(inside);
                    Instances.clearTrialOmen(inside);
                }
            }
            record.timer.close();
            record.timer = null;
        }

        // M66: restore the original bag if a legacy BAG_OVERRIDE was used.
        // M66 no longer swaps bags for new runs, but a legacy tag may still
        // carry bag_original from a pre-M66 run.
        if (record.recipeTags != null) {
            String originalBag = record.recipeTags.getStringOr("bag_original", "");
            if (!originalBag.isEmpty()) {
                DungeonLog.forServer(server).setBag(player.getUUID(), originalBag);
            }
            record.recipeTags = null;
        }

        // Reset the record for the next visit, but keep the staging
        // room and room origins (they are at new locations now).
        record.affixes = EnumSet.noneOf(Affix.class);
        record.theme = null;
        record.chosenStep = 0;
        record.freeDoor = false;
        record.selectedStep = 0;
        record.floorIndex = 0;
        record.safeStaging = false;
        record.omen = 0;
        record.previewPlan = null;
        record.previewCellOrigin = null;
        record.clearPreviousRunState();

        // M65: set the pending cleanup flag. The old staging room and
        // old floor cells will be released once all members cross into
        // the room, handled by onTick.
        record.oldStagingCellOrigin = record.stagingCellOrigin;
        record.oldLayoutForCleanup = record.layout;
        record.pendingHomecomingCleanup = true;

        // The staging room stays in place for now; the party walks
        // through its dungeon door into the room. The layout is the
        // lobby layout centered on the new room origin.
        record.layout = Instances.lobbyLayout(safeOrigin);
        record.awaitingDoorChoice = true;

        // M65: transition to HOME. The room is loaded, the door is open,
        // and the party can walk through. No teleport, no chime, no
        // explanation message.
        RunSession.transition(record, RunSession.Phase.HOME);
        return true;
    }

    /**
     * M65: fallback for the silent homecoming when room stamping fails.
     * Reverts to the old teleport-based return: purges everything,
     * re-stamps at the slot origin, teleports the party. Used only when
     * the silent stamp fails so the party is never stranded.
     */
    private static boolean fallbackTeleportHomecoming(MinecraftServer server, ServerLevel level,
                                                       InstanceRecord record, ServerPlayer player) {
        // Release the current layout's force-load tickets.
        if (record.layout != null) {
            Instances.forceLoad(level, record.layout.geometry().chunks(), false);
        }

        // Clear every cell of the current dungeon and the staging room.
        List<BlockPos> keepCells = new java.util.ArrayList<>();
        if (record.layout != null) {
            for (BlockPos cellOrigin : record.layout.geometry().cellOrigins()) {
                Instances.clearCellSync(level, cellOrigin, keepCells);
            }
        }
        if (record.stagingCellOrigin != null) {
            Instances.clearCellSync(level, record.stagingCellOrigin, keepCells);
            level.setChunkForced(record.stagingCellOrigin.getX() >> 4,
                    record.stagingCellOrigin.getZ() >> 4, false);
        }

        // Re-stamp the safe room at the slot origin from RoomStore.
        BlockPos safeOrigin = record.origin;
        DoorMask.Direction dungeonDir = DoorMask.Direction.SOUTH;
        level.setChunkForced(safeOrigin.getX() >> 4, safeOrigin.getZ() >> 4, true);
        Instances.stampSafeRoom(level, server, record.owner, safeOrigin);
        record.roomCellOrigin = safeOrigin;
        record.roomDungeonDoor = dungeonDir;

        // Stamp a fresh staging room adjacent to the safe room.
        BlockPos stagingOrigin = CellGeometry.offsetInDirection(safeOrigin, dungeonDir, RoomGeometry.CELL);
        level.setChunkForced(stagingOrigin.getX() >> 4, stagingOrigin.getZ() >> 4, true);
        Instances.stampStagingRoom(level, stagingOrigin, dungeonDir);
        RoomBuilder.openDoor(level, safeOrigin, Instances.mcDirection(dungeonDir));
        BedrockEnvelope.clearFace(level, safeOrigin, dungeonDir);
        record.stagingCellOrigin = stagingOrigin;

        // Summon fresh screens at the staging room.
        DungeonScreen.summonDoor(level, stagingOrigin, dungeonDir,
                DungeonScreen.idleContent(level, record.owner));
        DungeonScreen.summonEngine(level, stagingOrigin, dungeonDir,
                DungeonScreen.engineContent(null));
        DungeonScreen.summonTracker(level, stagingOrigin, dungeonDir,
                DungeonScreen.trackerContent(level.getServer(), record.owner));

        // Re-arm the bag chest at the safe room.
        Instances.clearBagChest(level, safeOrigin);
        Instances.placeBagChestForParty(level, server, record);

        // Teleport every member into the safe room.
        BlockPos roomCentre = safeOrigin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
        for (UUID member : record.members.keySet()) {
            ServerPlayer inside = server.getPlayerList().getPlayer(member);
            if (inside == null) {
                continue;
            }
            if (inside.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                Instances.teleport(server, inside, PocketDungeonsMod.DUNGEON_LEVEL,
                        net.minecraft.world.phys.Vec3.atBottomCenterOf(roomCentre), 0.0f, 0.0f);
            }
        }

        // Close the timer and clear trial omen from every member.
        if (record.timer != null) {
            for (UUID member : record.members.keySet()) {
                ServerPlayer inside = server.getPlayerList().getPlayer(member);
                if (inside != null) {
                    record.timer.removePlayer(inside);
                    Instances.clearTrialOmen(inside);
                }
            }
            record.timer.close();
            record.timer = null;
        }

        // Reset the record for the next visit.
        record.layout = Instances.lobbyLayout(safeOrigin);
        record.affixes = EnumSet.noneOf(Affix.class);
        record.theme = null;
        record.awaitingDoorChoice = true;
        record.chosenStep = 0;
        record.freeDoor = false;
        record.selectedStep = 0;
        record.floorIndex = 0;
        record.safeStaging = false;
        record.omen = 0;
        record.previewPlan = null;
        record.previewCellOrigin = null;
        // M66: restore the original bag if a legacy BAG_OVERRIDE was used.
        // M66 no longer swaps bags for new runs, but a legacy tag may still
        // carry bag_original from a pre-M66 run.
        if (record.recipeTags != null) {
            String originalBag = record.recipeTags.getStringOr("bag_original", "");
            if (!originalBag.isEmpty()) {
                DungeonLog.forServer(server).setBag(player.getUUID(), originalBag);
            }
            record.recipeTags = null;
        }
        record.previewRecipePlan = null;
        record.previewOfferStep = 0;
        record.clearPreviousRunState();

        RunSession.transition(record, RunSession.Phase.HOME);

        player.sendSystemMessage(Component.literal(
                "You return to the safe room. The dungeon closes behind you.")
                .withStyle(ChatFormatting.GREEN));
        // M67: no sound for room movement (VISION.md §4, milestone constraint).
        return true;
    }

    /**
     * Drops an offline member; used by the disconnect handler and the tick
     * watcher. {@code member} is threaded through as the stray-sweep
     * exclusion for any resulting purge -- this player is known to be
     * leaving (disconnecting, or already off the player list), so teardown's
     * "someone is still standing here" safety net must not try to teleport
     * them. Doing so mid-disconnect is what corrupts their next login's
     * chunk tracking, leaving them stuck on "Loading terrain..." (see docs/PLAN.md).
     *
     * <p>U8 Stage 1: for an ordinary dungeon this is no longer a lifetime event
     * -- an empty instance is now normal, a dungeon waiting for its owner to
     * come back. It stays one for the selector room, which is not a keystone run
     * and has no clock of its own to close it eventually.
     */
    static void dropMember(MinecraftServer server, InstanceRecord record,
                                   UUID member, ServerPlayer player, String reason) {
        // M43.2: Instances.detach is the one primitive for this now. Dropping
        // a member used to mean only "forget them," which was survivable when
        // an instance died with its last member; U8 made instances outlive
        // everyone, so what detach also clears (onPad, the timer, Trial Omen)
        // matters for the rest of the run, not just at teardown.
        Instances.detach(server, record, member, player);

        // record.members has already had `member` removed above, so a non-empty
        // set here means someone else is still in the party.
        if (leadershipChanged(record, member, !record.members.isEmpty())) {
            InstanceTeardown.purge(server, record, "party leader left", member);
            return;
        }

        if ((record.awaitingDoorChoice && record.chosenStep == 0 && record.members.isEmpty())
                || (record.visitInstance && record.members.isEmpty())) {
            InstanceTeardown.purge(server, record, reason, member);
        }
    }

    /**
     * T2.6 / {@code docs/MYTHIC_PLUS_RECONCILIATION.md} §7.2: leadership does not
     * transfer. If the owner is the one leaving <em>and someone else is still in
     * the party</em>, the whole run ends with them -- stricter than plain
     * purge-when-empty, and deliberately so: transferring ownership is real work
     * with real edge cases this codebase already paid for once (the
     * disconnect-during-teardown race {@code docs/PLAN.md} documents), and "purge and
     * let them re-key" costs nobody anything (no death, inventory kept), just the
     * run.
     *
     * <p>An owner leaving <em>alone</em> is not a leadership change -- that is
     * still U8 Stage 1's free re-entry, unaffected by this rule. A lingering
     * quarry (T2.5) has no live run left to end, so it is exempt too.
     *
     * @param othersRemain whether anyone besides {@code member} is still in the
     *                     party at the moment of leaving
     */
    static boolean leadershipChanged(InstanceRecord record, UUID member, boolean othersRemain) {
        return !record.lingering && member.equals(record.owner) && othersRemain;
    }

    /**
     * The clock ran out: the keystone is downgraded once, but the dungeon
     * stays open (PD-7) so the owner can still reach a door in overtime.
     *
     * <p>Thin wrapper over {@link #applyTimedOutPenalty}, kept as its own
     * method because {@link Instances#onTick} calls it by name against
     * {@code record.timer.overTime()}.
     */
    static void expireTimedOut(MinecraftServer server, InstanceRecord record) {
        ServerPlayer owner = record.owner != null ? server.getPlayerList().getPlayer(record.owner) : null;
        applyTimedOutPenalty(server, record, owner, false);
    }

    /**
     * The keystone-downgrade half of a timeout, shared by the clock actually
     * running out ({@link #expireTimedOut}) and a player choosing to quit the
     * door instead of waiting for it ({@link #quitDoor}). Depletes the owner
     * alone -- a party member riding along never had a key at stake -- and
     * messages them wherever they are. The two paths must apply the exact
     * same penalty, so this is the one place either of them can drift from.
     *
     * <p>M12: a free-door run settles as {@code NO_CHANGE} instead. Door 1
     * never depletes, and a generous flat clock ({@code door1TimerSeconds})
     * running out (or being given up on) is not a different kind of failure
     * than any other way a free door ends.
     *
     * @param voluntary whether the player chose this over waiting out the
     *                  clock, purely for the message's wording
     */
    private static void applyTimedOutPenalty(MinecraftServer server, InstanceRecord record,
                                             ServerPlayer owner, boolean voluntary) {
        returnKeystone(server, record, record.owner, owner, Keystones.Outcome.TIMED_OUT);
        record.timedOutPenaltyApplied = true;
        if (owner != null) {
            String verb = voluntary ? "You quit the door." : "The clock ran out.";
            owner.sendSystemMessage(Component.literal(
                    verb + " Your keystone is downgraded by "
                            + PocketDungeonsConfig.timedOutDepletion()
                            + (voluntary ? "." : ", but the dungeon stays open if you want to finish."))
                    .withStyle(ChatFormatting.YELLOW));
            Chime.runTimedOut(owner);
        } else {
            PocketDungeonsMod.LOG.info("Dungeon slot {} timed out with its owner offline", record.slot);
        }
    }

    /**
     * Player-facing: instantly fails the caller's own active keystone door,
     * applies the keystone penalty, then resets the dungeon back to its
     * lobby state so the player can pick a new door. Nobody is ejected: the
     * player stays in the safe room, the dungeon beyond it is cleared, the
     * selector doors come back, and the record returns to
     * {@code awaitingDoorChoice}.
     *
     * <p>Reuses {@link #applyTimedOutPenalty} exactly, so a voluntary quit
     * and the clock actually running out can never apply different
     * penalties. Skipped (falls straight through to {@link #exit}) when
     * there is nothing to quit: no active keystone run, the caller does not
     * own it, it already completed, or it was already timed out. A non-
     * keystone run (untimed, admin build) also falls through to {@link #exit},
     * since there is no door to quit and no lobby to reset to.
     *
     * @return whether the player was actually in a dungeon to leave, exactly
     *         {@link #exit}'s own contract
     */
    static boolean quitDoor(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null) {
            player.sendSystemMessage(Component.literal("You are not in a dungeon.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        // A non-keystone run (untimed, admin build) has no door to quit and
        // no lobby to reset to. Treat it as a plain exit.
        if (!record.isKeystoneRun()) {
            return exit(player, ExitReason.COMMAND);
        }
        // Only the owner can quit the door: their keystone is the one on the
        // line. A party member riding along never had a key at stake.
        if (!player.getUUID().equals(record.owner)) {
            player.sendSystemMessage(Component.literal(
                    "Only the dungeon's owner can quit the door.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        // Nothing to quit: the run already completed or already timed out.
        // Fall through to a plain exit so the player leaves normally.
        if (!record.completed.isEmpty() || record.timedOutPenaltyApplied) {
            return exit(player, ExitReason.COMMAND);
        }
        // Apply the keystone penalty, then reset the dungeon to its lobby
        // state. The player stays in the safe room and picks a new door.
        applyTimedOutPenalty(server, record, player, true);
        Instances.resetToLobby(server, record);
        return true;
    }
}
