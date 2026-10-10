package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The six groups of {@link DungeonLog.Entry} each change on their own and survive the
 * save codec on their own, and a field the codec has never heard of is ignored.
 *
 * <p>Each group is changed through the real {@link DungeonLog} mutators that own it, on a
 * log read from the "oldest" fixture (everything at its default). Only that group may
 * differ afterwards; then the whole log goes through the codec (JSON and NBT) and the
 * group, and the entry, must come back equal.
 */
public class EntrySubRecordTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    public static void main(String[] args) throws IOException {
        // Reading a save raises the compass to the keystone (a pre scrap save keeps its keystone as
        // the compass), so a ladder with a keystone comes back with the compass lifted to match.
        group("ladder", e -> e.withCompass(Math.max(e.highestCharts(), e.keystoneLevel()), e.chartProgress()), log -> {
            log.recordCompletion(PLAYER, 12, 9);
            log.setKeystone(PLAYER, 7, Set.of());
            log.setPendingOffer(PLAYER, 7);
            log.setKeyProgress(PLAYER, 2);
        });
        group("themes", e -> e, log -> {
            log.recordTheme(PLAYER, "frost");
            log.recordTheme(PLAYER, "frost");
        });
        group("compass", e -> e, log -> {
            log.addHaul(PLAYER, 3);
            log.markHaulIntroSeen(PLAYER);
            log.setCompass(PLAYER, 6, 2);
        });
        group("room", e -> e, log -> {
            log.setPublicListed(PLAYER, true);
            log.setRoomName(PLAYER, "The Vault");
            log.addRoomCompletion(PLAYER);
            log.addVisitor(PLAYER, "Bob", 5L);
        });
        group("unlocks", e -> e, log -> {
            log.setBag(PLAYER, "pilgrim");
            log.setKitGranted(PLAYER, true);
            log.unlockShell(PLAYER, "sandstone");
            log.addExtractedPower(PLAYER, "power_a");
            log.addDiaryBand(PLAYER, 3);
            log.addDungeonFinished(PLAYER, "pocketdungeons:frostworks");
            log.addFuel(PLAYER, 4);
        });
        group("campaign", e -> e, log -> {
            log.unlockAct(PLAYER, 2);
            log.beginTrip(PLAYER);
            log.setDecideWhitelist(PLAYER, true);
            log.recordMineFloor(PLAYER, 4);
            log.markCampaignComplete(PLAYER);
        });

        unknownFieldsAreIgnored();
        System.out.println("EntrySubRecordTest passed");
    }

    private static final String[] GROUPS = {"ladder", "themes", "compass", "room", "unlocks", "campaign"};

    private static Object part(DungeonLog.Entry e, String group) {
        return switch (group) {
            case "ladder" -> e.ladder();
            case "themes" -> e.themes();
            case "compass" -> e.compass();
            case "room" -> e.room();
            case "unlocks" -> e.unlocks();
            default -> e.campaign();
        };
    }

    private static void group(String changed, UnaryOperator<DungeonLog.Entry> afterReload,
                              Consumer<DungeonLog> mutate) throws IOException {
        DungeonLog log = DungeonLogGoldenTest.decode(DungeonLogGoldenTest.read("oldest.json"));
        DungeonLog.Entry base = log.get(PLAYER);
        mutate.accept(log);
        DungeonLog.Entry next = log.get(PLAYER);

        for (String g : GROUPS) {
            boolean same = part(base, g).equals(part(next, g));
            check(same, !g.equals(changed), changed + " mutators changed the " + g + " group "
                    + (same ? "not at all" : "unexpectedly"));
        }

        // The whole log through the JSON codec, and through the NBT one SavedData uses.
        DungeonLog viaJson = DungeonLogGoldenTest.decode(DungeonLogGoldenTest.encode(log));
        net.minecraft.nbt.Tag nbt = DungeonLog.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, log)
                .result().orElseThrow();
        DungeonLog viaNbt = DungeonLog.CODEC.decode(net.minecraft.nbt.NbtOps.INSTANCE, nbt)
                .result().orElseThrow().getFirst();
        for (DungeonLog back : new DungeonLog[] {viaJson, viaNbt}) {
            check(part(back.get(PLAYER), changed), part(afterReload.apply(next), changed),
                    changed + " group round trips");
            check(back.get(PLAYER), afterReload.apply(next), changed + " entry round trips");
        }
    }

    private static void unknownFieldsAreIgnored() throws IOException {
        JsonObject clean = DungeonLogGoldenTest.read("full_current.json").getAsJsonObject();
        DungeonLog.Entry expected = DungeonLogGoldenTest.decode(clean).get(PLAYER);

        // A key no version knows, at the top of the save, on the entry, on the player row, on a
        // sidecar row, and on a visitor row.
        JsonObject noisy = DungeonLogGoldenTest.read("full_current.json").getAsJsonObject();
        noisy.add("from_the_future", new JsonPrimitive(1));
        JsonObject row = noisy.getAsJsonArray("players").get(0).getAsJsonObject();
        row.add("extra_row_key", new JsonPrimitive("x"));
        JsonObject entry = row.getAsJsonObject("entry");
        entry.add("future_group", new JsonObject());
        entry.add("streak", new JsonPrimitive(9));
        entry.getAsJsonArray("recent_visitors").get(0).getAsJsonObject().add("note", new JsonPrimitive("hi"));
        noisy.getAsJsonArray("task_progress").get(0).getAsJsonObject().add("noise", new JsonArray());

        DungeonLog loaded = DungeonLogGoldenTest.decode(noisy);
        check(loaded.get(PLAYER), expected, "unknown fields do not change the decoded entry");
        check(loaded.taskProgress(PLAYER, "station_a"), 2, "unknown fields do not disturb a sidecar");

        // And they are not carried forward: the next save is the clean written form.
        JsonElement rewritten = DungeonLogGoldenTest.encode(loaded);
        check(DungeonLogGoldenTest.pretty(DungeonLogGoldenTest.normalize(rewritten)),
                DungeonLogGoldenTest.pretty(DungeonLogGoldenTest.normalize(
                        DungeonLogGoldenTest.read("full_current.written.json"))),
                "unknown fields are dropped on the next write");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ":\nexpected " + expected + "\nbut was  " + actual);
        }
    }
}
