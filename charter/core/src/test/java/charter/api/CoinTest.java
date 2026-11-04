package charter.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static charter.Check.check;
import static charter.Check.eq;

/**
 * Coin is told in Gold Sovereigns (owner, 2026-11-04): the formatter, and a scan of the
 * player text (the lang file and the string literals of the Charter sources) that fails
 * on any amount named in Marks or Crowns. Server log lines keep Marks: they are the unit
 * in code, knobs and files.
 */
public final class CoinTest {

    private static final Pattern UNIT = Pattern.compile("\\b(Marks?|Crowns?)\\b");

    private CoinTest() {
    }

    public static void run() {
        formatter();
        playerText();
        typedAmounts();
    }

    /** What a player types or picks for a treasury amount is whole Sovereigns (owner, 2026-11-04). */
    static void typedAmounts() {
        Path base = null;
        for (String candidate : List.of("..", ".", "charter")) {
            if (Files.isDirectory(Path.of(candidate).resolve("fabric/src/main/java/charter/mc"))) {
                base = Path.of(candidate);
                break;
            }
        }
        check("typed coin: the Charter sources are found", base != null);
        if (base == null) return;
        try {
            String commands = Files.readString(base.resolve("fabric/src/main/java/charter/mc/cmd/CharterCommands.java"));
            String leader = Files.readString(base.resolve("fabric/src/main/java/charter/mc/book/LeaderPages.java"));
            check("typed coin: /charter vault withdraw <n> reads n as Sovereigns",
                    commands.contains("charter.api.Coin.marks(IntegerArgumentType.getInteger(ctx, \"amount\"))"));
            check("typed coin: the book's slider counts Sovereigns",
                    leader.contains("take(form.player(), (int) Coin.marks(amount.get()))")
                            && leader.contains("balance / Coin.MARKS_PER_SOVEREIGN"));
            check("typed coin: the book's take buttons are whole Sovereigns",
                    leader.contains("PRESETS = {Coin.marks(1), Coin.marks(10), Coin.marks(64)}"));
            check("typed coin: Charter still pays out through the operator commands in Marks",
                    Files.readString(base.resolve("fabric/src/main/java/charter/mc/Treasury.java"))
                            .contains("String tail = \" \\\"\" + org + \"\\\" \" + playerName + \" \" + marks;"));
        } catch (IOException e) {
            check("typed coin: the sources are readable: " + e, false);
        }
    }

    static void formatter() {
        eq("coin: whole Sovereigns", "2,261", Coin.sovereigns(144_704));
        eq("coin: one Sovereign", "1 Sovereign", Coin.words(64));
        eq("coin: a remainder is a decimal Sovereign", "2,261.5 Sovereigns", Coin.words(144_736));
        eq("coin: a quarter", "0.25 Sovereigns", Coin.words(16));
        eq("coin: the decimal is cut, never rounded up", "0.98", Coin.sovereigns(63));
        eq("coin: one Mark is still more than nothing", "0.01", Coin.sovereigns(1));
        eq("coin: a cut decimal drops its last zero", "1.5", Coin.sovereigns(96));
        eq("coin: large numbers group their digits", "1,234,567,890", Coin.sovereigns(64L * 1_234_567_890L));
        check("coin: the largest amount does not overflow", Coin.sovereigns(Long.MAX_VALUE).equals("144,115,188,075,855,871.98"));
        eq("coin: zero", "0 Sovereigns", Coin.words(0));
        eq("coin: below zero", "-1.5", Coin.sovereigns(-96));
        eq("coin: typed Sovereigns become Marks", 640L, Coin.marks(10));
        eq("coin: typed Sovereigns saturate", Long.MAX_VALUE, Coin.marks(Long.MAX_VALUE));
    }

    static void playerText() {
        Path base = null;
        for (String candidate : List.of("..", ".", "charter")) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p.resolve("fabric/src/main/java")) && Files.isDirectory(p.resolve("core/src/main/java"))) {
                base = p;
                break;
            }
        }
        check("coin scan: the Charter sources are found", base != null);
        if (base == null) return;
        List<String> found = new ArrayList<>();
        int scanned = 0;
        try {
            Path lang = base.resolve("fabric/src/main/resources/data/charter/lang/en_us.json");
            for (String line : Files.readAllLines(lang)) {
                if (line.contains("\"book.justice.cell_mark\"")) continue; // "Mark a cell": the verb, not the coin
                if (UNIT.matcher(line.contains("\":") ? line.substring(line.indexOf("\":")) : line).find()) found.add("en_us.json: " + line.trim());
            }
            for (String dir : List.of("fabric/src/main/java", "core/src/main/java")) {
                try (var files = Files.walk(base.resolve(dir))) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                        scanned++;
                        for (String statement : statements(Files.readString(file))) {
                            if (statement.contains("LOG.") || statement.contains("warn.accept(") || statement.contains("Exception(")) continue;
                            for (String literal : literals(statement)) {
                                if (UNIT.matcher(literal).find()) found.add(file.getFileName() + ": \"" + literal + "\"");
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            found.add("could not read the sources: " + e);
        }
        check("coin scan: read the sources (" + scanned + " files)", scanned > 100);
        check("coin scan: no player text names Marks or Crowns" + (found.isEmpty() ? "" : ": " + found), found.isEmpty());
        check("coin scan: catches a line that names Marks",
                literals(statements("x.tell(\"You paid 5 Marks.\");").get(0)).stream().anyMatch(l -> UNIT.matcher(l).find()));
        check("coin scan: skips comments", statements("// 5 Marks\n/* 2 Crowns */ int a = 1;").stream()
                .flatMap(s -> literals(s).stream()).noneMatch(l -> UNIT.matcher(l).find()));
    }

    /** The code cut at semicolons, with comments removed and string and char literals kept whole. */
    static List<String> statements(String code) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '/') {
                while (i < code.length() && code.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '*') {
                int end = code.indexOf("*/", i + 2);
                i = end < 0 ? code.length() : end + 2;
            } else if (c == '"' && code.startsWith("\"\"\"", i)) {
                int end = code.indexOf("\"\"\"", i + 3);
                int j = end < 0 ? code.length() : end + 3;
                cur.append(code, i, j);
                i = j;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < code.length() && code.charAt(j) != c) {
                    if (code.charAt(j) == '\\') j++;
                    j++;
                }
                j = Math.min(code.length(), j + 1);
                cur.append(code, i, j);
                i = j;
            } else if (c == ';') {
                out.add(cur.toString());
                cur.setLength(0);
                i++;
            } else {
                cur.append(c);
                i++;
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    /** The double quoted string literals in one statement. */
    static List<String> literals(String statement) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < statement.length()) {
            char c = statement.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < statement.length() && statement.charAt(j) != c) {
                    if (statement.charAt(j) == '\\') j++;
                    j++;
                }
                if (c == '"') out.add(statement.substring(i + 1, Math.min(j, statement.length())));
                i = j + 1;
            } else {
                i++;
            }
        }
        return out;
    }
}
