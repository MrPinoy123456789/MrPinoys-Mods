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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
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
                    "You need a compass to open a dungeon. ")
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
        Set<String> affixes = AffixMath.effective(player.getUUID(), level,
                AffixMath.elective(Keystone.affixOf(keystone)),
                AffixManifest.current().definitions());

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
        PlaytestJournal.hintEnter(player.getUUID(), "reenter");
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
     * <p>An instance that has already completed (T2.4) is not: once the room
     * has moved to the terminal cell, "re-entering" this instance means
     * dropping the player at a cleared entrance cell with their room sitting
     * at the far end, which reads as broken even though it is exactly what a
     * finished run looks like now. A run that is done is done; there is
     * nothing left to continue, and the reward room stays reachable through
     * its own grace window, not through free re-entry.
     *
     * <p>Nor is an instance that is tearing down: a purge sets {@link InstanceRecord#tearingDown} before ejecting the first
     * member, so a re-entry search that runs in the same tick does not
     * offer the owner a way back into an instance that is seconds away
     * from being removed from {@code InstanceRegistry.bySlot}.
     *
     * <p>A run between floors ({@link #betweenFloors}) is reenterable even
     * though its {@code completed} set is not empty: the floor is done but
     * the interval is not, and the owner who stepped out or disconnected in
     * the staging room goes back to it rather than forfeiting every floor
     * since the last safe visit. {@link Instances#admit} lands them in the
     * staging room, not at the cleared floor's entrance.
     */
    static boolean isReenterable(InstanceRecord record) {
        return !record.tearingDown && !record.visitInstance
                && (record.floor.completed.isEmpty() || betweenFloors(record));
    }

    /**
     * Whether this run has cleared a floor and is waiting in its staging room
     * for the next door: {@code FLOOR_CLEARED}, or a door preview opened from
     * there. The cleared floor's layout is still {@code record.layout} until
     * the next commit. A completed admin untimed run never gets here, since
     * it never leaves {@code HOME}.
     */
    static boolean betweenFloors(InstanceRecord record) {
        return !record.floor.completed.isEmpty()
                && (record.phase == RunSession.Phase.FLOOR_CLEARED
                        || record.phase == RunSession.Phase.PREVIEW);
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

    static boolean enter(ServerPlayer player, int keystoneLevel, Set<String> affixes) {
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
                ominous ? Set.of(AffixIds.OMINOUS) : Set.of(),
                AffixManifest.current().definitions()), true, theme);
    }

    static boolean enter(ServerPlayer player, int keystoneLevel, Set<String> affixes,
                         boolean untimed) {
        return enter(player, keystoneLevel, affixes, untimed, null);
    }

    static boolean enter(ServerPlayer player, int keystoneLevel, Set<String> affixes,
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

        // An instance of this owner's with a completed floor that free
        // re-entry did not take them back into is closed before a second one
        // is built, so it does not sit orphaned holding its slot until its
        // grace window runs out.
        for (InstanceRecord candidate : new ArrayList<>(InstanceRegistry.bySlot.values())) {
            if (!candidate.floor.completed.isEmpty()
                    && player.getUUID().equals(candidate.owner)) {
                InstanceTeardown.purge(server, candidate, "new run started");
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

        // M76: refuse a new run once the server is at the declared instance
        // cap, before any keystone, fuel or generation work is spent. The
        // purge loop above already freed this player's own finished instance,
        // so a re-entry is not blocked by the instance it just replaced.
        int cap = PocketDungeonsConfig.maxConcurrentInstances();
        if (cap > 0 && InstanceRegistry.liveInstanceCount() >= cap) {
            player.sendSystemMessage(Component.literal(
                    "The dungeon is at capacity (" + cap + " concurrent runs). Try again shortly.")
                    .withStyle(ChatFormatting.RED));
            PocketDungeonsMod.LOG.warn("Refused /dungeon for {}: instance cap {} reached",
                    player.getName().getString(), cap);
            return false;
        }

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
                    "Level [" + layout.keystoneLevel() + "], untimed. Clear a trial spawner for a "
                            + "key and spend the key on a vault. Anything a vault ejects onto the "
                            + "floor is lost when the dungeon closes; pick it up.")
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
     * the lobby's already-sealed door, and admits every
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
        if (record == null || !RunSession.canChooseDoor(record)) {
            return false;
        }
        // D15: any member may preview unless the leader's decide whitelist is on.
        if (!PartyDecide.mayOrRefuse(server, record, player)) {
            return false;
        }

        // The leader's keystone and acts set the doors (D15), whoever stands at them.
        DungeonLog log = DungeonLog.forServer(server);
        DungeonLog.Entry entry = log.get(record.owner);
        Keystone.Offer[] offers = Keystone.offers(server, record, record.owner, entry.keystoneLevel());
        if (record.interval.finished || step > offers.length) {
            // The final floor is cleared (only the way home is left), or this slot has no door.
            return false;
        }
        Keystone.Offer offer = offers[step - 1];

        // PD-90: Lemon is setting up a playtest bias for this player; the
        // floor a preview plans now would not carry it.
        if (refuseWhileHeld(player, record)) {
            return false;
        }

        // M76: refuse a new preview once the server is at the declared preview
        // cap, before the catalyst is escrowed inside previewDoor. A player
        // switching doors does not count: their own existing preview is
        // reused or purged by previewDoor itself, so the net footprint does
        // not grow.
        int previewCap = PocketDungeonsConfig.maxConcurrentPreviews();
        if (previewCap > 0 && record.floor.previewCellOrigin == null
                && InstanceRegistry.previewCount() >= previewCap) {
            player.sendSystemMessage(Component.literal(
                    "Too many door previews are open right now. Close one or try again shortly.")
                    .withStyle(ChatFormatting.RED));
            PocketDungeonsMod.LOG.warn("Refused door preview for {}: preview cap {} reached",
                    player.getName().getString(), previewCap);
            return false;
        }

        // A side branch costs scrap (design J1). The door screen names the
        // shortfall (RitualListener.doorRefusal); re-check here for safety.
        int previewCost = offer.cost();
        if (previewCost > 0) {
            // The member who right-clicks the door is the one who would pay.
            int carried = DungeonLog.forServer(server).get(player.getUUID()).scrap();
            if (!SideBranchPay.affordable(previewCost, carried)) {
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
        record.floor.previewBiasGeneration = PlaytestBias.generation();
        return true;
    }

    /** PD-90: refuses, with the time left, while a playtest bias hold is on the run's owner. */
    private static boolean refuseWhileHeld(ServerPlayer player, InstanceRecord record) {
        int held = PlaytestBias.holdSecondsLeft(record.owner);
        if (held <= 0) {
            return false;
        }
        player.sendSystemMessage(Component.literal("Lemon is setting up the next floor. Your doors open in "
                + held + "s at most.").withStyle(ChatFormatting.YELLOW));
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
        if (record == null || !RunSession.canChooseDoor(record)) {
            player.sendSystemMessage(Component.literal("There is no door here for you to choose.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        // D15: any member may commit unless the leader's decide whitelist is on.
        if (!PartyDecide.mayOrRefuse(server, record, player)) {
            return false;
        }
        if (record.floor.selectedStep == 0 || record.floor.previewPlan == null) {
            player.sendSystemMessage(Component.literal("Select and preview a door first.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        int step = record.floor.selectedStep;

        // PD-90: no commit while Lemon holds the doors, and none on a preview
        // planned before the bias last changed: that floor would not carry it.
        if (refuseWhileHeld(player, record)) {
            return false;
        }
        if (record.floor.previewBiasGeneration != PlaytestBias.generation()) {
            player.sendSystemMessage(Component.literal(
                    "The next floor changed while this door was open. Right-click the door again to see it.")
                    .withStyle(ChatFormatting.YELLOW));
            return false;
        }

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
                        "Waiting at the Doors for: " + String.join(", ", missing))
                        .withStyle(ChatFormatting.YELLOW));
                return false;
            }
        }

        DungeonLog log = DungeonLog.forServer(server);
        DungeonLog.Entry entry = log.get(record.owner);
        Keystone.Offer[] offers = Keystone.offers(server, record, record.owner, entry.keystoneLevel());
        if (record.interval.finished || step > offers.length) {
            player.sendSystemMessage(Component.literal("There is no door there. Pull the HOME lever.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        Keystone.Offer offer = offers[step - 1];

        // Re-check the side branch's scrap cost at commit time, since the
        // player's pool may have changed since the preview. The member who commits
        // (the one pulling the lever) pays from their own pool, never the owner's
        // (dungeon structure W5, SideBranchPay).
        int doorCost = offer.cost();
        if (doorCost > 0) {
            int carried = log.get(player.getUUID()).scrap();
            if (!SideBranchPay.affordable(doorCost, carried)) {
                player.sendSystemMessage(Component.literal(SideBranchPay.refusal(doorCost, carried))
                        .withStyle(ChatFormatting.RED));
                return false;
            }
        }
        // Where the trip stood before this door, for the journal's edge event.
        boolean firstDoorOfTrip = record.interval.dungeonId.isEmpty();
        String fromNode = record.interval.nodeId;

        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return false;
        }

        // M55: save and despawn the safe room before committing the dungeon.
        // The room is sealed first so the blob carries a closed wall on the
        // staging side. One save, checked before anything is despawned or
        // spent: if it fails, the seal is undone, the room stays standing and
        // the commit is refused. No envelope goes on the room here: its ring
        // on the staging side would replace the staging room's wall.
        if (record.roomCellOrigin != null && !record.visitInstance) {
            DoorMask.Direction dungeonDir = record.roomDungeonDoor;
            RoomBuilder.sealDoor(level, record.roomCellOrigin,
                    Instances.mcDirection(dungeonDir));
            if (!saveRoom(level, server, record)) {
                RoomBuilder.openDoor(level, record.roomCellOrigin, Instances.mcDirection(dungeonDir));
                PocketDungeonsMod.LOG.error("Refused the door commit for {}: their room could not be saved",
                        player.getName().getString());
                player.sendSystemMessage(Component.literal(
                        "Your room could not be saved, so the door stays shut. Nothing was spent. "
                                + "Tell an operator.")
                        .withStyle(ChatFormatting.RED));
                return false;
            }
            // Playtest 2026-10-03 (A9): read the room once as the player leaves it,
            // from the blob just saved, before it is despawned.
            RoomScan.onExit(server, record, player);
            despawnRoomBehindStaging(level, record);
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

        // The spend happens only once the commit has actually succeeded,
        // straight from the member's scrap pool; the gate above checked it.
        if (doorCost > 0) {
            log.spendScrap(player.getUUID(), doorCost);
            Keystone.showScrap(player, log.get(player.getUUID()).scrap());
        }

        // A trip has begun (the first door of a dungeon): count it for the leader, which
        // changes the first door's deal for their next trip even if this one is quit early.
        if (firstDoorOfTrip && !record.interval.dungeonId.isEmpty() && record.owner != null) {
            log.beginTrip(record.owner);
        }
        PlaytestJournal.doorCommit(server, record, offer.step(), doorCost);
        if (!record.interval.dungeonId.isEmpty()) {
            PlaytestJournal.tripDoor(server, record, firstDoorOfTrip, fromNode, offer.step(), doorCost);
        }

        Set<String> granted = Keystone.dealtAffixes(record.owner, offer);
        player.sendSystemMessage(Component.literal(
                AffixMath.name(offer.level(), granted, AffixManifest.current().definitions())
                        + ". The door opens.")
                .withStyle(Keystone.colourOf(
                        AffixMath.ordered(granted, AffixManifest.current().definitions())
                                .stream().map(d -> d.id).findFirst().orElse(null))));
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
        // M55: the safe room holds only the owner's blob. Selector doors,
        // furniture and screens live in the staging room. The bag chest is
        // part of the room since 2026-10-04 and saves with it, wherever the
        // player has moved it.
        // The safe room is always stamped at rotation 0 (its ee and MM walls
        // are sealed by stampSafeRoom regardless of what the blob was saved
        // with), so the capture rotation is always 0.
        return RoomStore.capture(level, server, record.owner, record.roomCellOrigin, 0);
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
     * {@code Instances.eject}, {@link InstanceTeardown#purge}) and defers to the next tick otherwise, so
     * this is a no-op behaviour change for all of them.
     */
    static void saveRoomIfOwner(MinecraftServer server, InstanceRecord record, UUID member) {
        // An unowned instance (/dungeon admin build) has no owner to save a room
        // for and never gets a roomCellOrigin, so there is nothing to capture.
        // The owner null check has to come first regardless: purge opens by
        // passing record.owner straight back in here,
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
     * room cell is about to be cleared (purge). The deferred
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
     * The block half of the commit's room despawn, once the room is saved. The
     * room's cell is cleared with the staging room kept (its wall, and its
     * bedrock put back on that face), and the staging room's doorway into the
     * room is sealed.
     */
    static void despawnRoomBehindStaging(ServerLevel level, InstanceRecord record) {
        DoorMask.Direction eeDir = CellGeometry.opposite(record.roomDungeonDoor);
        Instances.despawnSafeRoom(level, record);
        RoomBuilder.sealDoor(level, record.stagingCellOrigin, Instances.mcDirection(eeDir));
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
        if (level != null) {
            resetForNextDungeon(server, level, record);
        }
    }

    /** {@link #resetForNextDungeon(MinecraftServer, InstanceRecord)} in a given level. */
    static void resetForNextDungeon(MinecraftServer server, ServerLevel level, InstanceRecord record) {
        if (record.stagingCellOrigin == null) {
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

        // Clear everything else the run has standing: the previous floor, the
        // liminal cells beside it, homecoming leftovers, and at the first
        // commit the lobby's room cell. The staging room and the previewed
        // entrance about to become the new floor are kept, walls and bedrock
        // included, and their tickets with them.
        Set<BlockPos> previous = record.liveCells();
        previous.remove(stagingOrigin);
        previous.remove(record.floor.previewCellOrigin);
        Instances.clearCells(level, record, previous);
        record.leftBehind.clear();
        record.homecoming = null;
        // The previous floor is gone. Until the commit adopts the new one, the
        // staging room is all the layout there is, so the record never names
        // a cleared cell as standing.
        record.layout = Instances.lobbyLayout(stagingOrigin);

        // Seal the staging room's entrance from the previous dungeon. The
        // clear already put the bedrock back behind that seal.
        CellGeometry.sealDoorOnWall(level, stagingOrigin, eeDir);
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
        boolean completed = record.floor.completed.contains(player.getUUID());

        // The owner leaving between floors banks the interval one band worse
        // rather than forfeiting it, and the run ends.
        if (player.getUUID().equals(record.owner) && record.inFloorLoop() && record.isKeystoneRun()
                && betweenFloors(record)) {
            return leaveAtCheckpoint(server, record, player);
        }
        PlaytestJournal.inventorySnapshot(player, record, "exit");
        PlaytestJournal.hintLeave(player.getUUID(), "exit", record);

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

        // U8 Stage 1: leaving never costs anything. Only a voluntary quit
        // depletes a keystone (quitDoor).
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
     * The owner leaving between floors ({@code /dungeon exit}, the
     * lodestone's Leave, or {@code /dungeon quit} with nothing left to quit):
     * the interval settles for every member present (D24: exactly like the
     * home lever, no band penalty), and the run ends, as a party leader
     * leaving always has ended it. The room was saved at the first commit and
     * stays despawned, so the next run opens at home. A preview still open is
     * cancelled first, its catalyst refunded.
     */
    private static boolean leaveAtCheckpoint(MinecraftServer server, InstanceRecord record, ServerPlayer owner) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (record.phase == RunSession.Phase.PREVIEW && level != null) {
            Instances.clearPreview(level, record, true);
        }
        for (UUID member : record.members.keySet()) {
            ServerPlayer leaving = server.getPlayerList().getPlayer(member);
            if (leaving != null) {
                PlaytestJournal.inventorySnapshot(leaving, record, "checkpoint_exit");
            }
            PlaytestJournal.hintLeave(member, "checkpoint_exit", record);
        }
        settleOnce(server, record, "checkpoint_exit");
        Instances.announce(server, record, owner.getName().getString()
                + " leaves at the checkpoint. The run ends.",
                owner.getUUID());
        InstanceTeardown.purge(server, record, "owner left at a checkpoint", owner.getUUID());
        return true;
    }

    /**
     * Hands this member's keystone back, once.
     *
     * <p>Every call site but {@link #quitDoor} passes
     * {@link Keystones.Outcome#NO_CHANGE}, which costs nothing; the write
     * still happens, so a stale remote in an offline member's pocket still
     * catches up.
     */
    static void returnKeystone(MinecraftServer server, InstanceRecord record,
                                       UUID member, ServerPlayer player,
                                       Keystones.Outcome outcome) {
        if (!record.isKeystoneRun() || !record.floor.keystoneReturned.add(member)) {
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
        Keystones.returnTo(server, member, player, memberLevel, record.floor.affixes, outcome);
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
     *
     * <p>A member who reaches the pad after the first, while the run is
     * {@link #betweenFloors}, gets the same per-member credit (completion
     * count, theme, guided task, message, chime, and so the run record at the
     * safe visit) without the floor advancing a second time.
     */
    static void completeRun(MinecraftServer server, InstanceRecord record,
                                    ServerPlayer player) {
        // A party member reaching the pad after the first: the floor is
        // already cleared and advanced, so they are credited for it and
        // nothing else happens. No gate re-check, no second advance.
        boolean lateArrival = betweenFloors(record);
        // M65: otherwise the phase must be ACTIVE. A pad contact in any other
        // phase is a programming error (the pad should not be reachable).
        if (!lateArrival && !RunSession.require(record, RunSession.Phase.ACTIVE)) {
            return;
        }
        if (!lateArrival && record.isKeystoneRun()) {
            // M67: every trial spawner on the floor counts toward the
            // completion gate, not just gated encounter cells. The threshold
            // (default 0.75) lets the player skip some spawners without
            // letting them sprint past the entire floor.
            Set<BlockPos> spawners = TrialContent.activeSpawners(record.layout, player.level());
            int cleared = TrialContent.countCleared(player.level(), spawners);
            if (!DifficultyProfile.spawnersCleared(cleared, spawners.size(),
                    PocketDungeonsConfig.spawnerClearThreshold())) {
                int more = DifficultyProfile.spawnersStillNeeded(cleared, spawners.size(),
                        PocketDungeonsConfig.spawnerClearThreshold());
                player.sendSystemMessage(Component.literal(
                        "Not yet: clear " + more + " more trial spawner" + (more == 1 ? "" : "s") + ".")
                        .withStyle(ChatFormatting.YELLOW));
                return;
            }
            // M11: the boss is the ultimate gated completion for a zone whose
            // capstone is the boss, the same shape as the spawner gate above
            // but for a single mob instead of a fraction of many.
            if (ZoneRules.bossCapstone(record.floor.theme)
                    && BossContent.bossAlive(player.level(), record.layout.terminal())) {
                player.sendSystemMessage(Component.literal(
                        "The Drowned Warden still stands. Finish it first.")
                        .withStyle(ChatFormatting.YELLOW));
                return;
            }
            // Dungeon structure W7a: the Spawner Dungeon's brood gates the pad the same way (the
            // spawners, then the final wave). The Ancient City never gates its pad.
            String broodRefusal = CapstoneFights.padRefusal(server, record);
            if (broodRefusal != null) {
                player.sendSystemMessage(Component.literal(broodRefusal).withStyle(ChatFormatting.YELLOW));
                return;
            }
        }

        boolean firstCompletion = record.floor.completed.isEmpty();
        if (!record.floor.completed.add(player.getUUID())) {
            return;
        }

        // The floor history entry, before advanceFloor counts the floor and
        // stamps the next staging room (whose board then already shows it).
        FloorHistory.cleared(player, record,
                firstCompletion ? record.interval.floorIndex + 1 : record.interval.floorIndex);

        if (firstCompletion) {
            // J1: the floor pays on the spot, before the physical advance:
            // each member present gets the dealt step as scrap, or emeralds
            // when their permanent chart level stands above the floor.
            payFloorMembers(server, record);
            // M65: advanceFloor replaces completeDungeon. It does the
            // physical floor advance (increment floorIndex, bank omen,
            // place chests, stamp new staging room) and transitions the
            // phase to FLOOR_CLEARED. The interval-level settlement
            // (keystone, prestige, payout, bounty) happens at the safe
            // visit, not here.
            advanceFloor(server, record);
            // Playtest 2026-10-03 (A5): the first floor cleared is when Lemon explains run storage and the set of three.
            FirstVisitTutorial.floorCleared(server, record);
        }

        // M65: per-floor observations only. The completion count and theme
        // record are per-floor; the keystone level up, payout, prestige and
        // bounty hooks move to settleSafeVisit, called from returnToSafe.
        DungeonLog log = DungeonLog.forServer(server);
        log.recordCompletion(player.getUUID(), record.layout.pathLength(),
                record.layout.keystoneLevel());
        DungeonLog.Entry entry = log.recordTheme(player.getUUID(), record.floor.theme);

        int floorsCleared = record.interval.floorIndex;
        ZoneRules rules = ZoneRules.of(record);
        // J3: the verdict is lives, not a band; omen is danger only.
        String verdict = OmenBarText.completionVerdict(record.interval.omen,
                record.floor.rewardChests >= 0 ? record.floor.rewardChests : Omen.baseRewardChests());
        if (EndlessMineRules.isMine(record)) {
            // M78: the Mine checkpoint is the commitment surface. The player
            // sees the depth reached and the escalating loot tier before
            // choosing a door deeper or going home. The spatial movement
            // stays silent, the same as the ordinary loop.
            int tier = LootBands.floorTier(record, rules, floorsCleared);
            player.sendSystemMessage(Component.literal(
                    EndlessMineRules.mineCheckpointMessage(floorsCleared, tier) + " " + verdict
                            + (record.interval.mineSealedAct > 0
                                    ? " " + EndlessMineRules.sealedMessage(record.interval.mineSealedAct) : ""))
                    .withStyle(ChatFormatting.AQUA));
        } else if (record.interval.finished) {
            // The dungeon's final floor: only the way home is left.
            player.sendSystemMessage(Component.literal(
                    "You clear the last floor of " + TripView.dungeonName(record) + ". " + verdict
                            + " The barrel waits beyond the door"
                            + (TripView.def(record) != null ? ", the vault's rolls in with it" : "")
                            + ". Only the way home is open: pull the HOME lever to bank your charts.")
                    .withStyle(ChatFormatting.AQUA));
        } else {
            player.sendSystemMessage(Component.literal(
                    "You reach the end of this floor. " + verdict
                            + " The barrel waits beyond the door. GO HOME banks your charts;"
                            + " DESCEND for bonus chests and better loot."
                            + (TripView.finalAhead(record) ? " The final floor is ahead." : ""))
                    .withStyle(ChatFormatting.AQUA));
        }
        // Playtest 2026-09-27 (A1): the floor count on the bar went unnoticed at
        // the decision point, so a floor clear also gets a title.
        showFloorClearedTitle(player, OmenBarText.clearedHeadline(floorsCleared, TripView.dungeonName(record),
                EndlessMineRules.isMine(record), record.interval.finished, TripView.finalAhead(record)),
                record.interval.finished ? "GO HOME" : "GO HOME or DESCEND");
        // M66: the compass recipe promises a completion study list. The
        // list is the run's situations by name, emitted on the first
        // completion of the floor.
        if (firstCompletion && record.floor.recipeTags != null
                && record.floor.recipeTags.getBooleanOr("compass", false)
                && !record.floor.situations.isEmpty()) {
            String studyList = String.join(", ", record.floor.situations);
            player.sendSystemMessage(Component.literal(
                    "Completion study list: " + studyList + ".")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        PocketDungeonsMod.LOG.info("{} completed floor {} of slot {} (run #{}, chests {}, tier {})",
                player.getName().getString(), record.interval.floorIndex, record.slot,
                entry.runsCompleted(), record.floor.rewardChests, record.layout.lootTier());
        Set<BlockPos> floorSpawners = TrialContent.activeSpawners(record.layout, player.level());
        PlaytestJournal.floorComplete(player, record,
                TrialContent.countCleared(player.level(), floorSpawners), floorSpawners.size());
        PlaytestJournal.inventorySnapshot(player, record, "floor_complete");
        // A party member who completes after the first: their entry lands in
        // their own history; the board shows the owner's, so repaint it.
        FloorHistory.refresh(server, record);
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
     * <p>Records the floor's door step on the interval but banks nothing:
     * keystone levels, payout, prestige, bounties and diary delivery settle
     * when the interval ends ({@link #settleInterval}), from the HOME lever
     * or a checkpoint exit.
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

        // Dungeon structure W7a: the floor ended, so the Ancient City's Warden (and any brood
        // stragglers) go with it.
        CapstoneFights.floorEnded(level, record);

        // The staging room about to be stamped is for the next floor, or the
        // way home: every checkpoint offers both, and nothing forces either.
        record.interval.floorIndex++;
        int floorsCleared = record.interval.floorIndex;
        // The floor banks its door step toward the key when the interval
        // settles (IntervalBanking), whichever door the next floor takes.
        record.interval.floorSteps.add(record.floor.chosenStep);
        record.interval.floorLevels.add(record.floor.chosenLevel);
        // Dungeon structure W2: clearing a node marked final finishes the dungeon. The
        // staging room then offers only the way home (Instances.hideLockedDoors reads
        // this flag when the doors are placed below).
        DungeonDef tripDef = TripView.def(record);
        boolean finishedNow = tripDef != null && TripView.onFinal(record);
        if (finishedNow) {
            record.interval.finished = true;
        }
        // D13: record the deepest Mine floor, and seal the shaft at a layer boundary the
        // leader's unlocked acts do not reach (the staging room then offers only HOME).
        if (EndlessMineRules.isMine(record)) {
            DungeonLog mineLog = DungeonLog.forServer(server);
            for (UUID member : record.members.keySet()) {
                if (mineLog.recordMineFloor(member, floorsCleared)) {
                    // D29: a new deepest floor may complete the act's Mine leg.
                    ServerPlayer memberPlayer = server.getPlayerList().getPlayer(member);
                    if (memberPlayer != null) {
                        DungeonProgress.onProgress(server, record, memberPlayer, false);
                    }
                }
            }
            int sealed = EndlessMineRules.sealedAct(floorsCleared + 1,
                    DungeonProgress.unlockedActs(server, record.owner));
            if (sealed > 0) {
                record.interval.finished = true;
                record.interval.mineSealedAct = sealed;
            }
        }

        // J3: omen adds danger only, never reward cuts. The
        // base reward is always three chests, plus the zone's depth bonus on
        // deeper floors.
        ZoneRules rules = ZoneRules.of(record);
        bankFloorOmen(record);
        int chests = Omen.baseRewardChests() + rules.bonusChests(floorsCleared);
        record.floor.rewardChests = chests;

        // The reward corner on the far side of the terminal cell: one barrel
        // holding every roll the floor earned (plus the finish vault's rolls),
        // one copper chest holding the floor's promised rewards.
        ThemeManifest.Entry completionTheme = record.floor.theme == null ? null
                : ThemeManifest.current().byId(record.floor.theme);
        int vaultRolls = finishedNow && tripDef != null
                ? PocketDungeonsConfig.finishVaultChests() : 0;
        int vaultTier = tripDef == null ? LootBands.floorTier(record, rules, floorsCleared)
                : tripDef.lootBand().max();
        TrialContent.placeRewardContainers(level, terminalOrigin, entranceDir, chests,
                LootBands.floorTier(record, rules, floorsCleared), vaultRolls, vaultTier,
                record.floor.affixes.contains(AffixIds.OMINOUS), record.layout.seed(),
                completionTheme == null ? null : completionTheme.meta().lootSuffix,
                completionTheme == null ? null : completionTheme.meta().lootTable,
                promisedItems(tripDef, record));
        if (finishedNow) {
            finishDungeon(server, level, record, tripDef, terminalOrigin, entranceDir, completionTheme);
        }

        // Sealed door in the far wall, behind the chests.
        CellGeometry.sealDoorOnWall(level, terminalOrigin, farWall);

        // M55: the old staging room is cleared to a liminal cell and a new
        // one stamped behind the terminal's far wall for the next door choice.
        BlockPos newStagingOrigin = moveStagingBeyondTerminal(level, record, terminalOrigin, farWall);

        // M67: no sound for room movement. VISION.md §4: "No message, no
        // sound, no lore entry." The room relocation is the trick; the
        // silence is the effect. The previous Chime.roomRelocated call is
        // removed to honour both the vision and this milestone's constraint.

        // Summon fresh screens at the new staging room.
        record.floor.selectedStep = 0;
        Instances.hideLockedDoors(level, newStagingOrigin, farWall, record.owner);
        DungeonScreen.summonDoor(level, newStagingOrigin, farWall,
                DungeonScreen.idleContent(level, record.owner));
        DungeonScreen.summonHistory(level, newStagingOrigin, farWall,
                FloorHistory.board(level.getServer(), record.owner));

        // The way home, beside the doors: the HOME lever and the screen that
        // says what it would bank, lit once the interval has run its usual
        // length.
        RoomTemplateGenerator.placeHomeControl(level, newStagingOrigin, farWall, record.interval.finished);
        DungeonScreen.summonHome(level, newStagingOrigin, farWall, DungeonScreen.homeContent(server, record));

        // M65: transition to FLOOR_CLEARED. The party is now in the
        // staging room, choosing the next door or the way home.
        RunSession.transition(record, RunSession.Phase.FLOOR_CLEARED);
    }

    /**
     * The cleared node's authored {@code rewards}, as stacks for the copper
     * chest. Unknown item ids are logged and skipped, a content typo never
     * failing a floor's rewards.
     */
    private static List<ItemStack> promisedItems(DungeonDef def, InstanceRecord record) {
        DungeonDef.Node node = def == null ? null : def.node(record.interval.nodeId);
        List<ItemStack> promised = new ArrayList<>();
        if (node == null) {
            return promised;
        }
        for (DungeonDef.Node.Reward reward : node.rewards()) {
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getValue(net.minecraft.resources.Identifier.parse(reward.item()));
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                PocketDungeonsMod.LOG.warn("promised reward {} on {}:{} is not an item",
                        reward.item(), def.id(), node.id());
                continue;
            }
            promised.add(new ItemStack(item, reward.count()));
        }
        return promised;
    }

    /**
     * Finishing a dungeon (design D11, D12 revised, J1), run once when the final node's floor is
     * cleared: the dungeon is recorded as finished for each member present, and every
     * dungeon pays its {@code finishEmeralds} per member, the themed
     * vault (extra completion chests at the dungeon's top loot tier) and, on a
     * member's first finish of it, the dungeon's diary page if it names one.
     *
     * <p>The trip then settles as a bank through the ordinary HOME lever: the staging
     * room offers nothing else ({@code record.interval.finished}). That is the
     * simpler and sturdier of the two ways to end a trip: banking, the homecoming and
     * the return trip stay one code path, and the party can still loot the chests and
     * the vault before it walks home. Going home early (any earlier staging room)
     * banks chests only, with no emeralds, vault or page.
     */
    private static void finishDungeon(MinecraftServer server, ServerLevel level, InstanceRecord record,
                                      DungeonDef def, BlockPos terminalOrigin, DoorMask.Direction entranceDir,
                                      ThemeManifest.Entry completionTheme) {
        int vaultChests = PocketDungeonsConfig.finishVaultChests();
        DungeonLog log = DungeonLog.forServer(server);
        for (UUID member : record.members.keySet()) {
            ServerPlayer memberPlayer = server.getPlayerList().getPlayer(member);
            if (memberPlayer == null) {
                continue;
            }
            boolean first = log.addDungeonFinished(member, def.id());
            // D8, D16, D29: every finish re-evaluates the member's acts; a
            // finish that opens nothing names what is left.
            DungeonProgress.onProgress(server, record, memberPlayer, true, first);
            // J1: the finish pays emeralds now; the echo shard is retired.
            int emeralds = PocketDungeonsConfig.finishEmeralds();
            String diaryId = "";
            if (emeralds > 0) {
                Payout.deliver(memberPlayer, new ItemStack(Items.EMERALD, emeralds));
                memberPlayer.sendSystemMessage(Component.literal(
                        "+" + emeralds + " emeralds (dungeon finish)")
                        .withStyle(ChatFormatting.AQUA));
                PlaytestJournal.emeralds(memberPlayer, emeralds, "dungeon_finish");
            }
            // Lemon's archive: all diaries handed over earns a bonus.
            if (LemonArchive.complete(server, member)) {
                Payout.deliver(memberPlayer, new ItemStack(Items.EMERALD, 8));
                PlaytestJournal.emeralds(memberPlayer, 8, "lemon_archive");
            }
            // W6: every dungeon's first finish hands over its diary page (the page is
            // a memory, not a payout).
            if (first && !def.diary().isBlank()) {
                Diaries.Entry page = Diaries.current().byId(def.diary());
                if (page != null && DiaryDelivery.deliverEntry(log, memberPlayer, page)) {
                    diaryId = page.id();
                }
            }
            PlaytestJournal.dungeonFinished(memberPlayer, record, emeralds, vaultChests, first, diaryId);
        }
    }

    /**
     * The block half of a floor advance. The staging room the floor was
     * entered from becomes a blank liminal cell, still standing and so still
     * tracked ({@link InstanceRecord#leftBehind}) until the floor it served is
     * cleared. The safe room is already despawned, so there is no blob to
     * capture or move. A new staging room is stamped in the cell behind the
     * terminal's far wall, its envelope kept off every side a live cell stands
     * on, and the terminal's far door opens into it.
     *
     * @return the new staging room's floor corner
     */
    static BlockPos moveStagingBeyondTerminal(ServerLevel level, InstanceRecord record,
                                              BlockPos terminalOrigin, DoorMask.Direction farWall) {
        BlockPos oldStagingOrigin = record.stagingCellOrigin;
        RoomTemplateGenerator.clearPostSelectionDoors(level, oldStagingOrigin, record.roomDungeonDoor);
        RoomTemplateGenerator.clearFurniture(level, oldStagingOrigin, record.roomDungeonDoor);
        for (Entity leftover : level.getEntitiesOfClass(Entity.class, CellGeometry.cellBounds(oldStagingOrigin),
                e -> !(e instanceof ServerPlayer) && !Lemon.isPart(e))) {
            leftover.discard();
        }
        Set<net.minecraft.core.Direction> backDoors = new LinkedHashSet<>();
        for (DoorMask.Direction dir : CellGeometry.standingNeighbours(record.layout.geometry(), oldStagingOrigin)) {
            backDoors.add(Instances.mcDirection(dir));
        }
        RoomBuilder.buildLiminalCell(level, oldStagingOrigin, backDoors);
        record.leftBehind.add(oldStagingOrigin);

        BlockPos newStagingOrigin = CellGeometry.offsetInDirection(terminalOrigin, farWall, RoomGeometry.CELL);
        Instances.holdCell(level, newStagingOrigin);
        Instances.stampStagingRoom(level, newStagingOrigin, farWall,
                Instances.standingSides(record, newStagingOrigin));
        record.stagingCellOrigin = newStagingOrigin;
        record.roomDungeonDoor = farWall;
        CellGeometry.openDoorOnWall(level, terminalOrigin, farWall);
        return newStagingOrigin;
    }

    /**
     * Closes the floor in progress: banks its clamped omen onto the
     * interval's {@code floorOmens}, zeroes the running omen, and returns the
     * finish band for the interval so far, with the thresholds scaled to the
     * floors banked. The sum only resets when the interval ends
     * ({@link InstanceRecord#beginInterval}).
     */
    /**
     * J1: pays every member present for the floor just cleared. At or below the
     * floor's level the dealt step lands as scrap on the member's log entry, and
     * a new chart level cues the level up chime; a member whose permanent chart
     * high already stands above the floor is paid emeralds instead. The journal
     * gets a {@code floor_pay} event per member.
     */
    private static void payFloorMembers(MinecraftServer server, InstanceRecord record) {
        DungeonLog log = DungeonLog.forServer(server);
        int step = record.floor.chosenStep;
        int floorLevel = record.floor.chosenLevel;
        int rate = PocketDungeonsConfig.overlevelEmeraldsPerScrap();
        for (UUID member : record.members.keySet()) {
            ServerPlayer memberPlayer = server.getPlayerList().getPlayer(member);
            if (memberPlayer == null) {
                continue;
            }
            FloorPay.Payout pay = FloorPay.of(step, floorLevel, log.get(member).highestCharts(), rate);
            if (pay.scrap() > 0) {
                boolean newChart = log.addScrap(member, pay.scrap());
                if (newChart) {
                    int chartLevel = ScrapMath.chartLevel(log.get(member).scrap());
                    if (chartLevel > log.get(member).keystoneLevel()) {
                        Keystones.grantLevel(server, member, memberPlayer, chartLevel);
                    } else {
                        Chime.keystoneLevelUp(memberPlayer);
                    }
                } else {
                    Chime.scrapEarned(memberPlayer);
                }
                Keystone.showScrap(memberPlayer, log.get(member).scrap());
            } else if (pay.emeralds() > 0) {
                Payout.deliver(memberPlayer, new ItemStack(Items.EMERALD, pay.emeralds()));
            }
            // J7: unused trial keys never leave their floor; they settle for
            // emeralds on the same clear line at the salvage rates.
            int keyEmeralds = redeemKeys(memberPlayer);
            StringBuilder line = new StringBuilder();
            if (pay.scrap() > 0 || pay.emeralds() > 0) {
                line.append(pay.line());
                if (pay.scrap() > 0) {
                    int held = log.get(member).scrap();
                    line.append(" (").append(ScrapMath.scrapIntoChart(held)).append("/")
                            .append(ScrapMath.SCRAP_PER_CHART).append(" to the next chart)");
                }
            }
            if (keyEmeralds > 0) {
                if (!line.isEmpty()) {
                    line.append(", ");
                }
                line.append("+").append(keyEmeralds).append(keyEmeralds == 1 ? " emerald" : " emeralds")
                        .append(" for vault keys");
            }
            if (!line.isEmpty()) {
                memberPlayer.sendSystemMessage(Component.literal(line.toString())
                        .withStyle(ChatFormatting.AQUA));
            }
            PlaytestJournal.floorPay(memberPlayer, record, pay.scrap(), pay.emeralds() + keyEmeralds);
        }
    }

    /**
     * J7: buys back every trial key {@code player} still holds at the salvage
     * rates (plain keys at {@code salvageKeyEmeralds}, ominous at
     * {@code salvageOminousKeyEmeralds}), removes them, and returns the
     * emeralds paid; 0 when there were none. Keys never leave their floor.
     * Package private so a gametest can drive it without a floor clear.
     */
    static int redeemKeys(ServerPlayer player) {
        int keys = 0, ominous = 0;
        for (int i = 0; i < InventorySwap.LIVE_SLOTS; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.TRIAL_KEY)) {
                keys += stack.getCount();
                player.getInventory().setItem(i, ItemStack.EMPTY);
            } else if (stack.is(Items.OMINOUS_TRIAL_KEY)) {
                ominous += stack.getCount();
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
        int emeralds = SalvageMath.keyEmeralds(keys, PocketDungeonsConfig.salvageKeyEmeralds())
                + SalvageMath.keyEmeralds(ominous, PocketDungeonsConfig.salvageOminousKeyEmeralds());
        int left = emeralds;
        int max = new ItemStack(Items.EMERALD).getMaxStackSize();
        while (left > 0) {
            int n = Math.min(max, left);
            Payout.deliver(player, new ItemStack(Items.EMERALD, n));
            left -= n;
        }
        return emeralds;
    }

    /**
     * Records the trip's omen at this floor's clear for the journal (J3: the
     * omen itself carries across floors, it is lives, not per-floor pressure).
     */
    static void bankFloorOmen(InstanceRecord record) {
        record.interval.floorOmens.add(Omen.clamp(record.interval.omen));
    }

    /**
     * What {@code member} would bank if the interval settled right now: the
     * go-home screen's numbers, and the same arithmetic
     * {@link #settleInterval} applies.
     */
    static IntervalBanking.Settlement settlementFor(MinecraftServer server, InstanceRecord record,
                                                    UUID member) {
        int floors = record.interval.floorSteps.size();
        return IntervalBanking.settle(record.interval.floorSteps, record.interval.floorLevels,
                DungeonLog.forServer(server).get(member).keystoneLevel(),
                ZoneRules.of(record).bonusChests(floors));
    }

    /**
     * A floor clear's on-screen title: the floor count large, the choice
     * small. Playtest 2026-09-27: the count on the omen bar went unnoticed at
     * the moment of choosing between home and the next floor.
     */
    private static void showFloorClearedTitle(ServerPlayer player, String headline, String subtitle) {
        showBigTitle(player, headline, subtitle);
    }

    /**
     * The go-home title, sent to each member as their interval banks through
     * the home lever: {@code HOME} large, what they keep small. Playtest
     * 2026-09-29: the player liked the floor-clear title as a channel and
     * asked for the same at the moment of going home.
     */
    private static void showHomeTitle(ServerPlayer player, IntervalBanking.Settlement settled) {
        showBigTitle(player, "HOME", IntervalBanking.chests(settled.chests()));
    }

    /**
     * The big on-screen title shared by the floor clear and the way home: the
     * headline, then the subtitle one beat later, the same staggered reveal as
     * the floor start ({@link StaggeredTitle}).
     */
    private static void showBigTitle(ServerPlayer player, String headline, String subtitle) {
        StaggeredTitle.show(player.level().getServer(), player.getUUID(),
                Component.literal(headline).withStyle(ChatFormatting.GOLD),
                List.of(subtitle), ChatFormatting.GRAY);
    }

    /**
     * Settles the interval once: the guard is set before the settlement runs,
     * so one that throws partway is not repeated in full by a retry.
     */
    private static void settleOnce(MinecraftServer server, InstanceRecord record, String trigger) {
        if (!record.interval.safeVisitSettled) {
            record.interval.safeVisitSettled = true;
            settleInterval(server, record, trigger);
        }
    }

    /** The settlement of going home, as the home lever. */
    static void settleSafeVisit(MinecraftServer server, InstanceRecord record) {
        settleInterval(server, record, "home_lever");
    }

    /** {@link #settleInterval(MinecraftServer, InstanceRecord, String)} with the default trigger. */
    static void settleInterval(MinecraftServer server, InstanceRecord record) {
        settleInterval(server, record, "home_lever");
    }

    /**
     * The settlement that ends an interval, for every member present. The
     * home lever, a checkpoint exit and a grace expiry settle identically
     * (D24); {@code trigger} only names what ended the interval for the
     * journal's {@code bank} event: {@code home_lever},
     * {@code checkpoint_exit} or {@code grace_expiry}. Runs the payout
     * command, prestige, the diary and the
     * run record.
     */
    static void settleInterval(MinecraftServer server, InstanceRecord record, String trigger) {
        if (!record.isKeystoneRun()) {
            return;
        }
        IntervalState interval = record.interval;
        int floors = interval.floorSteps.size();
        int bonusChests = ZoneRules.of(record).bonusChests(floors);
        IntervalBanking.Settlement shared = IntervalBanking.settle(interval.floorSteps,
                interval.floorLevels, 0, bonusChests);

        // Per-member settlement: the journal row, payout, prestige, diary.
        DungeonLog log = DungeonLog.forServer(server);
        for (UUID member : record.members.keySet()) {
            ServerPlayer memberPlayer = server.getPlayerList().getPlayer(member);
            if (memberPlayer == null) {
                continue;
            }
            // J1: each floor paid its scrap at the clear, so going home
            // converts nothing. The settlement supplies the chest count for
            // the journal and the home title.
            if (!interval.floorSteps.isEmpty()) {
                DungeonLog.Entry memberEntry = log.get(member);
                IntervalBanking.Settlement settled = new IntervalBanking.Settlement(
                        0, 0, shared.chests());
                PlaytestJournal.bank(memberPlayer, record, trigger, floors, settled, shared.chests(),
                        bonusChests, memberEntry.keystoneLevel());
                PlaytestJournal.inventorySnapshot(memberPlayer, record, "bank");
                memberPlayer.sendSystemMessage(Component.literal("Home.")
                        .withStyle(ChatFormatting.GOLD));
                if ("home_lever".equals(trigger)) {
                    showHomeTitle(memberPlayer, settled);
                }
            }

            // J1: nothing is paid at the bank. The finish emeralds come from
            // finishing a dungeon (finishDungeon); going home early banks
            // chests only (design D11).

            Payout.runPayoutCommand(memberPlayer, record.layout.keystoneLevel(), shared.chests());

            // Dungeon structure W5 (design D14): no kit refill at the bank. The kit is granted
            // once; the Mineshaft and the other node dungeons are the restock.

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

            // M75: keep the server-side run record (the evidence behind a
            // memento) for each member who completed a floor this visit.
            // No item is minted here: the memento is opt-in, minted only
            // when a player asks for one with /dungeon memento. The record
            // is the durable proof, since a placed memento loses its
            // components (DISCOVERIES trap 16).
            if (record.floor.completed.contains(member)) {
                log.addRunRecord(member, new RunMemento.RunRecord(
                        RunMemento.nextDiscoveryId(),
                        record.floor.theme == null ? "" : record.floor.theme,
                        record.layout.keystoneLevel(),
                        LootBands.clamp(LootBands.of(record), KeystoneMath.lootTier(record.layout.keystoneLevel())),
                        record.floor.affixes,
                        System.currentTimeMillis(),
                        EndlessMineRules.cashOutDepth(record)));
            }
        }
    }

    /**
     * The way home from any checkpoint: the HOME lever in a cleared floor's
     * staging room, and {@code /dungeon cashout}, which is kept as the same
     * thing typed. Owner only, and only between floors. A door preview still
     * open is cancelled first (its escrowed catalyst refunded), then
     * {@link #returnToSafe} settles the interval and walks the party home. In
     * the Endless Mine the depth reached is named as well.
     *
     * @return whether the party went home
     */
    static boolean goHome(ServerPlayer player) {
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
        if (!record.inFloorLoop() || !betweenFloors(record)) {
            player.sendSystemMessage(Component.literal(
                    "The way home opens between floors, at the Doors.")
                    .withStyle(ChatFormatting.YELLOW));
            return false;
        }
        // D15: any member may pull HOME unless the leader's decide whitelist is on.
        if (!PartyDecide.mayOrRefuse(server, record, player)) {
            return false;
        }
        boolean mine = EndlessMineRules.isMine(record);
        int depth = EndlessMineRules.cashOutDepth(record);
        if (record.phase == RunSession.Phase.PREVIEW) {
            ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (level != null) {
                Instances.clearPreview(level, record, true);
            }
        }
        if (!returnToSafe(player)) {
            return false;
        }
        if (mine) {
            player.sendSystemMessage(Component.literal(EndlessMineRules.cashOutMessage(depth))
                    .withStyle(ChatFormatting.GOLD));
        }
        return true;
    }

    /**
     * (M57/M65) Returns the party to the safe room from a cleared floor's
     * staging room, settling the interval first. The return is a silent
     * homecoming. The saved room
     * is stamped behind the final staging door, the door opens, and the
     * party walks through physically. No teleport, no chime, no
     * explanation message. The old floor cells are released after all
     * members cross, handled by {@code onTick} through
     * {@link InstanceRecord#homecoming}.
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
                || !PartyDecide.may(server, record, player)) {
            return false;
        }
        // M65: transition to SAFE_RETURN for the duration of the return.
        if (!RunSession.transition(record, RunSession.Phase.SAFE_RETURN)) {
            return false;
        }
        // SAFE_RETURN's only way forward is HOME. Anything that throws before
        // the return lands puts the run back between floors so the lever
        // still works; the settlement guard keeps a retry from paying twice.
        try {
            return completeSafeReturn(server, record, player);
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Safe return for slot {} failed; the run is back between floors",
                    record.slot, e);
            if (record.phase == RunSession.Phase.SAFE_RETURN) {
                abortSafeReturn(record);
            }
            player.sendSystemMessage(Component.literal(
                    "The way home did not open. Try again, or tell an operator.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
    }

    /** Puts a run in {@code SAFE_RETURN} back to {@code FLOOR_CLEARED}, so the lever works again. */
    private static void abortSafeReturn(InstanceRecord record) {
        RunSession.transition(record, RunSession.Phase.FLOOR_CLEARED);
    }

    /** The body of {@link #returnToSafe}, run once the phase is {@code SAFE_RETURN}. */
    private static boolean completeSafeReturn(MinecraftServer server, InstanceRecord record,
                                              ServerPlayer player) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            abortSafeReturn(record);
            return false;
        }

        // M65: settle the interval before tearing down the dungeon, once.
        settleOnce(server, record, "home_lever");

        // M65: silent homecoming. Stamp the saved room behind the final
        // staging door, open the door, and let the party walk through.
        if (!stampHomecomingRoom(level, server, record)) {
            // M65: leave staging usable if stamping fails. Fall back to
            // the old teleport path so the party is never stranded.
            abortSafeReturn(record);
            return fallbackTeleportHomecoming(server, level, record, player);
        }
        BlockPos safeOrigin = record.roomCellOrigin;

        // Re-arm the bag chest at the new room.
        Instances.placeBagChestForParty(level, server, record);

        clearTrialOmenFromMembers(server, record);

        // M66: restore the original bag if a legacy BAG_OVERRIDE was used.
        // M66 no longer swaps bags for new runs, but a legacy tag may still
        // carry bag_original from a pre-M66 run.
        if (record.floor.recipeTags != null) {
            String originalBag = record.floor.recipeTags.getStringOr("bag_original", "");
            if (!originalBag.isEmpty()) {
                DungeonLog.forServer(server).setBag(player.getUUID(), originalBag);
            }
        }

        beginHomecoming(record, server, server.overworld().getGameTime());

        // M65: transition to HOME. The room is loaded, the door is open,
        // and the party can walk through. No teleport and no chime. One line
        // of explanation since the playtest of 2026-09-27 (A9): the room gave
        // no sign that it was the player's own or that they could build there.
        RunSession.transition(record, RunSession.Phase.HOME);
        Instances.announce(server, record, "Home is through the open door: your own room. Build and decorate"
                + " it freely; everything you place is kept, and chests there are safe storage.", null);
        FirstVisitTutorial.homeArrival(server, record);
        return true;
    }

    /**
     * The block half of the silent homecoming. The saved room is stamped in
     * the cell beyond the staging room's dungeon door, with its envelope kept
     * off every side a live cell stands on (the staging room, and any cell of
     * the floor that happens to border it), the staging room's selector wall
     * becomes a doorway, and the room's own wall on that seam is opened to
     * match. The record's room moves there.
     *
     * @return {@code false} if the stamp failed; whatever it wrote has been
     *         cleared again, with the staging room kept, and the record is
     *         unchanged
     */
    static boolean stampHomecomingRoom(ServerLevel level, MinecraftServer server, InstanceRecord record) {
        DoorMask.Direction dungeonDir = record.roomDungeonDoor;
        BlockPos safeOrigin = CellGeometry.offsetInDirection(
                record.stagingCellOrigin, dungeonDir, RoomGeometry.CELL);
        Instances.holdCell(level, safeOrigin);

        // Stamp the saved room while the door is still closed.
        try {
            Set<DoorMask.Direction> standing = new LinkedHashSet<>(Instances.standingSides(record, safeOrigin));
            standing.add(CellGeometry.opposite(dungeonDir));
            Instances.stampSafeRoom(level, server, record.owner, safeOrigin, standing);
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Silent homecoming room stamp failed", e);
            Instances.clearCells(level, record, List.of(safeOrigin));
            return false;
        }

        // The selector doors, their bulbs and the go-home control come out:
        // the doorway is the way home now, and nothing here chooses a floor.
        RoomTemplateGenerator.clearSelectorDoors(level, record.stagingCellOrigin, dungeonDir);
        RoomTemplateGenerator.clearBulbs(level, record.stagingCellOrigin, dungeonDir);
        RoomTemplateGenerator.clearHomeControl(level, record.stagingCellOrigin, dungeonDir);
        DungeonScreen.clearHome(level, record.stagingCellOrigin, dungeonDir);

        // Open the staging door, and the room's own wall on the same seam,
        // which stampSafeRoom sealed: without both there is no way through.
        RoomBuilder.openDoor(level, record.stagingCellOrigin, Instances.mcDirection(dungeonDir));
        BedrockEnvelope.clearFace(level, record.stagingCellOrigin, dungeonDir);
        RoomBuilder.openDoor(level, safeOrigin, Instances.mcDirection(CellGeometry.opposite(dungeonDir)));

        // The room is now at the new location, and the staging room's
        // dungeon door leads into it.
        record.roomCellOrigin = safeOrigin;
        record.roomDungeonDoor = CellGeometry.opposite(dungeonDir);
        return true;
    }

    /**
     * Ends the interval at a silent homecoming. The old staging room and the
     * old floor stay tracked on {@link InstanceRecord#homecoming} until every
     * member has crossed into the room ({@code Instances.onTick} then clears
     * them); the room's lobby layout replaces the floor's.
     */
    static void beginHomecoming(InstanceRecord record, MinecraftServer server, long now) {
        record.homecoming = new InstanceRecord.Homecoming(record.stagingCellOrigin, record.layout, now);
        record.beginInterval(Instances.lobbyLayout(record.roomCellOrigin));
    }

    /**
     * M65: fallback for the silent homecoming when room stamping fails.
     * Reverts to the old teleport-based return: purges everything,
     * re-stamps at the slot origin, teleports the party. Used only when
     * the silent stamp fails so the party is never stranded.
     */
    private static boolean fallbackTeleportHomecoming(MinecraftServer server, ServerLevel level,
                                                       InstanceRecord record, ServerPlayer player) {
        // Clear every cell the run has standing (the floor, the staging room,
        // the liminal cells beside the floors) and release their tickets.
        Instances.clearCells(level, record, record.liveCells());
        record.leftBehind.clear();
        record.stagingCellOrigin = null;

        // Re-stamp the safe room at the slot origin from RoomStore.
        BlockPos safeOrigin = record.origin;
        DoorMask.Direction dungeonDir = DoorMask.Direction.SOUTH;
        Instances.holdCell(level, safeOrigin);
        Instances.stampSafeRoom(level, server, record.owner, safeOrigin);
        record.roomCellOrigin = safeOrigin;
        record.roomDungeonDoor = dungeonDir;

        // Stamp a fresh staging room adjacent to the safe room.
        BlockPos stagingOrigin = CellGeometry.offsetInDirection(safeOrigin, dungeonDir, RoomGeometry.CELL);
        Instances.holdCell(level, stagingOrigin);
        Instances.stampStagingRoom(level, stagingOrigin, dungeonDir);
        RoomBuilder.openDoor(level, safeOrigin, Instances.mcDirection(dungeonDir));
        BedrockEnvelope.clearFace(level, safeOrigin, dungeonDir);
        record.stagingCellOrigin = stagingOrigin;

        // Summon fresh screens at the staging room.
        Instances.hideLockedDoors(level, stagingOrigin, dungeonDir, record.owner);
        DungeonScreen.summonDoor(level, stagingOrigin, dungeonDir,
                DungeonScreen.idleContent(level, record.owner));
        DungeonScreen.summonHistory(level, stagingOrigin, dungeonDir,
                FloorHistory.board(level.getServer(), record.owner));

        // Re-arm the bag chest at the safe room.
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

        clearTrialOmenFromMembers(server, record);

        // M66: restore the original bag if a legacy BAG_OVERRIDE was used.
        // M66 no longer swaps bags for new runs, but a legacy tag may still
        // carry bag_original from a pre-M66 run.
        if (record.floor.recipeTags != null) {
            String originalBag = record.floor.recipeTags.getStringOr("bag_original", "");
            if (!originalBag.isEmpty()) {
                DungeonLog.forServer(server).setBag(player.getUUID(), originalBag);
            }
        }

        // The interval ends here; everything was cleared above, so there is
        // no homecoming left to wait for.
        record.homecoming = null;
        record.beginInterval(Instances.lobbyLayout(safeOrigin));

        RunSession.transition(record, RunSession.Phase.HOME);

        player.sendSystemMessage(Component.literal(
                "You return to the safe room. The dungeon closes behind you.")
                .withStyle(ChatFormatting.GREEN));
        FirstVisitTutorial.homeArrival(server, record);
        // M67: no sound for room movement (VISION.md §4, milestone constraint).
        return true;
    }

    /** Takes Trial Omen back from every online member as the interval ends at home. */
    private static void clearTrialOmenFromMembers(MinecraftServer server, InstanceRecord record) {
        for (UUID member : record.members.keySet()) {
            ServerPlayer inside = server.getPlayerList().getPlayer(member);
            if (inside != null) {
                Instances.clearTrialOmen(inside);
            }
        }
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
     * come back. It stays one for a lobby that never chose a door and for a
     * visit copy, which nothing else would ever close.
     */
    static void dropMember(MinecraftServer server, InstanceRecord record,
                                   UUID member, ServerPlayer player, String reason) {
        dropMember(server, record, member, player, reason, false);
    }

    /**
     * {@link #dropMember(MinecraftServer, InstanceRecord, UUID, ServerPlayer, String)},
     * saying whether the member lost their connection ({@code disconnected})
     * rather than chose to go: an owner who drops with the party still inside
     * gets a reconnect grace ({@link #holdForOwner}) instead of ending the run.
     */
    static void dropMember(MinecraftServer server, InstanceRecord record,
                           UUID member, ServerPlayer player, String reason, boolean disconnected) {
        // M43.2: Instances.detach is the one primitive for this now. Dropping
        // a member used to mean only "forget them," which was survivable when
        // an instance died with its last member; U8 made instances outlive
        // everyone, so what detach also clears (onPad, Trial Omen)
        // matters for the rest of the run, not just at teardown.
        Instances.detach(server, record, member, player);

        // record.members has already had `member` removed above, so a non-empty
        // set here means someone else is still in the party.
        if (leadershipChanged(record, member, !record.members.isEmpty())) {
            if (disconnected && holdForOwner(server, record)) {
                return;
            }
            InstanceTeardown.purge(server, record, "party leader left", member);
            return;
        }

        if ((RunSession.awaitingFirstDoor(record) && record.members.isEmpty())
                || (record.visitInstance && record.members.isEmpty())) {
            InstanceTeardown.purge(server, record, reason, member);
        }
    }

    /**
     * R6: an owner who lost their connection with the party still inside gets
     * {@code ownerReconnectGraceSeconds} to come back before the run ends. The
     * members keep playing the floor they are on; door choices, the commit
     * lever and the way home stay the owner's and wait for them. A rejoin in
     * time goes through free re-entry ({@link Instances#rejoinOwnedInstance}),
     * and {@link Instances#admit} lifts the hold. {@link #watchOwnerGrace}
     * ends the run when the deadline passes. In memory, like the rest of the
     * record.
     *
     * @return whether the run is being held for the owner; {@code false} (grace
     *         off, or not a floor loop run) means end it now, as before
     */
    private static boolean holdForOwner(MinecraftServer server, InstanceRecord record) {
        int grace = PocketDungeonsConfig.ownerReconnectGraceSeconds();
        if (grace <= 0 || !record.inFloorLoop() || record.tearingDown) {
            return false;
        }
        if (record.ownerAbsentUntilTick == 0) {
            record.ownerAbsentUntilTick = server.overworld().getGameTime() + grace * 20L;
            Instances.announce(server, record, "Your party leader lost their connection. The run holds for "
                    + grace + " seconds; the doors and the way home wait for them.", null);
            PocketDungeonsMod.LOG.info("Holding slot {} for its owner {} for {}s after a disconnect",
                    record.slot, record.owner, grace);
            PlaytestJournal.ownerHold(server, record, "start");
        }
        return true;
    }

    /**
     * The watcher's half of {@link #holdForOwner}, once per watch tick while
     * the owner is away. The hold lifts when the owner is back, or when the
     * rest of the party has left too (a run nobody is in simply waits for its
     * owner, as it always has). When the deadline passes the run ends: at a
     * checkpoint the members present first bank the interval one band worse,
     * the same as the owner leaving there; mid-floor it ends as a leader
     * leaving always has.
     *
     * @return whether the run ended
     */
    static boolean watchOwnerGrace(MinecraftServer server, InstanceRecord record, long now) {
        if (record.members.containsKey(record.owner) || record.members.isEmpty()) {
            record.ownerAbsentUntilTick = 0;
            return false;
        }
        if (now < record.ownerAbsentUntilTick) {
            return false;
        }
        record.ownerAbsentUntilTick = 0;
        PlaytestJournal.ownerHold(server, record, "expire");
        if (record.isKeystoneRun() && betweenFloors(record)) {
            ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (record.phase == RunSession.Phase.PREVIEW && level != null) {
                Instances.clearPreview(level, record, true);
            }
            settleOnce(server, record, "grace_expiry");
        }
        Instances.announce(server, record, "Your party leader did not come back in time. The run ends.", null);
        InstanceTeardown.purge(server, record, "party leader did not reconnect");
        return true;
    }

    /**
     * T2.6 / {@code docs/MYTHIC_PLUS_RECONCILIATION.md} §7.2: leadership does not
     * transfer. If the owner is the one leaving <em>and someone else is still in
     * the party</em>, the whole run ends with them (a lost connection first gets
     * {@link #holdForOwner}'s grace). That is stricter than plain
     * purge-when-empty, and deliberately so: transferring ownership is real work
     * with real edge cases this codebase already paid for once (the
     * disconnect-during-teardown race {@code docs/PLAN.md} documents), and "purge and
     * let them re-key" costs nobody anything (no death, inventory kept), just the
     * run.
     *
     * <p>An owner leaving <em>alone</em> is not a leadership change -- that is
     * still U8 Stage 1's free re-entry, unaffected by this rule.
     *
     * @param othersRemain whether anyone besides {@code member} is still in the
     *                     party at the moment of leaving
     */
    static boolean leadershipChanged(InstanceRecord record, UUID member, boolean othersRemain) {
        return member.equals(record.owner) && othersRemain;
    }

    /**
     * The keystone half of {@link #quitDoor}: depletes the owner alone (a
     * party member riding along never had a key at stake) by
     * {@code timedOutDepletion}. The config key's name is kept from the clock it
     * used to belong to, and the default is now 1. Charged whichever door
     * opened the floor, the free door included.
     */
    private static void applyQuitPenalty(MinecraftServer server, InstanceRecord record, ServerPlayer owner) {
        int cost = PocketDungeonsConfig.timedOutDepletion();
        returnKeystone(server, record, record.owner, owner, Keystones.Outcome.QUIT);
        owner.sendSystemMessage(Component.literal(
                "You quit the door. Your compass is downgraded by " + cost
                        + (cost == 1 ? " level." : " levels."))
                .withStyle(ChatFormatting.YELLOW));
        Chime.doorQuit(owner);
    }

    /**
     * Player-facing: instantly fails the caller's own active keystone door,
     * applies the keystone penalty, then resets the dungeon back to its
     * lobby state so the player can pick a new door. Nobody is ejected: the
     * player stays in the safe room, the dungeon beyond it is cleared, the
     * selector doors come back, and the record returns to {@code HOME}.
     *
     * <p>The penalty is {@link #applyQuitPenalty}. Skipped (falls straight
     * through to {@link #exit}) when there is nothing to quit: no active
     * keystone run, or the floor is already cleared. A non-
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
        // Nothing to quit: the floor is already cleared. Fall through to a
        // plain exit so the player leaves normally.
        if (!record.floor.completed.isEmpty()) {
            return exit(player, ExitReason.COMMAND);
        }
        // The reset re-stamps the safe room from its saved blob, so a standing
        // room is saved first, before the penalty: a failed save refuses the
        // quit with nothing charged and nothing cleared.
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level != null && !saveRoom(level, server, record)) {
            PocketDungeonsMod.LOG.error("Refused /dungeon quit for {}: their room could not be saved",
                    player.getName().getString());
            player.sendSystemMessage(Component.literal(
                    "Your room could not be saved, so nothing was quit. Tell an operator.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        // Apply the keystone penalty, then reset the dungeon to its lobby
        // state. The player stays in the safe room and picks a new door.
        FloorHistory.quit(player, record);
        applyQuitPenalty(server, record, player);
        PlaytestJournal.quitFloor(player, PocketDungeonsConfig.timedOutDepletion());
        Instances.resetToLobby(server, record, true);
        return true;
    }
}
