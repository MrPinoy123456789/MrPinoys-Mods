package charter.gametest;

import charter.engine.Actor;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Claim;
import charter.land.Core;
import charter.land.actions.LoseCore;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.land.LandItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * A private patch of the world for one land gametest. Gametests of a batch run at
 * the same time, and a keep claims 3 by 3 chunks and refuses a rival keep within 4,
 * so a test cannot build next to its own arena: each kit takes its own region far
 * from every other, force-loads it (BUILDING.md: chunks that matter must be loaded),
 * and on {@link #close} unforces it and removes every core and claim its factions made.
 *
 * <p>The layout, in chunks from the region's base (rx, rz): neutral ground at rx and
 * rx+1, a keep centred on (rx+3, rz) covering rx+2 to rx+4 and rz-1 to rz+1. So the
 * border between neutral and claimed ground is the plane x = {@link #border}, and
 * every test builds on a stone floor at {@link #y} - 1.
 */
final class LandKit implements AutoCloseable {

    private static int next;

    final GameTestHelper helper;
    final ServerLevel level;
    final MinecraftServer server;
    final CharterRuntime rt;
    final int rx;
    final int rz;
    /** The x of the border: west of it is neutral, east of it is the keep. */
    final int border;
    /** Block row of the arena, the middle of chunk row rz. */
    final int zc;
    final int y = 100;
    private final List<OrgId> orgs = new ArrayList<>();
    private int counter;

    LandKit(GameTestHelper helper) {
        this.helper = helper;
        this.level = helper.getLevel();
        this.server = level.getServer();
        this.rt = CharterServer.runtime();
        int slot = next++;
        this.rx = 40_000 + slot * 40;
        this.rz = 40_000 + (slot % 7) * 40;
        this.border = (rx + 2) * 16;
        this.zc = rz * 16 + 8;
        for (int cx = rx; cx <= rx + 4; cx++) {
            for (int cz = rz - 1; cz <= rz + 1; cz++) level.setChunkForced(cx, cz, true);
        }
        for (int x = border - 24; x < border + 56; x++) {
            for (int z = zc - 8; z <= zc + 8; z++) {
                level.setBlockAndUpdate(new BlockPos(x, y - 1, z), Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy < 6; dy++) level.setBlockAndUpdate(new BlockPos(x, y + dy, z), Blocks.AIR.defaultBlockState());
            }
        }
    }

    BlockPos at(int x, int z) {
        return new BlockPos(x, y, z);
    }

    /** A block in the arena, dx blocks east of the border and dz blocks south of the middle row. */
    BlockPos pos(int dx, int dz) {
        return new BlockPos(border + dx, y, zc + dz);
    }

    /** The keep's core chunk centre block. */
    BlockPos nexusPos() {
        return new BlockPos((rx + 3) * 16 + 8, y, rz * 16 + 8);
    }

    /** A player who is in nothing, standing in the arena. */
    ServerPlayer outsider() {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.teleportTo(border - 4, y, zc);
        return player;
    }

    /** Founds a funded lordship through {@code /charter admin faction spawn}. The founder is its Lord. */
    ServerPlayer faction(String label) {
        ServerPlayer founder = outsider();
        String name = "T" + Long.toString(System.nanoTime() & 0xFFFFFFFFL, 36) + label + (counter++);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "charter admin faction spawn \"" + name + "\" " + founder.getUUID());
        OrgId org = rt.state.orgNamed(name).map(o -> o.id).orElse(null);
        helper.assertTrue(org != null, "faction spawn did not found " + name);
        orgs.add(org);
        return founder;
    }

    OrgId orgOf(ServerPlayer player) {
        return rt.state.membership(new PlayerId(player.getUUID())).orElseThrow().org();
    }

    /** Puts the item from the player's inventory in their hand: the first stack of this kind of ours. */
    ItemStack hold(ServerPlayer player, String kind) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (kind.equals(LandItems.kindOf(stack))) {
                player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                return stack;
            }
        }
        throw new AssertionError("The player holds no " + kind + " item");
    }

    /** Right-clicks the top face of the block under {@code place}, the way a client would. */
    InteractionResult placeOn(ServerPlayer player, ItemStack stack, BlockPos place) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos support = place.below();
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false);
        return player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit);
    }

    /** Places the founder's nexus at the kit's keep position through the real item path. */
    void keep(ServerPlayer founder) {
        ItemStack nexus = hold(founder, LandItems.NEXUS);
        placeOn(founder, nexus, nexusPos());
        helper.assertTrue(rt.state.claimAt(charter.mc.land.Placement.chunkOf(level, nexusPos())).isPresent(),
                "The nexus did not raise a keep");
    }

    /** Removes every core and claim the kit's factions made, releases the chunks, and forgets the arena. */
    @Override
    public void close() {
        for (OrgId org : orgs) {
            for (Core core : List.copyOf(rt.state.coresOf(org))) {
                rt.engine.run(new Actor.Admin("gametest"), new LoseCore(core.id));
            }
            for (Claim claim : List.copyOf(rt.state.claimsOf(org))) rt.state.removeClaim(claim.id);
        }
        rt.claims.rebuild();
        for (int cx = rx; cx <= rx + 4; cx++) {
            for (int cz = rz - 1; cz <= rz + 1; cz++) level.setChunkForced(cx, cz, false);
        }
    }
}
