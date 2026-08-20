package ballot.mc;

import ballot.Poll;
import ballot.PollState;
import ballot.Rules;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Filesystem regression tests for poll persistence transactions. */
public final class PollStoreTest {

    private int passed;

    public static void main(String[] args) throws Exception {
        new PollStoreTest().run();
    }

    private void run() throws Exception {
        archiveWriteFailureKeepsClosedPoll();
        archiveRetryDoesNotDuplicateHistory();
        deleteFailureKeepsPollInMemory();
        malformedFilenameKeyIsSkipped();
        System.out.println("PollStore tests: " + passed + " passed");
    }

    private void archiveWriteFailureKeepsClosedPoll() throws Exception {
        Path root = Files.createTempDirectory("ballot-archive-failure");
        try {
            List<String> logs = new ArrayList<>();
            PollStore store = new PollStore(root, logs::add);
            Poll poll = closedPoll(store);
            Path pollFile = root.resolve("polls").resolve(poll.key() + ".json");

            // A non-empty directory cannot be atomically replaced by archive.json.tmp.
            Path archive = root.resolve("archive.json");
            Files.createDirectory(archive);
            Files.writeString(archive.resolve("blocker"), "keep");

            check(!store.archive(poll.key(), 5), "archive should report its failed write");
            check(store.get(poll.key()).orElseThrow().state() == PollState.CLOSED,
                    "failed archive should leave the in-memory poll closed");
            check(Files.isRegularFile(pollFile),
                    "failed archive should not delete the active poll file");
            passed++;
        } finally {
            deleteTree(root);
        }
    }

    private void archiveRetryDoesNotDuplicateHistory() throws Exception {
        Path root = Files.createTempDirectory("ballot-archive-retry");
        try {
            PollStore store = new PollStore(root, ignored -> {});
            Poll poll = closedPoll(store);
            Path pollFile = root.resolve("polls").resolve(poll.key() + ".json");
            Files.delete(pollFile);
            Files.createDirectory(pollFile);
            Files.writeString(pollFile.resolve("blocker"), "keep");

            check(!store.archive(poll.key(), 5), "archive should report active-file deletion failure");
            check(!store.archive(poll.key(), 6), "archive retry should still report deletion failure");
            String archive = Files.readString(root.resolve("archive.json"));
            check(archive.indexOf("\"key\": \"" + poll.key() + "\"")
                            == archive.lastIndexOf("\"key\": \"" + poll.key() + "\""),
                    "archive retry should not duplicate poll history");
            passed++;
        } finally {
            deleteTree(root);
        }
    }

    private void deleteFailureKeepsPollInMemory() throws Exception {
        Path root = Files.createTempDirectory("ballot-delete-failure");
        try {
            PollStore store = new PollStore(root, ignored -> {});
            Poll poll = store.create(Rules.forOwnerless(), 1).orElseThrow();
            Path pollFile = root.resolve("polls").resolve(poll.key() + ".json");
            Files.delete(pollFile);
            Files.createDirectory(pollFile);
            Files.writeString(pollFile.resolve("blocker"), "keep");

            check(!store.delete(poll.key()), "delete should report its failed disk mutation");
            check(store.get(poll.key()).isPresent(),
                    "failed delete should retain the in-memory poll");
            passed++;
        } finally {
            deleteTree(root);
        }
    }

    private void malformedFilenameKeyIsSkipped() throws Exception {
        Path root = Files.createTempDirectory("ballot-invalid-key");
        try {
            Path polls = Files.createDirectories(root.resolve("polls"));
            Files.writeString(polls.resolve("not-a-poll-key.json"), "{}");
            List<String> logs = new ArrayList<>();
            PollStore store = new PollStore(root, logs::add);

            store.loadAll();

            check(store.size() == 0, "invalid filename keys should not be loaded");
            check(logs.stream().anyMatch(line -> line.contains("invalid poll key")),
                    "invalid filename keys should be logged");
            passed++;
        } finally {
            deleteTree(root);
        }
    }

    private static Poll closedPoll(PollStore store) {
        Poll poll = store.create(Rules.forOwnerless(), 1).orElseThrow();
        check(poll.setName("Regression poll", 1).ok(), "name poll");
        check(poll.addEntry("One", 1).ok(), "add first entry");
        check(poll.addEntry("Two", 1).ok(), "add second entry");
        check(poll.moveTo(PollState.OPEN, 2).ok(), "open poll");
        check(poll.moveTo(PollState.VOTING, 3).ok(), "start voting");
        check(poll.moveTo(PollState.CLOSED, 4).ok(), "close poll");
        store.save(poll);
        return poll;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
