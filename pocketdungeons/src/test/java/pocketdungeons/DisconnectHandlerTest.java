package pocketdungeons;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * PD-198: {@code ServerPlayConnectionEvents.DISCONNECT} can fire on Netty's IO thread (PD-12),
 * and every map and record this mod owns is touched by the server thread each tick. A handler
 * that mutates state directly races it. This reads the sources and fails if a DISCONNECT
 * handler does not hand its work to {@code server.execute}, so a new one cannot reopen the race.
 */
public class DisconnectHandlerTest {

    private static final Path SOURCES = Paths.get("src/main/java/pocketdungeons");
    private static final String MARKER = "DISCONNECT.register(";

    public static void main(String[] args) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        int handlers = 0;
        for (Path file : files) {
            String source = stripLineComments(Files.readString(file));
            int from = 0;
            while (true) {
                int at = source.indexOf(MARKER, from);
                if (at < 0) {
                    break;
                }
                int open = at + MARKER.length();
                String body = balanced(source, open);
                handlers++;
                check(body.contains(".execute("),
                        file.getFileName() + " has a DISCONNECT handler that does not defer to server.execute");
                from = open + body.length();
            }
        }
        check(handlers >= 5, "found only " + handlers + " DISCONNECT handlers in the sources");
        System.out.println("DisconnectHandlerTest passed (" + handlers + " handlers)");
    }

    /** The text from {@code open} up to the parenthesis that closes the call whose "(" precedes it. */
    private static String balanced(String source, int open) {
        int depth = 1;
        int i = open;
        while (i < source.length() && depth > 0) {
            char c = source.charAt(i++);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return source.substring(open, i);
    }

    private static String stripLineComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (String line : source.split("\n", -1)) {
            int slashes = line.indexOf("//");
            out.append(slashes >= 0 ? line.substring(0, slashes) : line).append('\n');
        }
        return out.toString();
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
