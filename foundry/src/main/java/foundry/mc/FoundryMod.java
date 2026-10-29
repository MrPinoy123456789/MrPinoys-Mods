package foundry.mc;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import foundry.core.ContentLoader;
import foundry.core.ContentSet;
import foundry.core.Ident;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint. Loads definitions from {@code config/foundry/content}, registers a Polymer item for each valid one,
 * and logs every problem with the content id and a suggested fix. Invalid definitions are skipped, never half-loaded.
 */
public final class FoundryMod implements ModInitializer {
    public static final String ID = "foundry";
    public static final Logger LOG = LoggerFactory.getLogger(ID);

    private static volatile ContentSet loaded = new ContentSet(java.util.Map.of(), java.util.List.of());

    static ContentSet loaded() {
        return loaded;
    }

    /** The content folder; the {@code foundry.content} system property overrides it (used by gametests). */
    static Path contentRoot() {
        String override = System.getProperty("foundry.content");
        if (override != null && !override.isBlank()) return Path.of(override);
        return FabricLoader.getInstance().getConfigDir().resolve(ID).resolve("content");
    }

    /** Parses and validates the content folder without registering anything. */
    static ContentSet loadContent() {
        ContentSet set = ContentLoader.load(contentRoot(),
                id -> BuiltInRegistries.ITEM.containsKey(Identifier.parse(id.toString())),
                id -> true); // TODO(phase 2): check model ids against the pack inputs
        set.problems().forEach(p -> LOG.warn("{}", p));
        return set;
    }

    @Override
    public void onInitialize() {
        PolymerResourcePackUtils.addModAssets(ID);
        PolymerResourcePackUtils.markAsRequired();
        ContentSet set = loadContent();
        set.items().values().forEach(FoundryItems::register);
        FoundryItems.applyNames();
        loaded = set;
        FoundryCommands.register();
        LOG.info("Foundry: {} items registered, {} problems", FoundryItems.all().size(), set.problems().size());
    }
}
