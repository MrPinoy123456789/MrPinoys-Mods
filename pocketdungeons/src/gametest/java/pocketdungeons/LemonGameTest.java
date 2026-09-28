package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Lemon's body in a real server level (the gametest world has no dungeon
 * dimension, but Lemon works in any level): it appears when it speaks and
 * vanishes when hushed, cannot be hurt, picks nothing up, stays out of a
 * room capture, and goes when its player logs out.
 *
 * <p>Not covered here: the privacy of the body and bubble to other players
 * (a mock player has no client to be paired with), the chat event routing
 * itself (a mock player sends no chat packets; the prefix rule is
 * {@code LemonSpeechTest}), the glow colour, particles and sound, and the real
 * {@code DISCONNECT} event (the logout test calls the handler's body,
 * {@link Lemon#forget}).
 */
public final class LemonGameTest {

    @GameTest(maxTicks = 20)
    public void appearsWhenSpeakingAndVanishesWhenHushed(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        try {
            if (Lemon.say(player, "Hello there. This is a test line.") != Lemon.Delivery.SHOWN) {
                helper.fail("an unprompted line outside a fight should show");
                return;
            }
            List<LemonBody> bodies = bodiesNear(helper.getLevel(), player);
            if (bodies.size() != 1 || !bodies.get(0).owner.equals(player.getUUID())) {
                helper.fail("expected exactly one Lemon for the player, found " + bodies.size());
                return;
            }
            Lemon.say(player, "A second line.");
            if (bodiesNear(helper.getLevel(), player).size() != 1) {
                helper.fail("a second line must not duplicate Lemon");
                return;
            }
            if (!Lemon.isPart(bodies.get(0)) || !bodies.get(0).entityTags().contains(Lemon.TAG)) {
                helper.fail("Lemon should carry its tag");
                return;
            }
            Lemon.hush(player);
            if (!bodies.get(0).isRemoved() || !bodiesNear(helper.getLevel(), player).isEmpty()) {
                helper.fail("hushing should remove Lemon");
                return;
            }
            helper.succeed();
        } finally {
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
        }
    }

    @GameTest(maxTicks = 20)
    public void cannotBeDamaged(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        try {
            Lemon.say(player, "Try it.");
            LemonBody body = single(helper, player);
            if (body == null) {
                return;
            }
            ServerLevel level = helper.getLevel();
            float health = body.getHealth();
            boolean hurt = body.hurtServer(level, level.damageSources().playerAttack(player), 100.0f)
                    | body.hurtServer(level, level.damageSources().fellOutOfWorld(), 1000.0f)
                    | body.hurtServer(level, level.damageSources().genericKill(), Float.MAX_VALUE);
            if (hurt || body.getHealth() != health || body.isRemoved() || !body.isAlive()) {
                helper.fail("Lemon took damage");
                return;
            }
            if (body.canBeSeenAsEnemy() || body.isPushable() || body.canBeLeashed()) {
                helper.fail("Lemon should be ignored by mobs, unpushable and unleashable");
                return;
            }
            helper.succeed();
        } finally {
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
        }
    }

    /**
     * An allay seeks items matching what it holds, so the body is handed a
     * stone first: vanilla would collect the stone on the floor, Lemon must
     * not.
     */
    @GameTest(maxTicks = 80)
    public void picksNothingUp(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        Lemon.say(player, "I will not touch that.");
        LemonBody body = single(helper, player);
        if (body == null) {
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
            return;
        }
        body.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
        ItemEntity item = new ItemEntity(helper.getLevel(), body.getX(), body.getY(), body.getZ(),
                new ItemStack(Items.STONE, 5));
        item.setPickUpDelay(0);
        helper.getLevel().addFreshEntity(item);
        helper.runAfterDelay(40, () -> {
            try {
                if (!item.isAlive() || item.getItem().getCount() != 5 || !body.getInventory().isEmpty()) {
                    helper.fail("Lemon picked something up");
                    return;
                }
                if (body.canPickUpLoot() || body.wantsToPickUp(helper.getLevel(), item.getItem())) {
                    helper.fail("Lemon should never want loot");
                    return;
                }
                item.discard();
                helper.succeed();
            } finally {
                Lemon.forget(helper.getLevel().getServer(), player.getUUID());
            }
        });
    }

    /**
     * A real {@link RoomStore#capture} of a cell Lemon is hovering in: the
     * blob carries no entity, and Lemon is neither discarded nor moved.
     */
    @GameTest(maxTicks = 20)
    public void staysOutOfARoomCapture(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos near = helper.absolutePos(BlockPos.ZERO);
        // Chunk aligned and well above the test structure, as SeamGameTest does.
        BlockPos cell = new BlockPos((near.getX() >> 4) << 4, near.getY() + 64, (near.getZ() >> 4) << 4);
        UUID owner = UUID.randomUUID();
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                level.setBlock(cell.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        ServerPlayer player = standingPlayer(helper, cell.offset(8, 1, 8));
        try {
            Lemon.say(player, "Do not mind me.");
            LemonBody body = single(helper, player);
            if (body == null) {
                return;
            }
            AABB volume = new AABB(cell.getX(), cell.getY(), cell.getZ(), cell.getX() + RoomGeometry.CELL,
                    cell.getY() + RoomGeometry.CEILING_Y + 1, cell.getZ() + RoomGeometry.CELL);
            if (!volume.contains(body.position())) {
                helper.fail("the test needs Lemon inside the captured cell; it is at " + body.position());
                return;
            }
            if (RoomStore.isRoomEntity(body)) {
                helper.fail("Lemon must not be on the room allowlist");
                return;
            }
            Vec3 before = body.position();
            if (!RoomStore.capture(level, server, owner, cell, 0)) {
                helper.fail("the capture itself failed");
                return;
            }
            CompoundTag blob = RoomStore.load(server, owner);
            if (blob == null) {
                helper.fail("no blob was saved");
                return;
            }
            if (!blob.getListOrEmpty("entities").isEmpty()) {
                helper.fail("the blob captured " + blob.getListOrEmpty("entities").size() + " entity(ies)");
                return;
            }
            if (body.isRemoved() || !body.position().equals(before)) {
                helper.fail("the capture discarded or moved Lemon");
                return;
            }
            helper.succeed();
        } finally {
            Lemon.forget(server, player.getUUID());
            for (int x = 0; x < RoomGeometry.CELL; x++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    level.setBlock(cell.offset(x, 0, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    @GameTest(maxTicks = 20)
    public void goesOnLogout(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        Lemon.say(player, "Bye soon.");
        LemonBody body = single(helper, player);
        if (body == null) {
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
            return;
        }
        Lemon.forget(helper.getLevel().getServer(), player.getUUID());
        if (!body.isRemoved() || Lemon.owns(body)) {
            helper.fail("Lemon should go with its player");
            return;
        }
        helper.succeed();
    }

    private static LemonBody single(GameTestHelper helper, ServerPlayer player) {
        List<LemonBody> bodies = bodiesNear(helper.getLevel(), player);
        if (bodies.size() != 1) {
            helper.fail("expected one Lemon, found " + bodies.size());
            return null;
        }
        return bodies.get(0);
    }

    private static List<LemonBody> bodiesNear(ServerLevel level, ServerPlayer player) {
        return level.getEntitiesOfClass(LemonBody.class, player.getBoundingBox().inflate(8),
                body -> !body.isRemoved() && body.owner.equals(player.getUUID()));
    }

    private static ServerPlayer standingPlayer(GameTestHelper helper, BlockPos where) {
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(helper.getLevel(), where.getX() + 0.5, where.getY(), where.getZ() + 0.5,
                Set.of(), 0.0F, 0.0F, false);
        return player;
    }
}
