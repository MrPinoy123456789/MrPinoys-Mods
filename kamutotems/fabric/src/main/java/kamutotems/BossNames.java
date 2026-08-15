package kamutotems;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import kamutotems.core.AuraKind;
import kamutotems.core.AuraSpec;
import kamutotems.core.Kamu;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Config-driven boss-bar names. The template and word lists live in
 * {@code config/kamutotems/config.json} under the "boss" section (BRAINSTORM §5.8).
 *
 * <p>The implementation falls back to the locked template and word lists from
 * §5.8 if the operator has not supplied them, so the generator is usable before
 * the master writes the shipped JSON defaults.
 */
public final class BossNames {

    private static final String DEFAULT_TEMPLATE = "<epithet> of <intensifier> <aura>";

    private static final String[] DEFAULT_INTENSIFIERS = {
            "Mid", "Aight", "W", "Goated"
    };

    private static final Map<String, String[]> DEFAULT_EPITHETS = Map.of(
            "zombie", new String[]{"Chicken Jockey", "Zombie With a Job", "Unemployed Zombie"},
            "skeleton", new String[]{"Bone Daddy", "Skeleton Who Peaked in High School"},
            "creeper", new String[]{"Sussy Creeper", "Creeper Aw Man"},
            "wither_skeleton", new String[]{"Wither Skeleton of All Time"},
            "piglin", new String[]{"Piglin Who Owes You Money"},
            "enderman", new String[]{"Enderman of Culture", "Enderman (He's Just Like Me Fr)"},
            "vindicator", new String[]{"Johnny's Cousin"},
            "ravager", new String[]{"Certified Ravager"}
    );

    private static final Map<String, String> DEFAULT_AURA_NAMES = Map.of(
            "bloom", "Bloom",
            "focus", "Locked In",
            "momentum", "Zoomies",
            "rebuke", "Uno Reverse"
    );

    private BossNames() {}

    public static Component build(int tier, EntityType<?> type, AuraSpec aura, List<Kamu> carried) {
        String mob = mobKey(type);
        Random r = new Random(seedFor(type, tier, aura));

        String template = template();
        String epithet = epithet(mob, r);
        String intensifier = intensifier(tier);
        String auraName = auraName(aura);

        String title = template
                .replace("<epithet>", epithet)
                .replace("<intensifier>", intensifier)
                .replace("<aura>", auraName);

        String subtitle = carriedKamu(carried);

        if (subtitle.isEmpty()) {
            return Component.literal(title).withStyle(ChatFormatting.GOLD);
        }
        return Component.literal(title).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" [" + subtitle + "]").withStyle(ChatFormatting.DARK_PURPLE));
    }

    private static String template() {
        return KamuTotemsConfig.s("boss", "name_template", DEFAULT_TEMPLATE);
    }

    private static String epithet(String mob, Random r) {
        JsonObject boss = KamuTotemsConfig.section("boss");
        JsonObject map = boss.has("mob_epithets") && boss.get("mob_epithets").isJsonObject()
                ? boss.getAsJsonObject("mob_epithets")
                : null;

        String[] list = null;
        if (map != null && map.has(mob) && map.get(mob).isJsonArray()) {
            JsonArray arr = map.getAsJsonArray(mob);
            list = new String[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                list[i] = arr.get(i).getAsString();
            }
        }

        if (list == null || list.length == 0) {
            list = DEFAULT_EPITHETS.get(mob);
        }
        if (list == null || list.length == 0) {
            list = new String[]{"Kamu Bearer"};
        }
        return list[r.nextInt(list.length)];
    }

    private static String intensifier(int tier) {
        JsonObject boss = KamuTotemsConfig.section("boss");
        String[] list = null;
        if (boss.has("intensifiers") && boss.get("intensifiers").isJsonArray()) {
            JsonArray arr = boss.getAsJsonArray("intensifiers");
            list = new String[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                list[i] = arr.get(i).getAsString();
            }
        }

        if (list == null || list.length == 0) {
            list = DEFAULT_INTENSIFIERS;
        }
        int idx = Math.max(0, Math.min(tier - 1, list.length - 1));
        return list[idx];
    }

    private static String auraName(AuraSpec aura) {
        if (aura == null || !aura.isPresent()) {
            return "No Aura";
        }
        String id = aura.kind().name().toLowerCase();

        JsonObject boss = KamuTotemsConfig.section("boss");
        String name = null;
        if (boss.has("aura_names") && boss.get("aura_names").isJsonObject()) {
            JsonObject map = boss.getAsJsonObject("aura_names");
            if (map.has(id)) {
                name = map.get(id).getAsString();
            }
        }
        if (name == null) {
            name = DEFAULT_AURA_NAMES.get(id);
        }
        if (name == null) {
            name = aura.kind().label();
        }
        return name;
    }

    private static String carriedKamu(List<Kamu> carried) {
        if (carried == null || carried.isEmpty()) {
            return "";
        }
        return carried.stream()
                .map(Kamu::displayName)
                .collect(Collectors.joining(" · "));
    }

    private static String mobKey(EntityType<?> type) {
        // ⚠ UNVERIFIED: BuiltInRegistries.ENTITY_TYPE.getKey is the expected 26.2
        // method but is not duplicated in the suite's existing code.
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
    }

    private static int seedFor(EntityType<?> type, int tier, AuraSpec aura) {
        int base = BuiltInRegistries.ENTITY_TYPE.getKey(type).hashCode();
        int auraHash = aura == null ? 0 : aura.hashCode();
        return base + tier * 31 + auraHash;
    }
}
