package spiritwolves;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.wolf.Wolf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verbs -- unlocked by deed, tiered by diamonds (SPEC.md section 18.2). Each
 * verb is themed to a mob family; killing that family teaches the wolf the
 * verb, and diamonds pay to attune the next tier, which is then filled by
 * more kills. All per-verb state lives in {@code WolfRecord.verbs}, keyed by
 * verb id, alongside the raw family kill count in {@code WolfRecord.familyKills}.
 */
public final class Verbs {

    /** Family kills needed to unlock tier I for free. */
    static final int UNLOCK_KILLS = 20;

    /** Diamonds required to attune tier II / tier III. Index 2 and 3 used. */
    static final int[] ATTUNE_COST = { 0, 0, 4, 16 };

    /** Family kills required to fill tier II / tier III once attuned. Index 2 and 3 used. */
    static final int[] FILL_REQUIREMENT = { 0, 0, 50, 150 };

    enum State { HIDDEN, SCENTED, UNLOCKED, ATTUNED }

    static final class Verb {
        final String id;
        final String displayName;
        final Set<EntityType<?>> family;
        final String familyLabel;
        final String unlockAdjective;
        final String unlockJournalSource;
        final String[] tierEffect = new String[4];
        final String[] fillVerbPhrase = new String[4];
        final String[] fillJournalLine = new String[4];

        Verb(String id, String displayName, String familyLabel, String unlockAdjective,
             String unlockJournalSource, Set<EntityType<?>> family) {
            this.id = id;
            this.displayName = displayName;
            this.familyLabel = familyLabel;
            this.unlockAdjective = unlockAdjective;
            this.unlockJournalSource = unlockJournalSource;
            this.family = family;
        }

        String id() {
            return id;
        }

        String displayName() {
            return displayName;
        }
    }

    static final Verb EMBERFANG = verb("emberfang", "Emberfang", "blazes", "burning",
            "the Nether's flames", EntityTypes.BLAZE, EntityTypes.MAGMA_CUBE);
    static final Verb VENOMFANG = verb("venomfang", "Venomfang", "spiders", "venomous",
            "the bite of spiders", EntityTypes.SPIDER, EntityTypes.CAVE_SPIDER);
    static final Verb RAVENOUS = verb("ravenous", "Ravenous", "the restless dead", "hungering",
            "the flesh of the restless dead", EntityTypes.ZOMBIE, EntityTypes.DROWNED, EntityTypes.HUSK);
    static final Verb BONECHILL = verb("bonechill", "Bonechill", "skeletons", "chilling",
            "the frost of old bones", EntityTypes.SKELETON, EntityTypes.STRAY);
    static final Verb WITHERBITE = verb("witherbite", "Witherbite", "wither skeletons", "withering",
            "the wither's touch", EntityTypes.WITHER_SKELETON);
    static final Verb BLINKSTRIKE = verb("blinkstrike", "Blinkstrike", "endermen", "otherworldly",
            "the space between endermen", EntityTypes.ENDERMAN);

    static final List<Verb> ALL = List.of(EMBERFANG, VENOMFANG, RAVENOUS, BONECHILL, WITHERBITE, BLINKSTRIKE);

    private static final Map<String, Verb> BY_ID = new LinkedHashMap<>();

    static {
        for (Verb verb : ALL) {
            BY_ID.put(verb.id, verb);
        }

        EMBERFANG.tierEffect[1] = "Bite ignites the target for 2s.";
        EMBERFANG.tierEffect[2] = "Bite ignites for 4s; small fire resistance aura for the owner nearby.";
        EMBERFANG.tierEffect[3] = "Bite ignites for 6s; the wolf is immune to fire.";
        EMBERFANG.fillVerbPhrase[2] = "burns hotter";
        EMBERFANG.fillVerbPhrase[3] = "burns hottest";
        EMBERFANG.fillJournalLine[2] = "Mastered the fire of fifty blazes.";
        EMBERFANG.fillJournalLine[3] = "Consumed the fire of a hundred and fifty blazes.";

        VENOMFANG.tierEffect[1] = "Bite poisons the target for 3s.";
        VENOMFANG.tierEffect[2] = "Bite poisons for 5s, Poison II.";
        VENOMFANG.tierEffect[3] = "Bite poisons for 8s, Poison II, and Slowness.";
        VENOMFANG.fillVerbPhrase[2] = "grows more venomous";
        VENOMFANG.fillVerbPhrase[3] = "grows deadliest";
        VENOMFANG.fillJournalLine[2] = "Mastered the venom of fifty spiders.";
        VENOMFANG.fillJournalLine[3] = "Consumed the venom of a hundred and fifty spiders.";

        RAVENOUS.tierEffect[1] = "The wolf heals 1 heart per kill.";
        RAVENOUS.tierEffect[2] = "The wolf also heals 0.5 hearts per hit landed.";
        RAVENOUS.tierEffect[3] = "Healing beyond full becomes absorption, capped at 2 hearts.";
        RAVENOUS.fillVerbPhrase[2] = "hungers deeper";
        RAVENOUS.fillVerbPhrase[3] = "hungers deepest";
        RAVENOUS.fillJournalLine[2] = "Mastered the hunger of fifty of the restless dead.";
        RAVENOUS.fillJournalLine[3] = "Consumed a hundred and fifty of the restless dead.";

        BONECHILL.tierEffect[1] = "Bite slows the target for 2s.";
        BONECHILL.tierEffect[2] = "Bite slows for 4s, Slowness II.";
        BONECHILL.tierEffect[3] = "Bite slows for 4s, Slowness II, and Weakness on the target.";
        BONECHILL.fillVerbPhrase[2] = "bites colder";
        BONECHILL.fillVerbPhrase[3] = "bites coldest";
        BONECHILL.fillJournalLine[2] = "Mastered the chill of fifty skeletons.";
        BONECHILL.fillJournalLine[3] = "Consumed the chill of a hundred and fifty skeletons.";

        WITHERBITE.tierEffect[1] = "Bite withers the target for 2s.";
        WITHERBITE.tierEffect[2] = "Bite withers for 4s.";
        WITHERBITE.tierEffect[3] = "Bite withers for 6s, Wither II.";
        WITHERBITE.fillVerbPhrase[2] = "festers darker";
        WITHERBITE.fillVerbPhrase[3] = "festers darkest";
        WITHERBITE.fillJournalLine[2] = "Mastered the rot of fifty wither skeletons.";
        WITHERBITE.fillJournalLine[3] = "Consumed the rot of a hundred and fifty wither skeletons.";

        BLINKSTRIKE.tierEffect[1] = "The wolf teleports to its target when more than 8 blocks away.";
        BLINKSTRIKE.tierEffect[2] = "The blink cooldown is halved.";
        BLINKSTRIKE.tierEffect[3] = "The owner's marked prey is also a valid blink target.";
        BLINKSTRIKE.fillVerbPhrase[2] = "strikes faster";
        BLINKSTRIKE.fillVerbPhrase[3] = "strikes fastest";
        BLINKSTRIKE.fillJournalLine[2] = "Mastered the stride of fifty endermen.";
        BLINKSTRIKE.fillJournalLine[3] = "Consumed the stride of a hundred and fifty endermen.";
    }

    private Verbs() {}

    private static Verb verb(String id, String displayName, String familyLabel, String unlockAdjective,
                              String unlockJournalSource, EntityType<?>... family) {
        return new Verb(id, displayName, familyLabel, unlockAdjective, unlockJournalSource, Set.of(family));
    }

    static Verb byId(String id) {
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

    /** The verb whose family this kill belongs to, or null. */
    private static Verb familyOf(Entity killed) {
        for (Verb verb : ALL) {
            if (verb.family.contains(killed.getType())) {
                return verb;
            }
        }
        return null;
    }

    static State stateOf(WolfRecord record, Verb verb) {
        int kills = record.familyKills.getOrDefault(verb.id, 0);
        WolfRecord.VerbRecord vr = record.verbs.get(verb.id);
        int tier = vr == null ? 0 : vr.tier;
        int attunedTier = vr == null ? 0 : vr.attunedTier;

        if (tier <= 0) {
            if (kills <= 0) {
                return State.HIDDEN;
            }
            return kills < UNLOCK_KILLS ? State.SCENTED : State.UNLOCKED;
        }
        return attunedTier > tier ? State.ATTUNED : State.UNLOCKED;
    }

    /** {@link WolfKill} listener: family kills always count; unlock/fill happen here. */
    static void onKill(Wolf wolf, ServerPlayer owner, WolfRecord record, Entity killed) {
        Verb verb = familyOf(killed);
        if (verb == null) {
            return;
        }

        int kills = record.familyKills.merge(verb.id, 1, Integer::sum);
        WolfRecord.VerbRecord vr = record.verb(verb.id);
        PlayerWolfRegistry.markDirty(owner.getUUID());

        if (vr.tier <= 0) {
            if (kills >= UNLOCK_KILLS) {
                vr.tier = 1;
                announceUnlock(owner, record, verb);
            }
            return;
        }

        if (vr.attunedTier > vr.tier) {
            vr.fillKills++;
            int requirement = FILL_REQUIREMENT[vr.attunedTier];
            if (vr.fillKills >= requirement) {
                vr.tier = vr.attunedTier;
                vr.fillKills = 0;
                announceFill(owner, record, verb, vr.tier);
            }
        }
    }

    private static void announceUnlock(ServerPlayer owner, WolfRecord record, Verb verb) {
        String name = record.wolfName != null ? record.wolfName : "Your wolf";
        owner.sendSystemMessage(Component.literal(
                        name + " has absorbed enough " + verb.unlockAdjective + " souls. "
                                + verb.displayName + " unlocked.")
                .withStyle(ChatFormatting.GOLD));
        record.addJournalEntry("verb_" + verb.id + "_unlock",
                "Learned " + verb.displayName + " from " + verb.unlockJournalSource + ".");
        Chime.verbTierUp(owner);
    }

    private static void announceFill(ServerPlayer owner, WolfRecord record, Verb verb, int tier) {
        owner.sendSystemMessage(Component.literal(
                        verb.displayName + " " + verb.fillVerbPhrase[tier] + ". Tier " + roman(tier) + ".")
                .withStyle(ChatFormatting.GOLD));
        record.addJournalEntry("verb_" + verb.id + "_tier" + tier, verb.fillJournalLine[tier]);
        Chime.verbTierUp(owner);
    }

    // ---- attune / equip / unequip validation --------------------------------

    /** Result of a validation check: null means allowed, otherwise the rejection message. */
    static String canAttune(ServerPlayer player, WolfRecord record, Verb verb, int diamondsHeld) {
        WolfRecord.VerbRecord vr = record.verbs.get(verb.id);
        int tier = vr == null ? 0 : vr.tier;
        int attunedTier = vr == null ? 0 : vr.attunedTier;

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

    static String canEquip(WolfRecord record, Verb verb) {
        if (record.summoned) {
            return "Recall your wolf to change its verbs.";
        }
        WolfRecord.VerbRecord vr = record.verbs.get(verb.id);
        if (vr == null || vr.tier <= 0) {
            return "Not yet unlocked.";
        }
        if (vr.equipped) {
            return "Already equipped.";
        }
        int equippedCount = 0;
        for (WolfRecord.VerbRecord other : record.verbs.values()) {
            if (other.equipped) {
                equippedCount++;
            }
        }
        int slots = Souls.slotsFor(levelFor(record));
        if (equippedCount >= slots) {
            return "No free verb slots. Unequip one first.";
        }
        return null;
    }

    static String canUnequip(WolfRecord record, Verb verb) {
        if (record.summoned) {
            return "Recall your wolf to change its verbs.";
        }
        WolfRecord.VerbRecord vr = record.verbs.get(verb.id);
        if (vr == null || !vr.equipped) {
            return "Not equipped.";
        }
        return null;
    }
}
