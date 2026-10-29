package lemon;

import java.nio.file.Files;
import java.nio.file.Path;

public final class IsolationTest {
    public static void main(String[] args) throws Exception {
        int handlers = 0;
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (source.contains("import pocketdungeons.") || source.contains("import charter.")) {
                    throw new AssertionError("Cross-mod import in " + file);
                }
                String marker = "DISCONNECT.register(";
                int from = 0;
                for (int at; (at = source.indexOf(marker, from)) >= 0;) {
                    int start = at + marker.length();
                    int depth = 1;
                    int end = start;
                    while (end < source.length() && depth > 0) {
                        char c = source.charAt(end++);
                        if (c == '(') depth++;
                        if (c == ')') depth--;
                    }
                    if (!source.substring(start, end).contains(".execute(")) {
                        throw new AssertionError("Disconnect work must defer to the server thread: " + file);
                    }
                    handlers++;
                    from = end;
                }
            }
        }
        if (handlers < 1) throw new AssertionError("Lemon must register its disconnect cleanup");
        System.out.println("IsolationTest passed");
    }
}
