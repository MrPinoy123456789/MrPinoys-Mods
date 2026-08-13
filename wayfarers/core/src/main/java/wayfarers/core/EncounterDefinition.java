package wayfarers.core;

import java.util.List;
import java.util.Map;

/**
 * The §5 encounter schema as a record. Unknown blocks are tolerated by
 * stashing them in {@link #extras()}.
 */
public record EncounterDefinition(
        String id,
        int weight,
        String template,
        List<String> biomes,
        boolean nightOnly,
        List<String> weather,
        boolean once,
        boolean recurring,
        Body body,
        int expireMinutes,
        String dialogue,
        double gossipChance,
        List<Listing> sells,
        List<Listing> buys,
        int coin,
        String wants,
        String drops,
        Map<String, Object> extras) {

    public EncounterDefinition {
        if (biomes == null) biomes = List.of();
        if (weather == null) weather = List.of();
        if (body == null) body = new Body("minecraft:wandering_trader", "");
        if (expireMinutes < 1) expireMinutes = 15;
        if (dialogue == null) dialogue = "";
        if (Double.isNaN(gossipChance) || gossipChance < 0) gossipChance = 0.0;
        if (sells == null) sells = List.of();
        if (buys == null) buys = List.of();
        if (coin < 0) coin = 0;
        if (wants == null) wants = "";
        if (drops == null) drops = "";
        if (extras == null) extras = Map.of();
    }

    public EncounterDefinition(String id, int weight, String template) {
        this(id, weight, template, List.of(), false, List.of(), false, false,
                new Body("minecraft:wandering_trader", ""), 15, "", 0.0,
                List.of(), List.of(), 0, "", "", Map.of());
    }

    public EncounterDefinition(String id, int weight, String template,
                               List<String> biomes, boolean nightOnly,
                               List<String> weather, boolean once, boolean recurring) {
        this(id, weight, template, biomes, nightOnly, weather, once, recurring,
                new Body("minecraft:wandering_trader", ""), 15, "", 0.0,
                List.of(), List.of(), 0, "", "", Map.of());
    }

    public EncounterDefinition(String id, int weight, String template,
                               List<String> biomes, boolean nightOnly,
                               List<String> weather, boolean once, boolean recurring,
                               Body body, int expireMinutes) {
        this(id, weight, template, biomes, nightOnly, weather, once, recurring,
                body, expireMinutes, "", 0.0, List.of(), List.of(), 0, "", "", Map.of());
    }
}
