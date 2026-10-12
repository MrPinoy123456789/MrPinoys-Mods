package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;

/**
 * The Endless Mine's guardian hook (decision 2026-10-11, task T40). A guardian waits in the
 * terminal cell of every {@link EndlessMineRules#guardianEvery()}-th floor of a mine; its pad stays
 * shut while the guardian lives, and clearing the floor pays that guardian's loot tier.
 *
 * <p>This class is the engine, not the content. The real guardians (minis of each act's bosses,
 * tasks T41 and T42) register here, one per act and per guardian slot:
 * <pre>
 *   MineGuardians.register(1, 1, spawn -&gt; { ... });   // Act 1 mine, first guardian of each lap
 * </pre>
 * Slot 1 to 3 is the guardian's place in a lap (guardian numbers 1, 4, 7 are slot 1; 2, 5, 8 are
 * slot 2; 3, 6, 9 are slot 3). The {@link Spawn} it is given carries the absolute guardian
 * {@code number}, the {@code lap} and the {@code hardness}, a dial that rises without a cap
 * ({@link EndlessMineRules#hardness}); how a guardian turns hardness into health, damage, adds and
 * attacks is its own. A guardian must tag every mob it spawns with {@link #tag} so the pad gate can
 * find it, and should spawn at {@link Spawn#centre()}.
 *
 * <p>Until a guardian is registered for an act and slot, {@link #placeholder} runs: a plain wave of
 * one vanilla mob type, count and stats scaled by hardness. It exists so the cadence, the gate and the
 * loot tiers can be played before the real guardians exist.
 */
final class MineGuardians {

    /** Read back by {@link #alive} to find the mobs this floor's guardian spawned. */
    static final String GUARDIAN_TAG = PocketDungeonsMod.MOD_ID + ".mine_guardian";

    /** The placeholder wave never spawns more mobs than this, however hard the guardian is. */
    static final int PLACEHOLDER_MAX_MOBS = 24;

    private MineGuardians() {}

    /**
     * What a guardian is told when its floor opens.
     *
     * @param level      the dimension to spawn in
     * @param terminal   the terminal cell's origin; {@code centre} is its middle
     * @param centre     the middle of the terminal cell, one block above its floor
     * @param act        the act of the mine
     * @param number     the guardian's number in the mine, from 1 (floor 3 is number 1 at the default cadence)
     * @param slot       its place in a lap, 1 to 3
     * @param lap        laps completed before it: 0 for numbers 1 to 3, 1 for 4 to 6, and so on
     * @param hardness   {@link EndlessMineRules#hardness}, 1.0 or more, no upper bound
     * @param floorLevel the level the floor runs at, for the ordinary mob scale
     */
    record Spawn(ServerLevel level, BlockPos terminal, BlockPos centre, int act, int number, int slot, int lap,
                 double hardness, int floorLevel) {}

    /** A guardian: spawns its mobs (tagged) for one {@link Spawn}. */
    @FunctionalInterface
    interface Guardian {
        void spawn(Spawn spawn);
    }

    private static final Map<Integer, Guardian> REGISTRY = new HashMap<>();

    private static int key(int act, int slot) {
        return act * 10 + slot;
    }

    /**
     * Registers the guardian for {@code act}'s mine at lap slot {@code slot} (1 to 3), replacing any earlier one.
     * Call once at mod init.
     */
    static synchronized void register(int act, int slot, Guardian guardian) {
        if (act < DungeonDef.MIN_ACT || act > DungeonDef.MAX_ACT
                || slot < 1 || slot > EndlessMineRules.GUARDIANS_PER_LAP || guardian == null) {
            throw new IllegalArgumentException("guardian slot out of range: act " + act + ", slot " + slot);
        }
        REGISTRY.put(key(act, slot), guardian);
    }

    /** The registered guardian for {@code act} and {@code slot}, or null for none (the placeholder runs). */
    static synchronized Guardian registered(int act, int slot) {
        return REGISTRY.get(key(act, slot));
    }

    /** Drops every registration; for tests. */
    static synchronized void clear() {
        REGISTRY.clear();
    }

    /** The {@link Spawn} for the guardian on Mine floor {@code floor} of {@code act}'s mine; null on a floor with none. */
    static Spawn spawnFor(ServerLevel level, BlockPos terminalOrigin, int act, int floor, int floorLevel) {
        int number = EndlessMineRules.guardianNumber(floor);
        if (number == 0) {
            return null;
        }
        BlockPos centre = terminalOrigin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
        return new Spawn(level, terminalOrigin.immutable(), centre, act, number, EndlessMineRules.slot(number),
                EndlessMineRules.lap(number), EndlessMineRules.hardness(number), floorLevel);
    }

    /**
     * Spawns the guardian waiting on Mine floor {@code floor}, if the floor has one: the registered
     * guardian for its act and slot, else the placeholder wave. Called once when the floor commits.
     */
    static void spawnIfGuardianFloor(ServerLevel level, InstanceRecord record, BlockPos terminalOrigin,
                                     int floor, int floorLevel) {
        Spawn spawn = spawnFor(level, terminalOrigin, EndlessMineRules.actOf(record), floor, floorLevel);
        if (spawn == null) {
            return;
        }
        Guardian guardian = registered(spawn.act(), spawn.slot());
        (guardian == null ? (Guardian) MineGuardians::placeholder : guardian).spawn(spawn);
    }

    /** Marks {@code mob} as part of the floor's guardian, so the pad stays shut while it lives. */
    static void tag(Mob mob) {
        mob.setPersistenceRequired();
        mob.addTag(GUARDIAN_TAG);
    }

    /**
     * Scales {@code mob} for a guardian: the ordinary floor mob scale for {@code floorLevel}, times
     * {@code 1 + hardness}, so a guardian of hardness 1 is twice an ordinary mob and the multiplier keeps
     * climbing with no cap.
     */
    static void scale(Mob mob, int floorLevel, double hardness) {
        double ordinary = Math.max(1.0, DifficultyProfile.mobScale(floorLevel,
                PocketDungeonsConfig.mobScalePerLevel(), PocketDungeonsConfig.mobScaleBase()));
        MobScaling.applyMobScaleBonus(mob, ordinary * (1.0 + hardness) - 1.0);
    }

    /** How many mobs the placeholder wave has at {@code hardness}: 3 at 1.0, two more per full point, capped. */
    static int placeholderCount(double hardness) {
        return Math.max(3, Math.min(PLACEHOLDER_MAX_MOBS, (int) Math.round(1.0 + 2.0 * hardness)));
    }

    /** The placeholder wave's mob type for an act's mine. */
    static EntityType<? extends Mob> placeholderType(int act) {
        return act == 2 ? EntityTypes.SKELETON : EntityTypes.ZOMBIE;
    }

    /**
     * A free floor spot in the cell at {@code cellOrigin}: air at feet and head, solid below, out of the
     * door lanes, and at least two blocks (Chebyshev) from every position in {@code taken}, which gains the
     * returned spot. Seed {@code taken} with the cell centre to keep a guardian's props off the pad.
     * {@code null} when no spot is found.
     */
    static BlockPos freeSpotInCell(ServerLevel level, BlockPos cellOrigin, java.util.Set<BlockPos> taken,
                                   net.minecraft.util.RandomSource random) {
        for (int attempt = 0; attempt < 48; attempt++) {
            int x = 1 + random.nextInt(RoomGeometry.CELL - 2);
            int z = 1 + random.nextInt(RoomGeometry.CELL - 2);
            if (CreepingRules.inDoorLane(x, z)) {
                continue;
            }
            BlockPos at = cellOrigin.offset(x, 1, z);
            if (!level.isLoaded(at) || !level.getBlockState(at).isAir() || !level.getBlockState(at.above()).isAir()
                    || !level.getBlockState(at.below()).isSolid()) {
                continue;
            }
            boolean near = false;
            for (BlockPos spot : taken) {
                if (Math.max(Math.abs(at.getX() - spot.getX()), Math.abs(at.getZ() - spot.getZ())) < 2) {
                    near = true;
                    break;
                }
            }
            if (near) {
                continue;
            }
            taken.add(at);
            return at;
        }
        return null;
    }

    /**
     * The placeholder guardian: a ring of one vanilla mob type around the cell centre, tagged and scaled
     * by hardness. Not a final design; the real guardians replace it per act and slot.
     */
    static void placeholder(Spawn spawn) {
        int count = placeholderCount(spawn.hardness());
        EntityType<? extends Mob> type = placeholderType(spawn.act());
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            BlockPos at = spawn.centre().offset((int) Math.round(Math.cos(angle) * 4.0), 0,
                    (int) Math.round(Math.sin(angle) * 4.0));
            Mob mob = type.spawn(spawn.level(), at, EntitySpawnReason.TRIGGERED);
            if (mob == null) {
                PocketDungeonsMod.LOG.warn("Guardian wave mob failed to spawn at {}", at.toShortString());
                continue;
            }
            tag(mob);
            mob.setCustomName(Component.literal("Guardian").withStyle(ChatFormatting.RED));
            scale(mob, spawn.floorLevel(), spawn.hardness());
        }
    }

    /** Whether any guardian mob of this floor still lives around the terminal cell. */
    static boolean alive(ServerLevel level, BlockPos terminalOrigin) {
        // A wave chases players, so look well beyond the cell the guardian spawned in.
        AABB bounds = CellGeometry.cellBounds(terminalOrigin).inflate(RoomGeometry.CELL * 2.0);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds)) {
            if (mob.isAlive() && mob.entityTags().contains(GUARDIAN_TAG)) {
                return true;
            }
        }
        return false;
    }

    /** The pad refusal line while the floor's guardian lives, or null (not a Mine, no guardian, or it is down). */
    static String padRefusal(ServerLevel level, InstanceRecord record) {
        if (!EndlessMineRules.isMine(record) || record.layout == null) {
            return null;
        }
        return alive(level, record.layout.terminal()) ? "The guardian still stands. Finish it first." : null;
    }

    /**
     * The loot table that pays Mine floor {@code floorsCleared}: the guardian's tier table when the floor
     * held a guardian, else {@code fallback} (the theme's own table, usually none).
     */
    static String payingTable(InstanceRecord record, int floorsCleared, String fallback) {
        if (!EndlessMineRules.isMine(record) || !EndlessMineRules.isGuardianFloor(floorsCleared)) {
            return fallback;
        }
        return EndlessMineRules.lootTableId(EndlessMineRules.actOf(record),
                EndlessMineRules.guardianNumber(floorsCleared));
    }
}
