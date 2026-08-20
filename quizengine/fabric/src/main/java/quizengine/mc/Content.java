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

    private volatile Snapshot snapshot = new Snapshot(
            List.of(), List.of(), ScoringConfig.defaults(), Timings.defaults(), Rewards.defaults());

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

    private record Snapshot(
            List<QuestionEntry> trivia,
            List<PromptEntry> prompts,
            ScoringConfig scoring,
            Timings timings,
            Rewards rewards) {}

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

    /** Reloads all hot-editable content registries and creates defaults for missing files. */
    public void reload() {
        try {
            Files.createDirectories(dir);
            TriviaFile triviaFile = readOrCreate("trivia.json", TriviaFile.class,
                    new TriviaFile(sampleQuestions()));
            PromptFile promptFile = readOrCreate("prompts.json", PromptFile.class,
                    new PromptFile(samplePrompts()));
            ScoringConfig nextScoring = readOrCreate("scoring.json", ScoringConfig.class,
                    ScoringConfig.defaults());
            Timings nextTimings = readOrCreate("timings.json", Timings.class, Timings.defaults());
            Rewards nextRewards = readOrCreate("rewards.json", Rewards.class, Rewards.defaults());

            Snapshot next = validate(triviaFile, promptFile, nextScoring, nextTimings, nextRewards);
            snapshot = next;
        } catch (IOException | RuntimeException e) {
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
            if (parsed == null) {
                throw new IllegalArgumentException(name + " is empty");
            }
            return parsed;
        }
    }

    private static Snapshot validate(TriviaFile triviaFile, PromptFile promptFile,
                                     ScoringConfig scoring, Timings timings, Rewards rewards) {
        if (triviaFile == null || triviaFile.questions() == null) {
            throw new IllegalArgumentException("trivia.json must contain a questions array");
        }
        for (QuestionEntry question : triviaFile.questions()) {
            if (question == null || question.prompt() == null || question.prompt().isBlank()
                    || question.options() == null || question.options().size() < 2
                    || question.options().stream().anyMatch(option -> option == null || option.isBlank())
                    || question.correct() < 0 || question.correct() >= question.options().size()) {
                throw new IllegalArgumentException("trivia.json contains an invalid question");
            }
        }
        if (promptFile == null || promptFile.prompts() == null
                || promptFile.prompts().stream().anyMatch(prompt -> prompt == null
                        || prompt.prompt() == null || prompt.prompt().isBlank())) {
            throw new IllegalArgumentException("prompts.json contains an invalid prompt");
        }
        if (scoring == null || scoring.participation() < 0 || scoring.triviaCorrect() < 0
                || scoring.writerBase() < 0 || scoring.perVote() < 0 || scoring.voterBase() < 0
                || scoring.voterPool() < 0 || scoring.quorumOptions() < 1) {
            throw new IllegalArgumentException("scoring.json contains an invalid value");
        }
        if (timings == null || timings.triviaSeconds() < 1
                || timings.quiplashSubmitSeconds() < 1 || timings.quiplashClosedSeconds() < 1
                || timings.quiplashVoteSeconds() < 1 || timings.autoStartSeconds() < 0
                || timings.afkThresholdSeconds() < 0) {
            throw new IllegalArgumentException("timings.json contains an invalid value");
        }
        if (rewards == null || rewards.participationDiamonds() < 0
                || rewards.correctDiamonds() < 0 || rewards.winnerDiamondBlocks() < 0
                || rewards.triviaCorrectDiamonds() < 0) {
            throw new IllegalArgumentException("rewards.json contains an invalid value");
        }
        return new Snapshot(
                List.copyOf(triviaFile.questions()), List.copyOf(promptFile.prompts()),
                scoring, timings, rewards);
    }

    // ---- round construction ----------------------------------------------

    public Round randomTrivia() {
        Snapshot current = snapshot;
        if (current.trivia().isEmpty()) {
            return null;
        }
        QuestionEntry q = current.trivia().get(RANDOM.nextInt(current.trivia().size()));
        return Round.trivia(newRoundId(RoundType.TRIVIA), q.prompt(),
                Option.of(q.options().toArray(new String[0])), q.correct(), current.scoring());
    }

    public Round randomQuiplash() {
        Snapshot current = snapshot;
        if (current.prompts().isEmpty()) {
            return null;
        }
        PromptEntry p = current.prompts().get(RANDOM.nextInt(current.prompts().size()));
        return Round.quiplash(newRoundId(RoundType.QUIPLASH), p.prompt(), current.scoring());
    }

    private static String newRoundId(RoundType type) {
        return type.name().toLowerCase() + "-" + System.currentTimeMillis();
    }

    public ScoringConfig scoring() {
        return snapshot.scoring();
    }

    public Timings timings() {
        return snapshot.timings();
    }

    public Rewards rewards() {
        return snapshot.rewards();
    }

    public int triviaCount() {
        return snapshot.trivia().size();
    }

    public int promptCount() {
        return snapshot.prompts().size();
    }

    // ---- seed content -----------------------------------------------------

    private static List<QuestionEntry> sampleQuestions() {
    List<QuestionEntry> list = new ArrayList<>();

    list.add(new QuestionEntry("How many wooden planks are needed to craft a crafting table?",
        List.of("2", "4", "6", "8"), 1));
    list.add(new QuestionEntry("How many planks does a single log produce?",
        List.of("1", "2", "4", "6"), 2));
    list.add(new QuestionEntry("What is the minimum pickaxe tier required to mine diamond ore?",
        List.of("Wooden", "Stone", "Iron", "Diamond"), 2));
    list.add(new QuestionEntry("Which mob explodes when it gets close to a player?",
        List.of("Zombie", "Creeper", "Skeleton", "Spider"), 1));
    list.add(new QuestionEntry("What is the minimum number of obsidian blocks needed for a working Nether portal?",
        List.of("8", "10", "12", "14"), 1));
    list.add(new QuestionEntry("Which item is used to breed cows?",
        List.of("Carrots", "Wheat", "Seeds", "Beetroot"), 1));
    list.add(new QuestionEntry("What item tames a wolf?",
        List.of("Bone", "Raw beef", "Wheat", "Stick"), 0));
    list.add(new QuestionEntry("What item tames an ocelot into trusting you?",
        List.of("Raw cod", "Cooked chicken", "Bone", "Wheat"), 0));
    list.add(new QuestionEntry("Which job site block turns a villager into a librarian?",
        List.of("Bookshelf", "Lectern", "Cartography table", "Enchanting table"), 1));
    list.add(new QuestionEntry("Which job site block turns a villager into a fletcher?",
        List.of("Loom", "Fletching table", "Smithing table", "Barrel"), 1));
    list.add(new QuestionEntry("What is the maximum enchantment level available at an enchanting table?",
        List.of("20", "25", "30", "50"), 2));
    list.add(new QuestionEntry("How many bookshelves are required for maximum enchanting power?",
        List.of("12", "14", "15", "16"), 2));
    list.add(new QuestionEntry("Which fuel smelts the most items per unit in a furnace?",
        List.of("Coal block", "Lava bucket", "Blaze rod", "Dried kelp block"), 1));
    list.add(new QuestionEntry("How many items can one piece of coal smelt?",
        List.of("4", "6", "8", "10"), 2));
    list.add(new QuestionEntry("How far does a redstone signal travel before it needs a repeater?",
        List.of("8 blocks", "12 blocks", "15 blocks", "16 blocks"), 2));
    list.add(new QuestionEntry("What is the maximum delay setting on a redstone repeater?",
        List.of("2 ticks", "3 ticks", "4 ticks", "5 ticks"), 2));
    list.add(new QuestionEntry("Which item is required to craft a redstone comparator?",
        List.of("Nether quartz", "Diamond", "Lapis lazuli", "Amethyst shard"), 0));
    list.add(new QuestionEntry("What does an observer detect?",
        List.of("Player movement", "Block state changes in front of it", "Redstone signals only", "Mob spawns"), 1));
    list.add(new QuestionEntry("What is the maximum build height in Java Edition 1.21?",
        List.of("256", "300", "320", "384"), 2));
    list.add(new QuestionEntry("What is the lowest Y level in the Overworld in modern versions?",
        List.of("0", "-32", "-64", "-128"), 2));
    list.add(new QuestionEntry("Which ingredient is the base of nearly every brewed potion?",
        List.of("Blaze powder", "Nether wart", "Glowstone dust", "Redstone dust"), 1));
    list.add(new QuestionEntry("Adding a fermented spider eye to a Potion of Swiftness creates what?",
        List.of("Potion of Weakness", "Potion of Slowness", "Potion of Harming", "Potion of Poison"), 1));
    list.add(new QuestionEntry("What does glowstone dust do to a potion?",
        List.of("Extends its duration", "Increases its potency", "Makes it splash", "Removes its effect"), 1));
    list.add(new QuestionEntry("What does redstone dust do to a potion?",
        List.of("Extends its duration", "Increases its potency", "Makes it lingering", "Adds glowing"), 0));
    list.add(new QuestionEntry("Which item turns a normal potion into a splash potion?",
        List.of("Gunpowder", "Dragon breath", "Blaze powder", "Sugar"), 0));
    list.add(new QuestionEntry("Which item creates a lingering potion?",
        List.of("Gunpowder", "Dragon breath", "Phantom membrane", "Glowstone"), 1));
    list.add(new QuestionEntry("Which mob drops ender pearls?",
        List.of("Endermite", "Enderman", "Shulker", "Blaze"), 1));
    list.add(new QuestionEntry("How many eyes of ender are needed to activate an End portal?",
        List.of("8", "10", "12", "16"), 2));
    list.add(new QuestionEntry("An eye of ender is crafted from an ender pearl and what?",
        List.of("Blaze powder", "Ghast tear", "Gunpowder", "Nether quartz"), 0));
    list.add(new QuestionEntry("Blaze powder is crafted from which item?",
        List.of("Magma cream", "Blaze rod", "Fire charge", "Nether wart"), 1));
    list.add(new QuestionEntry("Where do blazes naturally spawn?",
        List.of("Bastion remnants", "Nether fortresses", "Soul sand valleys", "Basalt deltas"), 1));
    list.add(new QuestionEntry("How many wither skeleton skulls are needed to summon the Wither?",
        List.of("2", "3", "4", "5"), 1));
    list.add(new QuestionEntry("How many soul sand blocks are used in the Wither summoning structure?",
        List.of("3", "4", "5", "6"), 1));
    list.add(new QuestionEntry("How many end crystals are needed to respawn the Ender Dragon?",
        List.of("2", "4", "6", "8"), 1));
    list.add(new QuestionEntry("Which items craft an end crystal?",
        List.of("Glass, ghast tear, eye of ender", "Glass, blaze powder, ender pearl", "Obsidian, glass, nether star", "Glass, amethyst, prismarine"), 0));
    list.add(new QuestionEntry("Where are elytra found?",
        List.of("Ancient cities", "End city ships", "Woodland mansions", "Bastion remnants"), 1));
    list.add(new QuestionEntry("Which item repairs elytra in an anvil?",
        List.of("Leather", "Phantom membrane", "Feather", "Rabbit hide"), 1));
    list.add(new QuestionEntry("Which mob drops phantom membrane?",
        List.of("Bat", "Phantom", "Ghast", "Vex"), 1));
    list.add(new QuestionEntry("Which mob spawns if a player has not slept for several in-game days?",
        List.of("Vex", "Phantom", "Wither skeleton", "Silverfish"), 1));
    list.add(new QuestionEntry("Which mob can drop a trident?",
        List.of("Guardian", "Drowned", "Elder guardian", "Dolphin"), 1));
    list.add(new QuestionEntry("Which trident enchantment summons lightning on a hit mob during a thunderstorm?",
        List.of("Loyalty", "Riptide", "Channeling", "Impaling"), 2));
    list.add(new QuestionEntry("Which trident enchantment launches the player through water or rain?",
        List.of("Riptide", "Loyalty", "Channeling", "Depth Strider"), 0));
    list.add(new QuestionEntry("Riptide is incompatible with which enchantment?",
        List.of("Impaling", "Loyalty", "Unbreaking", "Mending"), 1));
    list.add(new QuestionEntry("What is the maximum level of Depth Strider?",
        List.of("2", "3", "4", "5"), 1));
    list.add(new QuestionEntry("What is the maximum level of Efficiency?",
        List.of("3", "4", "5", "6"), 2));
    list.add(new QuestionEntry("What is the maximum level of Fortune?",
        List.of("2", "3", "4", "5"), 1));
    list.add(new QuestionEntry("What is the maximum level of Sharpness in survival?",
        List.of("3", "4", "5", "6"), 2));
    list.add(new QuestionEntry("What does the Mending enchantment do?",
        List.of("Repairs items using experience orbs", "Doubles item durability", "Prevents item loss on death", "Repairs items over time automatically"), 0));
    list.add(new QuestionEntry("What does Curse of Vanishing do?",
        List.of("Makes the item invisible", "Destroys the item when the player dies", "Prevents enchanting", "Reduces durability"), 1));
    list.add(new QuestionEntry("Which sword enchantment deals extra damage to undead mobs?",
        List.of("Sharpness", "Smite", "Bane of Arthropods", "Looting"), 1));
    list.add(new QuestionEntry("Bane of Arthropods is most effective against which group?",
        List.of("Zombies", "Spiders and silverfish", "Skeletons", "Illagers"), 1));
    list.add(new QuestionEntry("Which block can a piston NOT push?",
        List.of("Sand", "Obsidian", "Wool", "Glass"), 1));
    list.add(new QuestionEntry("What is the maximum number of blocks a piston can push?",
        List.of("8", "10", "12", "16"), 2));
    list.add(new QuestionEntry("Which block does not stick to a slime block when pushed?",
        List.of("Honey block", "Stone", "Wool", "Dirt"), 0));
    list.add(new QuestionEntry("What happens when you try to sleep in a bed in the Nether?",
        List.of("You sleep normally", "It explodes", "Nothing happens", "You take poison damage"), 1));
    list.add(new QuestionEntry("What happens to water placed in the Nether?",
        List.of("It flows normally", "It evaporates instantly", "It turns to ice", "It becomes lava"), 1));
    list.add(new QuestionEntry("Which of these is NOT a Nether biome?",
        List.of("Crimson Forest", "Warped Forest", "Basalt Deltas", "Cherry Grove"), 3));
    list.add(new QuestionEntry("In which biome do pink-blossomed cherry trees generate?",
        List.of("Flower Forest", "Cherry Grove", "Meadow", "Birch Forest"), 1));
    list.add(new QuestionEntry("A sniffer is hatched from an egg found in which block?",
        List.of("Suspicious gravel", "Suspicious sand", "Dripstone", "Moss block"), 1));
    list.add(new QuestionEntry("What does a sniffer dig up?",
        List.of("Ancient seeds like torchflower seeds", "Diamonds", "Emeralds", "Bones"), 0));
    list.add(new QuestionEntry("Which tool is used for archaeology?",
        List.of("Shears", "Brush", "Hoe", "Shovel"), 1));
    list.add(new QuestionEntry("Which structure is associated with sniffer eggs and pottery sherds?",
        List.of("Trail ruins", "Igloo", "Desert well", "Ruined portal"), 0));
    list.add(new QuestionEntry("Armadillo scutes are used to craft what?",
        List.of("Wolf armor", "Turtle helmet", "Horse armor", "Shields"), 0));
    list.add(new QuestionEntry("How do you obtain armadillo scutes without harming the mob?",
        List.of("Shear the armadillo", "Wait for it to shed naturally or brush it", "Feed it spider eyes", "Trade with a villager"), 1));
    list.add(new QuestionEntry("The mace is crafted from a heavy core and which item?",
        List.of("Breeze rod", "Blaze rod", "Netherite ingot", "Echo shard"), 0));
    list.add(new QuestionEntry("Where is the heavy core found?",
        List.of("Ancient cities", "Ominous vaults in trial chambers", "End cities", "Woodland mansions"), 1));
    list.add(new QuestionEntry("The mace deals more damage based on what?",
        List.of("Player health", "Fall distance", "Time of day", "Armor worn"), 1));
    list.add(new QuestionEntry("Which mob spawns from trial spawners in trial chambers and shoots wind charges?",
        List.of("Breeze", "Bogged", "Vex", "Evoker"), 0));
    list.add(new QuestionEntry("Which skeleton variant shoots poison-tipped arrows?",
        List.of("Stray", "Bogged", "Wither skeleton", "Skeleton horseman"), 1));
    list.add(new QuestionEntry("Which block added in 1.21 automatically crafts items using redstone?",
        List.of("Dispenser", "Crafter", "Dropper", "Smoker"), 1));
    list.add(new QuestionEntry("What does drinking an ominous bottle give the player?",
        List.of("Hero of the Village", "Bad Omen", "Luck", "Resistance"), 1));
    list.add(new QuestionEntry("What happens when a player with Bad Omen enters a village?",
        List.of("A raid begins", "Villagers flee", "Trades get cheaper", "An iron golem spawns"), 0));
    list.add(new QuestionEntry("Which mob drops the Totem of Undying?",
        List.of("Vindicator", "Evoker", "Pillager", "Ravager"), 1));
    list.add(new QuestionEntry("Which mobs does an evoker summon to attack?",
        List.of("Vexes", "Silverfish", "Endermites", "Bats"), 0));
    list.add(new QuestionEntry("Which large illager mob appears during raids and charges at players?",
        List.of("Ravager", "Ravenger", "Brute", "Warden"), 0));
    list.add(new QuestionEntry("How is an allay duplicated?",
        List.of("Feeding it cake", "Giving it an amethyst shard while it dances near a jukebox", "Breeding two allays", "Using an egg"), 1));
    list.add(new QuestionEntry("Which block inside an amethyst geode grows amethyst clusters?",
        List.of("Amethyst block", "Budding amethyst", "Calcite", "Smooth basalt"), 1));
    list.add(new QuestionEntry("A spyglass is crafted from an amethyst shard and which material?",
        List.of("Iron ingots", "Copper ingots", "Gold ingots", "Glass"), 1));
    list.add(new QuestionEntry("What prevents copper from oxidizing further?",
        List.of("Waxing with honeycomb", "Painting it", "Placing it underwater", "Enchanting it"), 0));
    list.add(new QuestionEntry("Which metal is used to craft a lightning rod?",
        List.of("Iron", "Copper", "Gold", "Netherite"), 1));
    list.add(new QuestionEntry("Which stone type replaces normal stone below roughly Y=0?",
        List.of("Andesite", "Deepslate", "Tuff", "Blackstone"), 1));
    list.add(new QuestionEntry("Ancient debris is smelted into what?",
        List.of("Netherite ingot", "Netherite scrap", "Netherite block", "Nether brick"), 1));
    list.add(new QuestionEntry("A netherite ingot requires four netherite scrap and what else?",
        List.of("4 gold ingots", "4 iron ingots", "4 diamonds", "1 diamond"), 0));
    list.add(new QuestionEntry("What is required to upgrade diamond gear to netherite?",
        List.of("An anvil only", "A smithing table and a netherite upgrade template", "A crafting table", "An enchanting table"), 1));
    list.add(new QuestionEntry("What happens to netherite items dropped in lava?",
        List.of("They burn up", "They float and survive", "They convert to gold", "They teleport away"), 1));
    list.add(new QuestionEntry("What item do piglins accept in bartering?",
        List.of("Gold ingots", "Iron ingots", "Emeralds", "Diamonds"), 0));
    list.add(new QuestionEntry("What happens to a piglin brought into the Overworld?",
        List.of("It becomes friendly", "It becomes a zombified piglin", "It despawns instantly", "It becomes a villager"), 1));
    list.add(new QuestionEntry("Which fungus scares away hoglins?",
        List.of("Crimson fungus", "Warped fungus", "Brown mushroom", "Red mushroom"), 1));
    list.add(new QuestionEntry("What is used to steer a strider?",
        List.of("Carrot on a stick", "Warped fungus on a stick", "Reins", "Lead"), 1));
    list.add(new QuestionEntry("Which mob walks on top of lava?",
        List.of("Magma cube", "Strider", "Ghast", "Blaze"), 1));
    list.add(new QuestionEntry("Which item does a ghast drop that is used in potion brewing?",
        List.of("Ghast tear", "Gunpowder", "Magma cream", "Blaze powder"), 0));
    list.add(new QuestionEntry("Which mob found in end cities can teleport you upward with levitation?",
        List.of("Shulker", "Endermite", "Phantom", "Vex"), 0));
    list.add(new QuestionEntry("What does eating a chorus fruit do?",
        List.of("Restores full hunger", "Teleports the player randomly", "Grants levitation", "Cures poison"), 1));
    list.add(new QuestionEntry("Popped chorus fruit is used to craft which block?",
        List.of("End stone", "Purpur blocks", "Obsidian", "Shulker box"), 1));
    list.add(new QuestionEntry("What happens when you punch a dragon egg?",
        List.of("It hatches", "It teleports away", "It explodes", "It drops as an item"), 1));
    list.add(new QuestionEntry("How many obsidian pillars surround the central island in the End?",
        List.of("8", "10", "12", "16"), 1));
    list.add(new QuestionEntry("How many levels does a full-size beacon pyramid have?",
        List.of("3", "4", "5", "6"), 1));
    list.add(new QuestionEntry("How many iron blocks are needed for a full four-level beacon pyramid?",
        List.of("144", "150", "164", "180"), 2));
    list.add(new QuestionEntry("Which items craft a conduit?",
        List.of("8 nautilus shells and a heart of the sea", "8 prismarine shards and a diamond", "8 scutes and a pearl", "8 shells and a nether star"), 0));
    list.add(new QuestionEntry("Where is a heart of the sea typically found?",
        List.of("Ocean monuments", "Buried treasure chests", "Shipwrecks", "Ruined portals"), 1));
    list.add(new QuestionEntry("Which mob drops prismarine shards?",
        List.of("Drowned", "Guardian", "Squid", "Dolphin"), 1));
    list.add(new QuestionEntry("What effect does an elder guardian inflict on nearby players?",
        List.of("Mining Fatigue", "Blindness", "Nausea", "Weakness"), 0));
    list.add(new QuestionEntry("What effect does wearing a turtle shell helmet grant out of water?",
        List.of("Night Vision", "Water Breathing", "Dolphins Grace", "Conduit Power"), 1));
    list.add(new QuestionEntry("Where do baby turtles drop scutes?",
        List.of("When they hatch", "When they grow into adults", "When sheared", "When fed seagrass"), 1));
    list.add(new QuestionEntry("Swimming near dolphins grants which effect?",
        List.of("Dolphins Grace", "Speed", "Water Breathing", "Conduit Power"), 0));
    list.add(new QuestionEntry("How many common axolotl colors are there, excluding the rare one?",
        List.of("2", "3", "4", "5"), 2));
    list.add(new QuestionEntry("Which axolotl color is the rarest?",
        List.of("Blue", "Gold", "Cyan", "Brown"), 0));
    list.add(new QuestionEntry("What does a glow squid drop?",
        List.of("Ink sac", "Glow ink sac", "Glowstone dust", "Prismarine"), 1));
    list.add(new QuestionEntry("How do you obtain froglight blocks?",
        List.of("Mining them in caves", "A frog eating a small magma cube", "Crafting glowstone and slime", "Trading with a villager"), 1));
    list.add(new QuestionEntry("Which block is created by combining mud and wheat?",
        List.of("Packed mud", "Mud bricks", "Clay", "Coarse dirt"), 0));
    list.add(new QuestionEntry("How do you obtain a goat horn?",
        List.of("Shearing a goat", "A goat ramming a solid block", "Breeding goats", "Killing a goat"), 1));
    list.add(new QuestionEntry("Which block detects vibrations and outputs a redstone signal?",
        List.of("Observer", "Sculk sensor", "Target block", "Tripwire hook"), 1));
    list.add(new QuestionEntry("Which block summons the Warden after being activated repeatedly?",
        List.of("Sculk catalyst", "Sculk shrieker", "Sculk sensor", "Sculk vein"), 1));
    list.add(new QuestionEntry("How does the Warden primarily locate players?",
        List.of("Sight", "Vibrations and smell", "Heat", "Redstone signals"), 1));
    list.add(new QuestionEntry("Which mob has the most health of any mob in Java Edition?",
        List.of("Ender Dragon", "Wither", "Warden", "Iron Golem"), 2));
    list.add(new QuestionEntry("Which item is crafted from echo shards?",
        List.of("Recovery compass", "Spyglass", "Lodestone", "Clock"), 0));
    list.add(new QuestionEntry("Which block spreads sculk when mobs die near it?",
        List.of("Sculk catalyst", "Sculk shrieker", "Sculk vein", "Deepslate"), 0));
    list.add(new QuestionEntry("Which structure contains the deep dark biome and ancient city ruins?",
        List.of("Stronghold", "Ancient city", "Mineshaft", "Dungeon"), 1));
    list.add(new QuestionEntry("How many gold ingots are required to craft a golden apple?",
        List.of("4", "6", "8", "9"), 2));
    list.add(new QuestionEntry("How many gunpowder are needed to craft one TNT?",
        List.of("3", "4", "5", "6"), 2));
    list.add(new QuestionEntry("Which two ingredients craft an ender chest?",
        List.of("Obsidian and an eye of ender", "Obsidian and an ender pearl", "Blackstone and a pearl", "Obsidian and a nether star"), 0));
    list.add(new QuestionEntry("How many iron ingots total are needed for an anvil?",
        List.of("24", "27", "31", "36"), 2));
    list.add(new QuestionEntry("Which items craft an enchanting table?",
        List.of("Obsidian, diamonds, book", "Obsidian, emeralds, book", "Stone, diamonds, paper", "Obsidian, lapis, book"), 0));
    list.add(new QuestionEntry("Which block is used to change a banner pattern?",
        List.of("Loom", "Cartography table", "Stonecutter", "Smithing table"), 0));
    list.add(new QuestionEntry("What does a grindstone do to an item?",
        List.of("Adds enchantments", "Removes enchantments and returns some XP", "Repairs durability only", "Renames items"), 1));
    list.add(new QuestionEntry("Which block lets you rename items?",
        List.of("Grindstone", "Anvil", "Smithing table", "Cartography table"), 1));
    list.add(new QuestionEntry("Which mob can be traded with using emeralds?",
        List.of("Villager", "Zombie", "Pillager", "Vex"), 0));
    list.add(new QuestionEntry("Which villager profession buys rotten flesh?",
        List.of("Farmer", "Cleric", "Butcher", "Toolsmith"), 1));
    list.add(new QuestionEntry("What builds an iron golem in a village?",
        List.of("Enough villagers and beds", "A single villager", "Placing iron blocks", "Killing a pillager"), 0));
    list.add(new QuestionEntry("Which items are used to construct an iron golem manually?",
        List.of("4 iron blocks and a carved pumpkin", "3 iron blocks and a pumpkin", "4 iron ingots and a jack o lantern", "9 iron blocks and a head"), 0));
    list.add(new QuestionEntry("Which mob is created with snow blocks and a carved pumpkin?",
        List.of("Snow golem", "Iron golem", "Wither", "Polar bear"), 0));
    list.add(new QuestionEntry("What happens to a snow golem in a desert biome?",
        List.of("It survives fine", "It takes damage from heat", "It turns into an iron golem", "It becomes hostile"), 1));
    list.add(new QuestionEntry("Which food restores the most hunger points in a single eat?",
        List.of("Cookie", "Apple", "Bread", "Cooked porkchop"), 3));
    list.add(new QuestionEntry("Which food has the highest saturation value in the game?",
        List.of("Golden carrot", "Steak", "Bread", "Melon slice"), 0));
    list.add(new QuestionEntry("Which food can inflict Poison when eaten?",
        List.of("Bread", "Pufferfish", "Carrot", "Cooked cod"), 1));
    list.add(new QuestionEntry("What happens when a zombie villager is cured?",
        List.of("It becomes a villager with discounted trades", "It despawns", "It becomes an iron golem", "Nothing changes"), 0));
    list.add(new QuestionEntry("Which two items are used to cure a zombie villager?",
        List.of("Splash Potion of Weakness and a golden apple", "Milk and bread", "Potion of Healing and an apple", "Weakness and an enchanted book"), 0));
    list.add(new QuestionEntry("Which block is required to make a fully working brewing stand?",
        List.of("Blaze rod and cobblestone", "Blaze powder and stone", "Nether wart and glass", "Quartz and cobblestone"), 0));
    list.add(new QuestionEntry("Who originally created Minecraft?",
        List.of("Markus Persson", "Jens Bergensten", "Gabe Newell", "Shigeru Miyamoto"), 0));
    list.add(new QuestionEntry("Which company acquired Mojang in 2014?",
        List.of("Sony", "Microsoft", "Nintendo", "Valve"), 1));
    list.add(new QuestionEntry("What was Minecraft originally called during its earliest development?",
        List.of("Cave Game", "Block World", "Dirt Sim", "Craft Quest"), 0));
    list.add(new QuestionEntry("Which of these has Mojang confirmed has never existed in vanilla Minecraft?",
        List.of("Herobrine", "The Wither", "The Warden", "The Illusioner"), 0));
    list.add(new QuestionEntry("The creeper design is famously the result of what?",
        List.of("A modeling mistake while making a pig", "A community contest", "A scrapped cow model", "A leftover from another game"), 0));
    list.add(new QuestionEntry("What was the zombified piglin called before the Nether Update?",
        List.of("Zombie Pigman", "Pig Zombie", "Nether Zombie", "Hoglin Zombie"), 0));
    list.add(new QuestionEntry("Which terrain glitch existed in very old versions at extreme coordinates?",
        List.of("The Far Lands", "The Void Edge", "The Null Zone", "The Deep End"), 0));
    list.add(new QuestionEntry("Which update was nicknamed the Adventure Update?",
        List.of("Beta 1.8", "Beta 1.5", "Alpha 1.2", "Release 1.3"), 0));
    list.add(new QuestionEntry("Which version is known as the Combat Update?",
        List.of("1.8", "1.9", "1.10", "1.11"), 1));
    list.add(new QuestionEntry("Which version is known as Update Aquatic?",
        List.of("1.12", "1.13", "1.14", "1.15"), 1));
    list.add(new QuestionEntry("Which version is the Nether Update?",
        List.of("1.15", "1.16", "1.17", "1.18"), 1));
    list.add(new QuestionEntry("Which version is Caves and Cliffs Part I?",
        List.of("1.16", "1.17", "1.18", "1.19"), 1));
    list.add(new QuestionEntry("Which version brought the new world generation of Caves and Cliffs Part II?",
        List.of("1.17", "1.18", "1.19", "1.20"), 1));
    list.add(new QuestionEntry("Which version is The Wild Update?",
        List.of("1.18", "1.19", "1.20", "1.21"), 1));
    list.add(new QuestionEntry("Which version is Trails and Tales?",
        List.of("1.19", "1.20", "1.21", "1.22"), 1));
    list.add(new QuestionEntry("Which version is Tricky Trials?",
        List.of("1.19", "1.20", "1.21", "1.18"), 2));
    list.add(new QuestionEntry("Which mob won the 2021 mob vote?",
        List.of("Glow Squid", "Copper Golem", "Moobloom", "Iceologer"), 0));
    list.add(new QuestionEntry("Which mob won the 2022 mob vote?",
        List.of("Allay", "Sniffer", "Rascal", "Tuff Golem"), 0));
    list.add(new QuestionEntry("Which mob won the 2023 mob vote?",
        List.of("Crab", "Penguin", "Armadillo", "Rascal"), 2));
    list.add(new QuestionEntry("Which mob lost the 2022 mob vote alongside the Rascal?",
        List.of("Tuff Golem", "Iceologer", "Moobloom", "Penguin"), 0));
    list.add(new QuestionEntry("Which mob was voted in at MINECON 2017 and later became the phantom?",
        List.of("Monster of the Night Skies", "Great Hunger", "Hovering Inferno", "Wildfire"), 0));
    list.add(new QuestionEntry("Who composed most of the original Minecraft soundtrack?",
        List.of("C418", "Lena Raine", "Kumi Tanioka", "Aaron Cherof"), 0));
    list.add(new QuestionEntry("What is the name of the first official Minecraft soundtrack album?",
        List.of("Volume Alpha", "Volume One", "Blocks", "Minecraft OST"), 0));
    list.add(new QuestionEntry("Which composer wrote the track Otherside and much of the Caves and Cliffs music?",
        List.of("Lena Raine", "C418", "Kumi Tanioka", "Samuel Aberg"), 0));
    list.add(new QuestionEntry("Where is the music disc Pigstep found?",
        List.of("Bastion remnants", "Nether fortresses", "End cities", "Ancient cities"), 0));
    list.add(new QuestionEntry("Which music disc is assembled from fragments found in ancient cities?",
        List.of("Disc 5", "Disc 11", "Disc 13", "Relic"), 0));
    list.add(new QuestionEntry("Which music discs are dropped when a skeleton kills a creeper?",
        List.of("Only 13 and cat", "Any disc except Pigstep, 5 and Relic", "Only Pigstep", "Any disc including Pigstep"), 1));
    list.add(new QuestionEntry("What is the most common competitive speedrun category?",
        List.of("Any% Random Seed Glitchless", "100% Glitched", "All Advancements Set Seed", "Any% No Nether"), 0));
    list.add(new QuestionEntry("What is the classic speedrun strategy for killing the Ender Dragon quickly?",
        List.of("Bed explosions", "TNT minecarts", "Snowballs", "Lava buckets"), 0));
    list.add(new QuestionEntry("What is the Overworld to Nether distance ratio?",
        List.of("1 to 4", "1 to 8", "1 to 16", "1 to 2"), 1));
    list.add(new QuestionEntry("A stasis chamber commonly uses which item to teleport a player back?",
        List.of("Ender pearl", "Chorus fruit", "Eye of ender", "Lodestone"), 0));
    list.add(new QuestionEntry("Which key opens the debug screen in Java Edition by default?",
        List.of("F1", "F3", "F5", "F11"), 1));
    list.add(new QuestionEntry("What are the horizontal dimensions of a chunk?",
        List.of("8 by 8", "16 by 16", "32 by 32", "64 by 64"), 1));
    list.add(new QuestionEntry("How many game ticks occur per second normally?",
        List.of("10", "20", "30", "60"), 1));
    list.add(new QuestionEntry("How long is one full Minecraft day in real time?",
        List.of("10 minutes", "15 minutes", "20 minutes", "30 minutes"), 2));
    list.add(new QuestionEntry("How many milliseconds is a single game tick?",
        List.of("20", "50", "100", "250"), 1));
    list.add(new QuestionEntry("One redstone tick equals how many game ticks?",
        List.of("1", "2", "4", "10"), 1));
    list.add(new QuestionEntry("What are chunks where slimes can spawn underground called?",
        List.of("Slime chunks", "Swamp chunks", "Green chunks", "Spawn chunks"), 0));
    list.add(new QuestionEntry("In modern versions, hostile mobs in the Overworld require what block light level to spawn?",
        List.of("0", "7 or lower", "9 or lower", "11 or lower"), 0));
    list.add(new QuestionEntry("What was the old crafting recipe for the enchanted golden apple that was later removed?",
        List.of("8 gold blocks around an apple", "8 gold ingots around an apple", "4 gold blocks and a nether star", "8 gold nuggets and an apple"), 0));
    list.add(new QuestionEntry("Which block was an April Fools joke that asked players to pay to open it?",
        List.of("Locked chest", "Barrier block", "Ender chest", "Trapped chest"), 0));
    list.add(new QuestionEntry("What is the format of a Java Edition snapshot version name?",
        List.of("Year, week, letter", "Month, day, letter", "Version, patch, letter", "Build number only"), 0));
    list.add(new QuestionEntry("Which development stage came directly before Alpha?",
        List.of("Indev", "Infdev", "Classic", "Beta"), 1));
    list.add(new QuestionEntry("What is the default texture resolution of vanilla Minecraft?",
        List.of("8 by 8", "16 by 16", "32 by 32", "64 by 64"), 1));
    list.add(new QuestionEntry("Roughly how wide is the maximum world border in Java Edition?",
        List.of("About 30 million blocks", "About 60 million blocks", "About 1 million blocks", "About 100 thousand blocks"), 0));
    list.add(new QuestionEntry("What was the build height limit before 1.18?",
        List.of("128", "192", "256", "320"), 2));
    list.add(new QuestionEntry("In which version was the Ender Dragon officially added as the final boss?",
        List.of("Beta 1.8", "Release 1.0", "Release 1.2", "Release 1.4"), 1));
    list.add(new QuestionEntry("Which version added the Wither and beacons?",
        List.of("1.2", "1.4", "1.6", "1.8"), 1));
    list.add(new QuestionEntry("Which version added ocean monuments and guardians?",
        List.of("1.7", "1.8", "1.9", "1.10"), 1));
    list.add(new QuestionEntry("Elytra were introduced in which version?",
        List.of("1.8", "1.9", "1.10", "1.11"), 1));
    list.add(new QuestionEntry("Llamas were added in which version?",
        List.of("1.10", "1.11", "1.12", "1.13"), 1));
    list.add(new QuestionEntry("Parrots and concrete were added in which version?",
        List.of("1.11", "1.12", "1.13", "1.14"), 1));
    list.add(new QuestionEntry("Which rare hostile mob variant is a hostile white rabbit?",
        List.of("Killer Bunny", "Cave Rabbit", "Blood Hare", "Nether Bunny"), 0));
    list.add(new QuestionEntry("Which mob exists in the game files but cannot be obtained in survival?",
        List.of("Illusioner", "Vindicator", "Pillager", "Evoker"), 0));
    list.add(new QuestionEntry("Which unused mob is an oversized version of the zombie?",
        List.of("Giant", "Titan", "Brute", "Colossus"), 0));
    list.add(new QuestionEntry("Which horse variant cannot be obtained in normal survival play?",
        List.of("Zombie horse", "Skeleton horse", "Donkey", "Mule"), 0));
    list.add(new QuestionEntry("Which creative-only item lets you cycle through block states?",
        List.of("Debug stick", "Structure block", "Jigsaw block", "Command block"), 0));
    list.add(new QuestionEntry("Which invisible block blocks movement and is only obtainable via commands?",
        List.of("Barrier", "Light block", "Structure void", "Air block"), 0));
    list.add(new QuestionEntry("Which block is used by the game to generate villages and other modular structures?",
        List.of("Structure block", "Jigsaw block", "Command block", "Spawner"), 1));
    list.add(new QuestionEntry("Which gamerule stops the day and night cycle?",
        List.of("doDaylightCycle", "doWeatherCycle", "doMobSpawning", "keepInventory"), 0));
    list.add(new QuestionEntry("Which command finds the nearest structure of a given type?",
        List.of("/find", "/locate", "/search", "/structure"), 1));
    list.add(new QuestionEntry("At which Y level does the Nether bedrock ceiling sit?",
        List.of("100", "127", "192", "256"), 1));
    list.add(new QuestionEntry("Which mob hides inside stone blocks and swarms when disturbed?",
        List.of("Silverfish", "Endermite", "Cave spider", "Slime"), 0));
    list.add(new QuestionEntry("Which mob can rarely spawn when a player throws an ender pearl?",
        List.of("Endermite", "Silverfish", "Enderman", "Vex"), 0));
    list.add(new QuestionEntry("Cave spider spawners are found in which structure?",
        List.of("Mineshafts", "Strongholds", "Dungeons", "Woodland mansions"), 0));
    list.add(new QuestionEntry("Which structure contains the End portal frame?",
        List.of("Stronghold", "Ancient city", "Mineshaft", "Fortress"), 0));
    list.add(new QuestionEntry("Which structure is home to evokers and vindicators?",
        List.of("Woodland mansion", "Pillager outpost", "Village", "Igloo"), 0));
    list.add(new QuestionEntry("Which structure has a basement containing a suspicious brewing setup?",
        List.of("Igloo", "Desert temple", "Jungle temple", "Shipwreck"), 0));
    list.add(new QuestionEntry("Which trap is found under the pressure plate in a desert pyramid?",
        List.of("TNT", "Lava", "Arrow dispensers", "Falling anvils"), 0));
    list.add(new QuestionEntry("Which structure uses tripwire and arrow dispensers as traps?",
        List.of("Jungle temple", "Igloo", "Ocean ruins", "Village"), 0));
    list.add(new QuestionEntry("Which mob was renamed from Pig Zombie in the game code long before its model change?",
        List.of("Zombified Piglin", "Piglin Brute", "Hoglin", "Zoglin"), 0));
    list.add(new QuestionEntry("What is a hoglin called after being brought to the Overworld?",
        List.of("Zoglin", "Zombified Hoglin", "Hogzombie", "Zombie Hoglin"), 0));
    list.add(new QuestionEntry("Which piglin variant never becomes friendly with gold armor?",
        List.of("Piglin Brute", "Piglin", "Zombified Piglin", "Baby Piglin"), 0));
    list.add(new QuestionEntry("What is the technical name for the effect that makes an anvil refuse further work?",
        List.of("Too Expensive", "Overloaded", "Max Repair", "Anvil Fatigue"), 0));
    list.add(new QuestionEntry("What is the highest enchantment level cost an anvil will accept in survival?",
        List.of("30", "39", "40", "50"), 1));
    list.add(new QuestionEntry("What is the capital city of Australia?",
        List.of("Sydney", "Melbourne", "Canberra", "Perth"), 2));
    list.add(new QuestionEntry("Which is the largest ocean on Earth?",
        List.of("Atlantic", "Indian", "Arctic", "Pacific"), 3));
    list.add(new QuestionEntry("Which river flows through Egypt?",
        List.of("Amazon", "Nile", "Danube", "Ganges"), 1));
    list.add(new QuestionEntry("What is the chemical symbol for gold?",
        List.of("Go", "Ag", "Au", "Gd"), 2));
    list.add(new QuestionEntry("How many planets are in our solar system?",
        List.of("7", "8", "9", "10"), 1));
    list.add(new QuestionEntry("Which planet is known as the Red Planet?",
        List.of("Venus", "Mars", "Jupiter", "Mercury"), 1));
    list.add(new QuestionEntry("Which is the largest planet in the solar system?",
        List.of("Saturn", "Neptune", "Jupiter", "Earth"), 2));
    list.add(new QuestionEntry("Roughly how fast does light travel in a vacuum?",
        List.of("300 km per second", "3000 km per second", "300000 km per second", "30 km per second"), 2));
    list.add(new QuestionEntry("What is the chemical formula for water?",
        List.of("CO2", "H2O", "O2", "NaCl"), 1));
    list.add(new QuestionEntry("How many bones are in the adult human body?",
        List.of("186", "206", "226", "246"), 1));
    list.add(new QuestionEntry("What is the largest mammal on Earth?",
        List.of("Elephant", "Blue whale", "Giraffe", "Orca"), 1));
    list.add(new QuestionEntry("Which is the hardest naturally occurring mineral?",
        List.of("Quartz", "Diamond", "Topaz", "Corundum"), 1));
    list.add(new QuestionEntry("What is the fastest land animal?",
        List.of("Lion", "Cheetah", "Pronghorn", "Horse"), 1));
    list.add(new QuestionEntry("How many continents are there?",
        List.of("5", "6", "7", "8"), 2));
    list.add(new QuestionEntry("Which country has the largest land area?",
        List.of("Canada", "China", "Russia", "United States"), 2));
    list.add(new QuestionEntry("On which continent is the Sahara Desert?",
        List.of("Asia", "Africa", "Australia", "South America"), 1));
    list.add(new QuestionEntry("Mount Everest is part of which mountain range?",
        List.of("Andes", "Alps", "Himalayas", "Rockies"), 2));
    list.add(new QuestionEntry("In which city is the Eiffel Tower?",
        List.of("Rome", "Paris", "Madrid", "Vienna"), 1));
    list.add(new QuestionEntry("The Great Wall is located in which country?",
        List.of("Japan", "Korea", "China", "Mongolia"), 2));
    list.add(new QuestionEntry("What is the currency of Japan?",
        List.of("Won", "Yuan", "Yen", "Baht"), 2));
    list.add(new QuestionEntry("Who painted the Mona Lisa?",
        List.of("Michelangelo", "Leonardo da Vinci", "Raphael", "Donatello"), 1));
    list.add(new QuestionEntry("Who wrote the play Romeo and Juliet?",
        List.of("Charles Dickens", "William Shakespeare", "Jane Austen", "Mark Twain"), 1));
    list.add(new QuestionEntry("How many houses are there at Hogwarts in the Harry Potter series?",
        List.of("3", "4", "5", "6"), 1));
    list.add(new QuestionEntry("What is the name of Mario's brother?",
        List.of("Wario", "Luigi", "Toad", "Yoshi"), 1));
    list.add(new QuestionEntry("Which company develops the Mario games?",
        List.of("Sega", "Nintendo", "Sony", "Capcom"), 1));
    list.add(new QuestionEntry("What type is Pikachu in Pokemon?",
        List.of("Fire", "Water", "Electric", "Psychic"), 2));
    list.add(new QuestionEntry("What is the name of the hero in most Legend of Zelda games?",
        List.of("Zelda", "Link", "Ganon", "Navi"), 1));
    list.add(new QuestionEntry("In which year did the Titanic sink?",
        List.of("1905", "1912", "1918", "1923"), 1));
    list.add(new QuestionEntry("Who was the first person to walk on the Moon?",
        List.of("Buzz Aldrin", "Neil Armstrong", "Yuri Gagarin", "Michael Collins"), 1));
    list.add(new QuestionEntry("In which year did humans first land on the Moon?",
        List.of("1961", "1965", "1969", "1972"), 2));
    list.add(new QuestionEntry("Which ancient wonder of the world still stands today?",
        List.of("Great Pyramid of Giza", "Colossus of Rhodes", "Hanging Gardens", "Lighthouse of Alexandria"), 0));
    list.add(new QuestionEntry("Which ancient civilization built the Colosseum?",
        List.of("Greek", "Roman", "Egyptian", "Persian"), 1));
    list.add(new QuestionEntry("Vikings originated from which region?",
        List.of("Scandinavia", "Iberia", "Balkans", "Anatolia"), 0));
    list.add(new QuestionEntry("How often are the Summer Olympic Games normally held?",
        List.of("Every 2 years", "Every 3 years", "Every 4 years", "Every 5 years"), 2));
    list.add(new QuestionEntry("How many pieces does each player start with in chess?",
        List.of("8", "12", "16", "20"), 2));
    list.add(new QuestionEntry("How many players from one team are on a soccer field at kickoff?",
        List.of("9", "10", "11", "12"), 2));
    list.add(new QuestionEntry("How many cards are in a standard deck without jokers?",
        List.of("48", "50", "52", "54"), 2));
    list.add(new QuestionEntry("How many sides does a hexagon have?",
        List.of("5", "6", "7", "8"), 1));
    list.add(new QuestionEntry("How many degrees are in the angles of a triangle in total?",
        List.of("90", "180", "270", "360"), 1));
    list.add(new QuestionEntry("What is the value of pi rounded to two decimal places?",
        List.of("3.12", "3.14", "3.16", "3.18"), 1));
    list.add(new QuestionEntry("Binary is a number system with which base?",
        List.of("2", "8", "10", "16"), 0));
    list.add(new QuestionEntry("Hexadecimal is which base?",
        List.of("8", "10", "12", "16"), 3));
    list.add(new QuestionEntry("What does DNA stand for?",
        List.of("Deoxyribonucleic acid", "Dinucleic acid", "Diribonucleic acid", "Deoxyribose nitrate"), 0));
    list.add(new QuestionEntry("Which gas do plants release during photosynthesis?",
        List.of("Carbon dioxide", "Oxygen", "Nitrogen", "Methane"), 1));
    list.add(new QuestionEntry("At what temperature does water boil at sea level in Celsius?",
        List.of("90", "95", "100", "110"), 2));
    list.add(new QuestionEntry("At what temperature does water freeze in Fahrenheit?",
        List.of("0", "16", "32", "45"), 2));
    list.add(new QuestionEntry("Which gas is most abundant in Earth's atmosphere?",
        List.of("Oxygen", "Nitrogen", "Carbon dioxide", "Argon"), 1));
    list.add(new QuestionEntry("The Sun is best classified as what?",
        List.of("A planet", "A star", "A comet", "A nebula"), 1));
    list.add(new QuestionEntry("Who formulated the famous equation E equals mc squared?",
        List.of("Isaac Newton", "Albert Einstein", "Niels Bohr", "Galileo"), 1));
    list.add(new QuestionEntry("Who discovered penicillin?",
        List.of("Louis Pasteur", "Alexander Fleming", "Marie Curie", "Joseph Lister"), 1));
    list.add(new QuestionEntry("How many legs does a spider have?",
        List.of("6", "8", "10", "12"), 1));
    list.add(new QuestionEntry("What is a group of wolves called?",
        List.of("Herd", "Pack", "Flock", "School"), 1));
    list.add(new QuestionEntry("What is a baby kangaroo called?",
        List.of("Cub", "Kit", "Joey", "Calf"), 2));
    list.add(new QuestionEntry("Which is the fastest bird in a dive?",
        List.of("Golden eagle", "Peregrine falcon", "Albatross", "Swift"), 1));
    list.add(new QuestionEntry("Which insect produces honey?",
        List.of("Wasp", "Bee", "Ant", "Hornet"), 1));
    list.add(new QuestionEntry("The Amazon rainforest is mainly on which continent?",
        List.of("Africa", "Asia", "South America", "North America"), 2));
    list.add(new QuestionEntry("Which country is famously shaped like a boot?",
        List.of("Greece", "Italy", "Portugal", "Croatia"), 1));
    list.add(new QuestionEntry("Which city is famous for its canals and gondolas?",
        List.of("Venice", "Amsterdam", "Bruges", "Stockholm"), 0));
    list.add(new QuestionEntry("What is the capital of Japan?",
        List.of("Osaka", "Kyoto", "Tokyo", "Nagoya"), 2));
    list.add(new QuestionEntry("What is the capital of Canada?",
        List.of("Toronto", "Ottawa", "Vancouver", "Montreal"), 1));
    list.add(new QuestionEntry("How many strings does a standard guitar have?",
        List.of("4", "5", "6", "7"), 2));
    list.add(new QuestionEntry("How many keys does a standard full-size piano have?",
        List.of("76", "82", "88", "92"), 2));
    list.add(new QuestionEntry("What are the three primary colors of light?",
        List.of("Red, green, blue", "Red, yellow, blue", "Cyan, magenta, yellow", "Red, white, blue"), 0));
    list.add(new QuestionEntry("How many colors are traditionally listed in a rainbow?",
        List.of("5", "6", "7", "8"), 2));
    list.add(new QuestionEntry("How many letters are in the English alphabet?",
        List.of("24", "25", "26", "27"), 2));
    list.add(new QuestionEntry("The metric prefix kilo means how many?",
        List.of("10", "100", "1000", "10000"), 2));
    list.add(new QuestionEntry("Which organ pumps blood around the human body?",
        List.of("Liver", "Heart", "Lungs", "Kidney"), 1));
    list.add(new QuestionEntry("How many sides does a standard die have?",
        List.of("4", "6", "8", "12"), 1));
    list.add(new QuestionEntry("What is the tallest type of tree on Earth?",
        List.of("Oak", "Coast redwood", "Pine", "Baobab"), 1));
    list.add(new QuestionEntry("Which metal is liquid at room temperature?",
        List.of("Mercury", "Lead", "Tin", "Aluminium"), 0));
    list.add(new QuestionEntry("What is the study of earthquakes called?",
        List.of("Seismology", "Geology", "Meteorology", "Volcanology"), 0));
    list.add(new QuestionEntry("Which layer of Earth is the outermost?",
        List.of("Core", "Mantle", "Crust", "Magma"), 2));
    list.add(new QuestionEntry("Which sea creature has three hearts?",
        List.of("Shark", "Octopus", "Dolphin", "Jellyfish"), 1));
    list.add(new QuestionEntry("What does CPU stand for in computing?",
        List.of("Central Processing Unit", "Computer Power Unit", "Core Program Utility", "Central Program Unit"), 0));
    list.add(new QuestionEntry("Which language is Minecraft Java Edition primarily written in?",
        List.of("C++", "Java", "Python", "C#"), 1));
    list.add(new QuestionEntry("What does RAM stand for?",
        List.of("Random Access Memory", "Rapid Active Memory", "Read Access Module", "Runtime Allocated Memory"), 0));
    list.add(new QuestionEntry("How many bits are in a byte?",
        List.of("4", "8", "16", "32"), 1));
    list.add(new QuestionEntry("You are falling into the void with no blocks left. Which of these actually saves you?",
        List.of("Elytra", "Water bucket", "Totem of Undying", "Nothing on this list"), 3));
    list.add(new QuestionEntry("Which of these is the least useful thing to bring to the Nether?",
        List.of("Water bucket", "Fire Resistance potion", "Gold armor", "Flint and steel"), 0));
    list.add(new QuestionEntry("Which of these blocks will NOT hurt you if you stand on it?",
        List.of("Magma block", "Cactus", "Sweet berry bush", "Moss block"), 3));
    list.add(new QuestionEntry("Worst pet idea: which of these mobs cannot be tamed at all?",
        List.of("Wolf", "Cat", "Parrot", "Creeper"), 3));
    list.add(new QuestionEntry("Which of these mobs is the tallest?",
        List.of("Wolf", "Creeper", "Enderman", "Ghast"), 3));
    list.add(new QuestionEntry("You have half a hunger bar. Which of these is safe to eat with no downside?",
        List.of("Rotten flesh", "Pufferfish", "Spider eye", "Bread"), 3));
    list.add(new QuestionEntry("Which block completely cancels fall damage when landed on?",
        List.of("Hay bale", "Slime block", "Wool", "Sponge"), 1));
    list.add(new QuestionEntry("Which of these makes the worst weapon in survival?",
        List.of("Wooden sword", "Stone axe", "Trident", "Dead bush"), 3));
    list.add(new QuestionEntry("Which Minecraft food actually exists in real life too?",
        List.of("Bread", "Rotten flesh", "Chorus fruit", "Enchanted golden apple"), 0));
    list.add(new QuestionEntry("Which of these is NOT a real Minecraft advancement?",
        List.of("How Did We Get Here?", "Hot Tourist Destinations", "Sky Is The Limit", "Two by Two"), 2));
    list.add(new QuestionEntry("Which of these is NOT a real mob?",
        List.of("Vex", "Vindicator", "Vandalizer", "Evoker"), 2));
    list.add(new QuestionEntry("Which of these is NOT a real biome?",
        List.of("Lush Caves", "Dripstone Caves", "Crystal Caves", "Deep Dark"), 2));
    list.add(new QuestionEntry("Which of these is NOT a real enchantment?",
        List.of("Feather Falling", "Frost Walker", "Soul Speed", "Lava Walker"), 3));
    list.add(new QuestionEntry("Which of these is NOT a brewable potion?",
        List.of("Potion of Leaping", "Potion of Slow Falling", "Potion of Invisibility", "Potion of Levitation"), 3));
    list.add(new QuestionEntry("Which of these is NOT a real music disc?",
        List.of("13", "cat", "otherside", "sunset"), 3));
    list.add(new QuestionEntry("Which of these is NOT a real wood type in 1.21?",
        List.of("Oak", "Birch", "Cherry", "Maple"), 3));
    list.add(new QuestionEntry("Which of these is NOT a real status effect?",
        List.of("Hunger", "Mining Fatigue", "Dizziness", "Bad Omen"), 2));
    list.add(new QuestionEntry("Which of these is NOT a real ore in vanilla Minecraft?",
        List.of("Copper", "Emerald", "Ruby", "Lapis lazuli"), 2));
    list.add(new QuestionEntry("Which mob is famously afraid of cats?",
        List.of("Zombie", "Creeper", "Skeleton", "Spider"), 1));
    list.add(new QuestionEntry("Which mob gets angry if you look directly at it?",
        List.of("Enderman", "Iron golem", "Piglin", "Ravager"), 0));
    list.add(new QuestionEntry("Would you rather punch a tree or a creeper? Which one actually gives you wood?",
        List.of("The creeper", "The tree", "Both", "Neither"), 1));
    list.add(new QuestionEntry("Which pickaxe material has the worst durability despite mining fast?",
        List.of("Wood", "Gold", "Stone", "Iron"), 1));
    list.add(new QuestionEntry("Which armor material has the highest enchantability?",
        List.of("Leather", "Iron", "Gold", "Diamond"), 2));
    list.add(new QuestionEntry("Which of these is the fastest way to travel long distances in the Overworld?",
        List.of("Sprinting", "Riding a horse", "A boat on blue ice", "Riding a pig"), 2));
    list.add(new QuestionEntry("Ranked purely by silliness of the death message, which one is real?",
        List.of("was squished too much", "was mildly inconvenienced", "was thoroughly disappointed", "gave up on life"), 0));
    list.add(new QuestionEntry("Which of these mobs would technically make the worst guard dog because it despawns in daylight?",
        List.of("Wolf", "Iron golem", "Zombie", "Cat"), 2));
    list.add(new QuestionEntry("Which item is the most useless in the Nether specifically?",
        List.of("Bed", "Boat", "Water bucket", "Golden apple"), 2));
    list.add(new QuestionEntry("Would you rather trade with a piglin or a villager for a diamond pickaxe? Which one can actually offer one?",
        List.of("Piglin", "Villager", "Both", "Neither"), 1));
    list.add(new QuestionEntry("Which of these does a llama spit at?",
        List.of("Wolves and attackers", "Only players", "Only villagers", "Nothing at all"), 0));
    list.add(new QuestionEntry("Which mob would win a fight against a single zombie without any help?",
        List.of("Chicken", "Iron golem", "Bat", "Rabbit"), 1));
    list.add(new QuestionEntry("Which of these is the worst possible base location because mobs constantly spawn there?",
        List.of("A well-lit village house", "An unlit cave entrance", "A snow-covered mountain peak with torches", "A lit-up desert temple"), 1));
    list.add(new QuestionEntry("Which of these is genuinely the most dangerous thing to do while wearing full netherite?",
        List.of("Falling into the void", "Standing in lava briefly", "Fighting a zombie", "Falling ten blocks"), 0));
    list.add(new QuestionEntry("What is the capital of France?",
        List.of("Paris", "Madrid", "Rome", "Berlin"), 0));
    list.add(new QuestionEntry("Which planet is closest to the Sun?",
        List.of("Mercury", "Venus", "Earth", "Mars"), 0));
    list.add(new QuestionEntry("Which planet is known for its prominent ring system?",
        List.of("Saturn", "Jupiter", "Neptune", "Uranus"), 0));
    list.add(new QuestionEntry("What is the largest planet in our solar system?",
        List.of("Jupiter", "Saturn", "Earth", "Neptune"), 0));
    list.add(new QuestionEntry("What is the smallest planet in our solar system?",
        List.of("Mercury", "Mars", "Venus", "Earth"), 0));
    list.add(new QuestionEntry("Which planet is known as the Red Planet?",
        List.of("Mars", "Venus", "Jupiter", "Mercury"), 0));
    list.add(new QuestionEntry("What is Earth's natural satellite?",
        List.of("The Moon", "Mars", "The Sun", "Venus"), 0));
    list.add(new QuestionEntry("What gas do humans primarily breathe in to survive?",
        List.of("Oxygen", "Nitrogen", "Carbon dioxide", "Hydrogen"), 0));
    list.add(new QuestionEntry("What gas makes up most of Earth's atmosphere?",
        List.of("Nitrogen", "Oxygen", "Carbon dioxide", "Argon"), 0));
    list.add(new QuestionEntry("What is the chemical symbol for gold?",
        List.of("Au", "Ag", "Gd", "Go"), 0));
    list.add(new QuestionEntry("What is the chemical symbol for iron?",
        List.of("Fe", "Ir", "In", "I"), 0));
    list.add(new QuestionEntry("What is H2O commonly known as?",
        List.of("Water", "Hydrogen peroxide", "Oxygen", "Salt"), 0));
    list.add(new QuestionEntry("What is the boiling point of water at standard atmospheric pressure?",
        List.of("50°C", "75°C", "100°C", "150°C"), 2));
    list.add(new QuestionEntry("What force keeps objects attracted to Earth?",
        List.of("Gravity", "Magnetism", "Friction", "Pressure"), 0));
    list.add(new QuestionEntry("What organ pumps blood around the human body?",
        List.of("Heart", "Liver", "Lung", "Kidney"), 0));
    list.add(new QuestionEntry("How many bones are in the typical adult human body?",
        List.of("106", "206", "306", "406"), 1));
    list.add(new QuestionEntry("Which organ is primarily responsible for filtering blood and producing urine?",
        List.of("Kidney", "Liver", "Heart", "Lung"), 0));
    list.add(new QuestionEntry("Which vitamin is produced in the skin in response to sunlight?",
        List.of("Vitamin D", "Vitamin C", "Vitamin B12", "Vitamin K"), 0));
    list.add(new QuestionEntry("What is the largest ocean on Earth?",
        List.of("Pacific Ocean", "Atlantic Ocean", "Indian Ocean", "Arctic Ocean"), 0));
    list.add(new QuestionEntry("What is the longest river in South America?",
        List.of("Amazon River", "Nile", "Yangtze", "Mississippi"), 0));
    list.add(new QuestionEntry("Which country is home to the Great Barrier Reef?",
        List.of("Australia", "Brazil", "Indonesia", "South Africa"), 0));
    list.add(new QuestionEntry("Which continent is the Sahara Desert located on?",
        List.of("Africa", "Asia", "Australia", "South America"), 0));
    list.add(new QuestionEntry("Which country is shaped roughly like a boot?",
        List.of("Italy", "Greece", "Portugal", "Chile"), 0));
    list.add(new QuestionEntry("What is the capital of Japan?",
        List.of("Tokyo", "Kyoto", "Osaka", "Hiroshima"), 0));
    list.add(new QuestionEntry("What is the capital of Canada?",
        List.of("Ottawa", "Toronto", "Vancouver", "Montreal"), 0));
    list.add(new QuestionEntry("What is the capital of Australia?",
        List.of("Canberra", "Sydney", "Melbourne", "Perth"), 0));
    list.add(new QuestionEntry("Which country has the city of Reykjavik as its capital?",
        List.of("Iceland", "Norway", "Finland", "Sweden"), 0));
    list.add(new QuestionEntry("Which language has the most native speakers worldwide?",
        List.of("Mandarin Chinese", "English", "Spanish", "Hindi"), 0));
    list.add(new QuestionEntry("What is the largest mammal on Earth?",
        List.of("Blue whale", "African elephant", "Giraffe", "Orca"), 0));
    list.add(new QuestionEntry("Which animal is known as the fastest land animal?",
        List.of("Cheetah", "Horse", "Lion", "Ostrich"), 0));
    list.add(new QuestionEntry("Which bird is commonly considered the fastest animal during a dive?",
        List.of("Peregrine falcon", "Eagle", "Albatross", "Ostrich"), 0));
    list.add(new QuestionEntry("What is the hardest naturally occurring mineral?",
        List.of("Diamond", "Quartz", "Topaz", "Corundum"), 0));
    list.add(new QuestionEntry("Which element has the atomic number 1?",
        List.of("Hydrogen", "Helium", "Oxygen", "Carbon"), 0));
    list.add(new QuestionEntry("Which element has the chemical symbol Na?",
        List.of("Sodium", "Nitrogen", "Neon", "Nickel"), 0));
    list.add(new QuestionEntry("What is the center of an atom called?",
        List.of("Nucleus", "Electron shell", "Proton field", "Core cloud"), 0));
    list.add(new QuestionEntry("What particle has a negative electric charge?",
        List.of("Electron", "Proton", "Neutron", "Photon"), 0));
    list.add(new QuestionEntry("What particle has no electric charge?",
        List.of("Neutron", "Electron", "Proton", "Ion"), 0));
    list.add(new QuestionEntry("What is the process by which plants convert light energy into chemical energy?",
        List.of("Photosynthesis", "Respiration", "Fermentation", "Transpiration"), 0));
    list.add(new QuestionEntry("Which gas do plants absorb during photosynthesis?",
        List.of("Carbon dioxide", "Oxygen", "Nitrogen", "Hydrogen"), 0));
    list.add(new QuestionEntry("Which scientist developed the theory of general relativity?",
        List.of("Albert Einstein", "Isaac Newton", "Galileo Galilei", "Nikola Tesla"), 0));
    list.add(new QuestionEntry("Who formulated the laws of motion and universal gravitation?",
        List.of("Isaac Newton", "Albert Einstein", "Charles Darwin", "Marie Curie"), 0));
    list.add(new QuestionEntry("Who is credited with developing the theory of evolution by natural selection?",
        List.of("Charles Darwin", "Gregor Mendel", "Louis Pasteur", "Galileo Galilei"), 0));
    list.add(new QuestionEntry("Which scientist is associated with the discovery of radioactivity and research on radium?",
        List.of("Marie Curie", "Ada Lovelace", "Jane Goodall", "Rosalind Franklin"), 0));
    list.add(new QuestionEntry("Which ancient civilization built Machu Picchu?",
        List.of("Inca", "Roman", "Egyptian", "Mayan"), 0));
    list.add(new QuestionEntry("Which ancient civilization built the pyramids at Giza?",
        List.of("Ancient Egyptians", "Romans", "Greeks", "Aztecs"), 0));
    list.add(new QuestionEntry("The Colosseum is located in which city?",
        List.of("Rome", "Athens", "Paris", "Istanbul"), 0));
    list.add(new QuestionEntry("The Great Wall is located in which country?",
        List.of("China", "Japan", "India", "Mongolia"), 0));
    list.add(new QuestionEntry("Which historical ship famously sank in 1912?",
        List.of("Titanic", "Mayflower", "Bismarck", "Endeavour"), 0));
    list.add(new QuestionEntry("Who wrote Romeo and Juliet?",
        List.of("William Shakespeare", "Charles Dickens", "Jane Austen", "Mark Twain"), 0));
    list.add(new QuestionEntry("Who wrote The Hobbit?",
        List.of("J. R. R. Tolkien", "C. S. Lewis", "George Orwell", "Jules Verne"), 0));
    list.add(new QuestionEntry("Who wrote 1984?",
        List.of("George Orwell", "Aldous Huxley", "Ray Bradbury", "Ernest Hemingway"), 0));
    list.add(new QuestionEntry("Which fictional detective lives at 221B Baker Street?",
        List.of("Sherlock Holmes", "Hercule Poirot", "Batman", "Columbo"), 0));
    list.add(new QuestionEntry("Which fictional wizard attends Hogwarts?",
        List.of("Harry Potter", "Frodo Baggins", "Bilbo Baggins", "Percy Jackson"), 0));
    list.add(new QuestionEntry("Which movie features the fictional world of Pandora?",
        List.of("Avatar", "Interstellar", "Inception", "The Matrix"), 0));
    list.add(new QuestionEntry("Which film series features the character Darth Vader?",
        List.of("Star Wars", "Star Trek", "The Matrix", "Alien"), 0));
    list.add(new QuestionEntry("Which superhero is also known as the Dark Knight?",
        List.of("Batman", "Superman", "Spider-Man", "Iron Man"), 0));
    list.add(new QuestionEntry("Which superhero is associated with the fictional city of Metropolis?",
        List.of("Superman", "Batman", "Spider-Man", "The Flash"), 0));
    list.add(new QuestionEntry("Which video game character is famous for collecting rings?",
        List.of("Sonic the Hedgehog", "Mario", "Link", "Kirby"), 0));
    list.add(new QuestionEntry("Which Nintendo character is famous for wearing a red cap and mustache?",
        List.of("Mario", "Link", "Kirby", "Fox McCloud"), 0));
    list.add(new QuestionEntry("Which game series features the character Link?",
        List.of("The Legend of Zelda", "Final Fantasy", "Metroid", "Dragon Quest"), 0));
    list.add(new QuestionEntry("Which game series features the Pokémon Pikachu?",
        List.of("Pokémon", "Digimon", "Final Fantasy", "Monster Hunter"), 0));
    list.add(new QuestionEntry("Which color combination is traditionally associated with Pac-Man's ghost Blinky?",
        List.of("Red", "Blue", "Pink", "Orange"), 0));
    list.add(new QuestionEntry("Which instrument has black and white keys and is commonly found in concert halls?",
        List.of("Piano", "Trumpet", "Violin", "Flute"), 0));
    list.add(new QuestionEntry("How many strings does a standard violin have?",
        List.of("4", "5", "6", "8"), 0));
    list.add(new QuestionEntry("Which instrument is typically played with a bow?",
        List.of("Violin", "Trumpet", "Piano", "Drums"), 0));
    list.add(new QuestionEntry("How many sides does a hexagon have?",
        List.of("5", "6", "7", "8"), 1));
    list.add(new QuestionEntry("How many sides does an octagon have?",
        List.of("6", "7", "8", "9"), 2));
    list.add(new QuestionEntry("What is the square root of 144?",
        List.of("10", "11", "12", "14"), 2));
    list.add(new QuestionEntry("What is 15 multiplied by 4?",
        List.of("45", "50", "60", "75"), 2));
    list.add(new QuestionEntry("What is the value of pi rounded to two decimal places?",
        List.of("3.12", "3.14", "3.16", "3.18"), 1));
    list.add(new QuestionEntry("How many degrees are in a full circle?",
        List.of("90", "180", "270", "360"), 3));
    list.add(new QuestionEntry("How many minutes are in two hours?",
        List.of("60", "90", "120", "180"), 2));
    list.add(new QuestionEntry("Which shape has exactly three sides?",
        List.of("Triangle", "Square", "Pentagon", "Hexagon"), 0));
    list.add(new QuestionEntry("Which number is prime?",
        List.of("21", "29", "39", "51"), 1));
    list.add(new QuestionEntry("Which number is the only even prime number?",
        List.of("0", "1", "2", "4"), 2));
    list.add(new QuestionEntry("What is the freezing point of water at standard atmospheric pressure?",
        List.of("-10°C", "0°C", "10°C", "32°C"), 1));
    list.add(new QuestionEntry("Which metal is liquid at room temperature?",
        List.of("Mercury", "Iron", "Copper", "Aluminum"), 0));
    list.add(new QuestionEntry("What is the main ingredient in glass?",
        List.of("Silica", "Iron", "Carbon", "Calcium"), 0));
    list.add(new QuestionEntry("Which natural material is traditionally used to make rubber?",
        List.of("Latex", "Silk", "Cotton", "Flax"), 0));
    list.add(new QuestionEntry("Which planet has the Great Red Spot?",
        List.of("Jupiter", "Mars", "Saturn", "Neptune"), 0));
    list.add(new QuestionEntry("Which moon is the largest moon of Saturn?",
        List.of("Titan", "Europa", "Ganymede", "Io"), 0));
    list.add(new QuestionEntry("Which is the largest moon in the Solar System?",
        List.of("Ganymede", "Titan", "Callisto", "The Moon"), 0));
    list.add(new QuestionEntry("Which galaxy contains our Solar System?",
        List.of("Milky Way", "Andromeda", "Triangulum", "Whirlpool"), 0));
    list.add(new QuestionEntry("What is the nearest star to Earth after the Sun?",
        List.of("Proxima Centauri", "Sirius", "Betelgeuse", "Polaris"), 0));
    list.add(new QuestionEntry("Which star is commonly known as the North Star?",
        List.of("Polaris", "Sirius", "Vega", "Betelgeuse"), 0));
    list.add(new QuestionEntry("What is the largest country by land area?",
        List.of("Russia", "Canada", "China", "United States"), 0));
    list.add(new QuestionEntry("Which country has the largest population as of the 2020s?",
        List.of("India", "China", "United States", "Indonesia"), 0));
    list.add(new QuestionEntry("Which continent is the largest by land area?",
        List.of("Asia", "Africa", "North America", "Europe"), 0));
    list.add(new QuestionEntry("Which continent is the smallest by land area?",
        List.of("Australia", "Europe", "Antarctica", "South America"), 0));
    list.add(new QuestionEntry("Which ocean surrounds Antarctica?",
        List.of("Southern Ocean", "Atlantic Ocean", "Pacific Ocean", "Indian Ocean"), 0));
    list.add(new QuestionEntry("Which country is famous for the ancient city of Petra?",
        List.of("Jordan", "Egypt", "Greece", "Turkey"), 0));
    list.add(new QuestionEntry("Which city is famous for the Eiffel Tower?",
        List.of("Paris", "London", "Rome", "Vienna"), 0));
    list.add(new QuestionEntry("Which city is famous for the Statue of Liberty?",
        List.of("New York City", "Boston", "Chicago", "Washington, D.C."), 0));
    list.add(new QuestionEntry("Which country is famous for the Taj Mahal?",
        List.of("India", "Pakistan", "Nepal", "Bangladesh"), 0));
    list.add(new QuestionEntry("Which country is famous for Mount Fuji?",
        List.of("Japan", "China", "South Korea", "Thailand"), 0));
    list.add(new QuestionEntry("Which animal is the largest living land animal?",
        List.of("African elephant", "White rhinoceros", "Giraffe", "Hippopotamus"), 0));
    list.add(new QuestionEntry("Which animal is known for changing its skin color and independently moving its eyes?",
        List.of("Chameleon", "Gecko", "Iguana", "Frog"), 0));
    list.add(new QuestionEntry("Which animal is the only mammal capable of true sustained flight?",
        List.of("Bat", "Flying squirrel", "Sugar glider", "Colugo"), 0));
    list.add(new QuestionEntry("Which animal is known for having three hearts?",
        List.of("Octopus", "Shark", "Dolphin", "Crab"), 0));
    list.add(new QuestionEntry("Which animal has fingerprints that can resemble human fingerprints?",
        List.of("Koala", "Panda", "Sloth", "Kangaroo"), 0));
    list.add(new QuestionEntry("Which animal is famous for its ability to regenerate lost limbs?",
        List.of("Axolotl", "Dolphin", "Eagle", "Penguin"), 0));
    list.add(new QuestionEntry("Which bird cannot fly but is the largest living bird?",
        List.of("Ostrich", "Penguin", "Emu", "Cassowary"), 0));
    list.add(new QuestionEntry("Which mammal is famous for laying eggs?",
        List.of("Platypus", "Dolphin", "Kangaroo", "Bat"), 0));
    list.add(new QuestionEntry("Which animal group includes frogs and salamanders?",
        List.of("Amphibians", "Reptiles", "Mammals", "Arthropods"), 0));
    list.add(new QuestionEntry("Which animal group includes spiders and insects?",
        List.of("Arthropods", "Mollusks", "Annelids", "Chordates"), 0));
    list.add(new QuestionEntry("Which historical period came after the Middle Ages?",
        List.of("Early Modern period", "Stone Age", "Bronze Age", "Classical Antiquity"), 0));
    list.add(new QuestionEntry("Which ancient people are associated with democracy in Athens?",
        List.of("Greeks", "Romans", "Persians", "Phoenicians"), 0));
    list.add(new QuestionEntry("Which empire used Latin extensively and was centered on Rome?",
        List.of("Roman Empire", "Ottoman Empire", "Mali Empire", "Inca Empire"), 0));
    list.add(new QuestionEntry("Who was the first person to walk on the Moon?",
        List.of("Neil Armstrong", "Buzz Aldrin", "Yuri Gagarin", "Michael Collins"), 0));
    list.add(new QuestionEntry("What was the first artificial satellite called?",
        List.of("Sputnik 1", "Apollo 1", "Explorer 1", "Vostok 1"), 0));
    list.add(new QuestionEntry("Which space telescope launched in 1990 and became famous for deep-space imagery?",
        List.of("Hubble Space Telescope", "Kepler", "Chandra", "Spitzer"), 0));
    list.add(new QuestionEntry("Which programming language was named after a species of Indonesian ape?",
        List.of("Python", "Java", "Ruby", "Go"), 0));
    list.add(new QuestionEntry("Which language was originally developed at Sun Microsystems and named after coffee?",
        List.of("Java", "C#", "Ruby", "Python"), 0));
    list.add(new QuestionEntry("Which markup language is commonly used to structure web pages?",
        List.of("HTML", "CSS", "SQL", "JSON"), 0));
    list.add(new QuestionEntry("Which language is primarily used to style web pages?",
        List.of("CSS", "HTML", "SQL", "Java"), 0));
    list.add(new QuestionEntry("Which protocol is commonly used to securely transfer web pages?",
        List.of("HTTPS", "FTP", "SMTP", "IRC"), 0));
    list.add(new QuestionEntry("Which file format is commonly used for lossless web images with transparency?",
        List.of("PNG", "JPEG", "MP3", "WAV"), 0));
    list.add(new QuestionEntry("Which format is commonly used for compressed digital photographs?",
        List.of("JPEG", "PNG", "SVG", "TXT"), 0));
    list.add(new QuestionEntry("Which unit is commonly used to measure computer storage capacity?",
        List.of("Byte", "Volt", "Hertz", "Newton"), 0));
    list.add(new QuestionEntry("Which unit measures electrical resistance?",
        List.of("Ohm", "Watt", "Volt", "Ampere"), 0));
    list.add(new QuestionEntry("Which unit measures frequency?",
        List.of("Hertz", "Watt", "Volt", "Ohm"), 0));
    list.add(new QuestionEntry("Which unit measures electrical power?",
        List.of("Watt", "Volt", "Ohm", "Ampere"), 0));
    list.add(new QuestionEntry("Which animal is commonly associated with the phrase 'the king of the jungle'?",
        List.of("Lion", "Tiger", "Jaguar", "Gorilla"), 0));
    list.add(new QuestionEntry("Which food is traditionally associated with Italy?",
        List.of("Pizza", "Sushi", "Tacos", "Poutine"), 0));
    list.add(new QuestionEntry("Which food is traditionally associated with Japan?",
        List.of("Sushi", "Paella", "Tacos", "Croissant"), 0));
    list.add(new QuestionEntry("Which sport uses a puck?",
        List.of("Ice hockey", "Basketball", "Baseball", "Tennis"), 0));
    list.add(new QuestionEntry("Which sport uses a shuttlecock?",
        List.of("Badminton", "Tennis", "Volleyball", "Squash"), 0));
    list.add(new QuestionEntry("Which sport is played at Wimbledon?",
        List.of("Tennis", "Golf", "Cricket", "Rugby"), 0));
    list.add(new QuestionEntry("Which country is traditionally credited with inventing modern pizza?",
        List.of("Italy", "France", "Greece", "Spain"), 0));
    list.add(new QuestionEntry("Which board game includes properties such as Boardwalk and Park Place?",
        List.of("Monopoly", "Clue", "Risk", "Sorry!"), 0));
    list.add(new QuestionEntry("Which board game asks players to solve a murder mystery?",
        List.of("Clue", "Risk", "Monopoly", "Catan"), 0));
    list.add(new QuestionEntry("Which card game is played with four suits including hearts and spades?",
        List.of("Standard playing cards", "Uno", "Tarot", "Magic: The Gathering"), 0));
    list.add(new QuestionEntry("Would you rather survive a Creeper explosion or a fall from 20 blocks onto stone if you have no armor?",
        List.of("Creeper explosion", "20-block fall", "They are exactly equal", "Neither can damage you"), 0));
    list.add(new QuestionEntry("Which is the safer place to hide from a hostile mob at night?",
        List.of("A fully enclosed lit shelter", "A dark cave entrance", "The top of a cactus", "Standing beside a Creeper"), 0));
    list.add(new QuestionEntry("If you need wood quickly, which is the most sensible first target?",
        List.of("A tree", "A Nether fortress", "An ocean monument", "An End city"), 0));
    list.add(new QuestionEntry("If you want food early in a new world, which is the most immediately useful?",
        List.of("Finding animals or crops", "Mining obsidian first", "Searching for an End city", "Building a beacon"), 0));
    list.add(new QuestionEntry("Which would be the least useful tool for mining stone?",
        List.of("Hoe", "Pickaxe", "Silk Touch pickaxe", "Efficiency pickaxe"), 0));
    list.add(new QuestionEntry("Which would be the most sensible item to bring when exploring a huge cave?",
        List.of("Torches", "A flower pot", "A music disc only", "A fishing rod with no water"), 0));
    list.add(new QuestionEntry("Which would be the worst place to build a wooden house if your only concern is fire risk?",
        List.of("Beside exposed lava", "On a grassy plain", "On a beach", "In a snowy biome"), 0));
    list.add(new QuestionEntry("If your goal is maximum mining speed, which enchantment would you want on your pickaxe?",
        List.of("Efficiency", "Respiration", "Knockback", "Loyalty"), 0));
    list.add(new QuestionEntry("If you want to keep the exact block you mine, which enchantment is the obvious choice?",
        List.of("Silk Touch", "Fortune", "Looting", "Mending"), 0));
    list.add(new QuestionEntry("If you want more diamonds from diamond ore, which enchantment is generally preferable?",
        List.of("Fortune", "Silk Touch", "Knockback", "Respiration"), 0));
    list.add(new QuestionEntry("Which is the safest answer if someone asks what to bring before entering the Nether?",
        List.of("Food and useful gear", "Only a flower", "Only a fishing rod", "Nothing"), 0));
    list.add(new QuestionEntry("Which would be the most ridiculous but technically usable Nether transportation choice?",
        List.of("A strider", "A cow", "A dolphin", "A polar bear"), 0));
    list.add(new QuestionEntry("If an Enderman is angry and you have no pumpkin, which environment is useful for avoiding its attacks?",
        List.of("A two-block-high shelter", "An open field", "A lava lake", "The top of a tree"), 0));
    list.add(new QuestionEntry("Which would be the worst mob to invite into a wooden house?",
        List.of("Creeper", "Cow", "Sheep", "Chicken"), 0));
    list.add(new QuestionEntry("Which would be the most sensible animal to bring into a farm if you want wool?",
        List.of("Sheep", "Pig", "Cow", "Chicken"), 0));
    list.add(new QuestionEntry("Which would be the least sensible item to use as a shield against an arrow?",
        List.of("A flower", "A shield", "A solid block", "A wall"), 0));
    list.add(new QuestionEntry("Which item would be the most useful if you expect to get lost underground?",
        List.of("A recovery compass or other navigation aid", "A cake only", "A flower pot", "A music disc"), 0));
    list.add(new QuestionEntry("Which is the most objective reason to carry a water bucket while mining?",
        List.of("It can help deal with lava", "It makes diamonds spawn", "It prevents hunger", "It repairs tools"), 0));
    list.add(new QuestionEntry("Which is the most useful thing to enchant with Mending?",
        List.of("A frequently used tool or piece of equipment", "A dirt block", "A torch", "A crafting table"), 0));
    list.add(new QuestionEntry("Which would be the worst place to leave a bed?",
        List.of("The Nether", "A secure Overworld base", "A village", "A well-lit bedroom"), 0));

    return list;
    }

    private static List<PromptEntry> samplePrompts() {
    List<PromptEntry> list = new ArrayList<>();

    list.add(new PromptEntry("The real reason Steve punches trees is _____."));
    list.add(new PromptEntry("Minecraft would be much easier if Creepers were replaced with _____."));
    list.add(new PromptEntry("The first thing every new Minecraft player secretly wants is _____."));
    list.add(new PromptEntry("The worst possible item to find in a dungeon chest is _____."));
    list.add(new PromptEntry("A villager's true dream job is _____."));
    list.add(new PromptEntry("The secret purpose of the Ender Dragon is _____."));
    list.add(new PromptEntry("The least useful enchantment would be called _____."));
    list.add(new PromptEntry("Steve's autobiography is titled _____."));
    list.add(new PromptEntry("The Enderman's biggest weakness, besides water, is _____."));
    list.add(new PromptEntry("The first thing a Creeper says before exploding is _____."));
    list.add(new PromptEntry("A Minecraft restaurant famous for serving only one thing would serve _____."));
    list.add(new PromptEntry("The suspicious thing hidden under this village is _____."));
    list.add(new PromptEntry("The real reason villagers say 'hmm' is _____."));
    list.add(new PromptEntry("If the Warden opened a restaurant, its specialty would be _____."));
    list.add(new PromptEntry("The worst possible thing to hear while mining diamonds is _____."));
    list.add(new PromptEntry("The official sport of the Nether should be _____."));
    list.add(new PromptEntry("A Creeper's dating profile would say _____."));
    list.add(new PromptEntry("The least intimidating Minecraft boss would be _____."));
    list.add(new PromptEntry("The Ender Dragon secretly collects _____."));
    list.add(new PromptEntry("If Minecraft had taxes, the first thing taxed would be _____."));
    list.add(new PromptEntry("The real reason the sun rises every morning in Minecraft is _____."));
    list.add(new PromptEntry("The most suspicious thing an Allay could bring you is _____."));
    list.add(new PromptEntry("A villager opened a nightclub called _____."));
    list.add(new PromptEntry("The Nether's tourism slogan should be _____."));
    list.add(new PromptEntry("The first rule of surviving a cave is _____."));
    list.add(new PromptEntry("Steve's emergency contact is _____."));
    list.add(new PromptEntry("The most embarrassing thing a Minecraft speedrunner could forget is _____."));
    list.add(new PromptEntry("The Ender Dragon's favorite hobby is _____."));
    list.add(new PromptEntry("A Piglin's idea of fine dining is _____."));
    list.add(new PromptEntry("The one thing zombies are secretly afraid of is _____."));
    list.add(new PromptEntry("If a Creeper became a teacher, it would teach _____."));
    list.add(new PromptEntry("The most useless thing to put in an Ender chest is _____."));
    list.add(new PromptEntry("The Warden's ringtone is _____."));
    list.add(new PromptEntry("A Skeleton's favorite music genre is _____."));
    list.add(new PromptEntry("The real reason Ghasts cry is _____."));
    list.add(new PromptEntry("A wandering trader's suspicious side business is _____."));
    list.add(new PromptEntry("The worst possible thing to discover in your base is _____."));
    list.add(new PromptEntry("The secret fourth dimension of Minecraft is _____."));
    list.add(new PromptEntry("If villagers had social media, their trending topic would be _____."));
    list.add(new PromptEntry("A Minecraft courtroom would be most likely to prosecute _____."));
    list.add(new PromptEntry("The most awkward thing to say while fighting the Ender Dragon is _____."));
    list.add(new PromptEntry("The Minecraft Olympics should add _____."));
    list.add(new PromptEntry("The one item Steve absolutely refuses to explain is _____."));
    list.add(new PromptEntry("A Shulker's worst nightmare is _____."));
    list.add(new PromptEntry("The real treasure inside a desert temple is _____."));
    list.add(new PromptEntry("The Nether's most popular dating app is called _____."));
    list.add(new PromptEntry("The End's worst tourist attraction is _____."));
    list.add(new PromptEntry("A zombie's perfect vacation would be _____."));
    list.add(new PromptEntry("The most suspicious villager trade ever offered would be _____."));
    list.add(new PromptEntry("If animals could talk for one minute, the first thing a cow would say is _____."));
    list.add(new PromptEntry("The worst possible thing to say on a first date."));
    list.add(new PromptEntry("A terrible superpower that would ruin your life."));
    list.add(new PromptEntry("The real reason your alarm clock did not go off."));
    list.add(new PromptEntry("An unusual thing to find in your grandmother's freezer."));
    list.add(new PromptEntry("A new olympic sport nobody asked for."));
    list.add(new PromptEntry("The strangest possible reason to be late for work."));
    list.add(new PromptEntry("What your pet would say if it could talk for one minute."));
    list.add(new PromptEntry("A very bad theme for a wedding."));
    list.add(new PromptEntry("The last thing you want to hear from your pilot."));
    list.add(new PromptEntry("A questionable new flavor of ice cream."));
    list.add(new PromptEntry("The most suspicious item to buy at three in the morning."));
    list.add(new PromptEntry("An unexpected use for a rubber duck."));
    list.add(new PromptEntry("What aliens would report back after visiting your kitchen."));
    list.add(new PromptEntry("A truly cursed pizza topping combination."));
    list.add(new PromptEntry("The strangest thing to whisper in an elevator."));
    list.add(new PromptEntry("A terrible new holiday tradition."));
    list.add(new PromptEntry("What is definitely inside that locked drawer."));
    list.add(new PromptEntry("A new rule for a board game that ruins friendships."));
    list.add(new PromptEntry("The most dramatic way to quit a job."));
    list.add(new PromptEntry("An animal that would make a surprisingly good lawyer."));
    list.add(new PromptEntry("A ridiculous thing to be world champion at."));
    list.add(new PromptEntry("What your search history says about you."));
    list.add(new PromptEntry("The strangest thing to keep in a wallet."));
    list.add(new PromptEntry("A new invention that solves a problem nobody has."));
    list.add(new PromptEntry("The reason the vending machine ate your money."));
    list.add(new PromptEntry("An honest slogan for Monday morning."));
    list.add(new PromptEntry("A very bad time to start laughing."));
    list.add(new PromptEntry("What you would put on a billboard in the middle of nowhere."));
    list.add(new PromptEntry("The weirdest possible group chat name."));
    list.add(new PromptEntry("A new fear that should absolutely exist."));
    list.add(new PromptEntry("The real reason the cat is staring at the wall."));
    list.add(new PromptEntry("An absurd item to bring to a job interview."));
    list.add(new PromptEntry("What would be written on your statue's plaque."));
    list.add(new PromptEntry("A really bad reason to cancel plans."));
    list.add(new PromptEntry("The strangest thing to say while shaking someone's hand."));
    list.add(new PromptEntry("A new emoji that the world urgently needs."));
    list.add(new PromptEntry("What is definitely hiding under the couch."));
    list.add(new PromptEntry("An unhelpful thing to shout during an emergency."));
    list.add(new PromptEntry("A terrible motivational poster caption."));
    list.add(new PromptEntry("The most unnecessary thing to bring camping."));
    list.add(new PromptEntry("What your refrigerator would post on social media."));
    list.add(new PromptEntry("A strange smell you would not want in a hotel room."));
    list.add(new PromptEntry("The worst possible name for a rescue helicopter."));
    list.add(new PromptEntry("A terrible name for a new energy drink."));
    list.add(new PromptEntry("The least reassuring thing a doctor could say."));
    list.add(new PromptEntry("The worst possible thing to find in your soup."));
    list.add(new PromptEntry("A terrible name for a daycare center."));
    list.add(new PromptEntry("The least reassuring sound to hear from your car engine."));
    list.add(new PromptEntry("The worst possible slogan for an airline."));
    list.add(new PromptEntry("A terrible name for a Minecraft mod."));
    list.add(new PromptEntry("The least reassuring message in a fortune cookie."));
    list.add(new PromptEntry("The worst possible thing to name your redstone machine."));
    list.add(new PromptEntry("A terrible name for a fishing boat."));
    list.add(new PromptEntry("The least reassuring thing written on a parachute."));
    list.add(new PromptEntry("The worst possible mascot for a bank."));
    list.add(new PromptEntry("A terrible name for a bakery."));
    list.add(new PromptEntry("The least reassuring thing to hear from tech support."));
    list.add(new PromptEntry("The worst possible name for a horse."));
    list.add(new PromptEntry("A terrible name for a survival multiplayer world."));
    list.add(new PromptEntry("The least reassuring warning label on a toy."));
    list.add(new PromptEntry("The worst possible thing to write on a birthday cake."));
    list.add(new PromptEntry("A terrible name for a self-driving car."));
    list.add(new PromptEntry("The least reassuring thing to find in an elevator."));
    list.add(new PromptEntry("The worst possible name for a nether portal hub."));

    return list;
    }
}
