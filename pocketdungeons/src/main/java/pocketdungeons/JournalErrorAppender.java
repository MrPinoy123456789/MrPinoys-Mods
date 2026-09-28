package pocketdungeons;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.util.Queue;

/**
 * Feeds the playtest journal's {@code error} event: a log4j appender on the
 * root logger that queues the first line of every ERROR the mod's own logger
 * ({@code PocketDungeons}) writes. The queue is drained on the server thread,
 * which is where "who is in an instance right now" can be answered safely.
 *
 * <p>Kept apart from {@link PlaytestJournal} so a server without log4j-core
 * on its classpath only loses this one event. A log4j reconfiguration drops
 * the appender until the next start.
 */
final class JournalErrorAppender extends AbstractAppender {

    private final Queue<String> queue;

    private JournalErrorAppender(Queue<String> queue) {
        super("PocketDungeonsPlaytestJournal", null, null, true, Property.EMPTY_ARRAY);
        this.queue = queue;
    }

    static void install(Queue<String> queue) {
        if (!(LogManager.getContext(false) instanceof LoggerContext context)) {
            return;
        }
        JournalErrorAppender appender = new JournalErrorAppender(queue);
        appender.start();
        LoggerConfig root = context.getConfiguration().getRootLogger();
        root.addAppender(appender, Level.ERROR, null);
        context.updateLoggers();
    }

    @Override
    public void append(LogEvent event) {
        if (!"PocketDungeons".equals(event.getLoggerName()) || !event.getLevel().isMoreSpecificThan(Level.ERROR)) {
            return;
        }
        String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        // Bounded, so a flood of errors with nobody draining cannot grow without end.
        if (queue.size() < 256) {
            queue.add(JournalFormat.firstLine(message));
        }
    }
}
