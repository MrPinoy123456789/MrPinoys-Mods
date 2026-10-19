package pocketdungeons;

import net.minecraft.server.MinecraftServer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The server side of the Chart Room's uncharted doors (campaign endgame, 2026-10-18; spec T5). {@link UnchartedGen}
 * makes a dungeon from plain inputs; this class reads those inputs from the player's save, remembers what was made
 * so a door id can be turned back into its dungeon ({@link DungeonDefs#byId} falls back to {@link #lookup}), and
 * pins the committed one on the trip ({@link IntervalState#generatedDef}).
 *
 * <p>The memory is a bounded map: a door is a pure function of its inputs, so a forgotten one is simply made
 * again the next time the astrolabe is built. The trip in progress never relies on it, because it holds its own
 * dungeon. Nothing here is ever saved; a restart ends an uncharted trip, as it ends every trip.
 */
final class UnchartedDoors {

    private UnchartedDoors() {}

    /** The most generated dungeons kept; oldest out first. A trip needs three at a time. */
    private static final int REMEMBERED = 256;

    private static final Map<String, DungeonDef> REGISTRY = SessionState.clearedAtStop(
            Collections.synchronizedMap(new LinkedHashMap<String, DungeonDef>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, DungeonDef> eldest) {
                    return size() > REMEMBERED;
                }
            }));

    /** The generated dungeon with this id, or {@code null} if it is not remembered. */
    static DungeonDef lookup(String id) {
        return id == null ? null : REGISTRY.get(DungeonDef.qualify(id));
    }

    /** Forgets every remembered dungeon; a test uses it to prove the trip holds its own. */
    static void forgetAll() {
        REGISTRY.clear();
    }

    /** Puts {@code def} back if the registry has dropped it; a cheap read when it is there. */
    static void ensure(DungeonDef def) {
        if (def != null && !REGISTRY.containsKey(def.id())) {
            remember(def);
        }
    }

    /** Remembers {@code def}, newest last. */
    static void remember(DungeonDef def) {
        if (def != null) {
            synchronized (REGISTRY) {
                REGISTRY.remove(def.id());
                REGISTRY.put(def.id(), def);
            }
        }
    }

    /**
     * The generated dungeon a first door commit stands on: the remembered one for an uncharted id (touched so it
     * stays), else {@code null}. The caller stores the result on the trip.
     */
    static DungeonDef pin(String dungeonId) {
        if (!UnchartedGen.isUnchartedId(dungeonId)) {
            return null;
        }
        DungeonDef def = lookup(dungeonId);
        remember(def);
        return def;
    }

    /**
     * The compass the doors are cut for: the owner's, or in a party the lowest among the members of the
     * record (decision 2026-10-18: a floor is never harder than the weakest member's compass). With no record
     * only the owner counts.
     */
    static int partyCompass(MinecraftServer server, UUID owner, InstanceRecord record) {
        DungeonLog log = DungeonLog.forServer(server);
        int lowest = Math.max(1, log.get(owner).highestCharts());
        if (record != null) {
            for (UUID member : record.members.keySet()) {
                lowest = Math.min(lowest, Math.max(1, log.get(member).highestCharts()));
            }
        }
        return lowest;
    }

    /**
     * The three uncharted doors for {@code owner}'s Chart Room, generated and remembered. Stable while the
     * player looks (the inputs do not change inside a trip) and different after each trip, which raises the
     * trip counter. Empty when the loaded dungeons offer no theme.
     */
    static List<DungeonDef> doors(MinecraftServer server, UUID owner, InstanceRecord record) {
        DungeonLog.Entry entry = DungeonLog.forServer(server).get(owner);
        List<DungeonDef> doors = UnchartedGen.doors(owner, entry.campaign().tripCounter(),
                partyCompass(server, owner, record), Campaign.releaseMaxAct(),
                PocketDungeonsConfig.relicFloorChance(), entry.dungeonsFinished(),
                DungeonDefs.current().all());
        for (DungeonDef def : doors) {
            remember(def);
        }
        return doors;
    }
}
