package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What a {@link FinaleScript} hook can see and do (roadmap T01). A fresh context is handed to each hook; it
 * is a thin view over the finale's state in {@link FinaleWave}, so holding one across ticks is not useful.
 * Everything a script adds through it counts toward {@code alive}, so the terminal pad stays shut until the
 * script's mobs are dead too.
 */
final class FinaleContext {

    private final MinecraftServer server;
    private final ServerLevel level;
    private final InstanceRecord record;
    private final DungeonDef.Finale finale;
    private final FinaleWave.State state;
    private final List<ServerPlayer> members;

    FinaleContext(MinecraftServer server, ServerLevel level, InstanceRecord record, DungeonDef.Finale finale,
                  FinaleWave.State state, List<ServerPlayer> members) {
        this.server = server;
        this.level = level;
        this.record = record;
        this.finale = finale;
        this.state = state;
        this.members = members;
    }

    // ---- what is there -----------------------------------------------------------------------------

    MinecraftServer server() {
        return server;
    }

    ServerLevel level() {
        return level;
    }

    InstanceRecord record() {
        return record;
    }

    /** The data finale of this dungeon (its wave and elite), whether or not the script uses it. */
    DungeonDef.Finale finale() {
        return finale;
    }

    /** The members online in the dungeon right now. */
    List<ServerPlayer> members() {
        return members;
    }

    /** At least 1: the size of the party the wave is scaled to. */
    int party() {
        return Math.max(1, members.size());
    }

    long tickCount() {
        return server.getTickCount();
    }

    // ---- the wave ----------------------------------------------------------------------------------

    /** The ids of every mob that counts toward the finale, living or dead, in the order they were added. */
    List<UUID> mobIds() {
        return Collections.unmodifiableList(state.mobs);
    }

    /** The wave's mobs that are still alive and loaded. */
    List<Mob> liveMobs() {
        List<Mob> out = new ArrayList<>();
        for (UUID id : state.mobs) {
            Entity entity = level.getEntity(id);
            if (entity instanceof Mob mob && mob.isAlive()) {
                out.add(mob);
            }
        }
        return out;
    }

    /** How many of the finale's mobs are alive. */
    int alive() {
        return FinaleWave.alive(level, state);
    }

    /** How many mobs the finale has had so far, extras included. */
    int total() {
        return state.mobs.size();
    }

    /** The elite of the data wave or of {@link #spawnElite}, if one stands. */
    Mob elite() {
        Entity entity = state.elite == null ? null : level.getEntity(state.elite);
        return entity instanceof Mob mob ? mob : null;
    }

    /**
     * Adds a mob the script spawned itself to the finale: it counts toward {@code alive} and the total, is
     * kept (never despawns), is tagged as a finale mob and is discarded with the floor. Returns the mob.
     */
    Mob addMob(Mob mob) {
        if (mob != null && !state.mobs.contains(mob.getUUID())) {
            mob.setPersistenceRequired();
            mob.addTag(FinaleWave.TAG);
            state.mobs.add(mob.getUUID());
        }
        return mob;
    }

    /** A free standing spot three to eight blocks from the first member, or {@code null}. */
    BlockPos freeSpotNearParty() {
        return members.isEmpty() ? null : FinaleWave.freeSpotNear(level, members.get(0).blockPosition(),
                level.getRandom());
    }

    /**
     * Spawns a mob of {@code typeId} near the party, points it at a member and adds it to the finale. Returns
     * {@code null} for an unknown type, a non-mob type or an empty party.
     */
    Mob spawnNearParty(String typeId) {
        if (members.isEmpty()) {
            return null;
        }
        Mob mob = FinaleWave.spawn(level, typeId, members.get(0).blockPosition(), level.getRandom());
        if (mob == null) {
            return null;
        }
        FinaleWave.provoke(mob, members.get(0));
        return addMob(mob);
    }

    /**
     * Spawns an elite as the data wave would (named, scaled to the party, glowing, with the boss bar for every
     * member) and adds it. Meant for a script that took over the spawning; the bar belongs to the last elite.
     */
    Mob spawnElite(DungeonDef.Elite elite) {
        if (members.isEmpty()) {
            return null;
        }
        return FinaleWave.spawnElite(level, state, elite, members.get(0).blockPosition(), party(), members);
    }

    // ---- per run state -----------------------------------------------------------------------------

    /**
     * This run's state for the script, created on first use and dropped with the finale (floor end, teardown
     * or server stop). Keyed by the class, so a script keeps one small state class:
     * {@code ctx.scriptState(Lights.class, Lights::new)}.
     */
    <T> T scriptState(Class<T> type, Supplier<T> create) {
        return type.cast(state.scriptState.computeIfAbsent(type, key -> create.get()));
    }

    // ---- talking -----------------------------------------------------------------------------------

    /** A chat line to every member online. Wording follows the player-text skill. */
    void tell(String text, ChatFormatting colour) {
        CapstoneFights.tell(members, text, colour);
    }
}
