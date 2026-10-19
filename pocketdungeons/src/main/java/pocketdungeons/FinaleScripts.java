package pocketdungeons;

import java.util.HashMap;
import java.util.Map;

/**
 * The registry of {@link FinaleScript}s, keyed by dungeon id ({@link DungeonDef#id()}). Registration is one
 * line per dungeon in {@link #registerAll()}; {@code PocketDungeonsMod} calls that method once and is never
 * edited again for a script.
 */
final class FinaleScripts {

    /** Static content filled once at startup, not per-run state, so it is not session state. */
    private static final Map<String, FinaleScript> SCRIPTS = new HashMap<>();

    private FinaleScripts() {}

    /**
     * Registers every dungeon's script. A dungeon task adds exactly one line here:
     * {@code register("pocketdungeons:some_dungeon", new SomeDungeonFinale());}
     */
    static void registerAll() {
        SCRIPTS.clear();
        // One line per dungeon, appended below by the task that writes the script.
        register(PotionDrip.DUNGEON_ID, new LushFinale());
        register("pocketdungeons:ossuary", new OssuaryFinale());
        register("pocketdungeons:mineshaft", new MineshaftFinale());
        register(InfestationFinale.DUNGEON_ID, new InfestationFinale());
        register("pocketdungeons:kennels", new KennelsFinale());
        register("pocketdungeons:deepslate", new DeepslateFinale());
        register("pocketdungeons:frostworks", new FrostFinale());
        register(CowPits.DUNGEON, new CowPitsFinale());
        register(CopperClock.DUNGEON, new CopperFinale());
    }

    /** Registers {@code script} for the dungeon with this id; a second script for one dungeon is a mistake. */
    static void register(String dungeonId, FinaleScript script) {
        if (dungeonId == null || dungeonId.isEmpty() || script == null) {
            throw new IllegalArgumentException("a finale script needs a dungeon id and a script");
        }
        if (SCRIPTS.putIfAbsent(dungeonId, script) != null) {
            throw new IllegalStateException("a finale script is already registered for " + dungeonId);
        }
    }

    /** Drops the script for this dungeon id (tests register their own and remove it again). */
    static void unregister(String dungeonId) {
        SCRIPTS.remove(dungeonId);
    }

    /** Whether this dungeon has a script. */
    static boolean has(String dungeonId) {
        return SCRIPTS.containsKey(dungeonId) || UnchartedGen.isUnchartedId(dungeonId);
    }

    /** The script for the dungeon id, or {@link FinaleScript#NONE}. */
    static FinaleScript forDungeon(String dungeonId) {
        FinaleScript script = dungeonId == null ? null : SCRIPTS.get(dungeonId);
        if (script == null && UnchartedGen.isUnchartedId(dungeonId)) {
            // Every generated Chart Room dungeon ends in the same guardian finale (campaign endgame, 2026-10-18).
            return UnchartedFinale.INSTANCE;
        }
        return script == null ? FinaleScript.NONE : script;
    }

    /** The script for the dungeon this run is playing, or {@link FinaleScript#NONE}. */
    static FinaleScript forRecord(InstanceRecord record) {
        return record == null ? FinaleScript.NONE : forDungeon(record.interval.dungeonId);
    }
}
