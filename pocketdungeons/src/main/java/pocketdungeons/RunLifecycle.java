package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
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

    // ---- entry ----------------------------------------------------------

    /**
     * Opens a dungeon for this player and their pre-registered party.
     *
     * <p>Returns whether the player actually went in. Every failure path here
     * leaves them standing exactly where they were, and {@link RitualListener}
     * pays a real item for entry -- so "did this work" has to be answerable by
     * the caller rather than inferred from a message the player was sent.
     */
    static boolean enter(ServerPlayer player) {
        return enter(player, 0, EnumSet.noneOf(Affix.class));
    }

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
                    "You need a keystone to open a dungeon. Run /dungeon key for your first one.")
                    .withStyle(ChatFormatting.RED));
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
            // A lingering quarry (T2.5) is not re-entered for free -- it is done,
            // and opening a new run purges it. See enter()'s lingering-quarry check.
            //
            // Nor is an instance that has already completed (T2.4): once the
            // room has moved to the terminal cell, "re-entering" this instance
            // means dropping the player at a cleared entrance cell with their
            // room sitting at the far end, which reads as broken even though it
            // is exactly what a finished run looks like now. A run that is done
            // is done -- there is nothing left to continue, and the reward room
            // stays reachable through its own grace window/lingering-quarry
            // path regardless (T2.5), not through free re-entry.
            if (!candidate.lingering && !candidate.visitInstance
                    && candidate.completed.isEmpty() && owner.equals(candidate.owner)) {
                return candidate;
            }
        }
        return null;
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
    static boolean chooseOffer(ServerPlayer player, int step) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        if (step < 1 || step > 3) {
            player.sendSystemMessage(Component.literal("Choose 1, 2 or 3.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !record.awaitingDoorChoice || !player.getUUID().equals(record.owner)) {
            player.sendSystemMessage(Component.literal("There is no door here for you to choose.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        DungeonLog log = DungeonLog.forServer(server);
        DungeonLog.Entry entry = log.get(player.getUUID());
        Keystone.Offer[] offers = Keystone.offers(player.getUUID(), entry.keystoneLevel(),
                entry.currentTheme(), entry.depth());
        Keystone.Offer offer = offers[step - 1];

        // M12: the Greater tier's two refusals, checked ahead of spending
        // anything or generating anything. Door 1 never refuses on either.
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
            if (Fuel.count(player) < cost) {
                player.sendSystemMessage(Component.literal(
                        "Door " + step + " costs " + cost + " fuel; you do not have enough.")
                        .withStyle(ChatFormatting.RED));
                return false;
            }
        }

        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null || !Instances.generateBehindLobby(server, level, record, offer, step)) {
            player.sendSystemMessage(Component.literal(
                    "The dungeon failed to build. Try another door.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        // The spend happens only once the choice has actually succeeded, so a
        // failed stamp (the branch above) never costs fuel for nothing.
        if (!offer.free()) {
            Fuel.spend(player, PocketDungeonsConfig.fuelCostPerGreaterDoor());
        }

        EnumSet<Affix> granted = AffixMath.effective(player.getUUID(), offer.level(),
                offer.affixes());
        player.sendSystemMessage(Component.literal(
                AffixMath.name(offer.level(), granted) + ". The door opens.")
                .withStyle(Keystone.colourOf(
                        AffixMath.ordered(granted).stream().findFirst().orElse(null))));
        return true;
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
    static void saveRoom(ServerLevel level, MinecraftServer server, InstanceRecord record) {
        if (record.roomCellOrigin == null || record.visitInstance) {
            return;
        }
        DoorMask.Direction eeDir = CellGeometry.opposite(record.roomDungeonDoor);
        int roomRotation = CellGeometry.rotationToFace(DoorMask.Direction.NORTH, eeDir);

        // While a door choice is pending, the three selector doors are standing
        // live blocks in the room -- capturing them would bake that instant's
        // doors into the blob permanently. They would be overwritten correctly
        // everywhere the room is re-stamped as a lobby (placeSelectorDoors always
        // runs right after RoomStore.place there), but LayoutStamper also
        // re-skins a generated dungeon's entrance cell with this same blob, and
        // that path has no reason to touch selector doors at all -- baked-in
        // ones would leak into every dungeon run forever. Clear them out for the
        // capture and put them straight back, so the live room the owner is
        // standing in is undisturbed.
        //
        // M18: once a door is chosen, the post-selection double doors stand in
        // the room's dungeon wall instead. Same capture hygiene, same reason:
        // the doors are run-scoped mod furniture, not part of the room, and
        // must not bake into the blob either.
        if (record.awaitingDoorChoice) {
            RoomTemplateGenerator.clearSelectorDoors(level, record.roomCellOrigin, record.roomDungeonDoor);
        } else {
            RoomTemplateGenerator.clearPostSelectionDoors(level, record.roomCellOrigin, record.roomDungeonDoor);
        }
        RoomStore.capture(level, server, record.owner, record.roomCellOrigin, roomRotation);
        if (record.awaitingDoorChoice) {
            RoomTemplateGenerator.placeSelectorDoors(level, record.roomCellOrigin, record.roomDungeonDoor);
        } else {
            RoomTemplateGenerator.placePostSelectionDoors(level, record.roomCellOrigin, record.roomDungeonDoor);
        }
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
            if (level != null) {
                saveRoom(level, server, record);
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
    static void saveRoomIfOwnerSync(ServerLevel level, MinecraftServer server,
                                    InstanceRecord record, UUID member) {
        if (record.owner == null || !record.owner.equals(member)
                || record.roomCellOrigin == null || record.visitInstance) {
            return;
        }
        saveRoom(level, server, record);
    }

    /**
     * Teleports party members still in the old dungeon back into the room, clears
     * the old dungeon cells, and seals the room's {@code ee} wall so the void
     * behind the previous dungeon cannot be entered.
     */
    static void resetForNextDungeon(MinecraftServer server, InstanceRecord record) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null || record.roomCellOrigin == null) {
            return;
        }
        BlockPos roomOrigin = record.roomCellOrigin;
        DoorMask.Direction eeDir = CellGeometry.opposite(record.roomDungeonDoor);

        // Persist any edits made to the room while the last run was lingering,
        // before the old dungeon is cleared and the room is re-sealed.
        saveRoom(level, server, record);

        // Pull stragglers into the room.
        BlockPos roomCentre = roomOrigin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
        for (ServerPlayer straggler : server.getPlayerList().getPlayers()) {
            if (!record.members.containsKey(straggler.getUUID())) {
                continue;
            }
            if (straggler.level().dimension() != PocketDungeonsMod.DUNGEON_LEVEL) {
                continue;
            }
            if (Instances.roomOwnerAt(straggler.blockPosition()) != null) {
                continue; // already inside the room
            }
            Instances.teleport(server, straggler, PocketDungeonsMod.DUNGEON_LEVEL,
                    net.minecraft.world.phys.Vec3.atBottomCenterOf(roomCentre), 0.0f, 0.0f);
        }

        // Clear every cell of the previous dungeon except the room itself. The
        // room is named as a keep-cell because the cell adjacent to it clears a
        // one-block margin that lands squarely on the room's own wall column.
        List<BlockPos> keepRoom = List.of(roomOrigin);
        for (BlockPos cellOrigin : record.layout.geometry().cellOrigins()) {
            if (cellOrigin.equals(roomOrigin)) {
                continue;
            }
            Instances.clearCellSync(level, cellOrigin, keepRoom);
        }

        // Seal the room's entrance from the previous dungeon.
        CellGeometry.sealDoorOnWall(level, roomOrigin, eeDir);

        // ...and put the bedrock back behind that seal. The clear above ran
        // through the margin the room's own envelope occupies, so without this
        // the room is left with a sealed wall and nothing but cleared void
        // behind it. Only the MM side stays reserved -- that is where the run
        // about to be generated connects.
        BedrockEnvelope.applyToCell(level, roomOrigin, Set.of(record.roomDungeonDoor));
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

    static void exit(ServerPlayer player, ExitReason reason) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null) {
            player.sendSystemMessage(Component.literal("You are not in a dungeon.")
                    .withStyle(ChatFormatting.RED));
            return;
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
            return;
        }

        Instances.eject(server, record, player);

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
            return;
        }

        Instances.purgeIfAbandonedLobby(server, record);
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
        Keystones.returnTo(server, member, player, record.layout.keystoneLevel(), record.affixes, outcome);
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
        if (record.isKeystoneRun()) {
            Set<BlockPos> spawners = record.layout.trialSpawners();
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

        if (firstCompletion) {
            if (record.timer != null) {
                record.timer.markCompleted();
            }
            completeDungeon(server, record);
        }

        DungeonLog log = DungeonLog.forServer(server);
        log.recordCompletion(player.getUUID(), record.layout.pathLength(),
                record.layout.keystoneLevel());
        DungeonLog.Entry entry = log.recordTheme(player.getUUID(), record.theme);

        int chests = record.rewardChests;
        boolean late = chests <= 0;
        if (late) {
            // Completed after the clock: the run still counts and still offers a
            // door, but finishing late costs a couple of levels -- the other way,
            // besides a full timeout, to lose ground. Settled through the same
            // path every other depletion is (Keystones.Outcome.LATE), so the
            // keystoneReturned guard it sets stops a later exit() from
            // overwriting this back to the pre-run level.
            //
            // M12: door 1 never depletes, full stop, so a late free-door finish
            // settles as NO_CHANGE instead, the same exemption expireTimedOut
            // applies to a free door that runs out the clock entirely.
            //
            // PD-7: a timeout already depleted the keystone once for this run
            // (record.timedOutPenaltyApplied). Applying LATE on top of that
            // would double penalize a player who finishes in overtime after
            // the timeout already fired, so it settles as NO_CHANGE instead.
            Keystones.Outcome outcome;
            if (record.freeDoor || record.timedOutPenaltyApplied) {
                outcome = Keystones.Outcome.NO_CHANGE;
            } else {
                outcome = Keystones.Outcome.LATE;
            }
            returnKeystone(server, record, player.getUUID(), player, outcome);
        }
        // M2/M3: the door choice already happened, at the lobby, before this
        // run started -- record.chosenStep is which of Keystone.offers this
        // player's own current level (the run's starting level normally, or
        // the just-depleted one on a late finish) is banked at. Reaching a
        // door at all is what mitigates the delevel: a +1 door nets only -1
        // overall against a 2-level late penalty. Banked immediately rather
        // than parked as a pending offer -- there is no later "go choose a
        // door" step any more, so nothing is left to settle.
        if (record.chosenStep > 0) {
            DungeonLog.Entry memberEntry = log.get(player.getUUID());
            Keystone.Offer[] offers = Keystone.offers(player.getUUID(), memberEntry.keystoneLevel(),
                    memberEntry.currentTheme(), memberEntry.depth());
            Keystone.Offer banked = offers[record.chosenStep - 1];
            Keystones.grantOffer(server, player.getUUID(), player, banked);
            // Guards a later exit() from settling the keystone again now that
            // it has already been replaced with the banked offer.
            record.keystoneReturned.add(player.getUUID());
        }

        // M12: door 1's second job. Granted guaranteed, not a loot roll, and
        // regardless of lateness: a late free-door finish already lost its
        // chests, and costing it the fuel too would be a second, undocumented
        // penalty for a tier that is supposed to never deplete anything.
        if (record.freeDoor) {
            Fuel.grant(player, PocketDungeonsConfig.fuelPerFreeRun());
        }

        Payout.runPayoutCommand(player, record.layout.keystoneLevel(), chests);

        if (!late) {
            player.sendSystemMessage(Component.literal(
                    "You reach the end. " + chests + " chest" + (chests == 1 ? "" : "s")
                            + " wait in your room, and the door stands open.")
                    .withStyle(ChatFormatting.AQUA));
        } else {
            player.sendSystemMessage(Component.literal(
                    "The chests stay empty, but the door to your room stands open.")
                    .withStyle(ChatFormatting.YELLOW));
        }
        PocketDungeonsMod.LOG.info("{} completed dungeon slot {} (run #{}, chests {}, tier {})",
                player.getName().getString(), record.slot, entry.runsCompleted(),
                chests, record.layout.lootTier());
    }

    /**
     * The player reached the terminal exit pad. The terminal cell itself stays
     * the exit room with its 4 lodestones; chests spawn on the far side, and the
     * persistent room is stamped behind the sealed far wall. The sealed door is
     * then opened so the player can walk into their room.
     */
    private static void completeDungeon(MinecraftServer server, InstanceRecord record) {
        if (record.roomCellOrigin == null) {
            return;
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return;
        }

        BlockPos terminalOrigin = record.layout.terminal();
        DoorMask.Direction entranceDir = CellGeometry.terminalEntranceDirection(record.layout.geometry(), terminalOrigin);
        DoorMask.Direction farWall = CellGeometry.opposite(entranceDir);

        int secondsRemaining = record.timer != null ? record.timer.secondsRemaining() : Integer.MAX_VALUE;
        int totalSeconds = record.timer != null ? record.timer.totalSeconds() : 1;
        int chests = PayoutMath.chestCount(secondsRemaining, totalSeconds,
                PocketDungeonsConfig.threeChestPercent(), PocketDungeonsConfig.twoChestPercent());
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

        // Capture, persist, and clear the current room cell. The room's actual
        // rotation right now comes from record.roomDungeonDoor -- exactly the
        // formula saveRoom/resetForNextDungeon use -- not layout.entranceRotation():
        // that field is the planner's rotation for a room LayoutStamper itself
        // stamps into the entrance cell, and stampBehindLobby explicitly never
        // does that for the lobby (Instances stamps/relocates it with its own,
        // unrelated rotation math). Using it here captured the room under
        // whatever unrelated value the planner happened to assign the entrance
        // cell, so the very next RoomStore.place below could rotate the entire
        // captured room by however far that value differed from the truth --
        // walls and doors landing wherever the rotation put them, not where the
        // fresh openDoorOnWall/sealDoorOnWall/placeSelectorDoors calls after it
        // assume they are. Each completed run recaptured whatever that had
        // already left standing, so the damage compounded floor over floor.
        BlockPos oldRoomOrigin = record.roomCellOrigin;
        int oldRoomRotation = CellGeometry.rotationToFace(DoorMask.Direction.NORTH, CellGeometry.opposite(record.roomDungeonDoor));
        // M18: the post-selection double doors stand in the room's dungeon wall
        // for the whole run, and this capture happens with the run just
        // finished. Clear them first (capture hygiene, trap 17) so they do not
        // bake into the blob and leak into some later placement of it; the room
        // is moving anyway, so there is nothing to put back. The old cell is
        // blanked below and the new one is re-armed by the caller.
        RoomTemplateGenerator.clearPostSelectionDoors(level, oldRoomOrigin, record.roomDungeonDoor);
        RoomStore.capture(level, server, record.owner, oldRoomOrigin, oldRoomRotation);

        // What the room leaves behind: a blank stone-brick room, not a hole.
        // Clearing this cell wrote air through the one-block margin too, and
        // that margin is the bedrock envelope -- so on every face of this cell
        // without an occupied neighbour, the clear erased the shell and left the
        // still-standing dungeon room next door with a mineable path off the
        // edge of the world. A stamp stays inside the cell and leaves the
        // bedrock alone.
        //
        // Doorways are reopened toward whichever neighbours are still standing,
        // so a player who walks back up the dungeon finds an empty room rather
        // than a sealed face where their room used to be.
        Set<net.minecraft.core.Direction> backDoors = new LinkedHashSet<>();
        for (DoorMask.Direction dir : CellGeometry.standingNeighbours(record.layout.geometry(), oldRoomOrigin)) {
            backDoors.add(Instances.mcDirection(dir));
        }
        for (Entity leftover : level.getEntitiesOfClass(Entity.class, CellGeometry.cellBounds(oldRoomOrigin),
                e -> !(e instanceof ServerPlayer))) {
            // The blob just captured these; without this they would stand here
            // as well as in the room's new cell.
            leftover.discard();
        }
        RoomBuilder.buildLiminalCell(level, oldRoomOrigin, backDoors);

        // Stamp the room in the cell behind the far wall, rotated so its entrance
        // (the 'ee' side) faces back toward the terminal cell. farWall is the MM
        // side, so ee is the opposite wall.
        BlockPos newRoomOrigin = CellGeometry.offsetInDirection(terminalOrigin, farWall, RoomGeometry.CELL);
        int targetRotation = CellGeometry.rotationToFace(DoorMask.Direction.NORTH, CellGeometry.opposite(farWall));
        boolean placed = RoomStore.place(level, server, record.owner, newRoomOrigin, targetRotation,
                net.minecraft.util.RandomSource.create(record.layout.seed()));
        if (!placed) {
            PocketDungeonsMod.LOG.error(
                    "completeDungeon captured a room for {} but could not re-place it at slot {}",
                    record.owner, record.slot);
            record.roomCellOrigin = null;
            return;
        }
        record.roomCellOrigin = newRoomOrigin;
        record.roomDungeonDoor = farWall;

        // The room arrives exactly as it was captured: 'ee' sealed (the run that
        // just finished sealed it), 'MM' standing open onto the cell the last
        // dungeon used to occupy, and no selector doors -- choosing one is what
        // cleared them. Re-arm all three against the room's new orientation, or
        // the player lands in a sealed box whose doors are gone and whose one
        // opening looks out onto nothing.
        CellGeometry.openDoorOnWall(level, newRoomOrigin, CellGeometry.opposite(farWall));
        CellGeometry.sealDoorOnWall(level, newRoomOrigin, farWall);
        RoomTemplateGenerator.placeSelectorDoors(level, newRoomOrigin, farWall);
        record.awaitingDoorChoice = true;

        // The bedrock envelope, same as stampLobby's applyToCell: nothing
        // else stamps one for this cell until a door is chosen and
        // LayoutStamper.stampBehindLobby's full BedrockEnvelope.apply runs over
        // the next plan's whole geometry. Left alone, the relocated room is
        // walled but has no bedrock backstop for however long the owner takes
        // to choose -- and voidGuardDepth's fallback aside, that gap is real
        // digging distance, not a documented default. farWall stays reserved
        // (no bedrock) since a real connection will exist there once a door is
        // chosen, exactly like a fresh lobby's reservedSide.
        BedrockEnvelope.applyToCell(level, newRoomOrigin, Set.of(farWall, CellGeometry.opposite(farWall)));

        // Open the sealed door into the room. That is the whole of the reward for
        // reaching the pad: nobody is teleported anywhere. The terminal cell's
        // lodestones mark the end of the run, not an exit -- the chests are here,
        // the door to the room now stands open behind them, and walking through
        // it is the player's to do. (The room's own leave-pad is the lodestone
        // that actually moves anyone, and it moves them out of the dungeon.)
        CellGeometry.openDoorOnWall(level, terminalOrigin, farWall);
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
        // Keyed off `member`, not `player`: an offline drop passes a null player.
        saveRoomIfOwner(server, record, member);
        record.members.remove(member);
        InstanceRegistry.byMember.remove(member);

        // Everything eject detaches, minus the teleport. Dropping a member used to
        // mean only "forget them", which was survivable when an instance died with
        // its last member; U8 made instances outlive everyone, so each of these
        // now persists for the rest of the run:
        //
        //  - onPad is UUID-keyed and read as an edge. A member who leaves standing
        //    on the pad and walks back in (free re-entry, U8 Stage 1) would find
        //    their contact already recorded and the pad inert until they step off.
        //  - ServerBossEvent holds ServerPlayer references and prunes none of them
        //    itself; the timer now ticks with nobody inside, so a disconnected
        //    player would be broadcast to for the rest of the run.
        //  - Trial Omen is the one effect this mod can export into the real world.
        //    eject cleared it; every dropMember path did not, so an admin teleport
        //    out of an ominous run -- or a disconnect -- carried it to the overworld.
        record.onPad.remove(member);
        if (player != null) {
            if (record.timer != null) {
                record.timer.removePlayer(player);
            }
            Instances.clearTrialOmen(player);
        }

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
     * Depletes the owner alone -- a party member riding along never had a
     * key at stake -- and messages them wherever they are.
     *
     * <p>M12: a free-door run settles as {@code NO_CHANGE} instead. Door 1
     * never depletes, and a generous flat clock ({@code door1TimerSeconds})
     * running out is not a different kind of failure than any other way a
     * free door ends.
     */
    static void expireTimedOut(MinecraftServer server, InstanceRecord record) {
        ServerPlayer owner = record.owner != null ? server.getPlayerList().getPlayer(record.owner) : null;
        returnKeystone(server, record, record.owner, owner,
                record.freeDoor ? Keystones.Outcome.NO_CHANGE : Keystones.Outcome.TIMED_OUT);
        record.timedOutPenaltyApplied = true;
        if (owner != null) {
            owner.sendSystemMessage(Component.literal(
                    "The clock ran out. Your keystone is downgraded by "
                            + PocketDungeonsConfig.timedOutDepletion()
                            + ", but the dungeon stays open. Finish it for a door.")
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            PocketDungeonsMod.LOG.info("Dungeon slot {} timed out with its owner offline", record.slot);
        }
    }
}
