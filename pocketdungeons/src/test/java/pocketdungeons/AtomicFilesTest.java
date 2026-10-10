package pocketdungeons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Code audit 2026-10-10 (item 14): the room editor's save and the lost and found entries used to write
 * straight onto the final path. {@link AtomicFiles} writes a sibling temp file and moves it over the target.
 * This proves the bytes are exactly what the writer produced, no temp file is left, a failed write leaves the
 * old file whole, and {@link LostAndFound#write} goes through it.
 */
public class AtomicFilesTest {

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("atomic-files-test");
        try {
            exactBytes(dir);
            replacesExisting(dir);
            failureKeepsTheOldFile(dir);
            lostAndFoundUsesIt(dir);
        } finally {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        System.out.println("AtomicFilesTest passed");
    }

    private static void exactBytes(Path dir) throws IOException {
        Path file = dir.resolve("room.nbt");
        byte[] bytes = {0, 1, 2, (byte) 0xFF, (byte) 0x80, 10, 13, 10};
        AtomicFiles.write(file, tmp -> Files.write(tmp, bytes));
        check(Arrays.equals(Files.readAllBytes(file), bytes), "the target holds exactly the bytes written");
        check(!Files.exists(dir.resolve("room.nbt.tmp")), "no temp file is left behind");

        Path text = dir.resolve("room.json");
        String json = "{\"name\":\"café\",\"n\":1}";
        AtomicFiles.writeString(text, json, StandardCharsets.UTF_8);
        check(Arrays.equals(Files.readAllBytes(text), Files.readString(text).getBytes(StandardCharsets.UTF_8)),
                "string write is plain UTF-8");
        check(Arrays.equals(Files.readAllBytes(text), json.getBytes(StandardCharsets.UTF_8)),
                "the text on disk equals the string given");
    }

    private static void replacesExisting(Path dir) throws IOException {
        Path file = dir.resolve("replace.json");
        Files.writeString(file, "old");
        AtomicFiles.writeString(file, "new", StandardCharsets.UTF_8);
        check(Files.readString(file).equals("new"), "an existing file is replaced");
    }

    private static void failureKeepsTheOldFile(Path dir) throws IOException {
        Path file = dir.resolve("keep.json");
        Files.writeString(file, "whole");
        try {
            AtomicFiles.write(file, tmp -> {
                Files.writeString(tmp, "half");
                throw new IOException("killed part way");
            });
            check(false, "the failure should propagate");
        } catch (IOException expected) {
            check(expected.getMessage().equals("killed part way"), "the writer's own failure is the one reported");
        }
        check(Files.readString(file).equals("whole"), "a failed write leaves the old file whole");
        check(!Files.exists(dir.resolve("keep.json.tmp")), "a failed write removes its temp file");
    }

    private static void lostAndFoundUsesIt(Path dir) throws IOException {
        UUID player = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        LostAndFound.write(dir, player, "Tester", "cause", 3, List.of("slot 0: stone"));
        Path folder = dir.resolve(player.toString());
        try (Stream<Path> files = Files.list(folder)) {
            List<Path> all = files.toList();
            check(all.size() == 1 && all.get(0).toString().endsWith(".log"),
                    "one finished entry and no temp file in the player's folder: " + all);
            String text = Files.readString(all.get(0));
            check(text.startsWith("--- BEGIN LOST+FOUND METADATA ---\n"), "entry header is unchanged");
            check(text.contains("player: Tester\n") && text.contains("slot 0: stone\n"), "entry body is unchanged");
            check(text.endsWith("--- END LOST+FOUND CONTENT ---\n"), "entry footer is unchanged");
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
