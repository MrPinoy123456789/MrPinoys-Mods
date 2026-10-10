package pocketdungeons;

import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Extracts the mod's bundled {@code data/pocketdungeons} tree into an editable
 * datapack under {@code <world>/datapacks/pocketdungeons_exported/}. This lets
 * server operators iterate on room metadata, themes, loot tables, and so on
 * with {@code /reload} instead of repacking the jar.
 */
final class DatapackExporter {

    private static final String DATA_ROOT = "data/pocketdungeons";
    private static final String PACK_NAME = "pocketdungeons_exported";
    private static final String PACK_DESCRIPTION = "Pocket Dungeons editable datapack";

    private DatapackExporter() {}

    static int export(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        Path worldDir = server.getWorldPath(LevelResource.ROOT);
        Path target = worldDir.resolve("datapacks").resolve(PACK_NAME);

        try {
            URL resource = PocketDungeonsMod.class.getResource("/" + DATA_ROOT);
            if (resource == null) {
                source.sendFailure(Component.literal(
                        "Could not find bundled Pocket Dungeons data"));
                return 0;
            }

            Files.createDirectories(target.resolve("data/pocketdungeons"));
            copyResources(resource, target.resolve("data/pocketdungeons"));
            writePackMeta(target);

            Path relative = Path.of(worldDir.relativize(target).toString().replace('\\', '/'));
            source.sendSuccess(() -> Component.literal(
                    "Exported editable datapack to " + relative
                            + ". Run /reload to load changes."), false);
            return 1;
        } catch (Exception e) {
            PocketDungeonsMod.LOG.error("Failed to export Pocket Dungeons datapack", e);
            source.sendFailure(Component.literal("Export failed: " + e));
            return 0;
        }
    }

    private static void copyResources(URL resource, Path target)
            throws IOException, URISyntaxException {
        if ("file".equals(resource.getProtocol())) {
            copyDirectory(Paths.get(resource.toURI()), target);
        } else if ("jar".equals(resource.getProtocol())) {
            String uriString = resource.toURI().toString();
            String fsUriString = uriString.substring(0, uriString.indexOf("!/") + 2);
            URI fsUri = URI.create(fsUriString);
            FileSystem fs;
            boolean created;
            try {
                fs = FileSystems.getFileSystem(fsUri);
                created = false;
            } catch (FileSystemNotFoundException e) {
                fs = FileSystems.newFileSystem(fsUri, Map.of());
                created = true;
            }
            try {
                copyDirectory(fs.getPath("/" + DATA_ROOT), target);
            } finally {
                if (created) {
                    fs.close();
                }
            }
        } else {
            throw new IllegalStateException(
                    "Unsupported resource protocol: " + resource.getProtocol());
        }
    }

    private static void copyDirectory(Path src, Path dst) throws IOException {
        try (Stream<Path> paths = Files.walk(src)) {
            paths.forEach(path -> {
                try {
                    Path relative = src.relativize(path);
                    Path target = dst.resolve(relative.toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to copy " + path, e);
                }
            });
        }
    }

    private static void writePackMeta(Path target) throws IOException {
        String meta = String.format("""
                {
                  "pack": {
                    "pack_format": %d,
                    "description": "%s"
                  }
                }
                """, SharedConstants.DATA_PACK_FORMAT_MAJOR, PACK_DESCRIPTION);
        try (Writer w = Files.newBufferedWriter(target.resolve("pack.mcmeta"),
                StandardCharsets.UTF_8)) {
            w.write(meta);
        }
    }
}
