package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * The Master Furnace's finale (roadmap T32). The fight is the data wave (husks, skeletons,
 * the Foreman) plus the room itself: copper grates in the floor are vents that pulse on a
 * readable rhythm in a fixed serpentine sweep, a hiss and rising smoke two seconds before
 * each pulse, then fire for a second that ignites whoever stands on the tile. Water
 * cauldrons are the vats: a Foreman lured into one takes damage every second and carries
 * Weakness and Slowness while he is in it.
 *
 * <p>If {@link CopperClock} ran out, the wave arrives the moment the party reaches the floor
 * (no cleared spawners, no countdown) and the pulses run at {@code copperVentBlownPercent}
 * of the usual cycle.
 *
 * <p>The vents and vats are found by scanning the floor's bounds for the blocks, never by
 * room local numbers, because a stamped room rotates. The grates sit in the floor row clear
 * of the door lanes, the spawn points and the way south to the pad, so the exit always
 * stays open. Nothing is placed in the world: the fire is particles plus ignition and both
 * fade on their own, so {@link #onFloorEnded} has nothing to undo.
 */
final class CopperFinale implements FinaleScript {

    /** The grate the furnace vents through; the theme processors never produce one. */
    private static Block vent() {
        return Blocks.COPPER_GRATE.waxed().unaffected();
    }

    /** A water vat is a filled cauldron baked into the room. */
    private static Block vat() {
        return Blocks.WATER_CAULDRON;
    }

    /**
     * One run's vent floor: the sweep order, the vats and the pulse in flight. The finale ticks
     * once per watch interval (a second), so the schedule is pinned to that grid: the hiss
     * plays on one tick and the fire is pinned exactly {@link CopperClockRules#TELL_TICKS}
     * later, which is always another watch tick. The tell can never come out short.
     */
    static final class Vents {
        boolean scanned;
        List<BlockPos> tiles = List.of();
        List<BlockPos> vats = List.of();
        int next;
        /** The next watch tick the hiss and smoke start; -1 while a pulse is in flight. */
        long tellAt = -1;
        /** The pinned fire tick: the tell tick plus TELL_TICKS, or -1 with no pulse armed. */
        long fireAt = -1;
        boolean told;
        long nextVatCheckAt;
        boolean vatTaught;
    }

    /** A blown clock wakes the fight without cleared spawners. */
    @Override
    public boolean shouldTrigger(FinaleContext ctx) {
        return CopperClock.expired(ctx.record());
    }

    @Override
    public List<String> triggerLines(FinaleContext ctx) {
        if (CopperClock.expired(ctx.record())) {
            return List.of("The furnace blows.", "The Foreman comes.");
        }
        return null;
    }

    @Override
    public void onTriggered(FinaleContext ctx) {
        if (CopperClock.expired(ctx.record())) {
            ctx.skipCountdown();
        }
    }

    @Override
    public void onWaveSpawned(FinaleContext ctx) {
        Vents vents = ctx.scriptState(Vents.class, Vents::new);
        if (!vents.scanned) {
            scan(ctx, vents);
            // One quiet cycle before the first pulse, so the party reads the floor first.
            vents.tellAt = ctx.tickCount() + period(ctx);
        }
    }

    @Override
    public void onTick(FinaleContext ctx) {
        Vents vents = ctx.scriptState(Vents.class, Vents::new);
        if (!vents.scanned) {
            scan(ctx, vents);
            vents.tellAt = ctx.tickCount() + period(ctx);
        }
        long now = ctx.tickCount();
        if (!vents.tiles.isEmpty()) {
            BlockPos tile = vents.tiles.get(vents.next);
            if (vents.told && now < vents.fireAt) {
                smoke(ctx.level(), tile);
            }
            if (!vents.told && now >= vents.tellAt) {
                vents.told = true;
                vents.fireAt = now + CopperClockRules.TELL_TICKS;
                hiss(ctx.level(), tile);
                smoke(ctx.level(), tile);
            } else if (vents.fireAt >= 0 && now >= vents.fireAt) {
                burn(ctx.level(), tile);
                vents.next = (vents.next + 1) % vents.tiles.size();
                vents.told = false;
                vents.fireAt = -1;
                vents.tellAt = now + period(ctx) - CopperClockRules.TELL_TICKS;
            }
        }
        if (now >= vents.nextVatCheckAt) {
            vents.nextVatCheckAt = now + 20L;
            weakenForeman(ctx, vents);
        }
    }

    /** The vent and vat scan, once per finale: grates become the sweep, cauldrons the vats. */
    private static void scan(FinaleContext ctx, Vents vents) {
        vents.scanned = true;
        AABB bounds = ctx.record().layout.bounds();
        List<BlockPos> tiles = new ArrayList<>();
        List<BlockPos> vats = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ),
                BlockPos.containing(bounds.maxX - 1.0, bounds.maxY - 1.0, bounds.maxZ - 1.0))) {
            Block block = ctx.level().getBlockState(pos).getBlock();
            if (block == vent()) {
                tiles.add(pos.immutable());
            } else if (block == vat()) {
                vats.add(pos.immutable());
            }
        }
        vents.tiles = CopperClockRules.ventOrder(tiles);
        vents.vats = List.copyOf(vats);
    }

    /** The vent cycle: {@code copperVentPulseSeconds}, faster once the clock blew. */
    private static int period(FinaleContext ctx) {
        return CopperClockRules.ventPeriodTicks(PocketDungeonsConfig.copperVentPulseSeconds(),
                CopperClock.expired(ctx.record()), PocketDungeonsConfig.copperVentBlownPercent());
    }

    /** The vent's tell: the hiss that says this tile fires in two seconds. Package for the Deep Mine's Foreman. */
    static void hiss(ServerLevel level, BlockPos tile) {
        level.playSound(null, tile, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.9f, 0.6f);
    }

    /** Rising smoke off a vent during its tell. Package for the Deep Mine's Foreman. */
    static void smoke(ServerLevel level, BlockPos tile) {
        level.sendParticles(ParticleTypes.LARGE_SMOKE, tile.getX() + 0.5, tile.getY() + 1.0,
                tile.getZ() + 0.5, 4, 0.15, 0.3, 0.15, 0.02);
    }

    /** The pulse itself: a column of flame for a second, igniting whoever stands on the tile. Package for the Deep Mine's Foreman. */
    static void burn(ServerLevel level, BlockPos tile) {
        BlockPos feet = tile.above();
        level.sendParticles(ParticleTypes.FLAME, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5,
                14, 0.2, 0.7, 0.2, 0.01);
        int seconds = PocketDungeonsConfig.copperVentBurnSeconds();
        if (seconds <= 0) {
            return;
        }
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(feet))) {
            entity.igniteForSeconds(seconds);
        }
    }

    /**
     * The vats: once a second a Foreman standing in or on a water cauldron takes
     * {@code copperForemanVatDamage} and carries Weakness and Slowness for
     * {@code copperForemanVatWeakSeconds}. The party learns the trick once, in chat.
     */
    private static void weakenForeman(FinaleContext ctx, Vents vents) {
        Mob foreman = ctx.elite();
        if (foreman == null || !foreman.isAlive()) {
            return;
        }
        BlockPos feet = foreman.blockPosition();
        BlockPos below = feet.below();
        boolean inVat = false;
        for (BlockPos vat : vents.vats) {
            if (vat.equals(feet) || vat.equals(below)) {
                inVat = true;
                break;
            }
        }
        if (!inVat) {
            return;
        }
        ServerLevel level = ctx.level();
        float damage = (float) PocketDungeonsConfig.copperForemanVatDamage();
        if (damage > 0.0f) {
            foreman.hurtServer(level, level.damageSources().drown(), damage);
        }
        int seconds = PocketDungeonsConfig.copperForemanVatWeakSeconds();
        if (seconds > 0) {
            foreman.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, seconds * 20, 0, false, true));
            foreman.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, seconds * 20, 0, false, true));
        }
        level.sendParticles(ParticleTypes.SPLASH, feet.getX() + 0.5, feet.getY() + 0.8,
                feet.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.05);
        if (!vents.vatTaught) {
            vents.vatTaught = true;
            ctx.tell("The water bites him.", ChatFormatting.AQUA);
        }
    }
}
