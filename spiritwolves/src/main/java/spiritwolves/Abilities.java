package spiritwolves;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.wolf.Wolf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the wolf can learn, in two families (SPEC.md section 18.2).
 *
 * <p><b>Fangs</b> are combat: passive procs on the wolf's own attacks and kills,
 * themed to a mob family, unlocked by killing enough of it. <b>Tricks</b> are
 * utility: things the wolf does for you, trained by the deed they resemble --
 * mine ore near the wolf to train Dig, let mobs turn on you to train Speak. Each
 * family has its own equip slots ({@link Souls#fangSlotsFor} and
 * {@link Souls#trickSlotsFor}), so carrying a nose never costs you a bite.
 *
 * <p>Both families share one progression shape: a free tier I at the unlock
 * goal, then diamonds to <i>attune</i> the next tier and more of the same deed
 * to <i>fill</i> it. All per-ability state lives in {@code WolfRecord.abilities},
 * keyed by ability id, alongside the raw progress count in {@code
 * WolfRecord.familyKills}.
 *
 * <p><b>Naming history.</b> This system was called <i>verbs</i> until it was
 * renamed to <i>fangs</i>, and then split into fangs + tricks when the utility
 * abilities outgrew the bite metaphor. The classes were {@code Verbs} then
 * {@code Fangs} then {@code Abilities}, likewise for the commands/procs/GUI; the save
 * key went {@code verbs} to {@code fangs} to {@code abilities}. Renamed along
 * the way: {@code witherbite} to {@code witherfang}, {@code blinkstrike} to
 * {@code voidfang}, {@code scavenger} to {@code fetch}, {@code light} to {@code
 * shine}. {@code predator} was deleted outright as a duplicate of Ravenous.
 * Nothing named "verb" survives here; other mods in the suite use that word for
 * their own unrelated systems. Old saves are migrated on read in
 * {@link WolfRecord#fromTag}.
 */
public final class Abilities {

    /** Diamonds required to attune tier II / tier III. Index 2 and 3 used. */
    static final int[] ATTUNE_COST = { 0, 0, 4, 16 };

    /** Further progress required to fill tier II / tier III once attuned. Index 2 and 3 used. */
    static final int[] FILL_REQUIREMENT = { 0, 0, 50, 150 };

    enum State { HIDDEN, SCENTED, UNLOCKED, ATTUNED }

    /** The two families. Each has its own equip slots and its own flavor of unlock message. */
    enum Category {
        FANG("Fang", "Fangs"),
        TRICK("Trick", "Tricks");

        final String singular;
        final String plural;

        Category(String singular, String plural) {
            this.singular = singular;
            this.plural = plural;
        }
    }

    static final class Ability {
        final String id;
        final String displayName;
        final Category category;
        /** What the progress counter counts, in words: "blazes", "ores mined nearby". */
        final String progressLabel;
        final String unlockAdjective;
        final String unlockJournalSource;
        final int unlockGoal;
        /** Mob family whose deaths feed this ability. Empty for tricks trained another way. */
        final Set<EntityType<?>> family;
        final String[] tierEffect = new String[4];
        final String[] fillPhrase = new String[4];
        final String[] fillJournalLine = new String[4];

        Ability(String id, String displayName, Category category, String progressLabel,
                String unlockAdjective, String unlockJournalSource, int unlockGoal,
                Set<EntityType<?>> family) {
            this.id = id;
            this.displayName = displayName;
            this.category = category;
            this.progressLabel = progressLabel;
            this.unlockAdjective = unlockAdjective;
            this.unlockJournalSource = unlockJournalSource;
            this.unlockGoal = unlockGoal;
            this.family = family;
        }

        String id() {
            return id;
        }

        String displayName() {
            return displayName;
        }
    }

    // ---- fangs: combat, fed by a mob family ----------------------------------

    static final Ability EMBERFANG = fang("emberfang", "Emberfang", "blazes", "burning",
            "the Nether's flames", 20, EntityTypes.BLAZE, EntityTypes.MAGMA_CUBE);
    static final Ability VENOMFANG = fang("venomfang", "Venomfang", "spiders", "venomous",
            "the bite of spiders", 20, EntityTypes.SPIDER, EntityTypes.CAVE_SPIDER);
    static final Ability RAVENOUS = fang("ravenous", "Ravenous", "the restless dead", "hungering",
            "the flesh of the restless dead", 20, EntityTypes.ZOMBIE, EntityTypes.DROWNED, EntityTypes.HUSK);
    static final Ability BONECHILL = fang("bonechill", "Bonechill", "skeletons", "chilling",
            "the frost of old bones", 20, EntityTypes.SKELETON, EntityTypes.STRAY);
    static final Ability WITHERFANG = fang("witherfang", "Witherfang", "wither skeletons", "withering",
            "the wither's touch", 20, EntityTypes.WITHER_SKELETON);
    static final Ability VOIDFANG = fang("voidfang", "Voidfang", "endermen", "otherworldly",
            "the space between endermen", 20, EntityTypes.ENDERMAN);

    // Predator ("kill an edible animal, heal the wolf") was removed: Ravenous
    // already heals on every kill the wolf makes, animals included, so Predator
    // was a strict subset of it firing on the same event -- two heals for one
    // kill with both equipped, and nothing Ravenous could not do.

    // ---- tricks: utility, trained by the deed they resemble --------------------

    static final Ability FETCH = trick("fetch", "Fetch", "items brought back", "tireless",
            "a hundred retrievals", 100);
    static final Ability SHINE = trick("shine", "Shine", "lights placed nearby", "luminous",
            "the dark", 50);
    static final Ability DIG = trick("dig", "Dig", "ores mined nearby", "curious",
            "the scent of buried stone", 50);
    static final Ability SPEAK = trick("speak", "Speak", "mobs that turned on you", "watchful",
            "the smell of danger", 20);

    static final List<Ability> ALL = List.of(
            EMBERFANG, VENOMFANG, RAVENOUS, BONECHILL, WITHERFANG, VOIDFANG,
            FETCH, SHINE, DIG, SPEAK);

    static final List<Ability> FANGS = byCategory(Category.FANG);
    static final List<Ability> TRICKS = byCategory(Category.TRICK);

    private static final Map<String, Ability> BY_ID = new LinkedHashMap<>();

    static {
        for (Ability ability : ALL) {
            BY_ID.put(ability.id, ability);
        }

        EMBERFANG.tierEffect[1] = "Bite ignites the target for 2s.";
        EMBERFANG.tierEffect[2] = "Bite ignites for 4s; small fire resistance aura for the owner nearby.";
        EMBERFANG.tierEffect[3] = "Bite ignites for 6s; the wolf is immune to fire.";
        EMBERFANG.fillPhrase[2] = "burns hotter";
        EMBERFANG.fillPhrase[3] = "burns hottest";
        EMBERFANG.fillJournalLine[2] = "Mastered the fire of fifty blazes.";
        EMBERFANG.fillJournalLine[3] = "Consumed the fire of a hundred and fifty blazes.";

        VENOMFANG.tierEffect[1] = "Bite poisons the target for 3s.";
        VENOMFANG.tierEffect[2] = "Bite poisons for 5s, Poison II.";
        VENOMFANG.tierEffect[3] = "Bite poisons for 8s, Poison II, and Slowness.";
        VENOMFANG.fillPhrase[2] = "grows more venomous";
        VENOMFANG.fillPhrase[3] = "grows deadliest";
        VENOMFANG.fillJournalLine[2] = "Mastered the venom of fifty spiders.";
        VENOMFANG.fillJournalLine[3] = "Consumed the venom of a hundred and fifty spiders.";

        RAVENOUS.tierEffect[1] = "The wolf heals 1 heart per kill.";
        RAVENOUS.tierEffect[2] = "The wolf also heals 0.5 hearts per hit landed.";
        RAVENOUS.tierEffect[3] = "Healing beyond full becomes absorption, capped at 2 hearts.";
        RAVENOUS.fillPhrase[2] = "hungers deeper";
        RAVENOUS.fillPhrase[3] = "hungers deepest";
        RAVENOUS.fillJournalLine[2] = "Mastered the hunger of fifty of the restless dead.";
        RAVENOUS.fillJournalLine[3] = "Consumed a hundred and fifty of the restless dead.";

        BONECHILL.tierEffect[1] = "Bite slows the target for 2s.";
        BONECHILL.tierEffect[2] = "Bite slows for 4s, Slowness II.";
        BONECHILL.tierEffect[3] = "Bite slows for 4s, Slowness II, and Weakness on the target.";
        BONECHILL.fillPhrase[2] = "bites colder";
        BONECHILL.fillPhrase[3] = "bites coldest";
        BONECHILL.fillJournalLine[2] = "Mastered the chill of fifty skeletons.";
        BONECHILL.fillJournalLine[3] = "Consumed the chill of a hundred and fifty skeletons.";

        WITHERFANG.tierEffect[1] = "Bite withers the target for 2s.";
        WITHERFANG.tierEffect[2] = "Bite withers for 4s.";
        WITHERFANG.tierEffect[3] = "Bite withers for 6s, Wither II.";
        WITHERFANG.fillPhrase[2] = "festers darker";
        WITHERFANG.fillPhrase[3] = "festers darkest";
        WITHERFANG.fillJournalLine[2] = "Mastered the rot of fifty wither skeletons.";
        WITHERFANG.fillJournalLine[3] = "Consumed the rot of a hundred and fifty wither skeletons.";

        VOIDFANG.tierEffect[1] = "The wolf teleports to its target when more than 8 blocks away.";
        VOIDFANG.tierEffect[2] = "The blink cooldown is halved.";
        VOIDFANG.tierEffect[3] = "The owner's marked prey is also a valid blink target.";
        VOIDFANG.fillPhrase[2] = "blinks faster";
        VOIDFANG.fillPhrase[3] = "blinks fastest";
        VOIDFANG.fillJournalLine[2] = "Mastered the stride of fifty endermen.";
        VOIDFANG.fillJournalLine[3] = "Consumed the stride of a hundred and fifty endermen.";

        FETCH.tierEffect[1] = "The wolf also gathers loose, unowned items within 5 blocks.";
        FETCH.tierEffect[2] = "Gathering radius increases to 8 blocks.";
        FETCH.tierEffect[3] = "Gathering radius increases to 12 blocks.";
        FETCH.fillPhrase[2] = "ranges farther";
        FETCH.fillPhrase[3] = "ranges farthest";
        FETCH.fillJournalLine[2] = "Learned to carry more from the battlefield.";
        FETCH.fillJournalLine[3] = "Became a relentless gatherer of the battlefield.";

        SHINE.tierEffect[1] = "The wolf emits a faint glow, visible through walls.";
        SHINE.tierEffect[2] = "The wolf's glow grants the owner Night Vision within 8 blocks.";
        SHINE.tierEffect[3] = "Night Vision aura expands to 16 blocks.";
        SHINE.fillPhrase[2] = "glows brighter";
        SHINE.fillPhrase[3] = "glows brightest";
        SHINE.fillJournalLine[2] = "Kept fifty lights against the dark.";
        SHINE.fillJournalLine[3] = "Kept a hundred and fifty lights against the dark.";

        DIG.tierEffect[1] = "Show it an ore or ingot: exposed matching ore within 12 blocks is revealed.";
        DIG.tierEffect[2] = "Reveal radius increases to 18 blocks.";
        DIG.tierEffect[3] = "Reveal radius increases to 24 blocks.";
        DIG.fillPhrase[2] = "noses deeper";
        DIG.fillPhrase[3] = "noses deepest";
        DIG.fillJournalLine[2] = "Learned the smell of fifty broken seams.";
        DIG.fillJournalLine[3] = "Knows every seam in a hundred and fifty stones.";

        SPEAK.tierEffect[1] = "Show it a mob drop: matching mobs within 24 blocks are revealed.";
        SPEAK.tierEffect[2] = "Reveal radius increases to 32 blocks.";
        SPEAK.tierEffect[3] = "Reveal radius increases to 48 blocks.";
        SPEAK.fillPhrase[2] = "speaks louder";
        SPEAK.fillPhrase[3] = "speaks loudest";
        SPEAK.fillJournalLine[2] = "Named fifty things that meant you harm.";
        SPEAK.fillJournalLine[3] = "Named a hundred and fifty things that meant you harm.";
    }

    private Abilities() {}

    private static Ability fang(String id, String displayName, String progressLabel, String unlockAdjective,
                                String unlockJournalSource, int unlockGoal, EntityType<?>... family) {
        return new Ability(id, displayName, Category.FANG, progressLabel, unlockAdjective,
                unlockJournalSource, unlockGoal, Set.of(family));
    }

    private static Ability trick(String id, String displayName, String progressLabel, String unlockAdjective,
                                 String unlockJournalSource, int unlockGoal) {
        return new Ability(id, displayName, Category.TRICK, progressLabel, unlockAdjective,
                unlockJournalSource, unlockGoal, Set.of());
    }

    private static List<Ability> byCategory(Category category) {
        List<Ability> out = new ArrayList<>();
        for (Ability ability : ALL) {
            if (ability.category == category) {
                out.add(ability);
            }
        }
        return List.copyOf(out);
    }

    static Ability byId(String id) {
        return BY_ID.get(id);
    }

    static String roman(int tier) {
        return switch (tier) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            default -> "-";
        };
    }

    /** The equipped tier of an ability, or 0 if unequipped or unknown. */
    static int equippedTier(WolfRecord record, Ability ability) {
        WolfRecord.AbilityRecord ar = record.abilities.get(ability.id);
        return ar == null || !ar.equipped ? 0 : ar.tier;
    }

    /** The fang whose family this kill belongs to, or null. */
    private static Ability familyOf(Entity killed) {
        for (Ability ability : FANGS) {
            if (ability.family.contains(killed.getType())) {
                return ability;
            }
        }
        return null;
    }

    static State stateOf(WolfRecord record, Ability ability) {
        int progress = record.familyKills.getOrDefault(ability.id, 0);
        WolfRecord.AbilityRecord ar = record.abilities.get(ability.id);
        int tier = ar == null ? 0 : ar.tier;
        int attunedTier = ar == null ? 0 : ar.attunedTier;

        if (tier <= 0) {
            if (progress <= 0) {
                return State.HIDDEN;
            }
            return progress < ability.unlockGoal ? State.SCENTED : State.UNLOCKED;
        }
        return attunedTier > tier ? State.ATTUNED : State.UNLOCKED;
    }

    /** {@link WolfKill} listener: family kills always count; unlock/fill happen here. */
    static void onKill(Wolf wolf, ServerPlayer owner, WolfRecord record, Entity killed) {
        onKill(owner, record, killed);
    }

    /**
     * Owner-only variant used by {@link Assists} when the wolf entity may have
     * already unloaded before the target died.
     */
    static void onKill(ServerPlayer owner, WolfRecord record, Entity killed) {
        Ability fang = familyOf(killed);
        if (fang != null) {
            onProgress(owner, record, fang, 1);
        }
    }

    /**
     * Applies {@code delta} progress toward an ability's unlock or fill. Abilities
     * call this from kills; tricks call it from {@link Training}.
     */
    static void onProgress(ServerPlayer owner, WolfRecord record, Ability ability, int delta) {
        int progress = record.familyKills.merge(ability.id, delta, Integer::sum);
        WolfRecord.AbilityRecord ar = record.ability(ability.id);
        PlayerWolfRegistry.markDirty(owner.getUUID());

        if (ar.tier <= 0) {
            if (progress >= ability.unlockGoal) {
                ar.tier = 1;
                announceUnlock(owner, record, ability);
            }
            return;
        }

        if (ar.attunedTier > ar.tier) {
            ar.fillKills += delta;
            int requirement = FILL_REQUIREMENT[ar.attunedTier];
            if (ar.fillKills >= requirement) {
                ar.tier = ar.attunedTier;
                ar.fillKills = 0;
                announceFill(owner, record, ability, ar.tier);
            }
        }
    }

    private static void announceUnlock(ServerPlayer owner, WolfRecord record, Ability ability) {
        String name = record.wolfName != null ? record.wolfName : "Your wolf";
        String line = ability.category == Category.FANG
                ? name + " has absorbed enough " + ability.unlockAdjective + " souls. "
                        + ability.displayName + " unlocked."
                : name + " has learned a new trick. " + ability.displayName + " unlocked.";
        owner.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.GOLD));
        record.addJournalEntry(ability.id + "_unlock",
                "Learned " + ability.displayName + " from " + ability.unlockJournalSource + ".");
        Chime.abilityTierUp(owner);
    }

    private static void announceFill(ServerPlayer owner, WolfRecord record, Ability ability, int tier) {
        owner.sendSystemMessage(Component.literal(
                        ability.displayName + " " + ability.fillPhrase[tier] + ". Tier " + roman(tier) + ".")
                .withStyle(ChatFormatting.GOLD));
        record.addJournalEntry(ability.id + "_tier" + tier, ability.fillJournalLine[tier]);
        Chime.abilityTierUp(owner);
    }

    // ---- attune / equip / unequip validation --------------------------------

    /** Slots this category grants at the wolf's current level. */
    static int slotsFor(WolfRecord record, Category category) {
        int level = levelFor(record);
        return category == Category.FANG ? Souls.fangSlotsFor(level) : Souls.trickSlotsFor(level);
    }

    /** How many of this category are currently equipped. */
    static int equippedCount(WolfRecord record, Category category) {
        int count = 0;
        for (Map.Entry<String, WolfRecord.AbilityRecord> entry : record.abilities.entrySet()) {
            Ability ability = byId(entry.getKey());
            if (ability != null && ability.category == category && entry.getValue().equipped) {
                count++;
            }
        }
        return count;
    }

    /** Result of a validation check: null means allowed, otherwise the rejection message. */
    static String canAttune(ServerPlayer player, WolfRecord record, Ability ability, int diamondsHeld) {
        WolfRecord.AbilityRecord ar = record.abilities.get(ability.id);
        int tier = ar == null ? 0 : ar.tier;
        int attunedTier = ar == null ? 0 : ar.attunedTier;

        if (tier <= 0) {
            return "Not yet unlocked.";
        }
        if (attunedTier > tier) {
            return "Fill Tier " + roman(attunedTier) + " first.";
        }
        int nextTier = tier + 1;
        if (nextTier > 3) {
            return "Already at maximum tier.";
        }
        int level = levelFor(record);
        if (Souls.maxAttunableTierFor(level) < nextTier) {
            return "Requires Level " + levelRequirementFor(nextTier) + ".";
        }
        int cost = ATTUNE_COST[nextTier];
        if (diamondsHeld < cost) {
            return "Requires " + cost + " diamonds -- you have " + diamondsHeld + ".";
        }
        return null;
    }

    static int levelRequirementFor(int tier) {
        return tier >= 3 ? 4 : 2;
    }

    private static int levelFor(WolfRecord record) {
        return Souls.levelFor(record.souls);
    }

    /**
     * Swapping is live -- a summoned wolf is no obstacle. Every consumer reads
     * {@link #equippedTier} at the moment it procs rather than caching a
     * loadout at summon time, so a fang swapped mid-fight takes effect on the
     * next tick or the next hit with no further wiring.
     *
     * <p>SPEC.md section 18.3 used to require the wolf be in the stone, to stop
     * mid-fight juggling. That gate is deliberately gone.
     */
    static String canEquip(WolfRecord record, Ability ability) {
        WolfRecord.AbilityRecord ar = record.abilities.get(ability.id);
        if (ar == null || ar.tier <= 0) {
            return "Not yet unlocked.";
        }
        if (ar.equipped) {
            return "Already equipped.";
        }
        if (equippedCount(record, ability.category) >= slotsFor(record, ability.category)) {
            return "No free " + ability.category.singular.toLowerCase()
                    + " slots. Set one aside first.";
        }
        return null;
    }

    /** @see #canEquip for why being summoned is not a rejection */
    static String canUnequip(WolfRecord record, Ability ability) {
        WolfRecord.AbilityRecord ar = record.abilities.get(ability.id);
        if (ar == null || !ar.equipped) {
            return "Not equipped.";
        }
        return null;
    }
}
