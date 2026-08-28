package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
        // container check above -- deny the use-on-block interaction that a
        // placement begins from when the target face sits inside someone else's
        // room and the held item would place something there. M18 9.1: the
        // room's shell is immutable to everyone, the owner included, so a
        // placement landing in the shell is denied even for a permitted player.
        // Decorations are unaffected: a wall sign, torch or banner sits on the
        // face of a wall (target x=1..14, interior), and a carpet lands on the
        // floor's top face (target Y=1, interior), so their target positions
        // are never shell.
        BlockPos placementPos = hit.getBlockPos().relative(hit.getDirection());
        UUID placementRoomOwner = Instances.roomOwnerAt(placementPos);
        if (placementRoomOwner != null
                && player.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BlockItem) {
            BlockPos placementRoomOrigin = Instances.roomOriginAt(placementPos);
            // M19 19.7: furniture is protected from placement the same way the
            // shell is. The door screen blocks sit in the wall ring (already
            // shell), but the bulbs, the lever and the engine screen stand on
            // interior or adjacent-wall positions that only this check covers.
            if ((placementRoomOrigin != null
                    && (RoomProtection.isShell(placementPos, placementRoomOrigin)
                            || RoomProtection.isFurniture(placementPos, placementRoomOrigin,
                                    Instances.roomDungeonDoorAt(placementPos))))
                    || !RoomProtection.isPermitted(level, player, placementRoomOwner)) {
                return InteractionResult.FAIL;
            }
        }

        // M14: the reroll station. A positive test on the held item, same as
        // the keystone branch below -- anything that is not tagged tiered gear
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
                Fuel.spend(serverPlayer, 1);
                BlockState anchor = level.getBlockState(pos);
                int charges = Math.min(anchor.getValue(RespawnAnchorBlock.CHARGE) + 1,
                        RespawnAnchorBlock.MAX_CHARGES);
                RoomBuilder.set((ServerLevel) level, pos,
                        anchor.setValue(RespawnAnchorBlock.CHARGE, charges));
            }
            InstanceRecord record = InstanceRegistry.byMember.get(serverPlayer.getUUID());
            if (record != null) {
                DungeonScreen.updateEngine((ServerLevel) level, record, serverPlayer);
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
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * M19 19.3/19.6: pulling the commit lever. With a door selected, starts
     * the run through {@code RunLifecycle.chooseOffer}; with none selected,
     * or a greater door the player cannot afford, the refusal stays on the
     * door screen rather than in a chat line. The greater-tier gates are
     * pre-checked here so the screen can name the reason; {@code chooseOffer}
     * re-checks them anyway, and its chat line is the fallback for anything
     * this screen cannot know (a failed stamp). Always consumes the click so
     * vanilla's lever toggle never runs: the lever stays visually up, ready
     * for the next pull.
     */
    private static InteractionResult pullLever(ServerPlayer player, Level level) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record != null && record.awaitingDoorChoice && record.roomCellOrigin != null) {
            if (record.selectedStep == 0) {
                // M22: Chime.refused(player).
                DungeonScreen.updateDoor((ServerLevel) level, record,
                        DungeonScreen.refusalContent("Select a door first"));
                return InteractionResult.SUCCESS_SERVER;
            }
            DungeonLog.Entry entry = DungeonLog.forServer(player.level().getServer())
                    .get(player.getUUID());
            int offerLevel = Math.max(1, entry.keystoneLevel());
            Keystone.Offer[] offers = Keystone.offers(player.getUUID(), offerLevel,
                    entry.currentTheme(), entry.depth());
            Keystone.Offer offer = offers[Math.min(record.selectedStep - 1, offers.length - 1)];
            if (!offer.free()) {
                int minLevel = PocketDungeonsConfig.greaterDoorMinLevel();
                if (entry.keystoneLevel() < minLevel) {
                    DungeonScreen.updateDoor((ServerLevel) level, record,
                            DungeonScreen.refusalContent("Door " + record.selectedStep
                                    + " needs level " + minLevel));
                    return InteractionResult.SUCCESS_SERVER;
                }
                if (Fuel.count(player) < PocketDungeonsConfig.fuelCostPerGreaterDoor()) {
                    DungeonScreen.updateDoor((ServerLevel) level, record,
                            DungeonScreen.refusalContent("Not enough fuel"));
                    return InteractionResult.SUCCESS_SERVER;
                }
            }
            if (RunLifecycle.chooseOffer(player, record.selectedStep)) {
                // The run started; generateBehindLobby reset the selection,
                // darkened the bulbs and switched the screen to the run.
                return InteractionResult.SUCCESS_SERVER;
            }
            DungeonScreen.updateDoor((ServerLevel) level, record,
                    DungeonScreen.refusalContent("The door refuses"));
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * M19: the physical replacement for the door-offer dialog. Right-clicking
     * a selector door lights that door's copper bulb, darkens the previous
     * selection's bulb, lights the ready-to-commit bulb above the lever, and
     * puts the chosen door's offer on the door screen. No dialog popup: the
     * walk between doors is the browse, the lever pull is the commit.
     */
    private static void selectDoor(ServerPlayer player, int step) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !record.awaitingDoorChoice || !player.getUUID().equals(record.owner)
                || record.roomCellOrigin == null) {
            return;
        }
        int previous = record.selectedStep;
        record.selectedStep = step;
        ServerLevel level = (ServerLevel) player.level();
        BlockPos o = record.roomCellOrigin;
        DoorMask.Direction wall = record.roomDungeonDoor;
        if (previous >= 1 && previous <= 3 && previous != step) {
            RoomTemplateGenerator.setBulb(level, o, wall, previous, false);
        }
        if (step != previous) {
            RoomTemplateGenerator.setBulb(level, o, wall, step, true);
        }
        RoomTemplateGenerator.setBulb(level, o, wall, 10, true); // ready to commit
        DungeonScreen.updateDoor(level, record, DungeonScreen.previewContent(level, record.owner, step));
    }
}
