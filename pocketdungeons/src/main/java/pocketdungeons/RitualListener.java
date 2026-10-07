package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
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
            RerollStation.warmUp();
            RunStorage.warmUp();
            SalvageStation.warmUp();
            TrimListener.warmUp();
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

        // PD-87: keys from before the fix carry the chest tables' bag tag,
        // which the vault's component match refuses. Strip it on the way in so
        // the key already in the player's pack still opens the vault; the
        // vault itself then runs as vanilla.
        if (level.getBlockState(pos).is(Blocks.VAULT)) {
            TrialContent.bareKey(serverPlayer.getMainHandItem());
            if (level instanceof ServerLevel serverLevel
                    && level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                PartyRewards.vaultClicked(serverPlayer, serverLevel, pos);
            }
        }

        // Dev tool: right-click any block with a spyglass to get its
        // coordinates in chat, both absolute and relative to its chunk and
        // to the nearest Pocket Dungeons cell origin. PD-71: operators only
        // and not while crouching, so a player's kit spyglass still opens
        // chests and pulls levers.
        if (serverPlayer.getMainHandItem().is(Items.SPYGLASS) && !serverPlayer.isShiftKeyDown()
                && Commands.LEVEL_GAMEMASTERS.check(serverPlayer.permissions())) {
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

        // PD-124: the Explosive affix uses fake TNT mines that trigger on
        // contact. Flint and steel or fire charges cannot prime them; the mine
        // itself handles ignition.
        if (level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)
                && level.getBlockState(pos).is(Blocks.TNT)
                && Instances.dungeonCellOriginAt(pos) != null) {
            ItemStack held = serverPlayer.getItemInHand(hand);
            if (held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE)) {
                serverPlayer.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(
                                "This TNT is part of the floor. Step on it and it goes off.")
                        .withStyle(ChatFormatting.YELLOW)));
                return InteractionResult.FAIL;
            }
        }

        // M48: the bag chest. A member of a non-visit instance who has not yet
        // chosen a bag right-clicks the ender chest at the safe room's centre
        // to open the bag picker, independently of every other member. This
        // claims the click before the ender-chest denial below, which would
        // otherwise block it (the bag chest is a waxed oxidized copper chest,
        // deliberately, to read as distinct from loot chests). Position-based, the same way
        // the selector doors, lever and engine are identified.
        // PD-131: every click on the bag chest is answered. A member who
        // already carries a bag used to fall through to the container denial
        // and get nothing at all, which read as "I can see it but cannot use it".
        // 2026-10-04: recognised by its block wherever it stands, so it can be
        // moved, and placeable only inside the safe room, so it is never lost
        // to a floor or to the Doors being rebuilt.
        InstanceRecord bagRecord = InstanceRegistry.byMember.get(serverPlayer.getUUID());
        if (bagRecord != null && !bagRecord.adminBuild
                && Instances.isBagChestItem(player.getItemInHand(hand))
                && !Instances.inSafeRoom(bagRecord, hit.getBlockPos().relative(hit.getDirection()))
                && !Instances.isBagChest(level.getBlockState(pos))) {
            serverPlayer.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(
                    "The bag chest only stands in your safe room.").withStyle(ChatFormatting.YELLOW)));
            return InteractionResult.FAIL;
        }
        if (bagRecord != null && !bagRecord.visitInstance
                && Instances.isBagChest(level.getBlockState(pos))) {
            Bags.clearGatedChoice(DungeonLog.forServer(level.getServer()), serverPlayer.getUUID());
            String carried = DungeonLog.forServer(level.getServer()).bagOf(serverPlayer.getUUID());
            if (carried.isEmpty()) {
                DialogKit.show(serverPlayer, DialogScreens.bagPicker(serverPlayer));
            } else if (KitChest.holdsLeftovers(serverPlayer)) {
                KitChest.open(serverPlayer);
            } else {
                serverPlayer.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(
                        "Your kit was handed over when you chose your bag. Nothing refills it.")
                        .withStyle(ChatFormatting.YELLOW)));
            }
            return InteractionResult.SUCCESS_SERVER;
        }

        // Dungeon Storage (playtest 2026-10-02-1, J6): the ender chest, gated
        // behind the scenes, opens the player's own storage anywhere in the
        // dungeon, run or no run, instead of their real ender chest. Ahead
        // of the denial below and the room permission mask, since every member
        // has their own storage in any room.
        if (RunStorage.onUse(serverPlayer, level.getBlockState(pos),
                level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL))) {
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
        // PD-132: a click that opens a station is a use, not a placement, so
        // a guest holding a block can still craft, smelt or salvage; vanilla
        // never places the held block when the clicked block consumes the use.
        if (placementRoomOwner != null && isPlacementSource(player.getItemInHand(hand))
                && !opensStation(serverPlayer, level, pos)) {
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

        // An Ordeal's lever: pulled once, it ends the room's danger and stays
        // down. Claimed ahead of vanilla so it can never be pushed back up.
        if (Ordeals.onUse(serverPlayer, pos)) {
            return InteractionResult.SUCCESS_SERVER;
        }

        // M14: the reroll station. A positive test on the held item, same as
        // the keystone branch below: anything that is not tagged tiered gear
        // falls straight through to whatever this block would otherwise do (by
        // default a plain vanilla enchanting table, since J5).
        if (RerollStation.onUse(serverPlayer, level.getBlockState(pos), player.getItemInHand(hand))) {
            return InteractionResult.SUCCESS_SERVER;
        }


        // The dead-end fountain (playtest 2026-10-02-1): a full water cauldron on
        // a chiseled pedestal. Any other cauldron falls through to vanilla.
        if (level instanceof ServerLevel serverLevel && Fountain.onUse(serverPlayer, serverLevel, pos)) {
            return InteractionResult.SUCCESS_SERVER;
        }

        // The salvage bench (playtest 2026-09-29, A3). PD-96: in the dungeon,
        // any use that is not a sneak opens it, so a player finds it; a sneak
        // is the vanilla grindstone, and the bench's Disenchant button hands
        // over to it. Outside the dungeon the grindstone is always vanilla.
        if (SalvageStation.onUse(serverPlayer, level.getBlockState(pos), hand,
                net.minecraft.world.inventory.ContainerLevelAccess.create(level, pos))) {
            return InteractionResult.SUCCESS_SERVER;
        }

        // The floor history board in a staging room opens the dungeon map (D6).
        if (Instances.isHistoryBoard(serverPlayer, pos)) {
            InstanceRecord mapRecord = InstanceRegistry.byMember.get(serverPlayer.getUUID());
            if (mapRecord != null && serverPlayer.level().getServer() != null) {
                DialogKit.show(serverPlayer, DialogScreens.dungeonMap(serverPlayer.level().getServer(), mapRecord));
                Chime.menuOpens(serverPlayer);
            }
            return InteractionResult.SUCCESS_SERVER;
        }

        // The HOME lever in a cleared floor's staging room: asks first, and
        // the confirm banks the interval and takes the party home (playtest
        // 2026-09-29, A5: a misclick ended a run). Claimed for any member so
        // vanilla never flips it; any member may take the party home (D15) unless
        // the leader's decide whitelist is on, in which case goHome tells them so
        // straight away rather than opening the dialog.
        if (Instances.isHomeLever(serverPlayer, pos)) {
            InstanceRecord homeRecord = InstanceRegistry.byMember.get(serverPlayer.getUUID());
            if (homeRecord != null && serverPlayer.level().getServer() != null
                    && PartyDecide.may(serverPlayer.level().getServer(), homeRecord, serverPlayer)) {
                DialogKit.show(serverPlayer, DialogScreens.goHomeConfirm(
                        serverPlayer.level().getServer(), homeRecord));
                Chime.menuOpens(serverPlayer);
            } else if (!RunLifecycle.goHome(serverPlayer)) {
                Chime.refused(serverPlayer);
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
        openMenu(serverPlayer);
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * Opens the navigation menu for {@code player}: the in-dungeon menu inside
     * the dungeon dimension, the lobby menu anywhere else. The room's wall
     * lodestone opens it, and so does reaching for Lemon's journal. Every
     * button it offers has a command of its own ({@code /dungeon exit},
     * {@code /dungeon quit}), so opening it away from the room grants nothing new.
     */
    static void openMenu(ServerPlayer player) {
        boolean inDungeon = player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL);
        DialogKit.show(player, DialogScreens.lodestoneMenu(player, inDungeon));
        Chime.menuOpens(player);
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
            // D15: any member may pull unless the leader limited it to a list.
            if (!PartyDecide.may(player.level().getServer(), record, player)) {
                Chime.refused(player);
                DungeonScreen.updateDoor((ServerLevel) level,
                        record, DungeonScreen.refusalContent("The party leader decides the doors here"));
                return InteractionResult.SUCCESS_SERVER;
            }
            if (record.floor.selectedStep == 0) {
                Chime.noSelection(player);
                DungeonScreen.updateDoor((ServerLevel) level, record,
                        DungeonScreen.refusalContent("Select a door first"));
                return InteractionResult.SUCCESS_SERVER;
            }
            // M56: if no preview is active, the player selected a door but
            // the preview failed or never ran. Refuse the commit.
            if (record.floor.previewPlan == null) {
                Chime.refused(player);
                DungeonScreen.updateDoor((ServerLevel) level, record,
                        DungeonScreen.refusalContent("Preview the door first"));
                return InteractionResult.SUCCESS_SERVER;
            }
            String refusal = doorRefusal(player, record.floor.selectedStep);
            if (refusal != null) {
                Chime.refused(player);
                DungeonScreen.updateDoor((ServerLevel) level, record,
                        DungeonScreen.refusalContent(refusal));
                return InteractionResult.SUCCESS_SERVER;
            }
            if (RunLifecycle.commitDoor(player)) {
                Chime.runStarts(player);
                FloorStartTitle.show(player.level().getServer(), record);
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
        if (record == null || !RunSession.canChooseDoor(record) || record.stagingCellOrigin == null) {
            return;
        }
        // D15: any member may choose, unless the leader limited it to a list.
        if (!PartyDecide.mayOrRefuse(player.level().getServer(), record, player)) {
            Chime.refused(player);
            return;
        }
        int previous = record.floor.selectedStep;
        record.floor.selectedStep = step;
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
            record.floor.selectedStep = previous;
            if (previous >= 1 && previous <= 3 && previous != step) {
                RoomTemplateGenerator.setBulb(level, o, wall,
                        RoomTemplateGenerator.bulbAlongForStep(previous), true);
            }
            if (step != previous) {
                RoomTemplateGenerator.setBulb(level, o, wall,
                        RoomTemplateGenerator.bulbAlongForStep(step), false);
            }
            // Show why this door cannot be previewed. The doorRefusal
            // method knows the exact reason (a scrap cost or a finished run).
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
                                ? DungeonScreen.previewContent(level, record, previous)
                                : DungeonScreen.idleContent(level, record.owner));
            }
            return;
        }
        DungeonScreen.updateDoor(level, record, DungeonScreen.previewContent(level, record, step));
        Chime.doorSelected(player, step);
        sideBranchBalance(player, record, step);
        if (doorRefusal(player, step) != null) {
            Chime.doorLocked(player);
        }
    }

    /**
     * Tells the player who picked a side branch door what it costs against their own pack
     * (the wall is shared, so it cannot show a balance; dungeon structure W5).
     */
    private static void sideBranchBalance(ServerPlayer player, InstanceRecord record, int step) {
        Keystone.Offer[] offers = Keystone.offers(player.level().getServer(), record, record.owner,
                Math.max(1, DungeonLog.forServer(player.level().getServer()).get(record.owner).keystoneLevel()));
        if (offers.length == 0) {
            return;
        }
        int cost = offers[Math.min(step - 1, offers.length - 1)].cost();
        if (cost > 0) {
            int carried = DungeonLog.forServer(player.level().getServer())
                    .get(player.getUUID()).scrap();
            player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(
                    SideBranchPay.balanceLine(cost, carried))
                    .withStyle(SideBranchPay.affordable(cost, carried) ? ChatFormatting.GRAY : ChatFormatting.RED)));
        }
    }

    /**
     * Why {@code player} cannot open selector door {@code step} yet, phrased
     * for the door screen, or null if they can. The one place a door's price is
     * spelled out: {@link #pullLever} names the reason on the screen and
     * {@link #selectDoor} sounds it on the preview click, so the two can never
     * disagree about which doors are openable. Only a side branch (an edge that
     * costs scrap) or a finished dungeon refuses; there are no level gates.
     * {@code RunLifecycle.commitDoor} re-checks all of this regardless; this
     * is for the message and the cue, not for the rule.
     */
    private static String doorRefusal(ServerPlayer player, int step) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null) {
            return null;
        }
        DungeonLog.Entry entry = DungeonLog.forServer(player.level().getServer())
                .get(record.owner);
        int offerLevel = Math.max(1, entry.keystoneLevel());
        Keystone.Offer[] offers = Keystone.offers(player.level().getServer(), record, record.owner, offerLevel);
        if (record.interval.mineSealedAct > 0) {
            return EndlessMineRules.sealedMessage(record.interval.mineSealedAct);
        }
        if (record.interval.finished || offers.length == 0) {
            return "The dungeon is cleared. Pull the HOME lever.";
        }
        Keystone.Offer offer = offers[Math.min(step - 1, offers.length - 1)];
        int cost = offer.cost();
        if (cost > 0) {
            // The viewing player's own scrap pool, never the owner's.
            int carried = DungeonLog.forServer(player.level().getServer())
                    .get(player.getUUID()).scrap();
            if (!SideBranchPay.affordable(cost, carried)) {
                return SideBranchPay.screenRefusal(cost, carried);
            }
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
    /**
     * PD-132 (playtest 2026-10-03-2): whether a right-click on {@code pos}
     * opens something rather than placing the held item: any block with a
     * menu (crafting table, furnace, grindstone, smithing table, chests) or
     * one of the mod's stations, unless the player is sneaking, which is how
     * vanilla places against an interactive block. Before this, a party
     * member holding blocks was refused every station in the leader's staging
     * room, and with an empty hand was let in, which read as intermittent.
     */
    static boolean opensStation(ServerPlayer player, Level level, BlockPos pos) {
        if (player.isSecondaryUseActive()) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.getMenuProvider(level, pos) != null
                || RerollStation.matchesStation(state)
                || SalvageStation.matchesStation(state);
    }

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
