package pocketdungeons;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * M68: an immutable, validated cross resource snapshot of every Pocket
 * Dungeons content surface at one instant. One {@link ContentSnapshot} is what
 * {@link ContentReload} builds from the live {@link ResourceManager}, validates
 * as a whole, and either commits atomically or discards in favour of the last
 * valid one.
 *
 * <p>Before M68 the four loaders ({@link RoomManifest}, {@link ThemeManifest},
 * {@link AdventureGraphs}, {@link Diaries}) each parsed and published their own
 * {@code volatile current} independently, so a {@code /reload} that produced an
 * incompatible adventure graph could leave themes published against a graph
 * whose transitions no longer resolved, and a pack that removed the
 * {@code pocketdungeons} built ins could empty the room manifest mid session.
 * The snapshot collects all five parses plus the cross resource checks that
 * span them, so the publish is a single coherent step or not at all.
 *
 * <p>Identity is namespaced: every manifest is keyed by
 * {@code namespace:path} (see {@link JsonPackSupport#resourceId}), so two packs
 * with the same local name coexist. Legacy unqualified references resolve to the
 * {@code pocketdungeons} namespace at lookup time
 * ({@link RoomManifest#byName}, {@link ThemeManifest#byId},
 * {@link AdventureGraph#node}); a bare name that is not a {@code pocketdungeons}
 * built in returns null, the deterministic rejection the schema set promises in
 * place of last file wins.
 *
 * <h2>Validity and rollback</h2>
 *
 * <p>{@link #valid} is the required coverage gate: the snapshot must contain at
 * least one {@code entrance} role room and one {@code exit} role room, the two
 * roles no generation can proceed without. A candidate that fails this gate
 * (the {@code pocketdungeons} pack removed, or every entrance room rejected) is
 * not published, and {@link ContentReload} keeps the last valid snapshot
 * standing. Optional content a third party pack broke is reported in
 * {@link #allRejections()} but does not sink the snapshot as long as the
 * required coverage survives: a rejected room is one fewer room, not a failed
 * reload.
 */
final class ContentSnapshot {

    private final RoomManifest rooms;
    private final RoomManifest anomalyRooms;
    private final ThemeManifest themes;
    private final AdventureGraphs adventure;
    private final Diaries diaries;
    private final List<String> errors;
    private final boolean valid;

    private ContentSnapshot(RoomManifest rooms, RoomManifest anomalyRooms,
                            ThemeManifest themes, AdventureGraphs adventure, Diaries diaries,
                            List<String> errors, boolean valid) {
        this.rooms = rooms;
        this.anomalyRooms = anomalyRooms;
        this.themes = themes;
        this.adventure = adventure;
        this.diaries = diaries;
        this.errors = List.copyOf(errors);
        this.valid = valid;
    }

    /**
     * Builds a candidate snapshot from the live resource manager, parsing all
     * five surfaces without publishing any of them, then running the cross
     * resource validation and the required coverage gate.
     *
     * <p>Parse order matters: themes before the adventure graph, because the
     * graph's transition fixpoint resolves transition targets against the theme
     * set. The {@code themeExists} predicate passed to
     * {@link AdventureGraphs#parse} reads the snapshot's own theme manifest, so
     * a transition to a theme this candidate never loaded is dropped before the
     * graph is published, not after.
     */
    static ContentSnapshot build(MinecraftServer server) {
        RoomManifest rooms = RoomManifest.parse(server);
        RoomManifest anomalyRooms = RoomManifest.parseAnomaly(server);
        ThemeManifest themes = ThemeManifest.parse(server);
        AdventureGraphs adventure = AdventureGraphs.parse(server,
                themeId -> themes.byId(themeId) != null);
        Diaries diaries = Diaries.parse(server);

        List<String> errors = new ArrayList<>();
        boolean hasEntrance = false;
        boolean hasExit = false;
        for (RoomManifest.Entry room : rooms.rooms()) {
            if (room.meta.roles.contains("entrance")) {
                hasEntrance = true;
            }
            if (room.meta.roles.contains("exit")) {
                hasExit = true;
            }
        }
        // Required coverage: a generation needs an entrance and an exit cell.
        // A reload that drops the pocketdungeons pack (or rejects every
        // entrance/exit room) fails this gate and is not published, so the last
        // valid snapshot stands and active floors keep resolving.
        boolean valid = hasEntrance && hasExit;
        if (!valid) {
            StringBuilder reason = new StringBuilder("required coverage failed: ");
            if (!hasEntrance) {
                reason.append("no entrance room; ");
            }
            if (!hasExit) {
                reason.append("no exit room; ");
            }
            errors.add(reason.toString().trim());
        }

        return new ContentSnapshot(rooms, anomalyRooms, themes, adventure, diaries, errors, valid);
    }

    RoomManifest rooms() {
        return rooms;
    }

    RoomManifest anomalyRooms() {
        return anomalyRooms;
    }

    ThemeManifest themes() {
        return themes;
    }

    AdventureGraphs adventure() {
        return adventure;
    }

    Diaries diaries() {
        return diaries;
    }

    /** Cross resource and coverage errors that made this candidate invalid. */
    List<String> errors() {
        return errors;
    }

    /** Whether this candidate passed the required coverage gate and may be published. */
    boolean valid() {
        return valid;
    }

    /**
     * Every rejection the snapshot collected: per resource parse rejections
     * from all five surfaces plus the cross resource {@link #errors}. One list
     * for the admin command and the log, in stable order.
     */
    List<String> allRejections() {
        List<String> all = new ArrayList<>();
        all.addAll(rooms.rejections());
        all.addAll(anomalyRooms.rejections());
        all.addAll(themes.rejections());
        all.addAll(adventure.rejections());
        all.addAll(diaries.rejections());
        all.addAll(errors);
        return all;
    }
}
