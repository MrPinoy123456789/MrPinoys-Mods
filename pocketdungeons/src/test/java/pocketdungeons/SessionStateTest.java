package pocketdungeons;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Code audit 2026-10-10 (item 8): a static map or list that holds per player, per slot or per cell state
 * must be cleared on disconnect and at server stop. {@link SessionState} is the one registry for that.
 *
 * <p>Two halves. The behaviour: a registered per player map loses one player's entry on
 * {@code forgetPlayer} and everything on {@code clearAll}; a stop only collection loses nothing on
 * {@code forgetPlayer}. The source scan: every static collection with a fresh initialiser in the main
 * sources either declares itself through a {@code SessionState} wrapper, or is named in {@link #ELSEWHERE}
 * with the reason it is handled another way (or is global on purpose). A new static map therefore cannot
 * slip in unclassified, and an allowlist entry cannot outlive its field.
 */
public class SessionStateTest {

    private static final Path SOURCES = Paths.get("src/main/java/pocketdungeons");

    /** {@code File.java:field} to why it is not registered with SessionState. */
    private static final Map<String, String> ELSEWHERE = new TreeMap<>();

    static {
        // Cell keyed: dropped with the cell by InstanceTeardown.forgetCellState (CellStateForgetTest).
        ELSEWHERE.put("AltarOffering.java:ACTIVE", "cell keyed; forgetCellState");
        ELSEWHERE.put("Locks.java:ACTIVE", "cell keyed; forgetCellState");
        ELSEWHERE.put("Ordeals.java:ACTIVE", "cell keyed; forgetCellState");
        ELSEWHERE.put("IronDoorLatch.java:closeAt", "cell keyed; an entry is removed when its close falls due");
        // Slot keyed: dropped at teardown and floor end, and the stop hook purges every slot.
        ELSEWHERE.put("CapstoneFights.java:STATES", "slot keyed; teardown, floor end and the stop purge");
        ELSEWHERE.put("FinaleScripts.java:SCRIPTS", "static content filled once by registerAll, not per run state");
        ELSEWHERE.put("PocketDungeonsConfig.java:KNOBS", "the config schema, filled once at class init, not session state");
        ELSEWHERE.put("HerobrineFight.java:STATES", "slot keyed; teardown, floor end and the stop purge");
        ELSEWHERE.put("WitherFight.java:STATES", "slot keyed; teardown, floor end and the stop purge");
        // The run engine's own tables.
        ELSEWHERE.put("InstanceRegistry.java:byMember", "the run registry; the stop hook purges every slot");
        ELSEWHERE.put("InstanceRegistry.java:bySlot", "the run registry; the stop hook purges every slot");
        ELSEWHERE.put("InstanceRegistry.java:usedSlots", "the run registry; the stop hook purges every slot");
        ELSEWHERE.put("InstanceTeardown.java:pendingClears", "drained by drainClears at server stop");
        // Player keyed with their own disconnect or stop handling.
        ELSEWHERE.put("PartyService.java:invites", "cleared per player by PartyService.clearFor on disconnect");
        ELSEWHERE.put("PartyService.java:pendingParty", "cleared per player by PartyService.clearFor on disconnect");
        ELSEWHERE.put("PartyService.java:pendingKicks", "cleared per player by PartyService.clearFor on disconnect");
        ELSEWHERE.put("PlaytestJournal.java:ENTER_HINTS", "PlaytestJournal clears it at disconnect");
        ELSEWHERE.put("PlaytestJournal.java:LEAVE_HINTS", "PlaytestJournal clears it at disconnect");
        ELSEWHERE.put("PlaytestJournal.java:TALLIES", "PlaytestJournal clears it at disconnect");
        ELSEWHERE.put("PowerListener.java:applied", "removed at disconnect by the listener (PD-43, PD-198)");
        ELSEWHERE.put("TrimListener.java:applied", "removed at disconnect by the listener (PD-43, PD-198)");
        ELSEWHERE.put("RoomScan.java:LATEST", "RoomScan clears it at stop");
        ELSEWHERE.put("StaggeredTitle.java:RUNNING", "StaggeredTitle clears it at stop");
        ELSEWHERE.put("StaggeredTitle.java:PENDING", "StaggeredTitle clears it at stop");
        ELSEWHERE.put("DiaryReading.java:reading", "drains in its own tick loop");
        ELSEWHERE.put("RoomEditorHistory.java:UNDO", "operator tool; cleared on save or exit");
        ELSEWHERE.put("RoomEditorHistory.java:REDO", "operator tool; cleared on save or exit");
        ELSEWHERE.put("RoomEditorMetadata.java:META_STATE", "operator tool; cleared on save or exit");
        // Global on purpose, bounded, or entity scoped.
        ELSEWHERE.put("PlaytestBias.java:MULTIPLIERS", "operator harness knobs; global on purpose");
        ELSEWHERE.put("PlaytestBias.java:HOLDS", "operator harness holds; global on purpose");
        ELSEWHERE.put("RoomContent.java:PENDING_EXPLOSIVES", "removed as each mine fires; holds levels, not players");
        ELSEWHERE.put("RoomTemplateGenerator.java:queue", "admin tool queue; drains as it runs");
        ELSEWHERE.put("SpiderUnstick.java:STUCK_SINCE", "weak keys; collected with the spider");
        ELSEWHERE.put("SpiderUnstick.java:TRACKED", "weak keys; collected with the spider");
        ELSEWHERE.put("Situations.java:HANDLERS", "handler table filled at registration; holds no player state");
        ELSEWHERE.put("TrialContent.java:SITUATION_SPAWNERS", "write only and bounded by the spawner positions");
        // Known gaps, left for the owner (audit item 8): a UUID or a container per player, not cleared at stop.
        ELSEWHERE.put("InventoryJournal.java:checked", "KNOWN GAP: one UUID per player; no stop clear");
        ELSEWHERE.put("SidebarDisplay.java:SHOWN", "KNOWN GAP: no stop clear; entries end when a player leaves the run");
        ELSEWHERE.put("SidebarDisplay.java:HIDDEN", "KNOWN GAP: one UUID per player who hid it; no stop clear");
        ELSEWHERE.put("RunStorage.java:live", "KNOWN GAP: open containers; closed by the player");
    }

    private static final Pattern DECL = Pattern.compile(
            "^\\s*(?:(?:public|private|protected)\\s+)?static\\s+(?:final\\s+)?(?:volatile\\s+)?"
                    + "(?:Map|HashMap|LinkedHashMap|ConcurrentHashMap|WeakHashMap|TreeMap|List|ArrayList|Set|HashSet|"
                    + "TreeSet|Deque|ArrayDeque|Queue)<[^;=(){}]*>\\s+(\\w+)\\s*=\\s*([^;]*);",
            Pattern.MULTILINE | Pattern.DOTALL);

    public static void main(String[] args) throws IOException {
        behaviour();
        scan();
    }

    private static void behaviour() {
        Map<UUID, Long> perPlayer = SessionState.perPlayer(new HashMap<>());
        Map<Integer, String> stopOnly = SessionState.clearedAtStop(new HashMap<>());
        List<String> list = SessionState.clearedAtStop(new ArrayList<>());
        UUID a = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
        UUID b = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        perPlayer.put(a, 1L);
        perPlayer.put(b, 2L);
        stopOnly.put(7, "slot");
        list.add("pending");

        SessionState.forgetPlayer(a);
        check(!perPlayer.containsKey(a), "forgetPlayer drops the leaver from a per player map");
        check(perPlayer.containsKey(b), "forgetPlayer leaves other players alone");
        check(stopOnly.size() == 1 && list.size() == 1, "forgetPlayer does not touch stop only collections");

        SessionState.clearAll();
        check(perPlayer.isEmpty() && stopOnly.isEmpty() && list.isEmpty(), "clearAll empties everything registered");
    }

    private static void scan() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        check(files.size() > 100, "found only " + files.size() + " source files; wrong working directory?");
        Set<String> unclassified = new HashSet<>();
        Set<String> seenElsewhere = new HashSet<>();
        Set<String> registeredAndListed = new HashSet<>();
        int registered = 0;
        for (Path file : files) {
            if (file.getFileName().toString().equals("SessionState.java")) {
                continue; // the registry's own hook lists
            }
            String text = Files.readString(file).replace("\r\n", "\n");
            Matcher m = DECL.matcher(text);
            while (m.find()) {
                String init = m.group(2);
                String start = init.strip();
                boolean fresh = start.startsWith("new ") || start.startsWith("SessionState.")
                        || init.contains("newKeySet(");
                if (!fresh) {
                    continue; // a built table or an immutable list, not mutable per session state
                }
                String key = file.getFileName() + ":" + m.group(1);
                boolean wrapped = init.contains("SessionState.");
                if (wrapped) {
                    registered++;
                }
                if (ELSEWHERE.containsKey(key)) {
                    seenElsewhere.add(key);
                    if (wrapped) {
                        registeredAndListed.add(key);
                    }
                } else if (!wrapped) {
                    unclassified.add(key);
                }
            }
        }
        check(unclassified.isEmpty(), "static collections neither registered with SessionState nor allowlisted "
                + "(wrap with SessionState.perPlayer or clearedAtStop, or add to ELSEWHERE with a reason): "
                + unclassified);
        Set<String> stale = new HashSet<>(ELSEWHERE.keySet());
        stale.removeAll(seenElsewhere);
        check(stale.isEmpty(), "allowlist entries with no matching static collection: " + stale);
        check(registeredAndListed.isEmpty(), "allowlisted but already registered: " + registeredAndListed);
        check(registered >= 12, "expected the audited statics to be registered, found only " + registered);
        System.out.println("SessionStateTest passed (" + registered + " registered, " + ELSEWHERE.size()
                + " handled elsewhere)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
