package foundry.mc;

import foundry.core.ContentSet;
import foundry.core.RecipeFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/**
 * The generated data pack behind Foundry recipes. DECISIONS.md D8: RecipeManager has no public add path, so recipes
 * reach it as vanilla JSON in an always-enabled builtin pack. The pack is an NIO folder pack,
 * {@code config/foundry/datapack/resourcepacks/recipes/}, re-read on every data reload; the folder is served through a
 * {@link ModContainer} whose root is the config directory instead of the mod jar. {@code registerBuiltinPack}
 * resolves the pack at {@code resourcepacks/<id path>} inside each container root.
 */
final class FoundryDatapack {
    private static final String PACK_ID = "foundry:recipes";

    private FoundryDatapack() {}

    /** Where the generated pack lives; {@code foundry.datapack} overrides it (used by gametests). */
    static Path datapackRoot() {
        String override = System.getProperty("foundry.datapack");
        if (override != null && !override.isBlank()) return Path.of(override);
        return FabricLoader.getInstance().getConfigDir().resolve(FoundryMod.ID).resolve("datapack");
    }

    /** The pack root {@code registerBuiltinPack} resolves: {@code <datapack>/resourcepacks/recipes}. */
    static Path packDir() {
        return datapackRoot().resolve("resourcepacks").resolve(Identifier.parse(PACK_ID).getPath());
    }

    /** Creates the pack skeleton, writes the current recipes and registers the pack as always enabled. */
    static void init(ContentSet set) {
        try {
            Files.createDirectories(packDir());
            Path mcmeta = packDir().resolve("pack.mcmeta");
            if (!Files.isRegularFile(mcmeta)) {
                Files.writeString(mcmeta, """
                        {
                          "pack": {
                            "description": "Foundry generated recipes",
                            "min_format": [107, 1],
                            "max_format": 107
                          }
                        }
                        """);
            }
        } catch (IOException e) {
            FoundryMod.LOG.error("Foundry: could not create the generated datapack at {}: {}. Recipes will not load.",
                    packDir(), e.toString());
            return;
        }
        sync(set);
        ModContainer delegate = FabricLoader.getInstance().getModContainer(FoundryMod.ID).orElseThrow();
        boolean ok = ResourceLoader.registerBuiltinPack(Identifier.parse(PACK_ID),
                datapackContainer(delegate, datapackRoot()),
                Component.literal("Foundry generated"), PackActivationType.ALWAYS_ENABLED);
        if (!ok) {
            FoundryMod.LOG.error("Foundry: could not register the generated datapack; recipes will not load.");
        }
    }

    /** Rewrites the recipe files under {@code data/} to match the set; called after every successful content swap. */
    static void sync(ContentSet set) {
        Path data = packDir().resolve("data");
        try {
            if (Files.isDirectory(data)) {
                try (var walk = Files.walk(data)) {
                    for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            }
            Files.createDirectories(data);
            // Fabric registers a builtin pack only if it has a namespace, so a set with no recipes still keeps one,
            // and recipes added later load on a reload instead of waiting for a restart.
            Files.createDirectories(data.resolve(FoundryMod.ID));
            for (var entry : RecipeFiles.generate(set.recipes()).entrySet()) {
                Path file = data.resolve(entry.getKey().substring("data/".length()));
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue());
            }
        } catch (IOException e) {
            FoundryMod.LOG.error("Foundry: could not write generated recipes to {}: {}.", data, e.toString());
        }
    }

    /** Applies a content reload: rewrite the pack files, then run the same data reload {@code /reload} does. */
    static void apply(ContentSet set, MinecraftServer server) {
        sync(set);
        server.reloadResources(server.getPackRepository().getSelectedIds())
                .thenRun(() -> FoundryMod.LOG.info("Foundry: data reload finished; {} recipe(s) live.",
                        set.recipes().size()))
                .exceptionally(e -> {
                    FoundryMod.LOG.warn("Foundry: data reload failed: {}", e.toString());
                    return null;
                });
    }

    /** A ModContainer whose root is the config datapack folder; metadata comes from the real Foundry container. */
    private static ModContainer datapackContainer(ModContainer delegate, Path root) {
        return new ModContainer() {
            @Override
            public ModMetadata getMetadata() {
                return delegate.getMetadata();
            }

            @Override
            public List<Path> getRootPaths() {
                return List.of(root);
            }

            @Override
            public ModOrigin getOrigin() {
                return new ModOrigin() {
                    @Override
                    public Kind getKind() {
                        return Kind.PATH;
                    }

                    @Override
                    public List<Path> getPaths() {
                        return List.of(root);
                    }

                    @Override
                    public String getParentModId() {
                        return null;
                    }

                    @Override
                    public String getParentSubLocation() {
                        return null;
                    }
                };
            }

            @Override
            public Optional<ModContainer> getContainingMod() {
                return Optional.of(delegate);
            }

            @Override
            public Collection<ModContainer> getContainedMods() {
                return List.of();
            }

            @Override
            public Path getRootPath() {
                return root;
            }

            @Override
            public Path getPath(String s) {
                return root.resolve(s);
            }
        };
    }
}
