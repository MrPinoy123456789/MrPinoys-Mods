package pocketdungeons;

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
        log.setKeystone(player, 5, java.util.EnumSet.noneOf(Affix.class));
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

        System.out.println("DungeonLogTest passed");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
