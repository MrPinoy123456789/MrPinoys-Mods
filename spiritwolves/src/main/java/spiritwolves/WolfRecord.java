package spiritwolves;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The full state of one player's spirit wolf -- everything that used to live
 * on the stone, plus the v4 soul/ability progression. Mutable by design: this is
 * mutated constantly by binding, summoning, combat, and the ability commands.
 * See SPEC.md section 16.1 for the schema this mirrors.
 */
final class WolfRecord {

    UUID wolfUuid;
    CompoundTag wolfTag;
    String wolfName;
    String collar;
    boolean summoned;
    long boundAt;
    long lastSummonedAt;
    int saveCount;
    int bestStreak;
    final List<JournalEntry> journal = new ArrayList<>();
    long souls;
    final Map<String, Integer> familyKills = new HashMap<>();
    final Map<String, AbilityRecord> abilities = new HashMap<>();

    static final class JournalEntry {
        String k;
        String t;

        JournalEntry(String k, String t) {
            this.k = k;
            this.t = t;
        }
    }

    static final class AbilityRecord {
        int tier;
        int attunedTier;
        int fillKills;
        boolean equipped;
    }

    /** Writes an entry, replacing any existing entry with the same key in place. */
    void addJournalEntry(String key, String text) {
        for (JournalEntry entry : journal) {
            if (entry.k.equals(key)) {
                entry.t = text;
                return;
            }
        }
        journal.add(new JournalEntry(key, text));
    }

    boolean hasJournalEntry(String key) {
        for (JournalEntry entry : journal) {
            if (entry.k.equals(key)) {
                return true;
            }
        }
        return false;
    }

    List<String> journalLines() {
        List<String> out = new ArrayList<>();
        for (JournalEntry entry : journal) {
            out.add(entry.t);
        }
        return out;
    }

    AbilityRecord ability(String id) {
        return abilities.computeIfAbsent(id, k -> new AbilityRecord());
    }

    CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("wolfUuid", wolfUuid.toString());
        tag.put("wolfTag", wolfTag);
        if (wolfName != null) {
            tag.putString("wolfName", wolfName);
        }
        if (collar != null) {
            tag.putString("collar", collar);
        }
        tag.putBoolean("summoned", summoned);
        tag.putLong("boundAt", boundAt);
        tag.putLong("lastSummonedAt", lastSummonedAt);
        tag.putInt("saveCount", saveCount);
        tag.putInt("bestStreak", bestStreak);
        tag.putLong("souls", souls);

        ListTag journalList = new ListTag();
        for (JournalEntry entry : journal) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("k", entry.k);
            entryTag.putString("t", entry.t);
            journalList.add(entryTag);
        }
        tag.put("journal", journalList);

        CompoundTag familyKillsTag = new CompoundTag();
        for (Map.Entry<String, Integer> entry : familyKills.entrySet()) {
            familyKillsTag.putInt(entry.getKey(), entry.getValue());
        }
        tag.put("familyKills", familyKillsTag);

        CompoundTag abilitiesTag = new CompoundTag();
        for (Map.Entry<String, AbilityRecord> entry : abilities.entrySet()) {
            CompoundTag abilityTag = new CompoundTag();
            AbilityRecord ability = entry.getValue();
            abilityTag.putInt("tier", ability.tier);
            abilityTag.putInt("attunedTier", ability.attunedTier);
            abilityTag.putInt("fillKills", ability.fillKills);
            abilityTag.putBoolean("equipped", ability.equipped);
            abilitiesTag.put(entry.getKey(), abilityTag);
        }
        tag.put("abilities", abilitiesTag);

        return tag;
    }

    static WolfRecord fromTag(CompoundTag tag) {
        WolfRecord rec = new WolfRecord();
        rec.wolfUuid = UUID.fromString(tag.getStringOr("wolfUuid", ""));
        rec.wolfTag = tag.getCompoundOrEmpty("wolfTag");
        rec.wolfName = tag.contains("wolfName") ? tag.getStringOr("wolfName", null) : null;
        rec.collar = tag.contains("collar") ? tag.getStringOr("collar", null) : null;
        rec.summoned = tag.getBooleanOr("summoned", false);
        rec.boundAt = tag.getLongOr("boundAt", 0L);
        rec.lastSummonedAt = tag.getLongOr("lastSummonedAt", 0L);
        rec.saveCount = tag.getIntOr("saveCount", 0);
        rec.bestStreak = tag.getIntOr("bestStreak", 0);
        rec.souls = tag.getLongOr("souls", 0L);

        ListTag journalList = tag.getListOrEmpty("journal");
        for (int i = 0; i < journalList.size(); i++) {
            CompoundTag entryTag = journalList.getCompoundOrEmpty(i);
            String k = entryTag.getStringOr("k", "");
            String t = entryTag.getStringOr("t", "");
            if (!k.isBlank()) {
                rec.journal.add(new JournalEntry(migrateKey(k), migrateText(t)));
            }
        }

        CompoundTag familyKillsTag = tag.getCompoundOrEmpty("familyKills");
        for (String key : familyKillsTag.keySet()) {
            rec.familyKills.put(migrateId(key), familyKillsTag.getIntOr(key, 0));
        }

        // "abilities" is the current key; "fangs" and before that "verbs" are the
        // older ones. Read whichever is present so a wolf saved before either
        // rename keeps its tiers, attunements, and fill progress.
        CompoundTag abilitiesTag = firstPresent(tag, "abilities", "fangs", "verbs");
        for (String key : abilitiesTag.keySet()) {
            String id = migrateId(key);
            // Drop abilities that no longer exist (e.g. the removed "predator").
            // An orphan record is invisible in the GUI but would still count
            // against the equipped-slot limit in Abilities.canEquip.
            if (Abilities.byId(id) == null) {
                continue;
            }
            CompoundTag abilityTag = abilitiesTag.getCompoundOrEmpty(key);
            AbilityRecord ability = new AbilityRecord();
            ability.tier = abilityTag.getIntOr("tier", 0);
            ability.attunedTier = abilityTag.getIntOr("attunedTier", 0);
            ability.fillKills = abilityTag.getIntOr("fillKills", 0);
            ability.equipped = abilityTag.getBooleanOr("equipped", false);
            rec.abilities.put(id, ability);
        }

        return rec;
    }

    // ---- save migration ------------------------------------------------------
    // The §18 progression system has been renamed twice: "verbs" -> "fangs" ->
    // "abilities" (now split into the Fang and Trick categories). Individual
    // abilities were renamed with it, and "predator" was deleted. Everything
    // below rewrites old saves on load; new saves only ever contain current
    // names, so this is pure read-side compatibility. Delete it once no world
    // predating the renames is still in play.

    private static CompoundTag firstPresent(CompoundTag tag, String... keys) {
        for (String key : keys) {
            CompoundTag found = tag.getCompoundOrEmpty(key);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return new CompoundTag();
    }

    private static String migrateId(String id) {
        return switch (id) {
            case "witherbite" -> "witherfang";
            case "blinkstrike" -> "voidfang";
            case "scavenger" -> "fetch";
            case "light" -> "shine";
            default -> id;
        };
    }

    /**
     * Journal keys are {@code <id>_unlock} / {@code <id>_tier<n>}. They used to
     * carry a {@code verb_} and later a {@code fang_} prefix; strip either, and
     * migrate the id that follows it.
     */
    private static String migrateKey(String key) {
        String rest;
        if (key.startsWith("verb_")) {
            rest = key.substring("verb_".length());
        } else if (key.startsWith("fang_")) {
            rest = key.substring("fang_".length());
        } else {
            return key;
        }
        int underscore = rest.indexOf('_');
        if (underscore < 0) {
            return migrateId(rest);
        }
        return migrateId(rest.substring(0, underscore)) + rest.substring(underscore);
    }

    /** Journal text bakes in the display name at the moment it was earned. */
    private static String migrateText(String text) {
        return text.replace("Witherbite", "Witherfang")
                .replace("Blinkstrike", "Voidfang")
                .replace("Scavenger", "Fetch")
                .replace("Learned Light", "Learned Shine");
    }
}
