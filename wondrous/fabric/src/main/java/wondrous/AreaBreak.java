package wondrous;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import wondrous.api.WondrousTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Area-of-effect block breaking, in the shape of a Tinkers' Construct hammer.
 *
 * <p>TiC does this by overriding {@code onBlockStartBreak} on a real custom Item --
 * a Forge-patched hook that doesn't exist in vanilla, on a registry entry this mod
 * doesn't have. So the structure is different, but the balance rules are copied,
 * because those are the part with a decade of tuning behind them.
 *
 * <p>Each extra block is broken via {@link net.minecraft.server.level.ServerPlayerGameMode#destroyBlock},
 * not by removing the block and dropping loot by hand. That one call gives correct
 * tool checks, Fortune and Silk Touch from the held tool, ore experience, tool
 * durability, and block-entity handling -- all of it vanilla-accurate for free.
 * It also re-enters this handler, which is what {@link #breaking} guards.
 */
public final class AreaBreak {

    /**
     * Which face the player last started hitting.
     *
     * <p>PlayerBlockBreakEvents doesn't carry the face, and without it the plane
     * can't be oriented. Deriving it from the look vector instead is wrong exactly
     * when you're mining at an angle, which is most of the time underground.
     */
    private static final Map<UUID, Direction> lastFace = new ConcurrentHashMap<>();

    /** Players currently inside an area break. Prevents infinite recursion. */
    private static final Set<UUID> breaking = ConcurrentHashMap.newKeySet();

    private AreaBreak() {}

    /** Radius 1 = a 3x3 plane. Radius 2 would be 5x5. */
    public record AreaTool(int radius) {}

    public static void register(ItemRegistry registry) {

        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                lastFace.put(serverPlayer.getUUID(), direction);
            }
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            // Re-entered from our own destroyBlock calls. Let it break normally.
            if (breaking.contains(serverPlayer.getUUID())) {
                return;
            }
            // Sneak for a single block. Non-negotiable -- it's how you mine one
            // block without wrecking the wall behind it.
            if (serverPlayer.isShiftKeyDown()) {
                return;
            }

            ItemStack tool = serverPlayer.getMainHandItem();
            Optional<String> id = WondrousTag.read(tool);
            if (id.isEmpty()) {
                return;
            }

            Optional<Definitions.Def> def = registry.defOf(id.get());
            if (def.isEmpty() || def.get().area() == null) {
                return;
            }

            breakAround(serverPlayer, level, pos, state, tool, def.get().area());
        });
    }

    private static void breakAround(ServerPlayer player,
                                    Level level,
                                    BlockPos centre,
                                    BlockState centreState,
                                    ItemStack tool,
                                    AreaTool area) {

        Direction face = lastFace.getOrDefault(player.getUUID(), Direction.UP);
        float centreHardness = centreState.getDestroySpeed(level, centre);

        breaking.add(player.getUUID());
        try {
            for (BlockPos target : plane(centre, face, area.radius())) {
                if (!shouldBreak(level, tool, target, centreHardness)) {
                    continue;
                }
                // Full vanilla break: drops with this tool, XP, durability, stats.
                player.gameMode.destroyBlock(target);

                // Stop early if the tool broke mid-swing.
                if (player.getMainHandItem().isEmpty()) {
                    break;
                }
            }
        } finally {
            breaking.remove(player.getUUID());
        }
    }

    /**
     * The filters, lifted from how TiC hammers behave. Each one exists because
     * without it the tool stops being fun:
     *
     * <ul>
     *   <li>Effectiveness -- mine stone, get a 3x3 of stone. The dirt and gravel in
     *       the plane stay put. This is the single rule that stops an area tool
     *       feeling like a terrain eraser.
     *   <li>Hardness guard -- prevents mining stone from incidentally scooping the
     *       obsidian or ancient debris next to it.
     *   <li>Unbreakables and block entities -- nobody wants their chest in the
     *       blast radius.
     * </ul>
     */
    private static boolean shouldBreak(Level level,
                                       ItemStack tool,
                                       BlockPos pos,
                                       float centreHardness) {

        BlockState state = level.getBlockState(pos);

        if (state.isAir()) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false;
        }

        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) {
            return false;
        }
        // Small tolerance so a vein of near-equal blocks still clears.
        if (hardness > centreHardness + 0.5F) {
            return false;
        }

        return tool.isCorrectToolForDrops(state);
    }

    /**
     * The 3x3 (or larger) plane perpendicular to the hit face, minus the centre --
     * vanilla already broke that one.
     */
    private static List<BlockPos> plane(BlockPos centre, Direction face, int radius) {
        List<BlockPos> out = new ArrayList<>();
        Direction.Axis axis = face.getAxis();

        for (int a = -radius; a <= radius; a++) {
            for (int b = -radius; b <= radius; b++) {
                if (a == 0 && b == 0) {
                    continue;
                }
                out.add(switch (axis) {
                    case Y -> centre.offset(a, 0, b);
                    case X -> centre.offset(0, a, b);
                    case Z -> centre.offset(a, b, 0);
                });
            }
        }
        return out;
    }

    /** Called on disconnect so the face cache doesn't leak. */
    public static void forget(UUID uuid) {
        lastFace.remove(uuid);
        breaking.remove(uuid);
    }
}
