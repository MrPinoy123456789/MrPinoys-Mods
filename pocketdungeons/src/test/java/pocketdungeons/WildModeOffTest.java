package pocketdungeons;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Wild lodestones (decision 2026-10-30): with {@code mode: campaign} the mod must behave exactly as before, so every wild
 * hook in a campaign file starts with a {@code WildMode.active()} or {@code record.wild} guard. This reads the sources
 * and fails when a hook in the table below loses its guard. Each phase adds its hooks to {@link #GUARDS}.
 */
public class WildModeOffTest {

    private static final Path SOURCES = Paths.get("src/main/java/pocketdungeons");

    /** {file, anchor text that opens the hook, guard that must appear before the next statement of the hook}. */
    private static final String[][] GUARDS = {
            // Task 0.5: the dialog gate is the first statement of handle.
            {"DialogRouter.java", "public static void handle(ServerPlayer player, Identifier id, Optional<Tag> payload) {",
                    "if (WildMode.active() && !WildMode.allowsDialogAction("},
            // Task 0.4: the reduced command tree is only chosen in wild mode.
            {"DungeonCommands.java", "CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {",
                    "if (WildMode.active()) {"},
            // Task 0.4: the first visit tutorial watches for a compass, which wild mode never hands out.
            {"FirstVisitTutorial.java", "ServerTickEvents.END_SERVER_TICK.register(server -> {",
                    "if (WildMode.active()) {"},
    };

    /** {file, text that must appear}: guards that sit inside a long method rather than at its start. */
    private static final String[][] CONTAINS = {
            // Task 1.5: the RitualListener chain skips the campaign claims in wild mode.
            {"RitualListener.java", "boolean wild = WildMode.active();"},
            {"RitualListener.java", "wild ? null : bagChest("},
            {"RitualListener.java", "!wild && PackPots.onUse("},
            // Task 1.6: the wild admin commands exist only in wild mode.
            {"DungeonCommands.java", "if (WildMode.active()) {\n            WildCommands.register(admin);"},
            // Task 0.3: the store loads nothing outside wild mode.
            {"WildStore.java", "if (!WildMode.active()) {\n            return;"},
            {"WildLodestones.java", "if (!WildMode.active() || level.isClientSide()"},
            // Phase 2: the run hooks in the hot campaign files.
            {"Instances.java", "if (record.wild) {\n            WildRun.tick(server, record, now, interval);\n            return;"},
            {"Instances.java", "if (record.wild) {\n            return true;"},
            {"Instances.java", "if (WildMode.active()) {\n            // Wild mode never touches the recovery compass target"},
            {"Instances.java", "if (record.wild) {\n                return point;"},
            {"InventorySwap.java", "if (WildMode.active()) {\n            // Wild mode never stashes or swaps"},
            {"InventorySwap.java", "if (WildMode.active()) {\n            // No inventory is stashed in wild mode"},
            {"WildRules.java", "if (!WildMode.active() || !(entity instanceof ServerPlayer player))"},
    };

    public static void main(String[] args) throws IOException {
        for (String[] guard : GUARDS) {
            guardPresent(guard[0], guard[1], guard[2]);
        }
        for (String[] contains : CONTAINS) {
            String source = Files.readString(SOURCES.resolve(contains[0])).replace("\r\n", "\n");
            check(source.contains(contains[1]), contains[0] + " lost its wild guard: " + contains[1]);
        }
        pureRules();
        System.out.println("WildModeOffTest passed (" + (GUARDS.length + CONTAINS.length) + " guarded hooks)");
    }

    private static void guardPresent(String fileName, String anchor, String guard) throws IOException {
        String source = Files.readString(SOURCES.resolve(fileName)).replace("\r\n", "\n");
        int at = source.indexOf(anchor);
        check(at >= 0, fileName + ": hook not found: " + anchor);
        String after = source.substring(at + anchor.length());
        // The guard is the first statement: only comments and blank lines may sit between the anchor and it.
        String[] lines = after.split("\n", 12);
        String firstCode = null;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("//")) {
                continue;
            }
            firstCode = t;
            break;
        }
        check(firstCode != null && firstCode.startsWith(guard),
                fileName + ": the hook \"" + anchor + "\" must start with " + guard + " but starts with " + firstCode);
    }

    private static void pureRules() {
        check(WildMode.isWild("wild") && WildMode.isWild(" Wild "), "wild is recognised");
        check(!WildMode.isWild("campaign") && !WildMode.isWild(null) && !WildMode.isWild("wlid"),
                "anything else is campaign");
        check(WildMode.allowsDialogAction("wild_enter") && WildMode.allowsDialogAction("wild_leave"),
                "wild actions pass the dialog gate");
        for (String forged : new String[] {"start_dungeon", "manage_room", "visit_room", "", "wildenter", "x_wild_"}) {
            check(!WildMode.allowsDialogAction(forged), "forged action refused: " + forged);
        }
        check(!WildMode.allowsDialogAction(null), "null action refused");
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
