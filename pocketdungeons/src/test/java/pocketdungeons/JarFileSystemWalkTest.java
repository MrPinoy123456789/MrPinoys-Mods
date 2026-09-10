package pocketdungeons;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Verifies that the jar: filesystem walk used by PackValidator.copyResourceTree
 * works against a packaged release jar. This closes UNVERIFIED item 33.
 *
 * <p>Run: gradlew jarFileSystemWalkTest
 */
class JarFileSystemWalkTest {

    public static void main(String[] args) throws Exception {
        String jarPath = "build/libs/MrPinoys_Pocket_Dungeons-0.1.0.jar";
        java.io.File jarFile = new java.io.File(jarPath);
        if (!jarFile.exists()) {
            System.out.println("SKIP: jar not built at " + jarPath);
            System.out.println("Run gradlew jar first.");
            return;
        }

        URI fsUri = URI.create("jar:" + jarFile.toURI().toString() + "!/");
        FileSystem fs;
        boolean created;
        try {
            fs = FileSystems.getFileSystem(fsUri);
            created = false;
        } catch (java.nio.file.FileSystemNotFoundException e) {
            fs = FileSystems.newFileSystem(fsUri, Map.of());
            created = true;
        }
        try {
            Path starterRoot = fs.getPath("pack_starter");
            if (!Files.exists(starterRoot)) {
                throw new AssertionError("pack_starter should exist in the jar");
            }
            long count;
            try (Stream<Path> paths = Files.walk(starterRoot)) {
                count = paths.count();
            }
            if (count <= 5) {
                throw new AssertionError("pack_starter should contain multiple files, got " + count);
            }
            Path readme = fs.getPath("pack_starter/README.txt");
            if (!Files.exists(readme)) {
                throw new AssertionError("README.txt should exist in pack_starter");
            }
            String content = Files.readString(readme);
            if (content.isEmpty()) {
                throw new AssertionError("README.txt should be non-empty");
            }
            System.out.println("PASS: jar: filesystem walk found " + count
                    + " entries in pack_starter, README.txt is readable ("
                    + content.length() + " chars)");
        } finally {
            if (created) {
                fs.close();
            }
        }
    }
}
