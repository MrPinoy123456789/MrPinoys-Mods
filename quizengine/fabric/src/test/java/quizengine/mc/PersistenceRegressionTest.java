package quizengine.mc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Regression coverage for content-snapshot atomicity and leaderboard snapshots. */
public final class PersistenceRegressionTest {

    public static void main(String[] args) throws Exception {
        contentReloadIsAtomic();
        leaderboardSnapshotIsThreadSafe();
        System.out.println("PersistenceRegressionTest passed");
    }

    private static void contentReloadIsAtomic() throws Exception {
        Path dir = Files.createTempDirectory("quiz-content-test");
        Content content = new Content(dir);
        content.reload();
        int originalTrivia = content.triviaCount();
        Content.Timings originalTimings = content.timings();

        Files.writeString(dir.resolve("trivia.json"), """
                {"questions":[{"prompt":"Replacement?","options":["yes","no"],"correct":0}]}
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("timings.json"), "{ broken", StandardCharsets.UTF_8);
        content.reload();

        if (content.triviaCount() != originalTrivia || !content.timings().equals(originalTimings)) {
            throw new AssertionError("failed reload exposed a partial content snapshot");
        }

        Files.writeString(dir.resolve("trivia.json"), """
                {"questions":[{"prompt":"Invalid?","options":["yes","no"],"correct":4}]}
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("timings.json"), """
                {"triviaSeconds":45,"quiplashSubmitSeconds":7200,
                 "quiplashClosedSeconds":60,"quiplashVoteSeconds":300,
                 "autoStartSeconds":1800,"afkThresholdSeconds":300}
                """, StandardCharsets.UTF_8);
        content.reload();
        if (content.triviaCount() != originalTrivia) {
            throw new AssertionError("invalid content replaced the previous snapshot");
        }

        Files.writeString(dir.resolve("timings.json"), "", StandardCharsets.UTF_8);
        content.reload();
        if (content.triviaCount() != originalTrivia || !content.timings().equals(originalTimings)) {
            throw new AssertionError("empty content replaced the previous snapshot");
        }
    }

    private static void leaderboardSnapshotIsThreadSafe() throws Exception {
        Path dir = Files.createTempDirectory("quiz-leaderboard-test");
        Leaderboard leaderboard = new Leaderboard(dir.resolve("leaderboard.json"));
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread mutator = new Thread(() -> {
            try {
                for (int i = 0; i < 10_000; i++) {
                    leaderboard.award(new UUID(0, i % 250), "Player" + i, 1, false);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        mutator.start();
        for (int i = 0; i < 2_000; i++) {
            Map<String, Leaderboard.Entry> snapshot = leaderboard.snapshot();
            snapshot.forEach((id, entry) -> {
                UUID.fromString(id);
                if (entry == null) {
                    throw new AssertionError("snapshot contains a null entry");
                }
            });
        }
        mutator.join();
        leaderboard.shutdown();

        if (failure.get() != null) {
            throw new AssertionError("concurrent leaderboard snapshot failed", failure.get());
        }
    }
}
