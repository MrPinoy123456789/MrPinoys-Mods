package charter.mc;

import charter.config.Knobs;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.gov.Preset;
import charter.gov.Vote;
import charter.history.History;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Claim;
import charter.land.ClaimKind;
import charter.land.CoreKind;
import charter.land.LandEvents;
import charter.mc.cmd.Args;
import charter.mc.cmd.Tokens;
import charter.mc.land.ClaimIndex;
import charter.mc.store.CharterStore;
import charter.org.MemberRecord;
import charter.org.actions.AcceptInvite;
import charter.org.actions.Found;
import charter.org.actions.InviteMember;
import charter.siege.SiegeEvents;
import charter.time.Clock;
import charter.time.Durations;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;
import charter.treasury.actions.Withdraw;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static charter.mc.FabricSuite.check;

/**
 * The wired runtime with no server: the history file, offline notices and items
 * owed, the siege log, the claim index, tokens, and a restart that keeps all of it
 * and does not move the siege clock (D14).
 */
final class RuntimeTest {

    private RuntimeTest() {
    }

    /** Players listed as online get messages at once; the rest get mail. */
    static final class FakeMessenger implements Notices.Messenger {
        final Set<PlayerId> online = new HashSet<>();
        final List<String> sent = new ArrayList<>();
        final List<PlayerId> delivered = new ArrayList<>();

        @Override
        public boolean online(PlayerId player) {
            return online.contains(player);
        }

        @Override
        public void send(PlayerId player, String text) {
            sent.add(text);
        }

        @Override
        public void deliver(PlayerId player) {
            if (online.contains(player)) delivered.add(player);
        }
    }

    static void run() throws Exception {
        aVoteRunsAndSurvivesARestart();
        claimIndexFollowsTheLand();
        siegeLogWritesTheDebriefLines();
        repairsLandInTheHistoryFile();
        tokensWorkOnceForTheirPlayer();
        durationsRead();
    }

    static CharterRuntime open(Path dir, Knobs knobs, Clock clock, FakeMessenger messenger) {
        return CharterRuntime.open(dir, knobs, clock, messenger, s -> { }, s -> { });
    }

    static void aVoteRunsAndSurvivesARestart() throws Exception {
        Path dir = Files.createTempDirectory("charter-runtime");
        Knobs knobs = new Knobs();
        Clock.Manual clock = new Clock.Manual(1_800_000_000_000L);
        FakeMessenger messenger = new FakeMessenger();
        PlayerId alice = FullState.player(1), bob = FullState.player(2);
        messenger.online.add(alice);

        CharterRuntime rt = open(dir, knobs, clock, messenger);
        check("runtime: a world with no save starts fresh", rt.loaded.source() == CharterStore.Source.FRESH);
        rt.state.touch(alice, "Alice", clock.now());
        rt.state.touch(bob, "Bob", clock.now());
        rt.engine.run(new Actor.Player(alice), new Found(alice, "Oakvale", Preset.COMMUNE));
        OrgId oak = rt.state.orgNamed("Oakvale").orElseThrow().id;
        rt.engine.run(new Actor.Admin("test"), new InviteMember(oak, bob));
        check("runtime: an offline player's invite waits in their mail",
                rt.mail.peek(bob).stream().anyMatch(m -> m.text().contains("Oakvale invited you")));
        rt.engine.run(new Actor.Player(bob), new AcceptInvite(bob, oak));
        check("runtime: an online member is told at once", messenger.sent.stream().anyMatch(s -> s.contains("Bob joined Oakvale")));
        rt.engine.run(new Actor.Admin("test"), new Deposit(oak, Currency.DIAMOND, 20));

        for (MemberRecord m : List.copyOf(rt.state.members(oak))) {
            rt.state.restoreMembership(new MemberRecord(m.player(), m.org(), m.joinedAt() - Durations.days(4), m.roles()));
        }
        Outcome refused = rt.engine.run(new Actor.Player(alice), new Withdraw(oak, Currency.DIAMOND, 5, bob));
        check("runtime: a commune member cannot spend without a vote", "needs_vote".equals(refused.reasonKey()));
        Outcome opened = rt.engine.propose(alice, new Withdraw(oak, Currency.DIAMOND, 5, bob));
        check("runtime: the spend opens as a vote", opened instanceof Outcome.VoteOpened);
        check("runtime: the offline voter is told about it",
                rt.mail.peek(bob).stream().anyMatch(m -> m.text().contains("A vote opened in Oakvale: pay 5 diamonds to Bob")));
        rt.engine.cast(bob, ((Outcome.VoteOpened) opened).vote(), Vote.Ballot.YES);
        check("runtime: the passed vote moved the treasury", rt.state.org(oak).orElseThrow().ledger.balance(Currency.DIAMOND) == 15);
        check("runtime: the payout is owed to the offline payee", rt.mail.owedTo(bob).getOrDefault("minecraft:diamond", 0) == 5);

        int entries = rt.state.history.size();
        long siegeClock = rt.state.siegeClock.now();
        rt.save();
        rt.close();

        clock.advance(Durations.hours(6));
        CharterRuntime back = open(dir, knobs, clock, messenger);
        check("runtime: after a restart the organization is still there",
                back.state.org(oak).map(o -> o.ledger.balance(Currency.DIAMOND) == 15).orElse(false));
        check("runtime: the offline messages are kept", back.mail.peek(bob).size() == rt.mail.peek(bob).size());
        check("runtime: items owed are kept", back.mail.owedTo(bob).getOrDefault("minecraft:diamond", 0) == 5);
        check("runtime: the history is read back from history.jsonl", back.state.history.size() == entries);
        check("runtime: the read back history keeps its types",
                back.state.history.of(charter.gov.GovEvents.VotePassed.class).size() == 1);
        check("runtime: a restart does not move the siege clock (D14)", back.state.siegeClock.now() == siegeClock);
        knobs.timeScale = 24;
        back.sim.step(5_000);
        check("runtime: a sim step moves siege time by real time times the scale", back.state.siegeClock.now() == siegeClock + 120_000);
        String history = Files.readString(dir.resolve("history.jsonl"), StandardCharsets.UTF_8);
        check("runtime: history.jsonl names each event's type", history.contains("\"type\":\"OrgEvents.Founded\""));
        check("runtime: history.jsonl is not rewritten on restart", history.lines().count() == entries);
        back.close();
    }

    static void claimIndexFollowsTheLand() {
        FullState f = FullState.build();
        ClaimIndex index = new ClaimIndex(f.state);
        f.state.history.listen(index::onEntry);
        check("index: built from the state on load", index.size() == f.state.claims().size()
                && index.at(FullState.OW, 2, 0) != null && index.at(FullState.OW, 2, 0).owner().equals(f.oakvale));
        check("index: other dimensions and unclaimed chunks find nothing",
                index.at("minecraft:the_nether", 2, 0) == null && index.at(FullState.OW, 40, 40) == null);

        ChunkKey chunk = new ChunkKey(FullState.OW, -3, 7);
        Claim claim = new Claim(f.state.ids.claim(), chunk, f.oakvale, ClaimKind.OUTLYING, f.keep.id, 3, 0);
        f.state.addClaim(claim);
        int rebuilds = index.rebuilds();
        f.state.history.record(new LandEvents.Claimed(0, f.oakvale, chunk, f.keep.id), "test", false);
        check("index: a claim is patched in without a rebuild", index.at(chunk) != null && index.rebuilds() == rebuilds);
        f.state.removeClaim(claim.id);
        f.state.history.record(new LandEvents.Unclaimed(0, f.oakvale, chunk), "test", false);
        check("index: an unclaim is patched out", index.at(chunk) == null);
        f.state.removeClaim(f.state.claimAt(f.camp.center).orElseThrow().id);
        f.state.history.record(new LandEvents.CoreLost(0, f.ironhold, f.camp.id, CoreKind.CAMP, f.camp.center), "test", false);
        check("index: a core lost rebuilds", index.rebuilds() == rebuilds + 1 && index.at(f.camp.center) == null);
        check("index: negative chunk coordinates pack apart",
                ClaimIndex.pack(-1, 0) != ClaimIndex.pack(0, -1) && ClaimIndex.pack(-1, -1) != ClaimIndex.pack(1, 1));
    }

    static void siegeLogWritesTheDebriefLines() throws Exception {
        FullState f = FullState.build();
        Path dir = Files.createTempDirectory("charter-siegelog");
        charter.mc.store.SiegeLog log = new charter.mc.store.SiegeLog(dir, charter.mc.store.Persistence.json(),
                f.state.siegeClock::now, true, s -> { });
        long at = 1_800_000_000_000L;
        log.onEntry(new History.Entry(new SiegeEvents.CampActive(at, 42_000, f.camp.id, f.ironhold), "system", false));
        log.onEntry(new History.Entry(new charter.org.OrgEvents.Activated(at, f.oakvale), "system", false));
        log.admin(at, "Op", "/charter admin time advance 2h");
        Path day = dir.resolve(java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneOffset.UTC).toLocalDate() + ".jsonl");
        List<String> lines = Files.readAllLines(day, StandardCharsets.UTF_8);
        check("siege log: one line per followed event plus the admin line", lines.size() == 2);
        JsonObject first = com.google.gson.JsonParser.parseString(lines.getFirst()).getAsJsonObject();
        check("siege log: the common fields are filled", first.get("ev").getAsString().equals("camp_active")
                && first.get("siegeT").getAsLong() == 42_000 && first.get("site").getAsString().equals(f.camp.id.value().toString())
                && first.has("t"));
        check("siege log: admin commands are written with their arguments", lines.get(1).contains("\"ev\":\"admin\"") && lines.get(1).contains("advance 2h"));
        log.close();
        Files.writeString(dir.resolve("2000-01-01.jsonl"), "{}\n");
        check("siege log: day files past the retention are deleted", log.prune(at, 30) == 1 && Files.exists(day));
    }

    static void repairsLandInTheHistoryFile() throws Exception {
        FullState f = FullState.build();
        Path dir = Files.createTempDirectory("charter-runtime");
        StoreTest.store(dir).save(f.state, f.clock.now());
        JsonObject root = StoreTest.read(dir);
        JsonArray grants = root.getAsJsonObject("grants").getAsJsonArray("grants");
        JsonObject stray = grants.get(0).getAsJsonObject().deepCopy();
        stray.addProperty("id", new UUID(7, 7).toString());
        stray.addProperty("subject", new UUID(8, 8).toString());
        grants.add(stray);
        StoreTest.write(dir, root);
        CharterRuntime rt = open(dir, f.knobs, f.clock, new FakeMessenger());
        String history = Files.readString(dir.resolve("history.jsonl"), StandardCharsets.UTF_8);
        check("runtime: each load repair is in history.jsonl as an admin event by repair",
                history.contains("\"actor\":\"repair\"") && history.contains("\"admin\":true") && history.contains("grant_dropped"));
        check("runtime: admin repair on a sound state finds nothing", rt.repairNow().count() == 0);
        rt.close();
    }

    static void tokensWorkOnceForTheirPlayer() {
        Tokens<String> tokens = new Tokens<>();
        UUID me = new UUID(1, 1), other = new UUID(2, 2);
        List<String> ran = new ArrayList<>();
        String token = tokens.issue(me, 0, ran::add);
        check("tokens: someone else's token does nothing", tokens.take(token, other, 1).isEmpty());
        tokens.take(token, me, 1).ifPresent(r -> r.accept("first"));
        check("tokens: the player's own token runs", ran.equals(List.of("first")));
        check("tokens: a token works once", tokens.take(token, me, 2).isEmpty());
        String late = tokens.issue(me, 0, ran::add);
        check("tokens: a token runs out", tokens.take(late, me, Tokens.LIFETIME_MILLIS + 1).isEmpty());
        for (int i = 0; i < Tokens.PER_PLAYER + 50; i++) tokens.issue(me, 10, ran::add);
        check("tokens: each player keeps a bounded number", tokens.issuedTo(me).size() <= Tokens.PER_PLAYER);
    }

    static void durationsRead() {
        check("args: 90m reads", Args.duration("90m") == Durations.minutes(90));
        check("args: 1d12h reads", Args.duration("1d12h") == Durations.hours(36));
        check("args: junk does not read", Args.duration("2 hours") == -1 && Args.duration("") == -1 && Args.duration("h") == -1);
    }
}
