package pocketdungeons;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint. Server-side only -- {@code /dungeon} builds a private instance in
 * a void dimension, {@code /dungeon exit} tears it down and sends you home.
 *
 * <p>The live build plans a seeded room graph from the configured path, branch,
 * loop, retry, and span limits, resolves it against the room manifest, and stamps
 * the result into an allocated slot. {@link StaticLayout} remains the fallback
 * when procedural planning cannot produce a valid build.
 */
public final class PocketDungeonsMod implements ModInitializer {

    public static final String MOD_ID = "pocketdungeons";
    public static final Logger LOG = LoggerFactory.getLogger("PocketDungeons");

    /** The void dimension declared by {@code data/pocketdungeons/dimension/void.json}. */
    public static final ResourceKey<Level> DUNGEON_LEVEL = ResourceKey.create(
            Registries.DIMENSION, Identifier.fromNamespaceAndPath(MOD_ID, "void"));

    @Override
    public void onInitialize() {
        PocketDungeonsConfig.load(FabricLoader.getInstance().getConfigDir());
        DungeonCommands.register();
        Instances.register();
        RitualListener.register();
        SilenceListener.register();
        RoomEditorListener.register();
        RoomEditorHistory.register();
        RoomProtection.register();
        RoomTemplateGenerator.register();
        Locks.register();
        OmenSources.register();
        DungeonDrops.register();
        // M68: a single reload listener owns the atomic build then commit of
        // all five content surfaces, replacing the per loader listeners
        // ThemeManifest and RoomManifest each registered for themselves.
        ContentReload.register();
        TrimListener.register();
        PowerListener.register();
        DiaryReading.register();
        BlacksmithNPC.register();
        StoreNPC.register();

        LOG.info("Pocket Dungeons initialised (server-side only)");
    }
}
