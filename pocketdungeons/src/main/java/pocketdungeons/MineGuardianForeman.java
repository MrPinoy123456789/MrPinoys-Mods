package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Act 2 Endless Mine's second guardian (roadmap T42), a miniature of the Copper Works' Master
 * Furnace fight. The Foreman is a vindicator standing over a floor of his own vents: copper grates the
 * guardian swaps into the cell's floor at spawn, swept in the same serpentine order
 * ({@link CopperClockRules#ventOrder}) and firing on the same pinned rhythm {@link CopperFinale} uses:
 * a hiss and rising smoke exactly {@link CopperClockRules#TELL_TICKS} before the pulse, then fire that
 * ignites whoever stands on the tile. The cycle shortens as hardness climbs but never under tell plus
 * burn ({@link MineGuardianRules#ventPeriodTicks}), so the two second tell can never come out short.
 *
 * <p>One water cauldron stands as the vat: a Foreman lured in takes the same drown damage and Weakness
 * plus Slowness the finale teaches. When the Foreman falls his vents quiet and the floor is given back;
 * per floor state lives in {@link #SETS}, keyed by the terminal cell origin, dropped and its floor
 * restored by {@link #clear(BlockPos)} when the cell ends.
 */
final class MineGuardianForeman implements MineGuardians.Guardian {

    /** His name, the finale's own. */
    static final String NAME = "The Foreman";

    /** How often the shared poll runs; the tell is pinned to this grid. */
    private static final int POLL_TICKS = 5;

    /** The grate the Foreman vents through; a method so a pure test never bootstraps {@link Blocks}. */
    private static Block vent() {
        return Blocks.COPPER_GRATE.waxed().unaffected();
    }

    /** One floor's vents, vat and pulse in flight, plus the floor blocks to give back. */
    private static final class Set {
        final ServerLevel level;
        final BlockPos terminal;
        final UUID foreman;
        final int period;
        final List<BlockPos> tiles;
        final List<BlockPos> vats;
        final Map<BlockPos, BlockState> restore = new HashMap<>();
        int next;
        long tellAt;
        long fireAt = -1;
        boolean told;
        int pulses;
        long nextVatCheckAt;
        boolean vatTaught;

        Set(ServerLevel level, BlockPos terminal, UUID foreman, int period, List<BlockPos> tiles,
            List<BlockPos> vats, long tellAt) {
            this.level = level;
            this.terminal = terminal;
            this.foreman = foreman;
            this.period = period;
            this.tiles = tiles;
            this.vats = vats;
            this.tellAt = tellAt;
        }
    }

    private static final Map<BlockPos, Set> SETS = SessionState.clearedAtStop(new HashMap<>());
    private static boolean registered;

    // ---- the spawn -----------------------------------------------------------------------------------

    @Override
    public void spawn(MineGuardians.Spawn spawn) {
        ServerLevel level = spawn.level();
        Mob foreman = EntityTypes.VINDICATOR.spawn(level, spawn.centre(), EntitySpawnReason.TRIGGERED);
        if (foreman == null) {
            PocketDungeonsMod.LOG.warn("The mine foreman failed to spawn at {}", spawn.centre().toShortString());
            return;
        }
        MineGuardians.tag(foreman);
        MineGuardians.scale(foreman, spawn.floorLevel(), spawn.hardness());
        foreman.setCustomName(Component.literal(NAME).withStyle(ChatFormatting.GOLD));
        foreman.setCustomNameVisible(true);

        java.util.Set<BlockPos> taken = new HashSet<>();
        taken.add(spawn.centre());
        List<BlockPos> tiles = new ArrayList<>();
        List<BlockPos> vats = new ArrayList<>();
        Map<BlockPos, BlockState> swapped = new HashMap<>();
        int vents = MineGuardianRules.ventsFor(spawn.hardness());
        for (int i = 0; i <= vents; i++) {
            BlockPos at = MineGuardians.freeSpotInCell(level, spawn.terminal(), taken, level.getRandom());
            if (at == null) {
                continue;
            }
            if (i == vents) {
                // The last spot is the vat, standing on the floor rather than in it.
                level.setBlock(at, Blocks.WATER_CAULDRON.defaultBlockState(), 3);
                vats.add(at);
                swapped.put(at, Blocks.AIR.defaultBlockState());
                continue;
            }
            BlockPos tile = at.below();
            swapped.put(tile, level.getBlockState(tile));
            level.setBlock(tile, vent().defaultBlockState(), 3);
            tiles.add(tile);
        }
        tiles = CopperClockRules.ventOrder(tiles);
        long now = level.getServer().getTickCount();
        int period = MineGuardianRules.ventPeriodTicks(PocketDungeonsConfig.copperVentPulseSeconds() * 20,
                spawn.hardness());
        Set set = new Set(level, spawn.terminal(), foreman.getUUID(), period, tiles, vats, now + period);
        set.restore.putAll(swapped);
        SETS.put(spawn.terminal(), set);
    }

    // ---- the poll: the pinned pulse and the vat --------------------------------------------------------

    /** Wires the shared tick. Called once from {@link MineGuardiansAct2#registerAll()}. */
    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % POLL_TICKS != 0 || SETS.isEmpty()) {
                return;
            }
            pollAll(server.getTickCount());
        });
    }

    /** Test seam: runs the poll once at {@code now}. */
    static void pollForTest(long now) {
        pollAll(now);
    }

    private static void pollAll(long now) {
        for (Map.Entry<BlockPos, Set> entry : new ArrayList<>(SETS.entrySet())) {
            Set set = entry.getValue();
            Entity entity = set.level.getEntity(set.foreman);
            if (!(entity instanceof Mob foreman) || !foreman.isAlive()) {
                // The Foreman is down: his vents quiet and the floor goes back to how it was.
                endSet(entry.getKey(), set);
                continue;
            }
            tick(set, foreman, now);
        }
    }

    private static void tick(Set set, Mob foreman, long now) {
        if (!set.tiles.isEmpty()) {
            BlockPos tile = set.tiles.get(set.next);
            if (set.told && now < set.fireAt) {
                CopperFinale.smoke(set.level, tile);
            }
            if (!set.told && now >= set.tellAt) {
                set.told = true;
                set.fireAt = now + CopperClockRules.TELL_TICKS;
                CopperFinale.hiss(set.level, tile);
                CopperFinale.smoke(set.level, tile);
            } else if (set.fireAt >= 0 && now >= set.fireAt) {
                CopperFinale.burn(set.level, tile);
                set.pulses++;
                set.next = (set.next + 1) % set.tiles.size();
                set.told = false;
                set.fireAt = -1;
                set.tellAt = now + set.period - CopperClockRules.TELL_TICKS;
            }
        }
        if (now >= set.nextVatCheckAt) {
            set.nextVatCheckAt = now + 20L;
            weakenForeman(set, foreman);
        }
    }

    /**
     * The vat: once a second a Foreman standing in or on the water cauldron takes
     * {@code copperForemanVatDamage} and carries Weakness and Slowness for
     * {@code copperForemanVatWeakSeconds}, the finale's lesson.
     */
    private static void weakenForeman(Set set, Mob foreman) {
        BlockPos feet = foreman.blockPosition();
        boolean inVat = false;
        for (BlockPos vat : set.vats) {
            if (vat.equals(feet) || vat.equals(feet.below())) {
                inVat = true;
                break;
            }
        }
        if (!inVat) {
            return;
        }
        ServerLevel level = set.level;
        float damage = (float) PocketDungeonsConfig.copperForemanVatDamage();
        if (damage > 0.0f) {
            foreman.hurtServer(level, level.damageSources().drown(), damage);
        }
        int seconds = PocketDungeonsConfig.copperForemanVatWeakSeconds();
        if (seconds > 0) {
            foreman.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, seconds * 20, 0, false, true));
            foreman.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, seconds * 20, 0, false, true));
        }
        level.sendParticles(ParticleTypes.SPLASH, feet.getX() + 0.5, feet.getY() + 0.8, feet.getZ() + 0.5,
                12, 0.3, 0.3, 0.3, 0.05);
        if (!set.vatTaught) {
            set.vatTaught = true;
            for (ServerPlayer member : membersInCell(set)) {
                member.sendOverlayMessage(Component.literal("The vat weakens him. Keep him in it.")
                        .withStyle(ChatFormatting.AQUA));
            }
        }
    }

    private static List<ServerPlayer> membersInCell(Set set) {
        List<ServerPlayer> out = new ArrayList<>();
        net.minecraft.world.phys.AABB bounds = CellGeometry.cellBounds(set.terminal).inflate(8.0);
        for (ServerPlayer player : set.level.players()) {
            if (bounds.contains(player.getX(), player.getY(), player.getZ())) {
                out.add(player);
            }
        }
        return out;
    }

    // ---- cleanup and seams ----------------------------------------------------------------------------

    /** Ends the floor's vents: the grates go back to the floor blocks they replaced. */
    private static void endSet(BlockPos cellOrigin, Set set) {
        SETS.remove(cellOrigin);
        restore(set);
    }

    private static void restore(Set set) {
        for (Map.Entry<BlockPos, BlockState> entry : set.restore.entrySet()) {
            BlockState now = set.level.getBlockState(entry.getKey());
            if (now.is(vent()) || now.is(Blocks.WATER_CAULDRON)) {
                set.level.setBlock(entry.getKey(), entry.getValue(), 3);
            }
        }
    }

    /**
     * The floor's cell is ending: drop the sweep and give the floor its blocks back. The Foreman himself,
     * if still up, is an ordinary live mob, the teardown's to sweep.
     */
    static void clear(BlockPos cellOrigin) {
        Set set = SETS.remove(cellOrigin);
        if (set != null) {
            restore(set);
        }
    }

    /** The vents standing in the cell, for tests. */
    static int ventsOf(BlockPos cellOrigin) {
        Set set = SETS.get(cellOrigin);
        return set == null ? 0 : set.tiles.size();
    }

    /** The vent pulses fired so far, for tests. */
    static int pulsesOf(BlockPos cellOrigin) {
        Set set = SETS.get(cellOrigin);
        return set == null ? 0 : set.pulses;
    }

    /** Whether a pulse's tell is running, for tests. */
    static boolean tellingOf(BlockPos cellOrigin) {
        Set set = SETS.get(cellOrigin);
        return set != null && set.told;
    }
}
