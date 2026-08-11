package cobbleeconomy;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Section 23. Every movement of money, to the server log and to
 * {@code config/cobbleeconomy/transactions.log}.
 *
 * <p>This is the file you read at 2am when someone insists they were robbed, or when
 * the total supply doubles overnight. It is append-only and never rotated by the mod,
 * because a log that deletes its own history is no use for the one job it has.
 *
 * <p>Writes go through a bounded queue onto a background thread: disk latency must
 * never reach the tick loop, and if the queue somehow fills, log lines are dropped
 * rather than blocking a player's command. Losing an audit line is bad; freezing the
 * server to write one is worse.
 */
public final class TransactionLog {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(4096);
    private final Path file;
    private final boolean toFile;
    private volatile boolean running = true;

    public TransactionLog(Path directory, boolean toFile) {
        this.file = directory.resolve("transactions.log");
        this.toFile = toFile;
        if (!toFile) return;

        Thread t = new Thread(this::drain, "cobbleeconomy-txlog");
        t.setDaemon(true);
        t.start();
    }

    /** A normal player-initiated movement. */
    public void tx(String message) {
        write("[Economy] " + message);
    }

    /** An operator changing balances directly. Kept visually distinct on purpose. */
    public void admin(String message) {
        write("[Economy/Admin] " + message);
    }

    private void write(String line) {
        CobbleEconomyMod.LOG.info(line);
        if (!toFile) return;
        // offer(), not put(): dropping a line beats blocking the server thread.
        if (!queue.offer(ZonedDateTime.now().format(STAMP) + " " + line)) {
            CobbleEconomyMod.LOG.warn("Transaction log queue full -- dropped a line");
        }
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            try {
                String line = queue.poll(1, TimeUnit.SECONDS);
                if (line == null) continue;
                try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    out.write(line);
                    out.write(System.lineSeparator());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                CobbleEconomyMod.LOG.error("Could not append to transactions.log", e);
            }
        }
    }

    public void close() {
        running = false;
    }
}
