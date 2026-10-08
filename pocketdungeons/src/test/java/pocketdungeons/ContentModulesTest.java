package pocketdungeons;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-JDK regression for {@link ContentModules} (L2, D41): manifest
 * defaults, config overrides, unknown ids, and the merged allow list. No
 * Minecraft classpath, run from {@code tasks.test}.
 */
public class ContentModulesTest {

    public static void main(String[] args) {
        testManifestDefaults();
        testOverrides();
        testUnknownIds();
        testAllowListMerge();
        testGatedSurfaces();
        testJsonParse();
        System.out.println("ContentModulesTest passed");
    }

    private static ContentModules.Module module(String id, boolean def) {
        return new ContentModules.Module("pocketdungeons:" + id, id, id + " module", def,
                Map.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static void publish(ContentModules.Module... modules) {
        Map<String, ContentModules.Module> map = new java.util.LinkedHashMap<>();
        for (ContentModules.Module module : modules) {
            map.put(module.id(), module);
        }
        ContentModules.publish(ContentModules.create(map, List.of()));
        ContentModules.setOverrides(Map.of());
    }

    private static void testManifestDefaults() {
        publish(module("alchemy", false), module("trims", true));
        check(!ContentModules.enabled("alchemy"), "alchemy defaults off");
        check(ContentModules.enabled("trims"), "trims defaults on");
        check(ContentModules.enabled("pocketdungeons:alchemy") == false,
                "namespaced lookup agrees with the bare id");
    }

    private static void testOverrides() {
        publish(module("alchemy", false));
        ContentModules.setOverride("alchemy", true);
        check(ContentModules.enabled("alchemy"), "config override turns alchemy on");
        ContentModules.setOverride("alchemy", false);
        check(!ContentModules.enabled("alchemy"), "override off wins over an enabled default");
        ContentModules.setOverride("alchemy", null);
        check(!ContentModules.enabled("alchemy"), "clearing the override restores the default");
    }

    private static void testUnknownIds() {
        publish(module("alchemy", false));
        check(!ContentModules.enabled("no_such_module"), "unknown id is off");
        ContentModules.setOverride("no_such_module", true);
        check(!ContentModules.enabled("no_such_module"),
                "an override cannot turn on a module no manifest declares");
        ContentModules.setOverride("no_such_module", null);
    }

    private static void testAllowListMerge() {
        ContentModules.Module alchemy = new ContentModules.Module(
                "pocketdungeons:alchemy", "Alchemy", "alchemy", false,
                Map.of(), List.of("nether_wart", "blaze_powder"), List.of(), List.of(), List.of());
        ContentModules.Module redstone = new ContentModules.Module(
                "pocketdungeons:redstone", "Redstone", "redstone", true,
                Map.of(), List.of("redstone", "piston"), List.of(), List.of(), List.of());
        publish(alchemy, redstone);
        Set<String> allowed = ContentModules.enabledAllowItems();
        check(allowed.contains("redstone") && allowed.contains("piston"),
                "enabled module contributes its allow list");
        check(!allowed.contains("nether_wart"), "disabled module's allow list stays out");
        ContentModules.setOverride("alchemy", true);
        allowed = ContentModules.enabledAllowItems();
        check(allowed.contains("nether_wart"), "enabling the module merges its allow list");
        ContentModules.setOverride("alchemy", null);
    }

    private static void testGatedSurfaces() {
        ContentModules.Module alchemy = new ContentModules.Module(
                "pocketdungeons:alchemy", "Alchemy", "alchemy", false,
                Map.of(), List.of(), List.of("brewing_stand"), List.of("nether_wart"),
                List.of("pocketdungeons:magician"));
        publish(alchemy);
        check(!ContentModules.stationEnabled("brewing_stand"),
                "the brewing stand is gated while alchemy is off");
        check(ContentModules.stationEnabled("grindstone"),
                "a station no module claims is never gated");
        check(!ContentModules.merchantItemEnabled("nether_wart"),
                "merchant stock line is filtered while alchemy is off");
        check(ContentModules.merchantItemEnabled("bread"),
                "stock no module lists is never filtered");
        check(!ContentModules.bagEnabled("magician"),
                "a module bag does not exist while the module is off");
        check(ContentModules.disabledBagIds().contains("pocketdungeons:magician"),
                "the disabled bag set reports magician for the snapshot's bag parse");
        ContentModules.setOverride("alchemy", true);
        check(ContentModules.stationEnabled("brewing_stand"), "the station opens with the module on");
        check(ContentModules.merchantItemEnabled("nether_wart"), "stock line lands with the module on");
        check(ContentModules.bagEnabled("magician"), "the bag exists with the module on");
        ContentModules.setOverride("alchemy", null);
    }

    private static void testJsonParse() {
        com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString("""
                {
                  "id": "alchemy",
                  "label": "Alchemy",
                  "description": "Nether wart and brewing.",
                  "default": true,
                  "loot": {"chests/tier_2": "modules/alchemy/chests/tier_2"},
                  "allow": ["nether_wart"],
                  "stations": ["brewing_stand"],
                  "merchantStock": ["nether_wart"],
                  "bags": ["magician"]
                }
                """).getAsJsonObject();
        ContentModules.Module module = ContentModules.Module.fromJson(obj, "pocketdungeons:alchemy");
        check(module.defaultEnabled(), "default parses");
        check(module.loot().get("pocketdungeons:chests/tier_2")
                        .equals("pocketdungeons:modules/alchemy/chests/tier_2"),
                "loot map qualifies both sides");
        check(module.allow().contains("nether_wart"), "allow parses raw item ids");
        check(module.bags().contains("pocketdungeons:magician"), "bag ids qualify");
        try {
            ContentModules.Module.fromJson(obj, "pocketdungeons:wrong_name");
            check(false, "an id that does not match the file name must reject");
        } catch (IllegalArgumentException expected) {
            check(true, "mismatched id rejected");
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
