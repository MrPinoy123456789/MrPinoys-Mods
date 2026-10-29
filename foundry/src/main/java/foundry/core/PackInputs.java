package foundry.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * A content hash of every file that feeds the resource pack: definitions and authored assets, hashed with their
 * paths in sorted order so the digest is deterministic and the order the roots are passed does not matter.
 * Used to skip a pack rebuild when nothing changed. Pure JDK, no Minecraft types.
 */
public final class PackInputs {
    private PackInputs() {}

    private record Entry(String key, Path file) implements Comparable<Entry> {
        public int compareTo(Entry other) {
            return key.compareTo(other.key);
        }
    }

    /** SHA-256 over every file under the roots, keyed on {@code <root folder name>/<relative path>}. */
    public static String digest(Path... roots) {
        List<Entry> files = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(p -> files.add(new Entry(
                        root.getFileName() + "/" + root.relativize(p).toString().replace('\\', '/'), p)));
            } catch (IOException e) {
                // A root that cannot be walked contributes nothing; validation reports real problems separately.
            }
        }
        files.sort(null);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        for (Entry e : files) {
            try {
                md.update(e.key().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(Files.readAllBytes(e.file()));
                md.update((byte) 0);
            } catch (IOException ex) {
                // An unreadable file changes nothing about the digest contract.
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
