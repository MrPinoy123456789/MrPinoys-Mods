package charter.mc;

import charter.chronicle.DefaultTemplates;
import charter.chronicle.TemplateSet;
import charter.land.LandEvents;
import charter.mc.chronicle.ChronicleBooks;
import charter.mc.chronicle.ChronicleVoice;
import charter.mc.chronicle.RealmLecterns;
import charter.mc.store.CharterStore;
import charter.mc.store.Persistence;
import charter.siege.SiegeEvents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static charter.mc.FabricSuite.check;

/**
 * M13: the Chronicle as book pages (the voice file, the page limits, who sees what) with no server.
 */
final class ChronicleBookTest {

    private ChronicleBookTest() {
    }

    static void run() throws Exception {
        Persistence.register();
        RealmLecterns.registerBlock();
        theVoiceFileIsReadOrCreated();
        pagesStayWithinTheLimits();
        aFactionBookTellsOnlyItsOwnStory();
        adminEventsAreHiddenUnlessTheServerOptsIn();
        realmLecternsSurviveASave();
        playerTextHasNoDashes();
        theVoiceFileHoldsHeadlinesAndTiers();
        theFeedFileAppendsAndResumes();
    }

    static void theVoiceFileIsReadOrCreated() throws Exception {
        Path dir = Files.createTempDirectory("charter-voice");
        List<String> warned = new ArrayList<>();
        TemplateSet first = ChronicleVoice.readOrCreate(dir, s -> { }, warned::add);
        check("voice: the first boot writes the file", Files.exists(dir.resolve(ChronicleVoice.FILE_NAME)));
        check("voice: the first boot uses the built in lines", first.get("OrgEvents.Left").orElseThrow().text().equals(DefaultTemplates.get().get("OrgEvents.Left").orElseThrow().text()));
        check("voice: the file holds the camp lines too", Files.readString(dir.resolve(ChronicleVoice.FILE_NAME)).contains("SiegeEvents.CampActive"));

        Files.writeString(dir.resolve(ChronicleVoice.FILE_NAME), "{ \"OrgEvents.Left\": \"{player} walked out of {org}.\", \"LandEvents.Claimed\": \"\" }");
        TemplateSet edited = ChronicleVoice.readOrCreate(dir, s -> { }, warned::add);
        check("voice: a rewritten line is used", edited.get("OrgEvents.Left").orElseThrow().text().equals("{player} walked out of {org}."));
        check("voice: a line missing from the file keeps its default", edited.get("OrgEvents.Joined").isPresent());
        check("voice: an emptied line stays empty", edited.get("LandEvents.Claimed").orElseThrow().text().isEmpty());

        Files.writeString(dir.resolve(ChronicleVoice.FILE_NAME), "{ not json");
        TemplateSet broken = ChronicleVoice.readOrCreate(dir, s -> { }, warned::add);
        check("voice: a broken file falls back to the built in voice", broken.get("OrgEvents.Joined").isPresent());
        check("voice: a broken file is reported", !warned.isEmpty());
        check("voice: a broken file is left for the operator to fix", Files.readString(dir.resolve(ChronicleVoice.FILE_NAME)).equals("{ not json"));
    }

    static void pagesStayWithinTheLimits() {
        FullState f = FullState.build();
        long at = f.clock.now();
        for (int i = 0; i < 3000; i++) {
            f.state.history.record(new LandEvents.Rescued(at + i, f.oakvale, i + 2), "sim", false);
        }
        ChronicleBooks.Volume realm = ChronicleBooks.realm(f.state, DefaultTemplates.get(), false, 1);
        check("pages: a volume holds at most 50 pages, so well under the book's 100", realm.pages().size() <= ChronicleBooks.VOLUME_PAGES);
        check("pages: a long history fills the volume", realm.pages().size() == ChronicleBooks.VOLUME_PAGES);
        check("pages: older volumes are announced", realm.hasOlder());
        check("pages: no page passes 1024 characters", realm.pages().stream().allMatch(p -> p.length() <= ChronicleBooks.MAX_PAGE_CHARS));
        check("pages: no page is too long to read without clipping", realm.pages().stream().allMatch(p -> p.length() <= 330));
        check("pages: the title page says where the older tales are", realm.pages().getFirst().contains("/charter chronicle realm volume 2"));
        check("pages: the title fits a book title", realm.title().length() <= 32);
        check("pages: newest first", realm.pages().get(1).contains("3001 claims"));

        ChronicleBooks.Volume second = ChronicleBooks.realm(f.state, DefaultTemplates.get(), false, 2);
        check("pages: the second volume holds older tales", !second.pages().get(1).equals(realm.pages().get(1)) && second.number() == 2);
        check("pages: the second volume has its own limit", second.pages().size() <= ChronicleBooks.VOLUME_PAGES);
        ChronicleBooks.Volume far = ChronicleBooks.realm(f.state, DefaultTemplates.get(), false, 500);
        check("pages: a volume past the end says so and stays small", far.pages().size() == 2 && !far.hasOlder());

        // one entry longer than a page runs over, whole
        TemplateSet longer = DefaultTemplates.get().overlay(new TemplateSet().put("LandEvents.Rescued", "x ".repeat(900)));
        ChronicleBooks.Volume run = ChronicleBooks.realm(f.state, longer, false, 1);
        check("pages: an entry longer than a page is split, not clipped", run.pages().stream().allMatch(p -> p.length() <= ChronicleBooks.MAX_PAGE_CHARS)
                && run.pages().size() <= ChronicleBooks.VOLUME_PAGES);

        FullState quiet = new FullState(new charter.state.CharterState());
        ChronicleBooks.Volume empty = ChronicleBooks.realm(quiet.state, DefaultTemplates.get(), false, 1);
        check("pages: an empty history still makes a readable book", empty.pages().size() == 2 && empty.pages().get(1).contains("Nothing has been written"));
    }

    static void aFactionBookTellsOnlyItsOwnStory() {
        FullState f = FullState.build();
        long at = f.clock.now() + 1000;
        f.state.history.record(new LandEvents.Rescued(at, f.oakvale, 7), "sim", false);
        f.state.history.record(new LandEvents.Lapsed(at + 1, f.ironhold, 9), "sim", false);
        f.state.history.record(new SiegeEvents.CampActive(at + 2, 0, f.camp.id, f.ironhold), "sim", false);
        String oak = String.join("\n", ChronicleBooks.faction(f.state, DefaultTemplates.get(), false, f.oakvale, 1).pages());
        check("faction: its own event is told", oak.contains("7 claims"));
        check("faction: a rival's event is not", !oak.contains("9 claims"));
        charter.id.OrgId owner = f.state.core(f.camp.id).orElseThrow().owner;
        String keepBook = String.join("\n", ChronicleBooks.faction(f.state, DefaultTemplates.get(), false, owner, 1).pages());
        check("faction: an event that names only its camp reaches the camp's owner", keepBook.contains("raising an outpost"));
    }

    static void adminEventsAreHiddenUnlessTheServerOptsIn() {
        FullState f = FullState.build();
        long at = f.clock.now() + 1000;
        f.state.history.record(new LandEvents.Rescued(at, f.oakvale, 42), "console", true);
        String hidden = String.join("\n", ChronicleBooks.faction(f.state, DefaultTemplates.get(), false, f.oakvale, 1).pages());
        String shown = String.join("\n", ChronicleBooks.faction(f.state, DefaultTemplates.get(), true, f.oakvale, 1).pages());
        check("admin: hidden by default", !hidden.contains("42 claims"));
        check("admin: shown with the clerk's voice when the server opts in", shown.contains("42 claims") && shown.contains("clerk"));
        check("admin: the knob defaults to off", !new charter.config.Knobs().chronicleShowAdmin);
    }

    static void realmLecternsSurviveASave() throws Exception {
        FullState f = FullState.build();
        RealmLecterns.of(f.state).add(new RealmLecterns.Spot(FullState.OW, 0, 64, 0));
        Path dir = Files.createTempDirectory("charter-realm");
        CharterStore store = new CharterStore(dir, Persistence.codec(Persistence.json()), s -> { }, s -> { });
        store.save(f.state, f.clock.now());
        String first = Files.readString(dir.resolve("state.json"));
        CharterStore.Loaded back = store.load(f.knobs, f.clock.now());
        check("realm: the lectern is remembered", RealmLecterns.of(back.state()).all().equals(List.of(new RealmLecterns.Spot(FullState.OW, 0, 64, 0))));
        store.save(back.state(), f.clock.now());
        check("realm: the same state writes the same snapshot", first.equals(Files.readString(dir.resolve("state.json"))));
    }

    static void theVoiceFileHoldsHeadlinesAndTiers() throws Exception {
        Path dir = Files.createTempDirectory("charter-voice2");
        ChronicleVoice.readOrCreate(dir, s -> { }, s -> { });
        String written = Files.readString(dir.resolve(ChronicleVoice.FILE_NAME));
        check("voice: the file holds headlines", written.contains("\"headline\""));
        check("voice: the file holds tiers", written.contains("\"tier\": \"realm\""));
        check("voice: the reserved lines stay plain strings", written.contains("\"_admin\": \"So it was done"));
        Files.writeString(dir.resolve(ChronicleVoice.FILE_NAME), "{ \"SiegeEvents.WarDeclared\": { \"line\": \"On {when}, {attacker} came for {defender}.\", \"headline\": \"War: {attacker} on {defender}.\", \"tier\": \"kingdom\" },"
                + " \"OrgEvents.Left\": \"{player} walked out of {org}.\", \"OrgEvents.Joined\": { \"line\": \"x\", \"tier\": \"nonsense\" } }");
        List<String> warned = new ArrayList<>();
        TemplateSet edited = ChronicleVoice.readOrCreate(dir, s -> { }, warned::add);
        charter.chronicle.Template war = edited.get("SiegeEvents.WarDeclared").orElseThrow();
        check("voice: an object sets all three", war.text().startsWith("On {when}, {attacker} came") && war.headline().startsWith("War:") && war.tier() == charter.chronicle.Tier.KINGDOM);
        charter.chronicle.Template left = edited.get("OrgEvents.Left").orElseThrow();
        check("voice: a string keeps the built in headline and tier", left.text().startsWith("{player} walked") && left.headline() != null && left.tier() == charter.chronicle.Tier.KINGDOM);
        check("voice: an unknown tier is reported and the built in one kept", warned.stream().anyMatch(w -> w.contains("unknown tier"))
                && edited.get("OrgEvents.Joined").orElseThrow().tier() == charter.chronicle.Tier.KINGDOM);
    }

    static void theFeedFileAppendsAndResumes() throws Exception {
        FullState f = FullState.build();
        Path dir = Files.createTempDirectory("charter-feed");
        charter.mc.chronicle.ChronicleFeedFile feed = new charter.mc.chronicle.ChronicleFeedFile(dir, s -> { });
        int size = f.state.history.size();
        feed.start(size);
        check("feed: with no file the feed starts from now", feed.cursor() == size);
        check("feed: nothing old is written", feed.write(f.state, DefaultTemplates.get()) == 0 && !Files.exists(feed.file()));
        long at = f.clock.now() + 1000;
        f.state.history.record(new LandEvents.Rescued(at, f.oakvale, 7), "sim", false);
        f.state.history.record(new LandEvents.Rescued(at + 1, f.oakvale, 42), "console", true);
        f.state.history.record(new charter.org.OrgEvents.Asked(at + 2, f.oakvale, f.cara), "cara", false);
        f.state.history.record(new SiegeEvents.WarDeclared(at + 3, 0, 1, f.oakvale, f.ironhold, f.camp.id, 99), "alice", false);
        int written = feed.write(f.state, DefaultTemplates.get());
        List<String> lines = Files.readAllLines(feed.file());
        check("feed: told entries are appended, admin and quiet ones are not " + lines, written == 2 && lines.size() == 2
                && lines.get(0).contains("7 claims") && !String.join("", lines).contains("42 claims") && lines.get(1).contains("\"tier\":\"realm\""));
        check("feed: the cursor points past the last entry", lines.get(1).endsWith(",\"n\":" + (size + 3) + "}"));
        check("feed: a second write with nothing new writes nothing", feed.write(f.state, DefaultTemplates.get()) == 0);
        feed.close();

        charter.mc.chronicle.ChronicleFeedFile again = new charter.mc.chronicle.ChronicleFeedFile(dir, s -> { });
        again.start(f.state.history.size());
        check("feed: a restart resumes after the last fed entry", again.cursor() == size + 4);
        f.state.history.record(new LandEvents.Lapsed(at + 4, f.ironhold, 9), "sim", false);
        check("feed: and feeds only what came after", again.write(f.state, DefaultTemplates.get()) == 1 && Files.readAllLines(feed.file()).size() == 3);
        again.close();
        check("feed: the knob defaults to on", new charter.config.Knobs().chronicleFeed);
    }

    static void playerTextHasNoDashes() {
        for (var e : Text.defaults().entrySet()) {
            if (!e.getKey().startsWith("chronicle.")) continue;
            check("text: " + e.getKey() + " has no em dash or double hyphen", !e.getValue().contains("2014") && !e.getValue().contains("--"));
        }
        check("text: the Chronicle's keys exist", Text.defaults().keySet().stream().anyMatch(k -> k.startsWith("chronicle.")));
    }
}
