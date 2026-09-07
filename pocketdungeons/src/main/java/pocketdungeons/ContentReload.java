package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * M68: the single owner of a Pocket Dungeons content reload. Builds a candidate
 * {@link ContentSnapshot} from the live resource manager, validates it as a
 * whole, and either publishes all five manifests atomically or keeps the last
 * valid snapshot standing. Replaces the four independent reload listeners
 * {@code RoomManifest}, {@code ThemeManifest}, {@code AdventureGraphs} and
 * {@code Diaries} each registered for themselves before M68.
 *
 * <h2>Atomicity and rollback</h2>
 *
 * <p>A reload is build then commit, never publish as you go. A candidate that
 * fails the required coverage gate (no entrance or exit room, the
 * {@code pocketdungeons} pack removed) is discarded: the four {@code current}
 * holders keep the last valid snapshot's manifests, so active floors keep
 * resolving and a half broken pack does not empty the room manifest mid
 * session. The candidate's rejections are still logged and returned, so an
 * operator sees what would have changed.
 *
 * <h2>Generation prohibition during reload</h2>
 *
 * <p>{@link #generationAllowed()} is false while a build is in progress. The
 * generation entry point refuses to start a new run while it is false, so a
 * reload can never stamp a floor against a half published manifest. Vanilla's
 * own reloadable registries (processor lists, loot tables, trial spawner
 * configs) change in the same window, so their references are validated up front
 * in the snapshot build rather than trusted to survive the swap.
 *
 * <h2>Active floor pinning</h2>
 *
 * <p>An active floor was stamped from the definitions the snapshot held at
 * generation time. After a coherent reload, {@link #reconcileActiveFloors}
 * walks the live instances and invalidates any preview whose plan references a
 * room or theme the new snapshot no longer carries, so a stale preview can never
 * commit against definitions the player never saw. An already stamped floor is
 * left in place: it is pinned to the geometry already in the world, and the
 * reload prohibition above is what kept a new generation from racing the swap.
 */
final class ContentReload {

    private static volatile MinecraftServer server;
    private static volatile ContentSnapshot current;
    private static volatile boolean reloadInProgress;

    private ContentReload() {}

    /**
     * Wires {@link #reload} to fire on every {@code /reload}. Call once from
     * {@code onInitialize}. Startup's own resource reload runs before
     * {@code SERVER_STARTED}, so the listener sees a null server on the first
     * pass and defers to the explicit {@link #reload} call in
     * {@code Instances.register()}'s {@code SERVER_STARTED} hook, the same
     * handoff the pre M68 listeners used.
     */
    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> server = null);

        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "content_reload");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager manager) {
                        MinecraftServer s = server;
                        if (s == null) {
                            // Startup's own reload, before SERVER_STARTED has run.
                            // The explicit call in Instances.register() covers it.
                            return;
                        }
                        // Qualified: the enclosing SimpleSynchronousResource
                        // ReloadListener's own reload method has a different
                        // signature in 26.2, so a bare reload(s) would resolve
                        // to it instead of this class's reload(MinecraftServer).
                        ContentReload.reload(s);
                    }
                });
    }

    /**
     * Builds a candidate snapshot, publishes it atomically if it passes the
     * required coverage gate, or keeps the last valid snapshot and logs the
     * rejection if it does not. Returns the candidate either way, so the admin
     * command can report what the reload would have changed.
     */
    static ContentSnapshot reload(MinecraftServer server) {
        reloadInProgress = true;
        try {
            ContentSnapshot candidate = ContentSnapshot.build(server);
            if (candidate.valid()) {
                current = candidate;
                RoomManifest.publish(candidate.rooms(), candidate.anomalyRooms());
                ThemeManifest.publish(candidate.themes());
                AdventureGraphs.publish(candidate.adventure());
                Diaries.publish(candidate.diaries());
                LootTables.validateAtStartup(server);
                reconcileActiveFloors(server, candidate);
            } else {
                // Keep the last valid snapshot. The candidate's rejections and
                // the coverage error are logged so an operator sees the cause
                // rather than a silently unchanged manifest.
                List<String> all = candidate.allRejections();
                PocketDungeonsMod.LOG.error("Content reload rejected; keeping last valid snapshot ({}):",
                        all.size());
                for (String reason : all) {
                    PocketDungeonsMod.LOG.error("  rejected: {}", reason);
                }
            }
            return candidate;
        } finally {
            reloadInProgress = false;
        }
    }

    /**
     * The last committed valid snapshot, or {@code null} before the first
     * successful reload.
     */
    static ContentSnapshot current() {
        return current;
    }

    /**
     * False while a content build is in progress. The generation entry point
     * checks this and refuses to start a new run during a reload, so a floor is
     * never stamped against a half published manifest.
     */
    static boolean generationAllowed() {
        return !reloadInProgress;
    }

    /**
     * After a coherent reload, walks the live instances and invalidates any
     * preview whose plan references a room or theme the new snapshot no longer
     * carries. An already stamped floor is left in place: it is pinned to the
     * geometry already in the world. A preview is the one thing that could
     * still commit against definitions the player never saw, so it is the thing
     * that has to go when its references no longer resolve.
     */
    private static void reconcileActiveFloors(MinecraftServer server, ContentSnapshot snapshot) {
        List<InstanceRecord> orphanedPreviews = new ArrayList<>();
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            if (record.previewPlan == null) {
                continue;
            }
            boolean stale = false;
            for (DungeonPlan.PlacedRoom placed : record.previewPlan.rooms().values()) {
                if (snapshot.rooms().byName(placed.name()) == null) {
                    stale = true;
                    break;
                }
            }
            if (!stale && record.theme != null && snapshot.themes().byId(record.theme) == null) {
                stale = true;
            }
            if (stale) {
                orphanedPreviews.add(record);
            }
        }
        for (InstanceRecord record : orphanedPreviews) {
            PocketDungeonsMod.LOG.warn("Invalidating preview for instance in slot {} after content reload: "
                    + "a referenced room or theme is no longer loaded", record.slot);
            record.previewPlan = null;
            record.previewCellOrigin = null;
            record.previewRecipePlan = null;
            record.previewOfferStep = 0;
        }
    }
}
