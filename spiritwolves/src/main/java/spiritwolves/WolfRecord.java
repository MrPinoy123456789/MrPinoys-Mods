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
 * on the stone, plus the v4 soul/verb progression. Mutable by design: this is
 * mutated constantly by binding, summoning, combat, and the verb commands.
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
    final Map<String, VerbRecord> verbs = new HashMap<>();

    static final class JournalEntry {
        String k;
        String t;

        JournalEntry(String k, String t) {
            this.k = k;
            this.t = t;
        }
    }

    static final class VerbRecord {
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

    VerbRecord verb(String id) {
        return verbs.computeIfAbsent(id, k -> new VerbRecord());
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

        CompoundTag verbsTag = new CompoundTag();
        for (Map.Entry<String, VerbRecord> entry : verbs.entrySet()) {
            CompoundTag verbTag = new CompoundTag();
            VerbRecord verb = entry.getValue();
            verbTag.putInt("tier", verb.tier);
            verbTag.putInt("attunedTier", verb.attunedTier);
            verbTag.putInt("fillKills", verb.fillKills);
            verbTag.putBoolean("equipped", verb.equipped);
            verbsTag.put(entry.getKey(), verbTag);
        }
        tag.put("verbs", verbsTag);

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
                rec.journal.add(new JournalEntry(k, t));
            }
        }

        CompoundTag familyKillsTag = tag.getCompoundOrEmpty("familyKills");
        for (String key : familyKillsTag.keySet()) {
            rec.familyKills.put(key, familyKillsTag.getIntOr(key, 0));
        }

        CompoundTag verbsTag = tag.getCompoundOrEmpty("verbs");
        for (String key : verbsTag.keySet()) {
            CompoundTag verbTag = verbsTag.getCompoundOrEmpty(key);
            VerbRecord verb = new VerbRecord();
            verb.tier = verbTag.getIntOr("tier", 0);
            verb.attunedTier = verbTag.getIntOr("attunedTier", 0);
            verb.fillKills = verbTag.getIntOr("fillKills", 0);
            verb.equipped = verbTag.getBooleanOr("equipped", false);
            rec.verbs.put(key, verb);
        }

        return rec;
    }
}
