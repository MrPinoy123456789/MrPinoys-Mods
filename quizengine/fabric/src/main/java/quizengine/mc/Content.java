package quizengine.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import quizengine.Option;
import quizengine.Round;
import quizengine.RoundType;
import quizengine.ScoringConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Reads questions, prompts, scoring and timings from disk. Never writes them —
 * these files are hand-edited and hot-reloadable via {@code /quiz reload}.
 */
public final class Content {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Random RANDOM = new Random();

    private final Path dir;

    private List<QuestionEntry> trivia = List.of();
    private List<PromptEntry> prompts = List.of();
    private ScoringConfig scoring = ScoringConfig.defaults();
    private Timings timings = Timings.defaults();
    private Rewards rewards = Rewards.defaults();

    public Content(Path dir) {
        this.dir = dir;
    }

    // ---- file shapes ------------------------------------------------------

    /** {@code { "prompt": "...", "options": ["a","b","c","d"], "correct": 2 }} */
    public record QuestionEntry(String prompt, List<String> options, int correct) {}

    /** {@code { "prompt": "..." }} — Quiplash options are written by players. */
    public record PromptEntry(String prompt) {}

    private record TriviaFile(List<QuestionEntry> questions) {}

    private record PromptFile(List<PromptEntry> prompts) {}

    /**
     * Phase durations, in seconds. The engine has no concept of time; these belong
     * entirely to the orchestrator and can be changed without touching game rules.
     *
     * @param autoStartSeconds    seconds between automatic trivia rounds; 0 disables auto-start
     * @param afkThresholdSeconds a player idle (no movement or look change) for at least this
     *                            long doesn't count as "online" for auto-start purposes; if
     *                            every online player is past this, the round is skipped
     */
    public record Timings(
            int triviaSeconds,
            int quiplashSubmitSeconds,
            int quiplashClosedSeconds,
            int quiplashVoteSeconds,
            int autoStartSeconds,
            int afkThresholdSeconds
    ) {
        public static Timings defaults() {
            // Quiplash submit defaults to two hours. People are mining, not waiting.
            return new Timings(45, 7200, 60, 300, 1800, 300);
        }
    }

    /**
     * Item payouts, in counts. Additive in the same way points are: the writer of a
     * winning Quiplash answer collects the participation diamond as well as the block,
     * and a correct trivia answer keeps the participation diamond on top of the bonus.
     *
     * @param participationDiamonds diamonds for anyone who submitted at all (trivia or Quiplash)
     * @param correctDiamonds       diamonds for backing the winning Quiplash answer
     * @param winnerDiamondBlocks   diamond blocks for writing the winning Quiplash answer
     * @param triviaCorrectDiamonds diamonds for a correct trivia answer, on top of the
     *                              participation diamond
     */
    public record Rewards(
            int participationDiamonds,
            int correctDiamonds,
            int winnerDiamondBlocks,
            int triviaCorrectDiamonds
    ) {
        public static Rewards defaults() {
            return new Rewards(1, 3, 1, 2);
        }
    }

    // ---- loading ----------------------------------------------------------

    public void reload() {
        try {
            Files.createDirectories(dir);
            trivia = readOrCreate("trivia.json", TriviaFile.class,
                    new TriviaFile(sampleQuestions())).questions();
            prompts = readOrCreate("prompts.json", PromptFile.class,
                    new PromptFile(samplePrompts())).prompts();
            scoring = readOrCreate("scoring.json", ScoringConfig.class,
                    ScoringConfig.defaults());
            timings = readOrCreate("timings.json", Timings.class, Timings.defaults());
            rewards = readOrCreate("rewards.json", Rewards.class, Rewards.defaults());
        } catch (IOException e) {
            QuizMod.LOG.error("Failed to load content, keeping previous values", e);
        }
    }

    private <T> T readOrCreate(String name, Class<T> type, T fallback) throws IOException {
        Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(fallback, w);
            }
            QuizMod.LOG.info("Created default {}", name);
            return fallback;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T parsed = GSON.fromJson(r, type);
            return parsed != null ? parsed : fallback;
        }
    }

    // ---- round construction ----------------------------------------------

    public Round randomTrivia() {
        if (trivia.isEmpty()) {
            return null;
        }
        QuestionEntry q = trivia.get(RANDOM.nextInt(trivia.size()));
        return Round.trivia(newRoundId(RoundType.TRIVIA), q.prompt(),
                Option.of(q.options().toArray(new String[0])), q.correct(), scoring);
    }

    public Round randomQuiplash() {
        if (prompts.isEmpty()) {
            return null;
        }
        PromptEntry p = prompts.get(RANDOM.nextInt(prompts.size()));
        return Round.quiplash(newRoundId(RoundType.QUIPLASH), p.prompt(), scoring);
    }

    private static String newRoundId(RoundType type) {
        return type.name().toLowerCase() + "-" + System.currentTimeMillis();
    }

    public ScoringConfig scoring() {
        return scoring;
    }

    public Timings timings() {
        return timings;
    }

    public Rewards rewards() {
        return rewards;
    }

    public int triviaCount() {
        return trivia.size();
    }

    public int promptCount() {
        return prompts.size();
    }

    // ---- seed content -----------------------------------------------------

    private static List<QuestionEntry> sampleQuestions() {
        List<QuestionEntry> list = new ArrayList<>();
        list.add(new QuestionEntry("Which of these is a real place?",
                List.of("Atlantis", "El Dorado", "Timbuktu", "Shangri-La"), 2));
        list.add(new QuestionEntry("How many blocks tall is a full stack of scaffolding drop?",
                List.of("32", "64", "128", "256"), 1));
        list.add(new QuestionEntry("What do you get from smelting raw iron?",
                List.of("Iron nugget", "Iron ingot", "Iron block", "Iron dust"), 1));
        list.add(new QuestionEntry("How many hearts does a fully-healed player have?",
                List.of("5", "10", "15", "20"), 1));
        list.add(new QuestionEntry("Which mob drops ender pearls?",
                List.of("Blaze", "Ghast", "Enderman", "Shulker"), 2));
        list.add(new QuestionEntry("What tool is needed to mine obsidian efficiently?",
                List.of("Iron pickaxe", "Diamond pickaxe", "Golden pickaxe", "Stone pickaxe"), 1));
        list.add(new QuestionEntry("What does a totem of undying do?",
                List.of("Grants flight", "Prevents death once", "Doubles XP", "Heals over time"), 1));
        list.add(new QuestionEntry("Which biome do polar bears spawn in?",
                List.of("Desert", "Jungle", "Snowy tundra", "Savanna"), 2));
        list.add(new QuestionEntry("What fuel burns the longest in a furnace, item for item?",
                List.of("Coal", "Blaze rod", "Lava bucket", "Charcoal"), 2));
        list.add(new QuestionEntry("How many slots are in a standard chest?",
                List.of("18", "27", "36", "54"), 1));
        list.add(new QuestionEntry("What enchantment lets you breathe underwater?",
                List.of("Aqua Affinity", "Depth Strider", "Respiration", "Frost Walker"), 2));
        list.add(new QuestionEntry("Which dimension do you reach through a nether portal?",
                List.of("The End", "The Overworld", "The Nether", "The Aether"), 2));
        list.add(new QuestionEntry("What do you need to breed villagers?",
                List.of("Bread", "Emeralds", "Beds and food", "Diamonds"), 2));
        list.add(new QuestionEntry("Which mob explodes when provoked?",
                List.of("Zombie", "Creeper", "Skeleton", "Spider"), 1));
        list.add(new QuestionEntry("What's the maximum enchantment level shown at a bookshelf-surrounded table?",
                List.of("10", "20", "30", "50"), 2));
        list.add(new QuestionEntry("Which wood type is the darkest naturally?",
                List.of("Oak", "Spruce", "Dark Oak", "Birch"), 2));
        list.add(new QuestionEntry("What do you trade with villagers for?",
                List.of("Gold coins", "Emeralds", "Diamonds", "Iron ingots"), 1));
        list.add(new QuestionEntry("What happens if you throw a splash potion of harming at a zombie?",
                List.of("It heals it", "It does nothing", "It hurts it less", "It hurts it"), 3));
        list.add(new QuestionEntry("Which of these mobs can swim?",
                List.of("Dolphin", "Chicken", "Spider", "Bat"), 0));
        list.add(new QuestionEntry("What's the rarest ore that generates naturally in the Overworld?",
                List.of("Diamond", "Emerald", "Ancient Debris", "Lapis Lazuli"), 1));
        list.add(new QuestionEntry("How many eyes of ender are needed to activate a full end portal frame?",
                List.of("8", "10", "12", "16"), 2));
        list.add(new QuestionEntry("What do you get from shearing a sheep?",
                List.of("Leather", "Wool", "String", "Feathers"), 1));
        list.add(new QuestionEntry("Which potion effect makes you take fall damage as if you weighed less?",
                List.of("Levitation", "Slow Falling", "Jump Boost", "Feather Falling"), 1));
        list.add(new QuestionEntry("What material is required to make a beacon?",
                List.of("Iron block", "Gold block", "Diamond block", "Emerald block"), 1));
        list.add(new QuestionEntry("Which structure is most likely to contain a library with enchanted books?",
                List.of("Village", "Woodland Mansion", "Stronghold", "Desert Temple"), 2));
        list.add(new QuestionEntry("What do axolotls do to hostile underwater mobs?",
                List.of("Ignore them", "Attack them", "Flee from them", "Heal them"), 1));
        list.add(new QuestionEntry("How many experience levels does it take to reach the enchanting cap?",
                List.of("15", "20", "30", "50"), 2));
        return list;
    }

    private static List<PromptEntry> samplePrompts() {
        List<PromptEntry> list = new ArrayList<>();
        list.add(new PromptEntry("The worst name for a cruise ship"));
        list.add(new PromptEntry("Worst possible thing to hear from your pilot"));
        list.add(new PromptEntry("A terrible motto for a hospital"));
        list.add(new PromptEntry("The least reassuring thing to find in a cave"));
        return list;
    }
}