package pocketdungeons;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dungeon structure W7a: the live half of the Act 1 and Act 2 capstone fights (W7b adds the Wither and
 * Herobrine, in {@link WitherFight} and {@link HerobrineFight}, which this class routes to). The rules are in
 * {@link BroodWave} and {@link SculkOmen}; this class reads the world and acts on them. Both fights
 * only exist on the final floor of their capstone dungeon.
 *
 * <h2>The Spawner Dungeon (brood)</h2>
 * The terminal cell is the {@code brood_chamber} room: three classic spawners (zombie, skeleton, cave
 * spider), configured at stamp time and registered here as soft breakable blocks so a pickaxe can take
 * them (the D20 break rule would otherwise refuse). The terminal pad completes the floor through the
 * same gate the Drowned Warden uses in {@link RunLifecycle#completeRun}: {@link #padRefusal} returns a
 * line while the brood stands. The fight advances on the watch tick: spawners all broken, or enough
 * brood killed to exhaust them (the rest are doused), then the final wave is released when a member is
 * in the room, and the pad opens when the wave is dead.
 *
 * <h2>The Ancient City (Warden)</h2>
 * Every sculk sensor and shrieker answers ({@link PressureSources}). On the final floor, the
 * {@code ancientWardenAnswers}-th answer of a sculk room summons one real vanilla Warden, set on the
 * nearest member. It is the only Warden the mod ever spawns. It has no part in the gate: the floor
 * completes by reaching the terminal pad, and the Warden is discarded when the floor ends or the
 * instance is torn down.
 */
final class CapstoneFights {

    /** Tag on every mob of the final brood wave. */
    private static final String WAVE_TAG = PocketDungeonsMod.MOD_ID + ".brood_wave";
    /** Tag on the one Warden the Ancient City summons. */
    private static final String WARDEN_TAG = PocketDungeonsMod.MOD_ID + ".ancient_warden";

    /** The Spawner Dungeon's id. */
    static final String SPAWNER_DUNGEON = "spawner_dungeon";
    /** The Act 4 capstone's id (W7b): its final floor holds the Wither. */
    static final String WITHER_KEEP = "wither_keep";
    /** The Act 5 capstone's id (W7b): its final floor holds Steve and Alex's rescue. */
    static final String HEROBRINE = "herobrine";

    /**
     * Tag on a capstone boss ({@link WitherFight}, {@link HerobrineFight}) that the keystone mob scaling
     * must leave alone: their health and damage are set by their own party scaling.
     */
    static final String UNSCALED_TAG = PocketDungeonsMod.MOD_ID + ".unscaled_boss";

    /** The mob each brood spawner runs, assigned in position order and cycling. */
    static final List<EntityType<?>> BROOD_TYPES = List.of(EntityTypes.ZOMBIE, EntityTypes.SKELETON,
            EntityTypes.CAVE_SPIDER);

    /** Which capstone fight a floor holds. */
    enum Fight { NONE, BROOD, WARDEN, WITHER, HEROBRINE }

    /** Per floor fight state, keyed by slot and reset when the floor's layout changes. */
    private static final class State {
        final long seed;
        final BlockPos terminal;
        boolean initialised;
        boolean introduced;
        boolean exhaustedAnnounced;
        boolean waveSpawned;
        boolean doneAnnounced;
        boolean wardenSummoned;
        int initialSpawners;
        int kills;
        final Map<BlockPos, EntityType<?>> spawners = new LinkedHashMap<>();

        State(long seed, BlockPos terminal) {
            this.seed = seed;
            this.terminal = terminal;
        }
    }

    private static final Map<Integer, State> STATES = new HashMap<>();

    private CapstoneFights() {}

    /** Wires the brood kill counter. Call once from {@code onInitialize}. */
    static void register() {
        WitherFight.register();
        HerobrineFight.register();
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity.level() instanceof ServerLevel level)
                    || !level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)
                    || STATES.isEmpty() || !isBroodType(entity)) {
                return;
            }
            InstanceRecord record = Instances.dungeonRecordAt(entity.blockPosition());
            if (record == null || record.layout == null) {
                return;
            }
            State state = STATES.get(record.slot);
            if (state == null || !state.initialised || entity.entityTags().contains(WAVE_TAG)) {
                return;
            }
            if (CellGeometry.cellBounds(state.terminal).contains(entity.position())) {
                state.kills++;
            }
        });
    }

    private static boolean isBroodType(LivingEntity entity) {
        return entity.getType() == EntityTypes.ZOMBIE || entity.getType() == EntityTypes.SKELETON
                || entity.getType() == EntityTypes.CAVE_SPIDER;
    }

    // ---- which fight --------------------------------------------------------------------

    /** The capstone fight the floor in progress holds, or {@link Fight#NONE}. */
    static Fight fightOf(InstanceRecord record) {
        DungeonDef def = TripView.def(record);
        if (def == null || def.kind() != DungeonDef.Kind.CAPSTONE || !TripView.onFinal(record)) {
            return Fight.NONE;
        }
        String id = bare(def.id());
        if (SPAWNER_DUNGEON.equals(id)) {
            return Fight.BROOD;
        }
        if (WITHER_KEEP.equals(id)) {
            return Fight.WITHER;
        }
        if (HEROBRINE.equals(id)) {
            return Fight.HEROBRINE;
        }
        return SculkOmen.wardenAllowed(def.id()) ? Fight.WARDEN : Fight.NONE;
    }

    private static String bare(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    private static State state(InstanceRecord record) {
        State state = STATES.get(record.slot);
        if (state == null || state.seed != record.layout.seed() || !state.terminal.equals(record.layout.terminal())) {
            state = new State(record.layout.seed(), record.layout.terminal());
            STATES.put(record.slot, state);
        }
        return state;
    }

    // ---- stamp time ---------------------------------------------------------------------

    /** The classic spawner blocks inside the cell at {@code cellOrigin}, in a stable (x, z, y) order. */
    static List<BlockPos> spawnerPositions(ServerLevel level, BlockPos cellOrigin) {
        List<BlockPos> found = new ArrayList<>();
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                for (int y = 1; y <= RoomGeometry.CEILING_Y; y++) {
                    BlockPos pos = cellOrigin.offset(x, y, z);
                    if (level.getBlockState(pos).is(Blocks.SPAWNER)) {
                        found.add(pos.immutable());
                    }
                }
            }
        }
        found.sort(Comparator.<BlockPos>comparingInt(pos -> pos.getX()).thenComparingInt(pos -> pos.getZ())
                .thenComparingInt(pos -> pos.getY()));
        return found;
    }

    /** Gives each classic spawner of the boss room its mob. Called by the {@code brood_chamber} handler. */
    static void configureBroodRoom(ServerLevel level, BlockPos cellOrigin) {
        List<BlockPos> spawners = spawnerPositions(level, cellOrigin);
        for (int i = 0; i < spawners.size(); i++) {
            ClassicSpawners.configureAt(level, spawners.get(i), BROOD_TYPES.get(i % BROOD_TYPES.size()));
        }
    }

    // ---- the watch tick -----------------------------------------------------------------

    /** One watch tick of the floor's capstone fight, if it holds one. */
    static void tick(MinecraftServer server, InstanceRecord record) {
        if (record.layout == null || record.phase != RunSession.Phase.ACTIVE
                || !record.floor.completed.isEmpty()) {
            return;
        }
        Fight fight = fightOf(record);
        if (fight == Fight.NONE) {
            // An ordinary dungeon\u0027s final floor ends in its finale, if it has one (design pass 2026-10-09, Q4).
            FinaleWave.tick(server, record);
            return;
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return;
        }
        State state = state(record);
        switch (fight) {
            case BROOD -> tickBrood(server, level, record, state);
            case WARDEN -> tickWarden(server, level, record, state);
            case WITHER -> WitherFight.tick(server, level, record);
            case HEROBRINE -> HerobrineFight.tick(server, level, record);
            default -> { }
        }
    }

    static List<ServerPlayer> onlineMembers(MinecraftServer server, InstanceRecord record) {
        List<ServerPlayer> out = new ArrayList<>();
        for (UUID id : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null && player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                out.add(player);
            }
        }
        return out;
    }

    static void tell(List<ServerPlayer> players, String text, ChatFormatting colour) {
        for (ServerPlayer player : players) {
            player.sendSystemMessage(Component.literal(text).withStyle(colour));
        }
    }

    // ---- brood ----------------------------------------------------------------------------

    private static void initBrood(ServerLevel level, InstanceRecord record, State state) {
        List<BlockPos> spawners = spawnerPositions(level, state.terminal);
        for (int i = 0; i < spawners.size(); i++) {
            state.spawners.put(spawners.get(i), BROOD_TYPES.get(i % BROOD_TYPES.size()));
            // D20: only nodes and soft blocks break; a pickaxe may take the brood's spawners.
            record.floor.softBreakables.add(spawners.get(i));
        }
        state.initialSpawners = spawners.size();
        state.initialised = true;
    }

    private static int spawnersLeft(ServerLevel level, State state) {
        int left = 0;
        for (BlockPos pos : state.spawners.keySet()) {
            if (level.getBlockState(pos).is(Blocks.SPAWNER)) {
                left++;
            }
        }
        return left;
    }

    private static int waveAlive(ServerLevel level, State state) {
        int alive = 0;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, CellGeometry.cellBounds(state.terminal))) {
            if (mob.isAlive() && mob.entityTags().contains(WAVE_TAG)) {
                alive++;
            }
        }
        return alive;
    }

    private static int partySize(List<ServerPlayer> members) {
        return Math.max(1, members.size());
    }

    private static void tickBrood(MinecraftServer server, ServerLevel level, InstanceRecord record, State state) {
        if (!state.initialised) {
            initBrood(level, record, state);
        }
        List<ServerPlayer> members = onlineMembers(server, record);
        AABB cell = CellGeometry.cellBounds(state.terminal);
        List<ServerPlayer> inRoom = new ArrayList<>();
        for (ServerPlayer player : members) {
            if (cell.contains(player.position())) {
                inRoom.add(player);
            }
        }
        if (!state.introduced && !inRoom.isEmpty()) {
            state.introduced = true;
            tell(members, "The Brood Chamber. Break the cages with a pickaxe, or out-kill them until they burn"
                    + " out. Then the last of the brood is loosed.", ChatFormatting.DARK_GREEN);
        }

        int party = partySize(members);
        int left = spawnersLeft(level, state);
        int exhaust = BroodWave.exhaustKills(state.initialSpawners, party);
        if (left > 0 && BroodWave.spawnersDone(left, state.kills, exhaust)) {
            // Out-killed: the cages gutter out and stop spawning, and stay as unlit iron.
            for (Map.Entry<BlockPos, EntityType<?>> entry : state.spawners.entrySet()) {
                ClassicSpawners.douse(level, entry.getKey(), entry.getValue());
            }
            if (!state.exhaustedAnnounced) {
                state.exhaustedAnnounced = true;
                tell(members, "The cages gutter out. They have nothing left to spit.", ChatFormatting.GRAY);
            }
        }
        boolean spawnersDone = BroodWave.spawnersDone(left, state.kills, exhaust);
        if (spawnersDone && !state.waveSpawned && !inRoom.isEmpty()) {
            state.waveSpawned = true;
            int spawned = releaseWave(level, state, party);
            tell(members, "The walls give. The last of the brood pours out (" + spawned + ").",
                    ChatFormatting.RED);
            level.playSound(null, state.terminal.offset(8, 2, 8), SoundEvents.RAVAGER_ROAR,
                    SoundSource.HOSTILE, 1.0f, 0.6f);
        }
        int alive = state.waveSpawned ? waveAlive(level, state) : 0;
        BroodWave.Phase phase = BroodWave.phase(left, state.kills, exhaust, state.waveSpawned, alive);
        if (phase == BroodWave.Phase.DONE && !state.doneAnnounced) {
            state.doneAnnounced = true;
            tell(members, "The brood is broken. The pad is open.", ChatFormatting.GREEN);
        }
    }

    /** Releases the final wave around the room; returns how many mobs stood up. */
    private static int releaseWave(ServerLevel level, State state, int party) {
        RandomSource random = level.getRandom();
        int skeletons = BroodWave.skeletons(party);
        int spiders = BroodWave.spiders(party);
        int spawned = 0;
        for (int i = 0; i < skeletons + spiders; i++) {
            EntityType<? extends Mob> type = i < skeletons ? EntityTypes.SKELETON : EntityTypes.CAVE_SPIDER;
            BlockPos at = freeSpot(level, state.terminal, random);
            if (at == null) {
                continue;
            }
            Mob mob = type.spawn(level, at, EntitySpawnReason.TRIGGERED);
            if (mob == null) {
                continue;
            }
            mob.setPersistenceRequired();
            mob.addTag(WAVE_TAG);
            spawned++;
        }
        return spawned;
    }

    /** A standing spot in the cell's interior: air at feet and head, solid below. */
    static BlockPos freeSpot(ServerLevel level, BlockPos cellOrigin, RandomSource random) {
        for (int attempt = 0; attempt < 40; attempt++) {
            BlockPos at = cellOrigin.offset(2 + random.nextInt(12), 1, 2 + random.nextInt(12));
            if (level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir()
                    && level.getBlockState(at.below()).isSolid()) {
                return at;
            }
        }
        return null;
    }

    // ---- the pad gate -------------------------------------------------------------------------

    /**
     * Why the terminal pad may not complete the floor yet, or {@code null} when it may. The brood
     * gate; the Ancient City never gates its pad. Read live from the world so it never lags the tick.
     */
    static String padRefusal(MinecraftServer server, InstanceRecord record) {
        if (record.layout == null) {
            return null;
        }
        Fight fight = fightOf(record);
        if (fight == Fight.WITHER) {
            return WitherFight.padRefusal(server, record);
        }
        if (fight == Fight.HEROBRINE) {
            return HerobrineFight.padRefusal(server, record);
        }
        if (fight == Fight.NONE) {
            return FinaleWave.padRefusal(server, record);
        }
        if (fight != Fight.BROOD) {
            return null;
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return null;
        }
        State state = state(record);
        if (!state.initialised) {
            initBrood(level, record, state);
        }
        int party = partySize(onlineMembers(server, record));
        int left = spawnersLeft(level, state);
        int exhaust = BroodWave.exhaustKills(state.initialSpawners, party);
        int alive = state.waveSpawned ? waveAlive(level, state) : 0;
        BroodWave.Phase phase = BroodWave.phase(left, state.kills, exhaust, state.waveSpawned, alive);
        return BroodWave.padRefusal(phase, left, alive);
    }

    // ---- the Warden ---------------------------------------------------------------------------

    private static void tickWarden(MinecraftServer server, ServerLevel level, InstanceRecord record, State state) {
        state.initialised = true;
        if (!SculkOmen.shouldSummonWarden(true, true, record.floor.sculkAnswers, state.wardenSummoned)) {
            return;
        }
        List<ServerPlayer> members = onlineMembers(server, record);
        if (members.isEmpty()) {
            return;
        }
        state.wardenSummoned = true;
        ServerPlayer target = members.get(0);
        BlockPos spot = wardenSpot(level, record, target);
        Warden warden = EntityTypes.WARDEN.spawn(level, spot, EntitySpawnReason.TRIGGERED);
        if (warden == null) {
            PocketDungeonsMod.LOG.warn("The Ancient City Warden failed to spawn at {}", spot.toShortString());
            return;
        }
        warden.setPersistenceRequired();
        warden.addTag(WARDEN_TAG);
        warden.increaseAngerAt(target, 80, false);
        tell(members, "The sculk has heard enough. Something old is climbing out of the floor. Reach the pad.",
                ChatFormatting.DARK_AQUA);
        PocketDungeonsMod.LOG.info("Ancient City Warden summoned in slot {} at {}", record.slot, spot.toShortString());
    }

    /**
     * Where the Warden rises: a clear three high spot in the target's own cell at least six blocks from
     * them, else anywhere clear in that cell, else the middle of the terminal cell.
     */
    private static BlockPos wardenSpot(ServerLevel level, InstanceRecord record, ServerPlayer target) {
        RandomSource random = level.getRandom();
        PlanCell cell = record.layout.geometry().cellAt(target.blockPosition());
        if (cell != null) {
            BlockPos origin = record.layout.geometry().cellOrigin(cell);
            BlockPos fallback = null;
            for (int attempt = 0; attempt < 60; attempt++) {
                BlockPos at = origin.offset(2 + random.nextInt(12), 1, 2 + random.nextInt(12));
                if (!clearForWarden(level, at)) {
                    continue;
                }
                if (Vec3.atCenterOf(at).distanceTo(target.position()) >= 6.0) {
                    return at;
                }
                fallback = at;
            }
            if (fallback != null) {
                return fallback;
            }
        }
        return record.layout.terminal().offset(8, 1, 8);
    }

    private static boolean clearForWarden(ServerLevel level, BlockPos at) {
        return level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir()
                && level.getBlockState(at.above(2)).isAir() && level.getBlockState(at.below()).isSolid();
    }

    // ---- ending a floor ---------------------------------------------------------------------

    /**
     * The floor has ended (its pad was reached): the Warden goes with it, the wave's stragglers and
     * the fight state too. Safe to call for any floor.
     */
    static void floorEnded(ServerLevel level, InstanceRecord record) {
        if (record == null) {
            return;
        }
        if (record.layout != null) {
            discardTagged(level, record.layout.bounds());
        }
        STATES.remove(record.slot);
        Whelp.clear(level, record.slot);
        FinaleWave.floorEnded(level, record);
        WitherFight.floorEnded(level, record);
        HerobrineFight.floorEnded(level, record);
    }

    /** Instance teardown: the same as {@link #floorEnded} with only a slot and layout bounds to hand. */
    static void teardown(ServerLevel level, int slot, InstanceLayout layout) {
        if (layout != null && level != null) {
            discardTagged(level, layout.bounds());
        }
        STATES.remove(slot);
        Whelp.clear(level, slot);
        FinaleWave.teardown(level, slot);
        WitherFight.teardown(level, slot, layout);
        HerobrineFight.teardown(level, slot, layout);
    }

    private static void discardTagged(ServerLevel level, AABB bounds) {
        for (Warden warden : level.getEntitiesOfClass(Warden.class, bounds)) {
            if (warden.entityTags().contains(WARDEN_TAG)) {
                warden.discard();
            }
        }
        for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds)) {
            if (mob.entityTags().contains(WAVE_TAG)) {
                mob.discard();
            }
        }
    }
}
