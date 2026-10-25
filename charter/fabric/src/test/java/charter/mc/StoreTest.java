package charter.mc;

import charter.config.Knobs;
import charter.gov.Vote;
import charter.grant.Power;
import charter.id.ChunkKey;
import charter.land.Claim;
import charter.mc.store.CharterStore;
import charter.mc.store.Persistence;
import charter.mc.store.Repairs;
import charter.mc.store.StateCodec;
import charter.mc.store.StoreEvents;
import charter.state.CharterState;
import charter.treasury.Currency;
import charter.treasury.actions.Withdraw;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static charter.mc.FabricSuite.check;

/** Test H, plain half: the store round trip, the .bak fallback, and each repair case. */
final class StoreTest {

    private StoreTest() {
    }

    static void run() throws Exception {
        Persistence.register();
        everyRecordRoundTrips();
        corruptStateFallsBackToBak();
        noReadableSaveRefusesToStart();
        unknownBlocksSurvive();
        newerFormatIsRefused();
        repairDropsGrantWhoseSourceIsGone();
        repairMovesOrphanedOrgToTop();
        repairOrphansClaimWhoseCoreIsGone();
        repairClosesVotesItCannotRun();
        repairDropsSiegeRecordsOfGoneSites();
        liveRepairRevokesBrokenGrant();
    }

    static CharterStore store(Path dir) {
        return new CharterStore(dir, Persistence.codec(Persistence.json()), s -> { }, s -> { });
    }

    static void everyRecordRoundTrips() throws Exception {
        FullState f = FullState.build();
        Path dir = Files.createTempDirectory("charter-store");
        store(dir).save(f.state, f.clock.now());
        CharterStore.Loaded loaded = store(dir).load(f.knobs, f.clock.now());
        CharterState back = loaded.state();

        check("store: a written state loads from state.json", loaded.source() == CharterStore.Source.STATE);
        check("store: nothing needed repair on a clean round trip", loaded.repairs().count() == 0);
        List<String> diff = DeepCompare.diff(f.state, back);
        check("store: every field of every record reads back the same" + (diff.isEmpty() ? "" : ": " + diff.subList(0, Math.min(5, diff.size()))),
                diff.isEmpty());

        // Spot checks on what matters most, so a broken comparator cannot hide a loss.
        Vote pending = back.votes().stream().filter(Vote::open).filter(v -> v.kind == Vote.Kind.MOTION).findFirst().orElseThrow();
        check("store: a pending motion keeps its action", pending.motion.equals(new Withdraw(f.oakvale, Currency.DIAMOND, 5, f.bob)));
        check("store: a settled vote keeps its ballots and result",
                back.votes().stream().anyMatch(v -> v.state() == Vote.State.FAILED && v.ballots().size() == 2));
        check("store: treasury balances read back", back.org(f.oakvale).orElseThrow().ledger.balance(Currency.DIAMOND) == 50);
        check("store: a derived grant keeps its parent", FullState.derivedCount(back) == 1);
        check("store: a revoked grant stays revoked", FullState.first(back, Power.CLAIM).revoked());
        check("store: the sub organization keeps its parent", f.ironhold.equals(back.org(f.guard).orElseThrow().parent()));
        Claim contested = back.claim(f.contested.id).orElseThrow();
        check("store: a contested claim keeps its grace", contested.contested() && contested.contestedUntil() == 9_999_999L);
        check("store: the claim chunk index is rebuilt", back.claimAt(new ChunkKey(FullState.OW, 2, 0)).isPresent());
        check("store: the siege clock reads back", back.siegeClock.now() == 123_456);
        check("store: granary debts read back", back.siege.stores.get(f.keep.id).eatDebt == 0.4
                && back.siege.stores.get(f.keep.id).spoilDebts.get("minecraft:bread") == 0.25);
        check("store: allowances keep their items", back.siege.allowances.getFirst().left("minecraft:bread") == 5);
        check("store: new ids never repeat a saved one", back.ids.peek() == f.state.ids.peek());
        check("store: the core order counter reads back", back.nextCoreOrder() == f.state.nextCoreOrder());
        check("store: a backup is kept", Files.list(dir.resolve("backups")).count() == 1);

        // The comparator itself must see a one-field change deep in the graph.
        back.siege.keep(f.keep.id).starving = false;
        check("store: the field comparison notices a changed field", !DeepCompare.diff(f.state, back).isEmpty());
    }

    static void corruptStateFallsBackToBak() throws Exception {
        FullState f = FullState.build();
        Path dir = Files.createTempDirectory("charter-store");
        CharterStore store = store(dir);
        store.save(f.state, f.clock.now());
        f.state.org(f.oakvale).orElseThrow().ledger.add(Currency.DIAMOND, 1000);
        store.save(f.state, f.clock.now());
        Files.writeString(store.stateFile(), "{ \"formatVersion\": 1, \"orgs\": [ oops");

        CharterStore.Loaded loaded = store(dir).load(f.knobs, f.clock.now());
        check("store: an unreadable state.json falls back to .bak", loaded.source() == CharterStore.Source.BAK);
        check("store: the .bak is the snapshot before the last write",
                loaded.state().org(f.oakvale).orElseThrow().ledger.balance(Currency.DIAMOND) == 50);
        try (Stream<Path> files = Files.list(dir)) {
            check("store: the unreadable file is kept as state.json.broken-<time>",
                    files.anyMatch(p -> p.getFileName().toString().startsWith("state.json.broken-")));
        }
    }

    static void noReadableSaveRefusesToStart() throws Exception {
        FullState f = FullState.build();
        Path dir = Files.createTempDirectory("charter-store");
        CharterStore store = store(dir);
        store.save(f.state, f.clock.now());
        store.save(f.state, f.clock.now());
        Files.writeString(store.stateFile(), "not json");
        Files.writeString(store.bakFile(), "not json either");
        CharterStore.Loaded loaded = store(dir).load(f.knobs, f.clock.now());
        check("store: with state.json and .bak broken, the newest backup loads", loaded.source() == CharterStore.Source.BACKUP);

        try (Stream<Path> backups = Files.list(store.backupDir())) {
            for (Path p : backups.toList()) Files.writeString(p, "[]");
        }
        boolean refused = false;
        try {
            store(dir).load(f.knobs, f.clock.now());
        } catch (CharterStore.Unreadable e) {
            refused = true;
        }
        check("store: with every save unreadable the load refuses rather than start empty", refused);
        check("store: a world with no save at all starts fresh",
                store(Files.createTempDirectory("charter-store")).load(f.knobs, 0).source() == CharterStore.Source.FRESH);
    }

    static void unknownBlocksSurvive() throws Exception {
        FullState f = FullState.build();
        Path dir = Files.createTempDirectory("charter-store");
        store(dir).save(f.state, f.clock.now());
        JsonObject root = read(dir);
        JsonObject future = new JsonObject();
        future.addProperty("keptBy", "a later milestone");
        root.add("some_future_block", future);
        write(dir, root);
        CharterStore again = store(dir);
        CharterStore.Loaded loaded = again.load(f.knobs, f.clock.now());
        again.save(loaded.state(), f.clock.now());
        check("store: a block no feature claims is written back unchanged",
                read(dir).getAsJsonObject("some_future_block").get("keptBy").getAsString().equals("a later milestone"));
    }

    static void newerFormatIsRefused() {
        StateCodec codec = Persistence.codec(Persistence.json());
        JsonObject root = codec.write(new CharterState());
        root.addProperty("formatVersion", StateCodec.FORMAT_VERSION + 1);
        boolean refused = false;
        try {
            codec.read(root, new Knobs(), new Repairs(0));
        } catch (RuntimeException e) {
            refused = e.getMessage().contains("newer");
        }
        check("store: a file from a newer format is refused, not half read", refused);
    }

    // ---- repair on load ----

    /** Saves a full state, lets the test edit the JSON, and loads it again. */
    static CharterStore.Loaded edited(FullState f, Consumer<JsonObject> edit) throws Exception {
        Path dir = Files.createTempDirectory("charter-repair");
        store(dir).save(f.state, f.clock.now());
        JsonObject root = read(dir);
        edit.accept(root);
        write(dir, root);
        return store(dir).load(f.knobs, f.clock.now());
    }

    static boolean noted(CharterStore.Loaded loaded, String kind) {
        return loaded.repairs().found().stream().anyMatch(e -> e instanceof StoreEvents.Repaired r && r.kind().equals(kind));
    }

    static void repairDropsGrantWhoseSourceIsGone() throws Exception {
        FullState f = FullState.build();
        String rootSpend = f.state.grants().stream().filter(g -> g.power == Power.SPEND && g.subject.equals(f.oakvale) && g.isRoot())
                .findFirst().orElseThrow().id.value().toString();
        long before = f.state.grants().size();
        CharterStore.Loaded loaded = edited(f, root -> removeWhere(root.getAsJsonObject("grants").getAsJsonArray("grants"),
                g -> g.get("id").getAsString().equals(rootSpend)));
        check("repair: a grant whose parent is gone is dropped with it", loaded.state().grants().size() == before - 2
                && FullState.derivedCount(loaded.state()) == 0);
        check("repair: the dropped grant is noted", noted(loaded, "grant_dropped"));
        check("repair: grants that still stand are kept", loaded.state().grants().stream().anyMatch(g -> g.power == Power.CLAIM));
    }

    static void repairMovesOrphanedOrgToTop() throws Exception {
        FullState f = FullState.build();
        String ironhold = f.ironhold.value().toString();
        CharterStore.Loaded loaded = edited(f, root -> removeWhere(root.getAsJsonObject("orgs").getAsJsonArray("orgs"),
                o -> o.get("id").getAsString().equals(ironhold)));
        check("repair: an organization whose parent is gone moves to the top level",
                loaded.state().org(f.guard).orElseThrow().isTopLevel());
        check("repair: the move is noted", noted(loaded, "org_reparented"));
        check("repair: records of the gone organization are dropped, not crashed on",
                loaded.state().core(f.camp.id).isEmpty() && noted(loaded, "core_dropped") && noted(loaded, "office_dropped"));
    }

    static void repairOrphansClaimWhoseCoreIsGone() throws Exception {
        FullState f = FullState.build();
        String keep = f.keep.id.value().toString();
        CharterStore.Loaded loaded = edited(f, root -> removeWhere(root.getAsJsonObject("land").getAsJsonArray("cores"),
                c -> c.get("id").getAsString().equals(keep)));
        CharterState s = loaded.state();
        check("repair: a fixed chunk of a gone core is dropped", s.claimAt(f.keep.center).isEmpty() && noted(loaded, "claim_dropped"));
        Claim farm = s.claimAt(new ChunkKey(FullState.OW, 2, 0)).orElseThrow();
        check("repair: land of a gone core enters the orphan grace", farm.contested() && farm.core() == null
                && farm.contestedUntil() > s.siegeClock.now());
        check("repair: the orphaned claim is noted and the rules record the contest",
                noted(loaded, "claim_orphaned") && loaded.repairs().found().stream().anyMatch(e -> e instanceof charter.land.LandEvents.Contested));
        check("repair: siege records of the gone keep go with it", !s.siege.stores.containsKey(f.keep.id)
                && s.siege.allowances.isEmpty() && noted(loaded, "allowance_dropped"));
    }

    static void repairClosesVotesItCannotRun() throws Exception {
        FullState f = FullState.build();
        CharterStore.Loaded loaded = edited(f, root -> {
            for (JsonElement v : root.getAsJsonObject("votes").getAsJsonArray("votes")) {
                JsonObject vote = v.getAsJsonObject();
                if (vote.has("motion") && vote.get("state").getAsString().equals("OPEN")) {
                    vote.getAsJsonObject("motion").addProperty("type", "m99.unheard_of");
                }
            }
        });
        check("repair: an open vote whose motion this jar cannot read is closed as failed",
                loaded.state().votes().stream().noneMatch(v -> v.open() && v.kind == Vote.Kind.MOTION)
                        && loaded.state().votes().stream().anyMatch(v -> "motion unknown".equals(v.result())));
        check("repair: the closed vote is noted", noted(loaded, "vote_closed"));

        FullState g = FullState.build();
        g.state.setStatus(g.ironhold, charter.org.OrgStatus.DISSOLVED);
        CharterStore.Loaded gone = edited(g, root -> { });
        check("repair: an open election of a dissolved organization is closed",
                gone.state().votes().stream().filter(v -> v.org.equals(g.ironhold)).noneMatch(Vote::open) && noted(gone, "vote_closed"));
    }

    static void repairDropsSiegeRecordsOfGoneSites() throws Exception {
        FullState f = FullState.build();
        String camp = f.camp.id.value().toString();
        CharterStore.Loaded loaded = edited(f, root -> {
            removeWhere(root.getAsJsonObject("land").getAsJsonArray("cores"), c -> c.get("id").getAsString().equals(camp));
            removeWhere(root.getAsJsonObject("land").getAsJsonArray("claims"), c -> c.get("chunk").getAsJsonObject().get("x").getAsInt() == 6);
        });
        CharterState s = loaded.state();
        check("repair: the camp store and site of a gone camp are dropped",
                !s.siege.stores.containsKey(f.camp.id) && !s.siege.camps.containsKey(f.camp.id) && noted(loaded, "store_dropped"));
        check("repair: the keep's own siege records are kept", s.siege.stores.containsKey(f.keep.id) && s.siege.allowances.size() == 1);
    }

    static void liveRepairRevokesBrokenGrant() {
        FullState f = FullState.build();
        Repairs repairs = new Repairs(f.clock.now());
        charter.mc.store.Repair.run(f.state, f.knobs, repairs);
        check("repair: a sound live state needs nothing", repairs.count() == 0);
        f.state.setParent(f.guard, null);
        f.state.org(f.guard).orElseThrow().setParent(new charter.id.OrgId(new java.util.UUID(9, 9)));
        Repairs again = new Repairs(f.clock.now());
        charter.mc.store.Repair.run(f.state, f.knobs, again);
        check("repair: on the live state a missing parent moves the organization to the top",
                f.state.org(f.guard).orElseThrow().isTopLevel() && again.count() == 1);
    }

    // ---- json helpers ----

    static JsonObject read(Path dir) throws Exception {
        return JsonParser.parseString(Files.readString(dir.resolve(CharterStore.STATE), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static void write(Path dir, JsonObject root) throws Exception {
        Files.writeString(dir.resolve(CharterStore.STATE), root.toString(), StandardCharsets.UTF_8);
    }

    static void removeWhere(JsonArray array, java.util.function.Predicate<JsonObject> test) {
        for (int i = array.size() - 1; i >= 0; i--) {
            if (test.test(array.get(i).getAsJsonObject())) array.remove(i);
        }
    }
}
