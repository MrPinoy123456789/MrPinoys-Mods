package ballot.mc;

import ballot.Poll;
import ballot.PollState;
import ballot.Rules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The polls folder.
 *
 * <p>One file per poll, and the filename is the id. There is no index file, because an
 * index is a thing that can disagree with the folder. Adding a poll means adding a
 * file; removing one means deleting it.
 *
 * <p>Memory is the source of truth at runtime. Files are read at boot and on reload,
 * and written after each mutation. Nothing on the hot path parses JSON.
 */
public final class PollStore {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path pollsDir;
    private final Path archiveFile;
    private final Map<String, Poll> polls = new LinkedHashMap<>();
    private final java.util.function.Consumer<String> log;

    public PollStore(Path root, java.util.function.Consumer<String> log) {
        this.pollsDir = root.resolve("polls");
        this.archiveFile = root.resolve("archive.json");
        this.log = log;
    }

    // ---- keys -------------------------------------------------------------

    /**
     * A poll's identity is an opaque key, not its name.
     *
     * <p>The filename is the key, so renaming never touches the filesystem — no file
     * moves, no partial renames, and nothing in the world can end up pointing at a
     * poll that has since been called something else.
     */
    public String newKey() {
        String key;
        do {
            key = "p_" + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current()
                    .nextLong(0x1000000L) | 0x100000L).substring(0, 6);
        } while (polls.containsKey(key));
        return key;
    }

    public static boolean isValidKey(String key) {
        return key != null && key.matches("p_[0-9a-f]{6}");
    }

    // ---- loading ----------------------------------------------------------

    /** Replaces the in-memory registry with every readable poll JSON file on disk. */
    public void loadAll() {
        polls.clear();
        try {
            Files.createDirectories(pollsDir);
        } catch (IOException e) {
            log.accept("Could not create the polls folder: " + e.getMessage());
            return;
        }
        try (Stream<Path> files = Files.list(pollsDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(this::loadOne);
        } catch (IOException e) {
            log.accept("Could not read the polls folder: " + e.getMessage());
        }
    }

    private void loadOne(Path file) {
        String name = file.getFileName().toString();
        String key = name.substring(0, name.length() - ".json".length());
        if (!isValidKey(key)) {
            log.accept("Skipping poll with invalid poll key: " + name);
            return;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Poll poll = GSON.fromJson(r, Poll.class);
            if (poll == null) {
                log.accept("Skipping empty poll file: " + name);
                return;
            }
            poll.afterLoad(key);
            polls.put(key, poll);
        } catch (IOException | RuntimeException e) {
            // A malformed file must not stop the others loading.
            log.accept("Skipping unreadable poll " + name + ": " + e.getMessage());
        }
    }

    // ---- access -----------------------------------------------------------

    public Optional<Poll> get(String id) {
        return Optional.ofNullable(polls.get(id));
    }

    public List<Poll> all() {
        return List.copyOf(polls.values());
    }

    public List<Poll> active() {
        return polls.values().stream()
                .filter(p -> p.state() != PollState.ARCHIVED)
                .toList();
    }

    public int size() {
        return polls.size();
    }

    // ---- mutation ---------------------------------------------------------

    /**
     * The one live poll, if there is one.
     *
     * <p>v2 runs a single vote at a time. That is a constraint on liveness, not on the
     * schema: archived polls keep accumulating as files, and the key/name split still
     * matters because blocks in the world point at keys. What it removes is every
     * screen and command argument that existed only to say <em>which</em> poll.
     */
    public Optional<Poll> current() {
        return polls.values().stream()
                .filter(p -> p.state() != PollState.ARCHIVED)
                .findFirst();
    }

    public boolean hasLive() {
        return current().isPresent();
    }

    /** Refuses while a poll is still live. The caller decides what to say about it. */
    public Optional<Poll> create(Rules rules, long now) {
        if (hasLive()) {
            return Optional.empty();
        }
        Poll poll = Poll.create(newKey(), "", rules, now);
        polls.put(poll.key(), poll);
        save(poll);
        return Optional.of(poll);
    }

    public void save(Poll poll) {
        writeAtomically(pollsDir.resolve(poll.key() + ".json"), poll);
    }

    public boolean delete(String key) {
        if (!polls.containsKey(key)) {
            return false;
        }
        try {
            Files.deleteIfExists(pollsDir.resolve(key + ".json"));
        } catch (IOException e) {
            log.accept("Could not delete " + key + ": " + e.getMessage());
            return false;
        }
        polls.remove(key);
        return true;
    }

    /**
     * Appends the whole poll to the archive and removes the active file.
     *
     * <p>The archive is never read at runtime. It exists so the server's history is on
     * disk and greppable, and so the active folder stays short enough to page through
     * in game.
     */
    public boolean archive(String key, long now) {
        Poll poll = polls.get(key);
        if (poll == null || poll.state() != PollState.CLOSED) {
            return false;
        }
        // Build the archived representation without mutating the live object. If either
        // disk operation fails, memory and the active file must continue to agree that
        // this is a closed poll which can be retried.
        Poll archived = GSON.fromJson(GSON.toJsonTree(poll), Poll.class);
        archived.afterLoad(key);
        archived.moveTo(PollState.ARCHIVED, now);

        Archive archive = readArchive();
        boolean alreadyArchived = archive.polls.stream()
                .anyMatch(entry -> entry.has("key") && entry.get("key").isJsonPrimitive()
                        && key.equals(entry.get("key").getAsString()));
        if (!alreadyArchived) {
            archive.polls.add(GSON.toJsonTree(archived).getAsJsonObject());
            archive.polls.get(archive.polls.size() - 1).addProperty("key", key);
            if (!writeAtomically(archiveFile, archive)) {
                return false;
            }
        }

        return delete(key);
    }

    private static final class Archive {
        List<com.google.gson.JsonObject> polls = new ArrayList<>();
    }

    private Archive readArchive() {
        if (!Files.exists(archiveFile)) {
            return new Archive();
        }
        try (Reader r = Files.newBufferedReader(archiveFile, StandardCharsets.UTF_8)) {
            Archive parsed = GSON.fromJson(r, Archive.class);
            if (parsed == null || parsed.polls == null) {
                return new Archive();
            }
            return parsed;
        } catch (IOException | RuntimeException e) {
            log.accept("Could not read the archive; starting a new one: " + e.getMessage());
            return new Archive();
        }
    }

    /** Temp file, then rename. A crash mid-write leaves the previous file intact. */
    private boolean writeAtomically(Path file, Object value) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(value, w);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            log.accept("Failed to write " + file.getFileName() + ": " + e.getMessage());
            return false;
        }
    }
}
