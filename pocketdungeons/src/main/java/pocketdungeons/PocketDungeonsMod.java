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
 * <p>Milestone 0 (this build) is a fixed four-room dungeon stamped from code:
 * skeleton, zombie, chest, exit. It exists to prove the dimension, allocation,
 * build, teleport, and teardown loop end to end. The procedural planner from
 * the spec replaces {@link StaticLayout} later; nothing else has to change.
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
        RoomTemplateGenerator.register();

        LOG.info("Pocket Dungeons initialised (server-side only)");
    }
}
