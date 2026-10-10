package pocketdungeons;

import java.util.Set;
import java.util.UUID;

/**
 * Regression for {@link DungeonLog}'s {@code currentTheme}/{@code depth}
 * plumbing (M11), the replacement for the {@code ThemeHistory} window this
 * file used to test. No server is available in this headless test, so
 * {@code AdventureGraphs.current()} is always the empty graph and
 * {@link DungeonLog#recordTheme} always takes its "no node for this theme"
 * branch; the graph's own kind-based branching is covered separately in
 * {@link KeystoneOfferTest}.
 */
public class DungeonLogTest {

    public static void main(String[] args) {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");

        DungeonLog log = new DungeonLog();
        check(log.get(player).currentTheme(), "", "unset player has no current theme");
        check(log.get(player).depth(), 0, "unset player is at depth 0");

        DungeonLog.Entry first = log.recordTheme(player, "deepslate");
        check(first.currentTheme(), "deepslate", "first completion sets currentTheme");
        check(first.depth(), 1, "first completion starts depth at 1");
        check(first.completedThemes().get("deepslate"), Integer.valueOf(1), "theme counted");

        DungeonLog.Entry second = log.recordTheme(player, "prismarine");
        check(second.currentTheme(), "prismarine", "second completion advances currentTheme");
        check(second.depth(), 2, "second completion advances depth");

        // A blank or null theme is a no-op, same as ThemeHistory.push's old contract.
        check(log.recordTheme(player, "").currentTheme(), "prismarine", "blank theme ignored");
        check(log.recordTheme(player, null).depth(), 2, "null theme ignored");

        // currentTheme/depth survive every other mutation untouched.
        log.setKeystone(player, 5, Set.of());
        check(log.get(player).currentTheme(), "prismarine", "setKeystone preserves currentTheme");
        log.recordCompletion(player, 7, 5);
        check(log.get(player).depth(), 2, "recordCompletion preserves depth");
        log.setPendingOffer(player, 5);
        log.clearPendingOffer(player);
        check(log.get(player).currentTheme(), "prismarine", "pending-offer round trip preserves currentTheme");

        // M20: publicListed and roomName round trip through the codec, and a
        // save written before M20 (missing both fields) loads with the safe
        // defaults. The codec is exercised through the whole DungeonLog CODEC
        // because ENTRY_CODEC itself is private.
        DungeonLog listed = new DungeonLog();
        listed.setPublicListed(player, true);
        listed.setRoomName(player, "The Vault");
        com.google.gson.JsonElement encoded = DungeonLog.CODEC.encodeStart(
                com.mojang.serialization.JsonOps.INSTANCE, listed).result().orElseThrow();
        DungeonLog decoded = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, encoded).result().orElseThrow().getFirst();
        check(decoded.get(player).publicListed(), true, "publicListed round trips");
        check(decoded.get(player).roomName(), "The Vault", "roomName round trips");

        com.google.gson.JsonObject old = new com.google.gson.JsonObject();
        com.google.gson.JsonArray players = new com.google.gson.JsonArray();
        com.google.gson.JsonObject oldEntry = new com.google.gson.JsonObject();
        oldEntry.addProperty("player", player.toString());
        com.google.gson.JsonObject oldFields = new com.google.gson.JsonObject();
        oldFields.addProperty("runs", 1);
        oldFields.addProperty("best_path", 2);
        oldEntry.add("entry", oldFields);
        players.add(oldEntry);
        old.add("players", players);
        DungeonLog legacy = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, old).result().orElseThrow().getFirst();
        check(legacy.get(player).publicListed(), false, "pre-M20 save defaults publicListed to false");
        check(legacy.get(player).roomName(), "", "pre-M20 save defaults roomName to empty");
        check(legacy.get(player).runsCompleted(), 1, "pre-M20 save still loads its runs");
        check(legacy.get(player).unlockedShells().isEmpty(), true,
                "pre-M24 save defaults unlockedShells to empty");
        check(legacy.get(player).roomCompletions(), 0, "pre-M24 save defaults roomCompletions to 0");

        // M24: unlockedShells and roomCompletions round trip through the codec.
        // The unlock is a set that never shrinks: re-unlocking is a no-op.
        DungeonLog shells = new DungeonLog();
        shells.unlockShell(player, "sandstone");
        shells.unlockShell(player, "sandstone");
        shells.unlockShell(player, "");
        shells.addRoomCompletion(player);
        shells.addRoomCompletion(player);
        com.google.gson.JsonElement shellJson = DungeonLog.CODEC.encodeStart(
                com.mojang.serialization.JsonOps.INSTANCE, shells).result().orElseThrow();
        DungeonLog shellDecoded = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, shellJson).result().orElseThrow().getFirst();
        check(shellDecoded.get(player).unlockedShells(),
                java.util.Set.of("sandstone"), "unlockedShells round trips");
        check(shellDecoded.get(player).roomCompletions(), 2, "roomCompletions round trips");
        check(shells.get(player).unlockedShells(),
                java.util.Set.of("sandstone"), "duplicate unlock is a no-op");

        // Dungeon structure W2: dungeonsFinished round trips, the first finish reports true
        // and a repeat is a no-op, a pre-W2 save loads with none, and the other fields survive.
        DungeonLog finished = new DungeonLog();
        check(finished.addDungeonFinished(player, "pocketdungeons:frostworks"), true, "first finish is reported");
        check(finished.addDungeonFinished(player, "pocketdungeons:frostworks"), false, "a repeat finish is not the first");
        finished.addDungeonFinished(player, "pocketdungeons:ossuary");
        finished.addRoomCompletion(player);
        com.google.gson.JsonElement finishedJson = DungeonLog.CODEC.encodeStart(
                com.mojang.serialization.JsonOps.INSTANCE, finished).result().orElseThrow();
        DungeonLog finishedDecoded = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, finishedJson).result().orElseThrow().getFirst();
        check(finishedDecoded.get(player).dungeonsFinished(),
                java.util.Set.of("pocketdungeons:frostworks", "pocketdungeons:ossuary"), "dungeonsFinished round trips");
        check(finishedDecoded.get(player).roomCompletions(), 1, "dungeonsFinished leaves other fields alone");
        check(new DungeonLog().get(player).dungeonsFinished(), java.util.Set.of(), "no finishes by default");
        check(legacy.get(player).dungeonsFinished(), java.util.Set.of(), "pre-W2 save defaults dungeonsFinished to empty");

        // The prestige count resets with the room: setRoomCompletions zeroes it.
        DungeonLog prestige = new DungeonLog();
        prestige.addRoomCompletion(player);
        prestige.addRoomCompletion(player);
        prestige.setRoomCompletions(player, 0);
        check(prestige.get(player).roomCompletions(), 0, "room reset zeroes prestige");
        prestige.setRoomCompletions(player, -3);
        check(prestige.get(player).roomCompletions(), 0, "negative prestige clamps at zero");

        // M71: recipe discovery round trips through the codec.
        DungeonLog recipes = new DungeonLog();
        recipes.recordRecipeDiscovery(player, "pocketdungeons:store");
        recipes.recordRecipeDiscovery(player, "pocketdungeons:compass");
        recipes.recordIngredientEncountered(player, "minecraft:emerald");
        com.google.gson.JsonElement recipeJson = DungeonLog.CODEC.encodeStart(
                com.mojang.serialization.JsonOps.INSTANCE, recipes).result().orElseThrow();
        DungeonLog recipeDecoded = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, recipeJson).result().orElseThrow().getFirst();
        check(recipeDecoded.discoveryOf(player).hasDiscovered("pocketdungeons:store"),
                true, "discovered recipe round trips");
        check(recipeDecoded.discoveryOf(player).hasDiscovered("pocketdungeons:compass"),
                true, "second discovered recipe round trips");
        check(recipeDecoded.discoveryOf(player).ingredientsEncountered()
                        .contains("minecraft:emerald"),
                true, "encountered ingredient round trips");

        // Duplicate discovery is a no-op: the set never grows twice.
        recipes.recordRecipeDiscovery(player, "pocketdungeons:store");
        check(recipes.discoveryOf(player).discoveredRecipes().size(), 2,
                "duplicate discovery is a no-op");

        // A pre-M71 save (no recipe_discoveries field) loads with empty
        // discovery state, the same way a pre-M20 save defaults publicListed.
        DungeonLog legacy2 = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, old).result().orElseThrow().getFirst();
        check(legacy2.discoveryOf(player).discoveredRecipes().isEmpty(), true,
                "pre-M71 save defaults discovered recipes to empty");
        check(legacy2.discoveryOf(player).ingredientsEncountered().isEmpty(), true,
                "pre-M71 save defaults ingredients to empty");
        check(legacy2.discoveryOf(player).floorDelivered(), false,
                "pre-M71 save defaults floorDelivered to false");

        // A removed-pack recipe id stays in the discovery set: knowledge
        // survives removal, even if the recipe is no longer in the manifest.
        // The discovery is personal history, not a live recipe reference.
        DungeonLog removed = new DungeonLog();
        removed.recordRecipeDiscovery(player, "theirpack:their_recipe");
        com.google.gson.JsonElement removedJson = DungeonLog.CODEC.encodeStart(
                com.mojang.serialization.JsonOps.INSTANCE, removed).result().orElseThrow();
        DungeonLog removedDecoded = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, removedJson).result().orElseThrow().getFirst();
        check(removedDecoded.discoveryOf(player).hasDiscovered("theirpack:their_recipe"),
                true, "removed-pack recipe id survives save and reload");

        // Knowledge surviving save and reload does not unlock undiscovered
        // entries: a player who discovered "store" does not also discover
        // "compass" by reloading.
        check(recipeDecoded.discoveryOf(player).hasDiscovered("pocketdungeons:ominous"),
                false, "save and reload does not unlock undiscovered entries");

        // The floor-delivered flag round trips and is idempotent.
        DungeonLog floor = new DungeonLog();
        floor.markFloorDelivered(player);
        floor.markFloorDelivered(player);
        check(floor.discoveryOf(player).floorDelivered(), true,
                "floor delivered flag is set");
        com.google.gson.JsonElement floorJson = DungeonLog.CODEC.encodeStart(
                com.mojang.serialization.JsonOps.INSTANCE, floor).result().orElseThrow();
        DungeonLog floorDecoded = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, floorJson).result().orElseThrow().getFirst();
        check(floorDecoded.discoveryOf(player).floorDelivered(), true,
                "floor delivered flag round trips");

        // M75: run-record sidecar. addRunRecord appends, latestRunRecord
        // returns the newest, and the list round trips through the codec.
        // The cap drops the oldest off the front.
        DungeonLog runs = new DungeonLog();
        check(runs.runRecordsOf(player).isEmpty(), true, "fresh log has no run records");
        check(runs.latestRunRecord(player) == null, true, "fresh log has no latest run record");
        runs.addRunRecord(player, new RunMemento.RunRecord("id1", "cave", 1, 1, Set.of(), 100L));
        runs.addRunRecord(player, new RunMemento.RunRecord("id2", "deepslate", 5, 2, Set.of("ominous"), 200L));
        check(runs.runRecordsOf(player).size(), 2, "two run records recorded");
        check(runs.latestRunRecord(player).discoveryId(), "id2", "latest is the newest record");
        com.google.gson.JsonElement runsJson = DungeonLog.CODEC.encodeStart(
                com.mojang.serialization.JsonOps.INSTANCE, runs).result().orElseThrow();
        DungeonLog runsDecoded = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, runsJson).result().orElseThrow().getFirst();
        check(runsDecoded.runRecordsOf(player).size(), 2, "run records round trip");
        check(runsDecoded.latestRunRecord(player).discoveryId(), "id2",
                "latest run record round trips");
        check(runsDecoded.runRecordsOf(player).get(0).theme(), "cave",
                "oldest run record round trips with theme");

        // The cap drops the oldest off the front.
        DungeonLog capped = new DungeonLog();
        for (int i = 0; i < DungeonLog.RUN_RECORD_LIMIT + 5; i++) {
            capped.addRunRecord(player, new RunMemento.RunRecord("id" + i, "t", i, 1, Set.of(), i));
        }
        check(capped.runRecordsOf(player).size(), DungeonLog.RUN_RECORD_LIMIT,
                "run records are capped at the limit");
        check(capped.latestRunRecord(player).discoveryId(),
                "id" + (DungeonLog.RUN_RECORD_LIMIT + 4),
                "capped list keeps the newest records");

        // A pre-M75 save has no run_records field and loads with an empty sidecar.
        com.google.gson.JsonObject legacyRuns = new com.google.gson.JsonObject();
        legacyRuns.add("players", new com.google.gson.JsonArray());
        DungeonLog legacyRunsDecoded = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, legacyRuns).result().orElseThrow().getFirst();
        check(legacyRunsDecoded.runRecordsOf(player).isEmpty(), true,
                "pre-M75 save defaults run records to empty");

        // Haul and Blood Doors: the haul, banking into the compass bar, the half kept on failure.
        DungeonLog haulLog = new DungeonLog();
        check(haulLog.haulOf(player), 0, "no haul before the first clear");
        check(haulLog.get(player).highestCharts(), 0, "no compass before the first bank");
        haulLog.addHaul(player, 4);
        haulLog.addHaul(player, 0);
        haulLog.addHaul(player, -3);
        check(haulLog.haulOf(player), 4, "earning adds, zero and negative amounts do nothing");
        check(haulLog.get(player).highestCharts(), 0, "a haul does not move the compass");
        DungeonLog.BankResult homeBank = haulLog.bankHaul(player, 100);
        check(homeBank.banked(), 4, "home banks the whole haul");
        check(homeBank.lost(), 0, "and loses none of it");
        check(haulLog.haulOf(player), 0, "the haul is empty after a bank");
        // The scrap curve (design pass 2026-10-09): a new player's first level costs 4, and compass 1 costs 4 too.
        check(haulLog.get(player).highestCharts(), 1, "4 scrap is the first level at the new price");
        check(haulLog.get(player).chartProgress(), 0, "and leaves the bar empty");
        check(homeBank.raisedCompass(), true, "the home bank raised the compass");
        haulLog.addHaul(player, 7);
        DungeonLog.BankResult failBank = haulLog.bankHaul(player, ScrapMath.FAIL_KEEP_PERCENT);
        check(failBank.banked(), 3, "a failed dungeon keeps half of 7, rounded down");
        check(failBank.lost(), 4, "and loses the rest");
        check(haulLog.get(player).highestCharts(), 1, "3 is not a level at compass 1 (4 to go)");
        check(haulLog.get(player).chartProgress(), 3, "it fills the bar to 3");
        check(failBank.raisedCompass(), false, "so the fail bank did not raise the compass");
        check(haulLog.bankHaul(player, 100).banked(), 0, "banking an empty haul banks nothing");
        haulLog.setCompass(player, 0, 0);
        check(haulLog.get(player).highestCharts(), 0, "an operator may lower the compass");
        check(haulLog.markHaulIntroSeen(player), true, "the intro shows once");
        check(haulLog.markHaulIntroSeen(player), false, "and not again");

        // Round trip, and the migration of a save written by the spendable pool model.
        DungeonLog roundTrip = new DungeonLog();
        roundTrip.setCompass(player, 12, 3);
        roundTrip.addHaul(player, 5);
        com.google.gson.JsonElement haulJson = DungeonLog.CODEC.encodeStart(
                com.mojang.serialization.JsonOps.INSTANCE, roundTrip).result().orElseThrow();
        DungeonLog haulDecoded = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, haulJson).result().orElseThrow().getFirst();
        check(haulDecoded.get(player).highestCharts(), 12, "the compass round trips");
        check(haulDecoded.get(player).chartProgress(), 3, "the bar round trips");
        check(haulDecoded.haulOf(player), 5, "the haul round trips");

        com.google.gson.JsonObject legacyEntry = haulJson.getAsJsonObject().getAsJsonArray("players")
                .get(0).getAsJsonObject().getAsJsonObject("entry");
        legacyEntry.remove("chart_progress");
        legacyEntry.remove("haul");
        legacyEntry.addProperty("highest_charts", 3);
        legacyEntry.addProperty("scrap", 16);
        DungeonLog migrated = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, haulJson).result().orElseThrow().getFirst();
        check(migrated.get(player).highestCharts(), 3, "a pool model save keeps its compass");
        check(migrated.get(player).chartProgress(), 1, "a never spent pool of 16 at compass 3 keeps 1/5");
        check(migrated.haulOf(player), 0, "and starts with no haul");
        legacyEntry.addProperty("highest_charts", 12);
        legacyEntry.addProperty("scrap", 0);
        DungeonLog migratedEmpty = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, haulJson).result().orElseThrow().getFirst();
        check(migratedEmpty.get(player).chartProgress(), 0, "a migrated compass 12 with an empty pool lands on 0/5");

        System.out.println("DungeonLogTest passed");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
