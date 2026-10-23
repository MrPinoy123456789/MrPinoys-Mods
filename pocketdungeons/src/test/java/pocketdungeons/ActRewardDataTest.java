package pocketdungeons;

import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;


/**
 * Spec 6.5, pure half: the six {@code act_reward} files load through the
 * real parser for acts 0 to 5, every window is 6 rows of 8 valid letters
 * (spec 5.3), act 0 pays nothing, acts 1 to 5 carry a title, a disc and a
 * decor bundle of at most 64 items, and act 1 grants the {@code old_mine}
 * shell. No server and no bootstrap: the parser is pure Gson plus the
 * {@link ChatFormatting} enum.
 */
public class ActRewardDataTest {

    private static final Path DIR = Path.of("src/main/resources/data/pocketdungeons/act_reward");

    public static void main(String[] args) throws Exception {
        List<String> colours = List.of("gray", "white", "aqua", "dark_aqua", "red", "light_purple");
        for (int n = 0; n <= 5; n++) {
            Path file = DIR.resolve("act_" + n + ".json");
            check(Files.exists(file), true, "act_" + n + ".json exists");
            ActReward.Act act;
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                act = ActReward.fromJson(JsonParser.parseReader(r).getAsJsonObject(), "act_" + n);
            }
            check(act.act(), n, "act_" + n + " declares act " + n);
            check(act.colour(), colours.get(n), "act_" + n + " colour");
            check(act.story().size(), 2, "act_" + n + " has two story lines");
            check(act.window().size(), 6, "act_" + n + " window has 6 rows");
            for (String row : act.window()) {
                check(row.length(), 8, "act_" + n + " window row has 8 letters");
                for (char c : row.toCharArray()) {
                    check(ActReward.isWindowLetter(c), true,
                            "act_" + n + " window letter " + c);
                }
            }
            int weight = act.rewards().decor().stream().mapToInt(ActReward.DecorItem::count).sum();
            check(weight <= 64, true, "act_" + n + " decor fits a bundle");
        }
        ActReward.Act zero = parse(0);
        check(zero.rewards().title() == null && zero.rewards().disc() == null
                && zero.rewards().decor().isEmpty() && zero.rewards().shell() == null,
                true, "act 0 pays nothing");
        for (int n = 1; n <= 5; n++) {
            ActReward.Act act = parse(n);
            check(act.rewards().title() != null, true, "act_" + n + " has a title");
            check(act.rewards().disc() != null, true, "act_" + n + " has a disc");
            check(act.rewards().decor().isEmpty(), false, "act_" + n + " has decor");
        }
        check(parse(1).rewards().shell(), "old_mine", "act 1 grants the old_mine shell");
        System.out.println("ActRewardDataTest passed");
    }

    private static ActReward.Act parse(int n) throws Exception {
        try (Reader r = Files.newBufferedReader(DIR.resolve("act_" + n + ".json"),
                StandardCharsets.UTF_8)) {
            return ActReward.fromJson(JsonParser.parseReader(r).getAsJsonObject(), "act_" + n);
        }
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
        }
    }
}
