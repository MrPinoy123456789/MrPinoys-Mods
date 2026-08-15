package kamutotems.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QuestCatalog {
    private final Map<String, QuestDefinition> byId;

    public QuestCatalog(List<QuestDefinition> definitions) {
        Map<String, QuestDefinition> map = new LinkedHashMap<>();
        if (definitions != null) {
            for (QuestDefinition d : definitions) {
                if (d != null && d.id() != null) {
                    map.put(d.id(), d);
                }
            }
        }
        this.byId = Collections.unmodifiableMap(map);
    }

    /** The sample set. Ships as the in-memory fallback if quests.json fails to parse. */
    public static QuestCatalog defaults() {
        List<QuestDefinition> defs = new ArrayList<>();

        defs.add(new QuestDefinition(
                "wayfarer_lost_cargo",
                "Lost Cargo",
                "A wayfarer asked this of you.",
                List.of(new QuestSegment("turn_in",
                        new EventMatcher("item_turned_in", Map.of("item", "minecraft:wheat"), 5),
                        "Hand over 5 minecraft:wheat")),
                List.of(new QuestReward("diamond", null, 3, null)),
                7,
                false));

        defs.add(new QuestDefinition(
                "wayfarer_cull_the_pack",
                "Cull the Pack",
                "A wayfarer asked this of you.",
                List.of(new QuestSegment("kill",
                        new EventMatcher("entity_killed", Map.of("entity", "minecraft:zombie"), 8),
                        "Put down 8 minecraft:zombie")),
                List.of(new QuestReward("sigil", null, 1, "A trial awaits.")),
                0,
                true));

        defs.add(new QuestDefinition(
                "wayfarer_survey_the_ridge",
                "Survey the Ridge",
                "A wayfarer asked this of you.",
                List.of(new QuestSegment("scan",
                        new EventMatcher("block_scanned", Map.of("block", "minecraft:iron_ore"), 4),
                        "Scan 4 minecraft:iron_ore")),
                List.of(new QuestReward("cobblestone", null, 32, null)),
                3,
                false));

        defs.add(new QuestDefinition(
                "wayfarer_two_favours",
                "Two Favours",
                "A wayfarer asked this of you.",
                List.of(
                        new QuestSegment("kill",
                                new EventMatcher("entity_killed", Map.of("entity", "minecraft:skeleton"), 4),
                                "Put down 4 minecraft:skeleton"),
                        new QuestSegment("turn_in",
                                new EventMatcher("item_turned_in", Map.of("item", "minecraft:coal"), 10),
                                "Hand over 10 minecraft:coal")),
                List.of(new QuestReward("item", "minecraft:emerald", 4, null)),
                14,
                false));

        return new QuestCatalog(defs);
    }

    public QuestDefinition get(String id) {
        return byId.get(id);
    }

    public List<QuestDefinition> all() {
        return new ArrayList<>(byId.values());
    }
}
