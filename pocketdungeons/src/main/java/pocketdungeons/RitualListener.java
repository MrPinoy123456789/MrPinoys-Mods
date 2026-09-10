package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.UUID;

/**
 * The lodestone terminal (M21): right-click a lodestone opens the mod
 * navigation menu, with any item or an empty hand. The keystone is checked
 * on the menu's Start Dungeon button, not at the block.
 *
 * <h2>U7 inverts the key rule</h2>
 *
 * <p>U4's rule was "consume only a stack with <em>no</em> {@code custom_data} at
 * all", which was right when the key was a plain echo shard and the danger was
 * eating somebody's kamutotems sigil. It is now the exact opposite: consume
 * <strong>only</strong> a stack carrying {@code pocketdungeons.keystone}, and
 * {@code PASS} on everything else -- sigils included, for exactly U4's reasoning,
 * and now for free, because the test is positive rather than a list of things to
 * avoid. Lodestone plus keystone is the font.
 *
 * <h2>U8 Stage 4: one lodestone, three destinations</h2>
 *
 * <p>Right-clicking a lodestone with the compass now branches on server state
 * (T14): a pending door offer routes to the selector room, an owned live
 * instance re-enters it for free, and anything else opens a fresh run. Ominous
 * is no longer requested here at all -- it rides entirely on the keystone's own
 * affix (U8 Stage 6).
 *
 * <p>{@code /dungeon} still works and still costs the same keystone. This is a
 * flavour entry point, not a gate: it is the <em>lodestone</em> that is optional,
 * never the key.
 */
final class RitualListener {

    private RitualListener() {}

    static void register() {
        UseBlockCallback.EVENT.register(RitualListener::onUseBlock);

        // Resolve every configured item once at startup rather than on the first
        // click. A typo is an operator's mistake to hear about at boot, not
        // something a player discovers by right-clicking a lodestone and having
        // nothing happen.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Keystone.warmUp();
            TrialContent.warmUp();
            Fuel.warmUp();
            RerollStation.warmUp();
            GambleStation.warmUp();
            TrimListener.warmUp();
            CubeStation.warmUp();
            PowerListener.warmUp();
        });
    }

    private static InteractionResult onUseBlock(Player player, Level level,
                                                InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        // Both hands fire this event; without the gate the ritual would try to
        // run twice for a single click.
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        BlockPos pos = hit.getBlockPos();

        // Dev tool: right-click any block with a spyglass to get its
        // coordinates in chat, both absolute and relative to its chunk and
        // to the nearest Pocket Dungeons cell origin.
        if (serverPlayer.getMainHandItem().is(Items.SPYGLASS)) {
            reportCoords(serverPlayer, level, pos);
            return InteractionResult.SUCCESS_SERVER;
        }

        // Room Editor Inspector: right-click a placed gameplay object with
        // the inspector tool to open its properties GUI. Only in a build room.
        if (RoomEditorKit.isInspector(serverPlayer.getMainHandItem())) {
            InstanceRecord record = InstanceRegistry.byMember.get(serverPlayer.getUUID());
            if (record != null && record.adminBuild) {
                if (RoomEditorInspector.open(serverPlayer, (ServerLevel) level, pos)) {
                    return InteractionResult.SUCCESS_SERVER;
                }
            }
        }

        // M48: the bag chest. A member of a non-visit instance who has not yet
        // chosen a bag right-clicks the ender chest at the safe room's centre
        // to open the bag picker, independently of every other member. This
        // claims the click before the ender-chest denial below, which would
        // otherwise block it (the bag chest is an ender chest, deliberately,
        // to read as distinct from loot chests). Position-based, the same way
        // the selector doors, lever and engine are identified.
        InstanceRecord bagRecord = InstanceRegistry.byMember.get(serverPlayer.getUUID());
        if (bagRecord != null && !bagRecord.visitInstance && bagRecord.roomCellOrigin != null
                && pos.equals(Instances.bagChestPos(bagRecord.roomCellOrigin))
                && level.getBlockState(pos).is(Blocks.ENDER_CHEST)
                && DungeonLog.forServer(level.getServer()).bagOf(serverPlayer.getUUID()).isEmpty()) {
            DialogKit.show(serverPlayer, DialogScreens.bagPicker(serverPlayer));
            return InteractionResult.SUCCESS_SERVER;
        }

        // M46 (spec 11.11): the ender chest is the one container that reaches
        // across dimensions, which makes it the obvious way around the scarcity
        // the bag creates: a player with netherite in their ender chest could
        // otherwise take it into a run from inside the room. The block stays
        // where it is and stays a decoration; it just does not open in here.
        if (level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)
                && level.getBlockState(pos).is(Blocks.ENDER_CHEST)) {
            return InteractionResult.FAIL;
        }

        // M2 T2.2: the room's permission mask, ahead of everything else below --
        // a denied container open or a denied placement must never fall through
        // to the ritual or to vanilla's own handling of the block. Positional
        // (Instances.roomOwnerAt is one bounds check per live instance) and
        // early-returns the moment the position is not inside anyone's room, so
        // the common case -- the other 15 cells of the dungeon, or the
        // overworld entirely -- costs exactly that one check.
        if (RoomProtection.denyContainerUse(level, player, pos, level.getBlockEntity(pos))) {
            return InteractionResult.FAIL;
        }
        // Placement: there is no generic "before a block is placed" event in
        // this mod's Fabric API surface, so this is the same trick as the
        // container check above: deny the use-on-block interaction that a
        // placement begins from when the target face sits inside someone else's
        // room and the held item would put something there. M18 9.1: the
        // room's shell is immutable to everyone, the owner included, so a
        // placement landing in the shell is denied even for a permitted player.
        // The gate covers fluid placement too (a bucket is not a BlockItem):
        // a bucket aimed at a shell or furniture position is denied the same
        // way, and a non-permitted player can no longer pour water or lava in
        // a room they are only visiting. Decorations are unaffected: a wall
        // sign, torch or banner sits on the face of a wall (target x=1..14,
        // interior), and a carpet lands on the floor's top face (target Y=1,
        // interior), so their target positions are never shell.
        BlockPos placementPos = hit.getBlockPos().relative(hit.getDirection());
        // M43.4: one roomRecordAt scan instead of the three separate
        // roomOwnerAt/roomOriginAt/roomDungeonDoorAt calls this used to make
        // for the same position.
        InstanceRecord placementRoomRecord = Instances.roomRecordAt(placementPos);
        UUID placementRoomOwner = placementRoomRecord == null ? null : placementRoomRecord.owner;
        if (placementRoomOwner != null && isPlacementSource(player.getItemInHand(hand))) {
            // M55: use roomOriginAt to get the correct cell origin (safe room
            // or staging room) for this position, and roomDungeonDoorAt for
            // the furniture direction (only set for the staging room).
            BlockPos placementRoomOrigin = Instances.roomOriginAt(placementPos);
            DoorMask.Direction placementDungeonDoor = Instances.roomDungeonDoorAt(placementPos);
            // M19 19.7: furniture is protected from placement the same way the
            // shell is. The door screen blocks sit in the wall ring (already
            // shell), but the bulbs, the lever and the engine screen stand on
            // interior or adjacent-wall positions that only this check covers.
            if ((placementRoomOrigin != null
                    && (RoomProtection.isShell(placementPos, placementRoomOrigin)
                            || (placementDungeonDoor != null
                                    && RoomProtection.isFurniture(placementPos, placementRoomOrigin,
                                            placementDungeonDoor))))
                    || !RoomProtection.isPermitted(level, player, placementRoomOwner)) {
                return InteractionResult.FAIL;
            }
        }
        // M31 9.2: the same denial for an active run's dungeon cells outside
        // any room -- but only the shell, the same as the player room. The
        // interior stays open so a player can place torches, blocks and the
        // like while fighting through. Protection lifts once the first member
        // completes.
        //
        // PD-62: one shell position is deliberately let through anyway. The
        // far side of an IRON_DOOR connector never gets its own lever
        // (ConnectorStamper.applyIronDoor only stamps the near side), and the
        // doorway threshold in front of the door is shell purely as a side
        // effect of isShell being a blanket coordinate rule with no notion of
        // "this square happens to be open air, not wall": a player who
        // backtracks and finds the door shut behind them had no way to power
        // it. The door itself stays shell-protected either way; only the
        // empty threshold square is exempted, and only for that connector
        // type. See InstanceLayout.ironDoorFarSideSlots.
        BlockPos dungeonPlacementOrigin = Instances.dungeonCellOriginAt(placementPos);
        if (dungeonPlacementOrigin != null
                && isPlacementSource(player.getItemInHand(hand))
                && RoomProtection.isShell(placementPos, dungeonPlacementOrigin)) {
            InstanceRecord dungeonRecord = Instances.dungeonRecordAt(placementPos);
            boolean ironDoorFarSideExempt = dungeonRecord != null
                    && dungeonRecord.layout.ironDoorFarSideSlots().contains(placementPos);
            if (!ironDoorFarSideExempt) {
                return InteractionResult.FAIL;
            }
        }

        // M25: the Pocket2 rare door. Claimed whenever the click lands on this
        // player's run's pocket door; anything else falls through to the
        // stations below.
        if (Pocket2.tryEnter(serverPlayer, level, pos)) {
            return InteractionResult.SUCCESS_SERVER;
        }

        // M14: the reroll station. A positive test on the held item, same as
        // the keystone branch below: anything that is not tagged tiered gear
        // falls straight through to whatever this block would otherwise do (by
        // default a plain vanilla smithing table).
        if (RerollStation.onUse(serverPlayer, level.getBlockState(pos), player.getItemInHand(hand))) {
            return InteractionResult.SUCCESS_SERVER;
        }

        // M16: the gamble station. Unlike the reroll station above, there is
        // nothing to check about what is held -- a gamble draw has no target
        // item -- so the configured block is fully claimed the moment it
        // matches, the same way a selector door claims its click.
        if (GambleStation.onUse(serverPlayer, level.getBlockState(pos))) {
            return InteractionResult.SUCCESS_SERVER;
        }

        // M17: the Herobrine Cube. Two positive tests on the held item (a rare
        // reward to extract, or imbuable gear with no power yet), same shape as
        // the reroll station above; anything else at the same block falls
        // straight through to vanilla's own behaviour.
        if (CubeStation.onUse(serverPlayer, level.getBlockState(pos), player.getItemInHand(hand))) {
            return InteractionResult.SUCCESS_SERVER;
        }

        // M19 19.6: the engine terminal, a respawn anchor on the wall beside
        // the selector wall. Intercepted ahead of vanilla's own anchor
        // behaviour (the Nether glowstone charge) and ahead of the door
        // branches: the anchor is not a door, but it stands in the same room
        // and answers to the same gesture.
        if (Instances.engineTerminalAt(serverPlayer, pos)
                && level.getBlockState(pos).is(Blocks.RESPAWN_ANCHOR)) {
            ItemStack held = player.getItemInHand(hand);
            if (Fuel.isFuel(held)) {
                // Banked, not burned. The shard leaves the inventory and lands
                // on the player's balance, which is the only thing a Greater
                // door can spend; the anchor's own charge level is redrawn from
                // that balance below purely so the block lights up as it fills.
                Fuel.bank(serverPlayer, 1);
                Chime.engineFed(serverPlayer);
                TaskTracker.progress(serverPlayer, TaskTracker.Task.FEED_ENGINE, 1);
            } else {
                Chime.refused(serverPlayer);
            }
            setEngineCharge((ServerLevel) level, pos, Fuel.banked(serverPlayer));
            InstanceRecord record = InstanceRegistry.byMember.get(serverPlayer.getUUID());
            if (record != null) {
                DungeonScreen.updateEngine((ServerLevel) level, record, serverPlayer);
                DungeonScreen.updateTracker((ServerLevel) level, record);
            }
            return InteractionResult.SUCCESS_SERVER;
        }

        // M19 19.3: the commit lever. Ahead of the selector-door branch: the
        // lever stands in the same row as the doors and answers to the same
        // gesture, but it commits rather than previews, so a lever pull must
        // never fall through to a door branch or to vanilla's own toggle.
        if (Instances.isCommitLever(serverPlayer, pos)) {
            return pullLever(serverPlayer, level);
        }

        // M2/M3: a door in the player's own lobby. Handled mod-side and ahead
        // of everything else -- vanilla's own door open/close must never run
        // for one of these, or a door that swings looks like it did something.
        // M19: the right-click now lights that door's copper bulb and puts its
        // offer on the door screen; the commit is the lever, not a dialog.
        Integer step = Instances.selectorDoorStep(serverPlayer, pos);
        if (step != null) {
            selectDoor(serverPlayer, step);
            return InteractionResult.SUCCESS_SERVER;
        }

        if (!PocketDungeonsConfig.ritualEnabled()) {
            return InteractionResult.PASS;
        }
        if (!level.getBlockState(pos).is(Blocks.LODESTONE)) {
            return InteractionResult.PASS;
        }
        // Sneaking means "act on what I am holding, not on this block" -- it is
        // how vanilla lets you place against a block you would otherwise use, and
        // it is the escape hatch for an operator who points keystoneItem at
        // something placeable. Same guard kamutotems' Station uses.
        if (player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        // M21: the wall lodestone is the navigation terminal. Any right-click
        // with any item or an empty hand opens the menu; the keystone is
        // checked on the menu's Start Dungeon button, never here, so a player
        // who just wants to leave or browse never needs their compass first.
        // The menu's context is the dimension: in the dungeon it is the
        // in-dungeon menu (Leave, Manage Room, Inspect Keystone), and it
        // opens only on the room's wall terminal: the only lodestone inside
        // a room cell. The terminal pad's lodestone at the dungeon's end is a
        // stand-on completion trigger, not a navigation surface, so a
        // right-click there falls through to the pad logic below.
        boolean inDungeon = serverPlayer.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL);
        if (inDungeon && Instances.roomOriginAt(pos) == null) {
            return InteractionResult.PASS;
        }
        DialogKit.show(serverPlayer, DialogScreens.lodestoneMenu(serverPlayer, inDungeon));
        Chime.menuOpens(serverPlayer);
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * M19 19.3/19.6: pulling the commit lever. With a door selected, starts
     * the run through {@code RunLifecycle.chooseOffer}; with none selected,
     * or a greater door the player cannot afford, the refusal stays on the
     * door screen rather than in a chat line, and now carries a cue as well:
     * a screen two blocks up is easy to miss when the click was a reflex. The
     * greater-tier gates are pre-checked in {@link #doorRefusal} so the screen
     * can name the reason; {@code chooseOffer} re-checks them anyway, and its
     * chat line is the fallback for anything this screen cannot know (a failed
     * stamp). Always consumes the click so vanilla's lever toggle never runs:
     * the lever stays visually up, ready for the next pull.
     */
    private static InteractionResult pullLever(ServerPlayer player, Level level) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record != null && RunSession.canChooseDoor(record) && record.stagingCellOrigin != null) {
            // M57: if this is a safe staging room, the lever returns the
            // party to the safe room. No door selection or preview needed.
            if (record.safeStaging) {
                if (RunLifecycle.returnToSafe(player)) {
                    Chime.runComplete(player);
                    return InteractionResult.SUCCESS_SERVER;
                }
                Chime.refused(player);
                return InteractionResult.SUCCESS_SERVER;
            }
            if (record.selectedStep == 0) {
                Chime.noSelection(player);
                DungeonScreen.updateDoor((ServerLevel) level, record,
                        DungeonScreen.refusalContent("Select a door first"));
                return InteractionResult.SUCCESS_SERVER;
            }
            // M56: if no preview is active, the player selected a door but
            // the preview failed or never ran. Refuse the commit.
            if (record.previewPlan == null) {
                Chime.refused(player);
                DungeonScreen.updateDoor((ServerLevel) level, record,
                        DungeonScreen.refusalContent("Preview the door first"));
                return InteractionResult.SUCCESS_SERVER;
            }
            String refusal = doorRefusal(player, record.selectedStep);
            if (refusal != null) {
                Chime.refused(player);
                DungeonScreen.updateDoor((ServerLevel) level, record,
                        DungeonScreen.refusalContent(refusal));
                return InteractionResult.SUCCESS_SERVER;
            }
            if (RunLifecycle.commitDoor(player)) {
                Chime.runStarts(player);
                return InteractionResult.SUCCESS_SERVER;
            }
            Chime.refused(player);
            DungeonScreen.updateDoor((ServerLevel) level, record,
                    DungeonScreen.refusalContent("The door refuses"));
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * M19: the physical replacement for the door-offer dialog. Right-clicking
     * a selector door lights that door's copper bulb in the wall above it,
     * darkens the previous selection's, and puts the chosen door's offer on
     * the door screen. No dialog popup: the walk between doors is the browse,
     * the lever pull is the commit, and the lever's own sign says so.
     */
    private static void selectDoor(ServerPlayer player, int step) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !RunSession.canChooseDoor(record) || !player.getUUID().equals(record.owner)
                || record.stagingCellOrigin == null) {
            return;
        }
        // M57: in a safe staging room, there are no dungeon doors to select.
        // The lever alone returns the party to the safe room.
        if (record.safeStaging) {
            return;
        }
        int previous = record.selectedStep;
        record.selectedStep = step;
        ServerLevel level = (ServerLevel) player.level();
        BlockPos o = record.stagingCellOrigin;
        DoorMask.Direction wall = record.roomDungeonDoor;
        if (previous >= 1 && previous <= 3 && previous != step) {
            RoomTemplateGenerator.setBulb(level, o, wall,
                    RoomTemplateGenerator.bulbAlongForStep(previous), false);
        }
        if (step != previous) {
            RoomTemplateGenerator.setBulb(level, o, wall,
                    RoomTemplateGenerator.bulbAlongForStep(step), true);
        }
        // M56: generate the physical preview for this door. The preview
        // stamps only the entrance cell and replaces the door with a window.
        // A failed preview leaves the door screen on the text preview.
        if (!RunLifecycle.previewDoor(player, step)) {
            // Restore the previous selection state if the preview failed.
            record.selectedStep = previous;
            if (previous >= 1 && previous <= 3 && previous != step) {
                RoomTemplateGenerator.setBulb(level, o, wall,
                        RoomTemplateGenerator.bulbAlongForStep(previous), true);
            }
            if (step != previous) {
                RoomTemplateGenerator.setBulb(level, o, wall,
                        RoomTemplateGenerator.bulbAlongForStep(step), false);
            }
            // Show why this door cannot be previewed. The doorRefusal
            // method knows the exact reason (level gate or fuel gate).
            // Without this, a player who right-clicks a greater door they
            // cannot afford gets no feedback at all: the bulb does not
            // light, the screen does not change, and they have no idea
            // why nothing happened.
            String refusal = doorRefusal(player, step);
            if (refusal != null) {
                Chime.doorLocked(player);
                DungeonScreen.updateDoor(level, record,
                        DungeonScreen.refusalContent(refusal));
            } else {
                DungeonScreen.updateDoor(level, record,
                        previous >= 1 && previous <= 3
                                ? DungeonScreen.previewContent(level, record.owner, previous)
                                : DungeonScreen.idleContent(level, record.owner));
            }
            return;
        }
        DungeonScreen.updateDoor(level, record, DungeonScreen.previewContent(level, record.owner, step));
        Chime.doorSelected(player, step);
        TaskTracker.progress(player, TaskTracker.Task.SELECT_DOOR, 1);
        if (doorRefusal(player, step) != null) {
            Chime.doorLocked(player);
        }
    }

    /**
     * Redraws the engine anchor's vanilla charge level from {@code banked}, so
     * an empty engine is dark and a stocked one glows. Cosmetic only: nothing
     * reads {@code CHARGE} back, and it saturates at the block's four charges
     * long before a serious balance does. It is the block telling you at a
     * glance that it has something in it, which the screen then quantifies.
     */
    private static void setEngineCharge(ServerLevel level, BlockPos pos, int banked) {
        BlockState anchor = level.getBlockState(pos);
        if (!anchor.is(Blocks.RESPAWN_ANCHOR)) {
            return;
        }
        int charges = Math.clamp(banked, 0, RespawnAnchorBlock.MAX_CHARGES);
        RoomBuilder.set(level, pos, anchor.setValue(RespawnAnchorBlock.CHARGE, charges));
    }

    /**
     * Why {@code player} cannot open selector door {@code step} yet, phrased
     * for the door screen, or null if they can. The one place the premium-door
     * gates are spelled out: {@link #pullLever} names the reason on the screen
     * and {@link #selectDoor} sounds it on the preview click, so the two can
     * never disagree about which doors are openable. A free door has no gate.
     * {@code RunLifecycle.chooseOffer} re-checks all of this regardless; this
     * is for the message and the cue, not for the rule.
     */
    private static String doorRefusal(ServerPlayer player, int step) {
        DungeonLog.Entry entry = DungeonLog.forServer(player.level().getServer())
                .get(player.getUUID());
        int offerLevel = Math.max(1, entry.keystoneLevel());
        Keystone.Offer[] offers = Keystone.offers(player.getUUID(), offerLevel,
                entry.currentTheme(), entry.depth());
        Keystone.Offer offer = offers[Math.min(step - 1, offers.length - 1)];
        if (offer.free()) {
            return null;
        }
        int minLevel = PocketDungeonsConfig.greaterDoorMinLevel();
        if (entry.keystoneLevel() < minLevel) {
            return "Door " + step + " needs level " + minLevel;
        }
        if (Fuel.banked(player) < PocketDungeonsConfig.fuelCostPerGreaterDoor()) {
            return "Feed the engine first";
        }
        return null;
    }

    /**
     * Whether {@code stack} modifies the world when used on a block face, in
     * a way the room's placement gate must answer for: a block item places a
     * block, a bucket places or collects a fluid. The same gate applies to
     * both, so a non-permitted player cannot pour water or lava in a room
     * they are only visiting, and nobody, owner included, can aim either at
     * the shell or the mod furniture.
     */
    private static boolean isPlacementSource(ItemStack stack) {
        net.minecraft.world.item.Item item = stack.getItem();
        return item instanceof net.minecraft.world.item.BlockItem
                || item instanceof net.minecraft.world.item.BucketItem;
    }

    /**
     * Dev tool: prints the block's coordinates to the clicking player. Shows
     * absolute coords, chunk coords, in-chunk offset, and, if the block is
     * inside a Pocket Dungeons cell, the cell origin and offset from it.
     */
    private static void reportCoords(ServerPlayer player, Level level, BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int inChunkX = x & 15;
        int inChunkZ = z & 15;
        net.minecraft.network.chat.Component base = net.minecraft.network.chat.Component.literal(
                "Block: " + x + ", " + y + ", " + z
                        + "  Chunk: " + chunkX + ", " + chunkZ
                        + "  In-chunk: " + inChunkX + ", " + y + ", " + inChunkZ)
                .withStyle(net.minecraft.ChatFormatting.AQUA);
        player.sendSystemMessage(base);
        BlockPos cellOrigin = Instances.roomOriginAt(pos);
        if (cellOrigin != null) {
            int offX = x - cellOrigin.getX();
            int offY = y - cellOrigin.getY();
            int offZ = z - cellOrigin.getZ();
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "Cell origin: " + cellOrigin.getX() + ", " + cellOrigin.getY() + ", " + cellOrigin.getZ()
                            + "  Offset: " + offX + ", " + offY + ", " + offZ)
                    .withStyle(net.minecraft.ChatFormatting.GOLD));
        }
    }
}
