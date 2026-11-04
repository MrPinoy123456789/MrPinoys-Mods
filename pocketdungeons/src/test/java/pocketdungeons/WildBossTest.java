package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The wild boss (decision 2026-11-04): Valor for the kill stays within 5 to 20 and grows with the level, the elite's
 * health grows with the level, and every boss table exists, pays the owner's list, gives Sovereigns with the Charter
 * Currency marker, and drops a heavy core only by chance (7 percent at tier 3), more often when ominous.
 */
public class WildBossTest {

    private static final Path ROOT = Path.of("src/main/resources/data/pocketdungeons/loot_table");

    public static void main(String[] args) throws Exception {
        for (int level = 0; level <= 20; level++) {
            int[] range = WildBoss.valorRange(level);
            check(range[0] >= 5 && range[1] <= WildBoss.VALOR_MAX && range[0] <= range[1], "Valor at level " + level);
        }
        check(WildBoss.valorRange(1)[0] == 5 && WildBoss.valorRange(10)[1] == 20, "5 at the bottom, 20 by level 10");
        check(WildBoss.scaledHealth(60, 0) == 60 && WildBoss.scaledHealth(60, 10) == 90, "health grows 5 percent a level");
        check(WildBoss.lootTable(3, false).equals("pocketdungeons:boss/tier_3_wild")
                && WildBoss.lootTable(9, true).equals("pocketdungeons:boss/tier_4_ominous_wild"), "table names");
        check(WildOmen.level(4, true) == 4 + WildOmen.EXTRA_LEVELS && WildOmen.level(4, false) == 4, "ominous level");
        check(WildOmen.cooldownSeconds(3600, true) == 3600L * WildOmen.COOLDOWN_PERCENT / 100
                && WildOmen.cooldownSeconds(3600, false) == 3600, "ominous cooldown");

        for (int tier = 1; tier <= 4; tier++) {
            double plain = heavyCoreChance(table(tier, false));
            double ominous = heavyCoreChance(table(tier, true));
            check(plain > 0 && plain < 0.2 && ominous > plain, "tier " + tier + " heavy core: " + plain + ", " + ominous);
            for (boolean om : new boolean[]{false, true}) {
                String text = table(tier, om).toString();
                for (String item : new String[]{"minecraft:gold_ingot", "minecraft:emerald", "minecraft:diamond",
                        "minecraft:ancient_debris", "minecraft:gold_nugget", "minecraft:heavy_core"}) {
                    check(text.contains("\"" + item + "\""), "tier " + tier + " boss pays " + item);
                }
                check(text.contains("\"chartercurrency\":{\"coin\":\"gold\"}"), "boss Sovereigns carry the marker");
            }
        }
        check(Math.abs(heavyCoreChance(table(3, false)) - 0.07) < 1e-9, "a tier 3 boss drops a heavy core 7 percent");
        System.out.println("WildBossTest passed");
    }

    private static JsonObject table(int tier, boolean ominous) throws Exception {
        String rel = WildBoss.lootTable(tier, ominous).substring("pocketdungeons:".length()) + ".json";
        Path file = ROOT.resolve(rel);
        check(Files.exists(file), "missing " + file);
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    private static double heavyCoreChance(JsonObject table) {
        for (JsonElement pool : table.getAsJsonArray("pools")) {
            if (pool.toString().contains("minecraft:heavy_core")) {
                JsonObject p = pool.getAsJsonObject();
                return p.has("condition") ? p.getAsJsonObject("condition").get("chance").getAsDouble() : 1.0;
            }
        }
        return 0.0;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
