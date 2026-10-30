package pocketdungeons;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Wild lodestones (decision 2026-10-30), phase 0: the mode rule, the saved state, and the cooldown and lifespan
 * arithmetic. Pure; no Minecraft.
 */
public class WildStateTest {

    public static void main(String[] args) throws IOException {
        modeRule();
        phases();
        lookups();
        Path dir = Files.createTempDirectory("wild-state-test");
        try {
            roundTrip(dir);
            missingFileIsEmpty(dir);
            badFileIsRefused(dir);
        } finally {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        System.out.println("WildStateTest passed");
    }

    private static void modeRule() {
        check(WildMode.isWild("wild"), "wild is wild");
        check(WildMode.isWild("  Wild "), "case and spaces do not matter");
        check(!WildMode.isWild("campaign"), "campaign is not wild");
        check(!WildMode.isWild("wlid"), "a typo counts as campaign");
        check(!WildMode.isWild(""), "empty counts as campaign");
        check(!WildMode.isWild(null), "null counts as campaign");
    }

    private static WildState.Lodestone sample(WildState state, int x) {
        WildState.Lodestone l = new WildState.Lodestone(state.nextId(), "minecraft:overworld", x, 70, -300,
                "copper_works", 4, 1000L, 1000L + 7L * 86400L);
        state.add(l);
        return l;
    }

    private static void phases() {
        WildState state = new WildState();
        WildState.Lodestone l = sample(state, 10);
        check(l.phase == WildState.Phase.READY, "a new lodestone is ready");
        check(l.secondsToReset(5000L) == 0L, "nothing cools while ready");
        l.markInProgress();
        check(l.phase == WildState.Phase.IN_PROGRESS, "entering makes it in progress");
        check(!l.resetDue(999999L), "in progress never resets by the clock");
        l.markCleared(2000L, 4L * 3600L);
        check(l.phase == WildState.Phase.CLEARED, "a clear marks it cleared");
        check(l.clearedAt == 2000L && l.resetAt == 2000L + 14400L, "the cooldown starts at the clear");
        check(l.secondsToReset(3000L) == 13400L, "seconds to reset count down");
        check(!l.resetDue(2000L + 14399L), "one second early is not due");
        check(l.resetDue(2000L + 14400L), "due exactly at the end");
        check(l.secondsToReset(999999L) == 0L, "remaining never goes negative");
        l.markReady();
        check(l.phase == WildState.Phase.READY && l.resetAt == 0L && l.clearedAt == 0L, "reset clears the clock");
        check(!l.expired(1000L + 7L * 86400L - 1L), "alive one second before the lifespan ends");
        check(l.expired(1000L + 7L * 86400L), "collapses at the end of the lifespan");
        check(l.secondsToCollapse(1000L + 7L * 86400L + 50L) == 0L, "collapse countdown never goes negative");
        WildState.Lodestone zero = new WildState.Lodestone("z", "d", 0, 0, 0, "x", 1, 0L, 100L);
        zero.markCleared(10L, -5L);
        check(zero.resetAt == 10L, "a negative cooldown counts as none");
    }

    private static void lookups() {
        WildState state = new WildState();
        WildState.Lodestone a = sample(state, 10);
        WildState.Lodestone b = sample(state, 20);
        check(!a.id.equals(b.id), "ids differ");
        check(state.atEntrance("minecraft:overworld", 20, 70, -300) == b, "found by its entrance block");
        check(state.atEntrance("minecraft:overworld", 21, 70, -300) == null, "a block beside it is not it");
        check(state.atEntrance("minecraft:the_nether", 20, 70, -300) == null, "another dimension is not it");
        state.remove(a.id);
        check(state.size() == 1 && state.get(a.id) == null, "removal works");
        String reused = state.nextId();
        check(!reused.equals(a.id) && !reused.equals(b.id), "an id is never reused");
    }

    private static void roundTrip(Path dir) throws IOException {
        WildState state = new WildState();
        WildState.Lodestone a = sample(state, 10);
        WildState.Lodestone b = sample(state, 20);
        b.markCleared(5000L, 3600L);
        b.lostAndFound.add(new int[] {1, 2, 3});
        b.lostAndFound.add(new int[] {4, 5, 6});
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000042");
        state.rememberPlayer(player, a.id);

        Path file = dir.resolve("sub").resolve("lodestones.json");
        state.save(file);
        WildState back = WildState.load(file);
        check(back.size() == 2, "both lodestones come back");
        WildState.Lodestone b2 = back.get(b.id);
        check(b2.phase == WildState.Phase.CLEARED && b2.resetAt == 8600L && b2.clearedAt == 5000L, "phase and clock survive");
        check(b2.lostAndFound.size() == 2 && b2.lostAndFound.get(1)[2] == 6, "lost and found chests survive");
        check(b2.dungeon.equals("copper_works") && b2.level == 4, "dungeon and level survive");
        check(back.get(a.id).phase == WildState.Phase.READY, "a ready lodestone stays ready");
        check(a.id.equals(back.lastLodestoneOf(player)), "the player's last lodestone survives");
        check(back.toJson().equals(state.toJson()), "writing it again gives the same text");
        check(!back.nextId().equals(a.id) && !back.nextId().equals(b.id), "the id counter survives");
    }

    private static void missingFileIsEmpty(Path dir) throws IOException {
        check(WildState.load(dir.resolve("none.json")).size() == 0, "no file means no lodestones");
    }

    private static void badFileIsRefused(Path dir) throws IOException {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, "this is not json");
        boolean refused = false;
        try {
            WildState.load(file);
        } catch (IllegalStateException e) {
            refused = true;
        }
        check(refused, "a garbage file is refused, not read as empty");
        Files.writeString(file, "{\"version\":99,\"nextId\":1,\"lodestones\":[],\"players\":{}}");
        refused = false;
        try {
            WildState.load(file);
        } catch (IllegalStateException e) {
            refused = e.getMessage().contains("version");
        }
        check(refused, "an unknown version is refused");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("WildStateTest: " + what);
        }
    }
}
