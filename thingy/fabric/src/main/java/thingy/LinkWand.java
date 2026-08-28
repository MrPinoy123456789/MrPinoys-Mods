package thingy;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import thingy.api.VirtualTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Put It There Wand: link a machine to a nearby chest and it will push one
 * output item to the chest on a short clock.
 */
public final class LinkWand {

    public static final String ID = "link_wand";
    private static final int TRANSFER_INTERVAL = 8;
    private static final int MAX_LINKS = 8;
    private static final int MAX_DISTANCE_SQ = 16 * 16;
    private static final int VISUAL_RANGE_SQ = 48 * 48;

    private static final int COLOR_SOURCE = 0x00AAFF;
    private static final int COLOR_DEST = 0xFFAA00;
    private static final int COLOR_PENDING = 0xAA00FF;

    private final Map<UUID, WondrousState.PosKey> pending = new HashMap<>();

    public void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            ItemStack held = player.getItemInHand(hand);
            if (!VirtualTag.is(held, "wondrous", ID)) {
                return InteractionResult.PASS;
            }

            ServerLevel serverLevel = (ServerLevel) level;
            WondrousState state = WondrousState.forLevel(serverLevel);
            BlockPos pos = hitResult.getBlockPos();
            WondrousState.PosKey here = new WondrousState.PosKey(serverLevel.dimension(), pos);
            UUID uuid = player.getUUID();

            WondrousState.Link existing = state.getLink(serverLevel, pos);
            if (existing != null && existing.owner().equals(uuid)) {
                state.removeLink(serverLevel, pos);
                pending.remove(uuid);
                Chime.unlink(serverPlayer);
                Chime.say(serverPlayer, "link gone");
                return InteractionResult.SUCCESS_SERVER;
            }

            WondrousState.PosKey selected = pending.get(uuid);
            if (selected == null) {
                if (!(level.getBlockEntity(pos) instanceof Container)) {
                    Chime.reject(serverPlayer);
                    Chime.say(serverPlayer, "not a machine");
                    return InteractionResult.SUCCESS_SERVER;
                }
                Visuals.outline(serverPlayer, pos, COLOR_SOURCE);
                Chime.select(serverPlayer);
                Chime.say(serverPlayer, "pick a chest");
                pending.put(uuid, here);
                return InteractionResult.SUCCESS_SERVER;
            }

            if (selected.equals(here)) {
                pending.remove(uuid);
                Chime.say(serverPlayer, "cancelled");
                return InteractionResult.SUCCESS_SERVER;
            }

            if (!(level.getBlockEntity(pos) instanceof Container)) {
                Chime.reject(serverPlayer);
                Chime.say(serverPlayer, "not a container");
                pending.remove(uuid);
                return InteractionResult.SUCCESS_SERVER;
            }

            if (state.linkCount(uuid) >= MAX_LINKS) {
                Chime.reject(serverPlayer);
                Chime.say(serverPlayer, "too many links");
                pending.remove(uuid);
                return InteractionResult.SUCCESS_SERVER;
            }

            if (!selected.dimension().equals(serverLevel.dimension())) {
                Chime.reject(serverPlayer);
                Chime.say(serverPlayer, "wrong dimension");
                pending.remove(uuid);
                return InteractionResult.SUCCESS_SERVER;
            }

            if (selected.pos().distSqr(pos) > MAX_DISTANCE_SQ) {
                Chime.reject(serverPlayer);
                Chime.say(serverPlayer, "too far");
                pending.remove(uuid);
                return InteractionResult.SUCCESS_SERVER;
            }

            state.setLink(serverLevel, selected.pos(), pos, uuid);
            pending.remove(uuid);
            Visuals.trail(serverPlayer, Vec3.atCenterOf(selected.pos()), Vec3.atCenterOf(pos));
            Chime.confirm(serverPlayer);
            Chime.say(serverPlayer, "linked");
            return InteractionResult.SUCCESS_SERVER;
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            ItemStack held = player.getItemInHand(hand);
            if (!VirtualTag.is(held, "wondrous", ID)) {
                return InteractionResult.PASS;
            }
            if (pending.remove(player.getUUID()) != null) {
                Chime.say(serverPlayer, "cancelled");
                return InteractionResult.SUCCESS_SERVER;
            }
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (!(level instanceof ServerLevel serverLevel)) {
                return;
            }
            WondrousState wondrous = WondrousState.forLevel(serverLevel);
            List<WondrousState.PosKey> toRemove = new ArrayList<>();
            for (Map.Entry<WondrousState.PosKey, WondrousState.Link> e : wondrous.allLinks().entrySet()) {
                if (!e.getKey().dimension().equals(serverLevel.dimension())) {
                    continue;
                }
                if (e.getKey().pos().equals(pos) || e.getValue().dest().equals(pos)) {
                    toRemove.add(e.getKey());
                    ServerPlayer owner = serverLevel.getServer().getPlayerList().getPlayer(e.getValue().owner());
                    if (owner != null) {
                        Chime.say(owner, "link broken");
                    }
                }
            }
            for (WondrousState.PosKey key : toRemove) {
                wondrous.removeLink(serverLevel, key.pos());
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(this::tick);
    }

    private void tick(MinecraftServer server) {
        if (server.getTickCount() % TRANSFER_INTERVAL != 0) {
            return;
        }

        WondrousState wondrous = WondrousState.forServer(server);
        for (Map.Entry<WondrousState.PosKey, WondrousState.Link> e : wondrous.allLinks().entrySet()) {
            ServerLevel level = server.getLevel(e.getKey().dimension());
            if (level == null) {
                continue;
            }
            BlockPos srcPos = e.getKey().pos();
            BlockPos dstPos = e.getValue().dest();
            if (!level.hasChunkAt(srcPos) || !level.hasChunkAt(dstPos)) {
                continue;
            }
            if (srcPos.distSqr(dstPos) > MAX_DISTANCE_SQ) {
                continue;
            }

            BlockEntity src = level.getBlockEntity(srcPos);
            BlockEntity dst = level.getBlockEntity(dstPos);
            if (!(src instanceof Container source) || !(dst instanceof Container dest)) {
                continue;
            }

            int[] sourceSlots = source instanceof WorldlyContainer wc
                    ? wc.getSlotsForFace(net.minecraft.core.Direction.DOWN)
                    : allSlots(source);

            boolean moved = false;
            for (int slot : sourceSlots) {
                if (moved) break;
                ItemStack stack = source.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                if (source instanceof WorldlyContainer wc && !wc.canTakeItemThroughFace(slot, stack, net.minecraft.core.Direction.DOWN)) {
                    continue;
                }

                int[] destSlots = dest instanceof WorldlyContainer wc
                        ? wc.getSlotsForFace(net.minecraft.core.Direction.UP)
                        : allSlots(dest);

                for (int dSlot : destSlots) {
                    // Both checks, the way a hopper does it: the slot filter applies
                    // to every container, the face filter only to worldly ones.
                    if (!dest.canPlaceItem(dSlot, stack)) {
                        continue;
                    }
                    if (dest instanceof WorldlyContainer wc
                            && !wc.canPlaceItemThroughFace(dSlot, stack, net.minecraft.core.Direction.UP)) {
                        continue;
                    }
                    ItemStack present = dest.getItem(dSlot);
                    if (present.isEmpty()) {
                        dest.setItem(dSlot, stack.split(1));
                        moved = true;
                    } else if (ItemStack.isSameItemSameComponents(present, stack)
                            && present.getCount() < Math.min(dest.getMaxStackSize(present), present.getMaxStackSize())) {
                        stack.shrink(1);
                        present.grow(1);
                        moved = true;
                    }
                    if (moved) {
                        source.setChanged();
                        dest.setChanged();
                        break;
                    }
                }
            }
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean active = VirtualTag.is(player.getMainHandItem(), "wondrous", ID)
                    || VirtualTag.is(player.getOffhandItem(), "wondrous", ID);
            if (!active) {
                continue;
            }
            ServerLevel level = (ServerLevel) player.level();

            // Only the closest of the player's links is drawn. Drawing all eight
            // costs a few thousand particle packets per pass and reads as noise
            // anyway; the one you are standing next to is the one you are asking
            // about.
            Map.Entry<WondrousState.PosKey, WondrousState.Link> closest = null;
            double closestDistance = Double.MAX_VALUE;
            for (Map.Entry<WondrousState.PosKey, WondrousState.Link> e : wondrous.allLinks().entrySet()) {
                if (!e.getKey().dimension().equals(level.dimension())) {
                    continue;
                }
                if (!e.getValue().owner().equals(player.getUUID())) {
                    continue;
                }
                double distance = Math.min(
                        distanceSq(player, e.getKey().pos()),
                        distanceSq(player, e.getValue().dest()));
                if (distance <= VISUAL_RANGE_SQ && distance < closestDistance) {
                    closest = e;
                    closestDistance = distance;
                }
            }
            if (closest != null) {
                BlockPos srcPos = closest.getKey().pos();
                BlockPos dstPos = closest.getValue().dest();
                Visuals.outline(player, srcPos, COLOR_SOURCE);
                Visuals.outline(player, dstPos, COLOR_DEST);
                Visuals.trail(player, Vec3.atCenterOf(srcPos), Vec3.atCenterOf(dstPos));
            }

            WondrousState.PosKey selected = pending.get(player.getUUID());
            if (selected != null && selected.dimension().equals(level.dimension())
                    && distanceSq(player, selected.pos()) <= VISUAL_RANGE_SQ) {
                Visuals.outline(player, selected.pos(), COLOR_PENDING);
            }
        }
    }

    private static double distanceSq(ServerPlayer player, BlockPos pos) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static int[] allSlots(Container container) {
        int[] slots = new int[container.getContainerSize()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = i;
        }
        return slots;
    }
}
