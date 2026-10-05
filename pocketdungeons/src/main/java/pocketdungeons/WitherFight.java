package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dungeon structure W7b: the live half of the Act 4 capstone, the Wither of {@code wither_keep}. The
 * rules are in {@link WitherRules}; this class reads the world and acts on them. The fight only
 * exists on the final floor of that dungeon, in the {@code wither_hall} boss room.
 *
 * <h2>How it works</h2>
 * <ul>
 *   <li><strong>Summon.</strong> The watch tick wakes a real vanilla Wither at the far end of the
 *       room when a member first stands in it. Its max health is scaled for the party
 *       ({@link WitherRules#maxHealth}) through the max health attribute, it is exempt from the
 *       keystone mob scaling ({@link CapstoneFights#UNSCALED_TAG}), and it grows for its usual
 *       eleven seconds before it explodes and fights.</li>
 *   <li><strong>No block damage.</strong> {@code ServerExplosionMixin} stops every blast block in a
 *       dungeon cell, which covers the spawn blast and the skulls (all explode through
 *       {@code ServerExplosion}); {@code WitherBossMixin} stops the Wither's own block breaking.
 *       Players and mobs are still hurt.</li>
 *   <li><strong>Containment.</strong> A per tick check puts a Wither that has left the room (the
 *       doorway, the ceiling) back inside it.</li>
 *   <li><strong>The pad.</strong> {@link #padRefusal} keeps the exit pad shut until the Wither is
 *       dead, the same gate path the Drowned Warden and the brood use.</li>
 *   <li><strong>The end.</strong> {@link #floorEnded} and {@link #teardown} discard the Wither.</li>
 * </ul>
 *
 * <h2>The nether star</h2>
 * The vanilla drop is kept. A kept star is the Wither's one signature reward, it is one per clear,
 * and nothing in the dungeon turns it into power (the run inventory does not leave the dungeon; a
 * star only goes home if the player banks it in a chest). The loot band reward is the finish vault,
 * two chests at the dungeon's top tier ({@code RunLifecycle#finishDungeon}), which is unchanged.
 */
final class WitherFight {

    /** Tag on the one Wither a floor summons. */
    static final String TAG = PocketDungeonsMod.MOD_ID + ".capstone_wither";

    /** Where the Wither rises, in cell coordinates: the far end of the room from the west door. */
    private static final double SPAWN_X = 12.5;
    private static final double SPAWN_Y = 1.0;
    private static final double SPAWN_Z = 8.5;

    /** How often the containment check runs, in ticks. */
    private static final int CONTAIN_PERIOD = 4;

    private static final class State {
        final long seed;
        final BlockPos terminal;
        boolean summoned;
        boolean spawnFailed;
        boolean deadAnnounced;
        UUID wither;

        State(long seed, BlockPos terminal) {
            this.seed = seed;
            this.terminal = terminal;
        }
    }

    private static final Map<Integer, State> STATES = new HashMap<>();

    private WitherFight() {}

    /** Wires the containment tick. Called once from {@link CapstoneFights#register()}. */
    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (STATES.isEmpty() || server.getTickCount() % CONTAIN_PERIOD != 0) {
                return;
            }
            ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (level == null) {
                return;
            }
            for (State state : new ArrayList<>(STATES.values())) {
                contain(level, state);
            }
        });
    }

    private static State state(InstanceRecord record) {
        State state = STATES.get(record.slot);
        if (state == null || state.seed != record.layout.seed() || !state.terminal.equals(record.layout.terminal())) {
            state = new State(record.layout.seed(), record.layout.terminal());
            STATES.put(record.slot, state);
        }
        return state;
    }

    // ---- the watch tick -----------------------------------------------------------------

    /** One watch tick of the Wither fight: wakes it when a member is in the room, notes its death. */
    static void tick(MinecraftServer server, ServerLevel level, InstanceRecord record) {
        State state = state(record);
        List<ServerPlayer> members = CapstoneFights.onlineMembers(server, record);
        AABB cell = CellGeometry.cellBounds(state.terminal);
        int inRoom = 0;
        for (ServerPlayer player : members) {
            if (cell.contains(player.position()) && !player.isSpectator()) {
                inRoom++;
            }
        }
        if (WitherRules.shouldSummon(true, state.summoned, inRoom)) {
            summon(server, level, state, members);
            return;
        }
        if (state.summoned && !state.spawnFailed && !state.deadAnnounced && !alive(level, state)) {
            state.deadAnnounced = true;
            CapstoneFights.tell(members, "The Wither falls. The pad warms underfoot.", ChatFormatting.GREEN);
            PocketDungeonsMod.LOG.info("Wither of slot {} is dead", record.slot);
        }
    }

    private static void summon(MinecraftServer server, ServerLevel level, State state, List<ServerPlayer> members) {
        state.summoned = true;
        WitherBoss wither = EntityTypes.WITHER.create(level, EntitySpawnReason.TRIGGERED);
        if (wither == null) {
            state.spawnFailed = true;
            PocketDungeonsMod.LOG.warn("The Wither failed to spawn at {}", state.terminal.toShortString());
            return;
        }
        BlockPos o = state.terminal;
        wither.snapTo(o.getX() + SPAWN_X, o.getY() + SPAWN_Y, o.getZ() + SPAWN_Z, 90.0f, 0.0f);
        wither.addTag(TAG);
        wither.addTag(CapstoneFights.UNSCALED_TAG);
        wither.setPersistenceRequired();
        double max = WitherRules.maxHealth(Math.max(1, members.size()));
        AttributeInstance health = wither.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(max);
        }
        wither.setHealth(wither.getMaxHealth());
        wither.setCustomName(Component.literal("The Wither").withStyle(ChatFormatting.DARK_GRAY));
        // The vanilla growth: eleven seconds invulnerable and building, then the blast (blocks spared).
        wither.makeInvulnerable();
        level.addFreshEntity(wither);
        state.wither = wither.getUUID();

        level.playSound(null, BlockPos.containing(wither.position()), SoundEvents.WITHER_SPAWN,
                SoundSource.HOSTILE, 1.0f, 1.0f);
        CapstoneFights.tell(members, "Soul sand boils at the far end of the room. Something with three heads is"
                + " rising out of it. Get back from the east wall.", ChatFormatting.DARK_GRAY);
        for (ServerPlayer player : members) {
            StaggeredTitle.show(server, player.getUUID(), Component.literal("The Wither").withStyle(ChatFormatting.DARK_GRAY),
                    List.of("It is rising.", "Stand clear."), ChatFormatting.GRAY);
        }
        PocketDungeonsMod.LOG.info("Wither summoned at {} with {} max health for {} member(s)",
                BlockPos.containing(wither.position()).toShortString(), max, members.size());
    }

    // ---- containment ----------------------------------------------------------------------

    /** Puts the Wither back inside the room when it has strayed out of it. */
    private static void contain(ServerLevel level, State state) {
        if (state.wither == null) {
            return;
        }
        Entity found = level.getEntity(state.wither);
        if (!(found instanceof WitherBoss wither) || !wither.isAlive()) {
            return;
        }
        BlockPos o = state.terminal;
        double rx = wither.getX() - o.getX();
        double ry = wither.getY() - o.getY();
        double rz = wither.getZ() - o.getZ();
        if (!WitherRules.outsideRoom(rx, ry, rz)) {
            return;
        }
        double low = WitherRules.INSET + 0.5;
        double high = WitherRules.CELL - WitherRules.INSET - 0.5;
        double cx = Math.max(low, Math.min(high, rx));
        double cz = Math.max(low, Math.min(high, rz));
        double cy = Math.max(1.0, Math.min(WitherRules.MAX_FEET_Y, ry));
        wither.teleportTo(o.getX() + cx, o.getY() + cy, o.getZ() + cz);
        wither.setDeltaMovement(Vec3.ZERO);
    }

    // ---- the pad gate ---------------------------------------------------------------------

    private static boolean alive(ServerLevel level, State state) {
        if (state.wither == null) {
            return false;
        }
        Entity found = level.getEntity(state.wither);
        return found instanceof WitherBoss wither && wither.isAlive();
    }

    /** Why the terminal pad may not complete the floor yet, or {@code null} when it may. */
    static String padRefusal(MinecraftServer server, InstanceRecord record) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null || record.layout == null) {
            return null;
        }
        State state = state(record);
        return WitherRules.padRefusal(state.summoned, state.spawnFailed, alive(level, state));
    }

    // ---- ending a floor ---------------------------------------------------------------------

    /** The floor ended: the Wither goes with it. */
    static void floorEnded(ServerLevel level, InstanceRecord record) {
        if (record == null) {
            return;
        }
        if (record.layout != null) {
            discard(level, record.layout.bounds());
        }
        STATES.remove(record.slot);
    }

    /** Instance teardown: the same with only a slot and layout to hand. */
    static void teardown(ServerLevel level, int slot, InstanceLayout layout) {
        if (layout != null && level != null) {
            discard(level, layout.bounds());
        }
        STATES.remove(slot);
    }

    private static void discard(ServerLevel level, AABB bounds) {
        for (WitherBoss wither : level.getEntitiesOfClass(WitherBoss.class, bounds)) {
            if (wither.entityTags().contains(TAG)) {
                wither.discard();
            }
        }
    }
}
