package excellentenchants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Dragon Heart and Flame Walker: effects that follow what a player wears. */
public final class Armor {

    /** A magma block waiting to turn back into lava. */
    record Decay(ResourceKey<Level> dimension, BlockPos pos, int ticksLeft) {
    }

    private static final List<Decay> DECAYING = new ArrayList<>();
    private static final Map<UUID, BlockPos> LAST_POS = new HashMap<>();

    private Armor() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!(player.level() instanceof ServerLevel level)) continue;
                if (player.tickCount % 20 == 0) dragonHeart(level, player);
                flameWalker(level, player);
            }
            tickDecay(server);
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!source.is(DamageTypes.HOT_FLOOR)) return true;
            if (!(entity.level() instanceof ServerLevel level)) return true;
            return Enchants.level(level, entity.getItemBySlot(EquipmentSlot.FEET), Enchants.FLAME_WALKER) == 0;
        });
    }

    /** Health Boost, refreshed every second for as long as the chestplate is worn. */
    public static void dragonHeart(ServerLevel level, ServerPlayer player) {
        int lvl = Enchants.level(level, player.getItemBySlot(EquipmentSlot.CHEST), Enchants.DRAGON_HEART);
        if (lvl <= 0) return;
        player.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST, 10 * 20, Numbers.amplifier(lvl), true, false));
    }

    public static void flameWalker(ServerLevel level, ServerPlayer player) {
        BlockPos here = player.blockPosition();
        BlockPos before = LAST_POS.put(player.getUUID(), here);
        if (here.equals(before)) return;
        int lvl = Enchants.level(level, player.getItemBySlot(EquipmentSlot.FEET), Enchants.FLAME_WALKER);
        if (lvl <= 0) return;
        BlockPos below = here.below();
        boolean nearLava = false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(below.relative(d)).is(Blocks.LAVA)) nearLava = true;
        }
        if (nearLava) convert(level, here, lvl);
    }

    /** Turns source lava in a disk under {@code feet} into magma, each with its own random life. */
    public static int convert(ServerLevel level, BlockPos feet, int lvl) {
        int radius = Numbers.flameWalkerRadius(lvl);
        int decay = Numbers.flameWalkerDecaySeconds(lvl);
        int changed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                BlockPos pos = feet.offset(dx, -1, dz);
                BlockState state = level.getBlockState(pos);
                if (!state.is(Blocks.LAVA) || state.getValue(LiquidBlock.LEVEL) != 0) continue;
                if (!level.getBlockState(pos.above()).isAir()) continue;
                level.setBlockAndUpdate(pos, Blocks.MAGMA_BLOCK.defaultBlockState());
                int seconds = (int) (level.getRandom().nextDouble() * decay) + 1;
                DECAYING.add(new Decay(level.dimension(), pos, seconds * 20));
                changed++;
            }
        }
        return changed;
    }

    public static void tickDecay(MinecraftServer server) {
        List<Decay> next = new ArrayList<>();
        for (Decay d : DECAYING) {
            if (d.ticksLeft() > 1) {
                next.add(new Decay(d.dimension(), d.pos(), d.ticksLeft() - 1));
                continue;
            }
            ServerLevel level = server.getLevel(d.dimension());
            if (level != null && level.getBlockState(d.pos()).is(Blocks.MAGMA_BLOCK)) {
                level.setBlockAndUpdate(d.pos(), Blocks.LAVA.defaultBlockState());
            }
        }
        DECAYING.clear();
        DECAYING.addAll(next);
    }

    /** Pending magma blocks, for the tests. */
    public static int pending() {
        return DECAYING.size();
    }

    public static void clearPending() {
        DECAYING.clear();
    }
}
