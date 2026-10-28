package charter.chronicle;

import charter.Fixture;
import charter.gov.GovEvents;
import charter.gov.Office;
import charter.gov.Preset;
import charter.gov.Vote;
import charter.gov.actions.Appoint;
import charter.gov.actions.CreateOffice;
import charter.grant.GrantEvents;
import charter.grant.Holder;
import charter.grant.Power;
import charter.history.History;
import charter.history.HistoryEvent;
import charter.id.GrantId;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.id.VoteId;
import charter.org.OrgEvents;
import charter.treasury.Currency;
import charter.treasury.TreasuryEvents;
import charter.treasury.actions.Deposit;
import charter.treasury.actions.Withdraw;
import charter.time.Durations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static charter.Check.check;
import static charter.Check.eq;

/** M4: templates, calendar and the renderer (DESIGN.md §17, PLAN.md §7.5). */
public final class ChronicleTest {

    private static final OrgId ASHFALL = new OrgId(new UUID(0, 1));
    private static final OrgId RIVERSIDE = new OrgId(new UUID(0, 2));
    private static final PlayerId KRIS = new PlayerId(new UUID(1, 1));
    private static final PlayerId GIL = new PlayerId(new UUID(1, 2));
    private static final OfficeId TREASURER = new OfficeId(new UUID(2, 1));
    private static final VoteId VOTE = new VoteId(new UUID(3, 1));
    private static final GrantId GRANT = new GrantId(new UUID(4, 1));

    /** Day 91 from the origin: the second week of Frostfall in year one. */
    private static final long AT = Durations.days(91) + Durations.hours(5);
    private static final String IN = "In the second week of Frostfall, ";

    private static final NameLookup NAMES = new NameLookup() {
        @Override public String org(OrgId id) { return id.equals(ASHFALL) ? "Ashfall" : id.equals(RIVERSIDE) ? "Riverside" : "an unknown organization"; }
        @Override public String player(PlayerId id) { return id.equals(KRIS) ? "Kris" : id.equals(GIL) ? "Gil" : "someone"; }
        @Override public String office(OfficeId id) { return id.equals(TREASURER) ? "Treasurer" : "an unknown office"; }
    };

    private ChronicleTest() {
    }

    public static void run() {
        calendarPhrases();
        goldenTemplates();
        noDashesInTemplates();
        adminEventsAreHiddenUnlessOptedIn();
        unknownEventsRenderTheFallback();
        serverTemplatesOverlayTheDefaults();
        sampleChronicle();
    }

    private static ChronicleRenderer renderer(boolean admin) {
        return new ChronicleRenderer(DefaultTemplates.get(), new Calendar(0, Durations.DAY), NAMES, admin);
    }

    static void calendarPhrases() {
        Calendar c = new Calendar(0, Durations.DAY);
        eq("calendar: day 0", "the first week of Seedtime", c.phrase(0));
        eq("calendar: the last day of a week", "the first week of Seedtime", c.phrase(Durations.days(6)));
        eq("calendar: the next week", "the second week of Seedtime", c.phrase(Durations.days(7)));
        eq("calendar: the next season", "the first week of Highsun", c.phrase(Durations.days(28)));
        eq("calendar: Frostfall", "the second week of Frostfall", c.phrase(Durations.days(91)));
        eq("calendar: the last day of the year", "the fourth week of Frostfall", c.phrase(Durations.days(111)));
        eq("calendar: the year turns", "the first week of Seedtime, year 2", c.phrase(Durations.days(112)));
        eq("calendar: before the origin counts as the start", "the first week of Seedtime", c.phrase(-5));
        Calendar fast = new Calendar(1_000, Durations.HOUR);
        eq("calendar: a short day moves the weeks along", "the second week of Seedtime", fast.phrase(1_000 + 7 * Durations.HOUR));
    }

    /** One known event per default template, with the exact sentence it must produce. */
    static void goldenTemplates() {
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, HistoryEvent> events = new LinkedHashMap<>();
        golden(events, expected, new OrgEvents.Founded(AT, RIVERSIDE, "Riverside", Preset.COMMUNE, KRIS),
                IN + "Kris founded Riverside as a commune.");
        golden(events, expected, new OrgEvents.Activated(AT, ASHFALL),
                IN + "Ashfall gained its second member and became a true body.");
        golden(events, expected, new OrgEvents.SubOrgCreated(AT, RIVERSIDE, ASHFALL, "Riverside", Preset.LORDSHIP, GIL),
                IN + "Gil raised Riverside as a lordship under Ashfall.");
        golden(events, expected, new OrgEvents.Invited(AT, ASHFALL, GIL),
                IN + "Ashfall invited Gil to join them.");
        golden(events, expected, new OrgEvents.Joined(AT, ASHFALL, GIL, RIVERSIDE),
                IN + "Gil joined Ashfall after leaving Riverside.");
        golden(events, expected, new OrgEvents.Left(AT, ASHFALL, GIL),
                IN + "Gil left Ashfall.");
        golden(events, expected, new OrgEvents.Expelled(AT, ASHFALL, GIL),
                IN + "Gil was expelled from Ashfall.");
        golden(events, expected, new OrgEvents.Renamed(AT, ASHFALL, "Ashfall", "Free Ashfall"),
                IN + "Ashfall took the name Free Ashfall.");
        golden(events, expected, new GovEvents.VoteOpened(AT, VOTE, RIVERSIDE, Vote.Kind.MOTION, "spend 30 diamond", KRIS, AT + Durations.days(1)),
                IN + "Kris put a motion before Riverside: spend 30 diamond. The members may vote until the second week of Frostfall.");
        golden(events, expected, new GovEvents.VotePassed(AT, VOTE, RIVERSIDE, 3, 1, 0),
                IN + "the vote of Riverside carried, 3 for and 1 against, with 0 abstaining.");
        golden(events, expected, new GovEvents.VoteFailed(AT, VOTE, RIVERSIDE, "the votes were tied"),
                IN + "the vote of Riverside failed: the votes were tied.");
        golden(events, expected, new GovEvents.VoteRefusedAtExecution(AT, VOTE, RIVERSIDE, "treasury_short"),
                IN + "the vote of Riverside carried, but could not be carried out, for the world had changed (treasury_short).");
        golden(events, expected, new GovEvents.OfficeCreated(AT, TREASURER, RIVERSIDE, "Treasurer", false),
                IN + "Riverside created the office of Treasurer.");
        golden(events, expected, new GovEvents.OfficeFilled(AT, TREASURER, RIVERSIDE, GIL),
                IN + "Gil took up the office of Treasurer in Riverside.");
        golden(events, expected, new GovEvents.OfficeVacated(AT, TREASURER, RIVERSIDE, GIL, "voted out"),
                IN + "Gil left the office of Treasurer in Riverside: voted out.");
        golden(events, expected, new OrgEvents.Admitted(AT, ASHFALL, GIL),
                IN + "Ashfall took in Gil.");
        golden(events, expected, new OrgEvents.BreakawayBegan(AT, ASHFALL, null, AT + 1000),
                IN + "Ashfall began to break away.");
        golden(events, expected, new OrgEvents.BreakawayLapsed(AT, ASHFALL),
                IN + "the break away of Ashfall came to nothing: its time ran out.");
        golden(events, expected, new OrgEvents.Seceded(AT, ASHFALL, RIVERSIDE),
                IN + "Ashfall broke away from Riverside and stood alone.");
        golden(events, expected, new OrgEvents.GroupBrokeAway(AT, ASHFALL, RIVERSIDE, 3),
                IN + "3 left Riverside and founded Ashfall.");
        golden(events, expected, new OrgEvents.Asked(AT, ASHFALL, GIL), "(hidden)");
        golden(events, expected, new OrgEvents.Rejected(AT, ASHFALL, GIL), "(hidden)");
        golden(events, expected, new OrgEvents.RequestLapsed(AT, ASHFALL, GIL), "(hidden)");
        golden(events, expected, new GovEvents.SeatsChanged(AT, TREASURER, RIVERSIDE, 3, charter.gov.Office.Deciding.TOGETHER),
                IN + "Riverside changed how it is ruled: 3 seats, deciding together.");
        golden(events, expected, new GrantEvents.Granted(AT, GRANT, RIVERSIDE, Power.SPEND, new Holder.OfOffice(TREASURER), null, false),
                IN + "Riverside granted the Treasurer the power to spend.");
        golden(events, expected, new GrantEvents.Revoked(AT, GRANT, RIVERSIDE, Power.MANAGE_MEMBERS),
                IN + "Riverside took back the power to manage members.");
        golden(events, expected, new TreasuryEvents.Deposited(AT, RIVERSIDE, Currency.DIAMOND, 40, KRIS),
                IN + "Kris paid 40 diamond into the treasury of Riverside.");
        golden(events, expected, new TreasuryEvents.Withdrawn(AT, RIVERSIDE, Currency.DIAMOND, 15, GIL),
                IN + "Gil drew 15 diamond from the treasury of Riverside.");
        golden(events, expected, new TreasuryEvents.Transferred(AT, RIVERSIDE, ASHFALL, Currency.COBBLESTONE, 5),
                IN + "Riverside sent 5 cobblestone to Ashfall.");

        ChronicleRenderer r = renderer(false);
        for (Map.Entry<String, HistoryEvent> e : events.entrySet()) {
            eq("golden: " + e.getKey(), expected.get(e.getKey()), r.render(new History.Entry(e.getValue(), "test", false)).orElse("(hidden)"));
        }

        // the optional parts of a template, in their other state
        eq("golden: a join from nowhere", IN + "Gil joined Ashfall.",
                r.render(new History.Entry(new OrgEvents.Joined(AT, ASHFALL, GIL, null), "test", false)).orElse(""));
        eq("golden: a reserved office", IN + "Ashfall created the office of Lord, which only a vote may fill.",
                r.render(new History.Entry(new GovEvents.OfficeCreated(AT, TREASURER, ASHFALL, "Lord", true), "test", false)).orElse(""));
        eq("golden: a grant that may be passed on", IN + "Riverside granted Gil the power to manage members, to be passed on as they see fit.",
                r.render(new History.Entry(new GrantEvents.Granted(AT, GRANT, RIVERSIDE, Power.MANAGE_MEMBERS, new Holder.OfPlayer(GIL), null, true), "test", false)).orElse(""));

        // every M1 event record has a default template and a golden test above
        TemplateSet defaults = DefaultTemplates.get();
        for (Class<?> events1 : new Class<?>[]{OrgEvents.class, GovEvents.class, GrantEvents.class, TreasuryEvents.class}) {
            for (Class<?> type : events1.getDeclaredClasses()) {
                if (!HistoryEvent.class.isAssignableFrom(type)) continue;
                String key = TemplateSet.keyOf(type);
                check("coverage: " + key + " has a default template", defaults.get(key).isPresent());
                check("coverage: " + key + " has a golden test", expected.containsKey(key));
            }
        }
    }

    private static void golden(Map<String, HistoryEvent> events, Map<String, String> expected, HistoryEvent event, String sentence) {
        String key = TemplateSet.keyOf(event.getClass());
        events.put(key, event);
        expected.put(key, sentence);
    }

    static void noDashesInTemplates() {
        for (Template t : DefaultTemplates.get().all().values()) {
            check("dashes: " + t.key() + " has no em dash", !t.text().contains("—"));
            check("dashes: " + t.key() + " has no en dash", !t.text().contains("–"));
            check("dashes: " + t.key() + " has no double hyphen", !t.text().contains("--"));
        }
        check("dashes: the calendar words have none either", java.util.Arrays.stream(Calendar.SEASONS).noneMatch(s -> s.contains("-")));
    }

    static void adminEventsAreHiddenUnlessOptedIn() {
        History.Entry admin = new History.Entry(new OrgEvents.Invited(AT, ASHFALL, GIL), "console", true);
        History.Entry player = new History.Entry(new OrgEvents.Left(AT, ASHFALL, GIL), "Gil", false);
        check("admin: hidden by default", renderer(false).render(admin).isEmpty());
        eq("admin: a player event still shows", 1, renderer(false).render(List.of(admin, player)).size());
        String shown = renderer(true).render(admin).orElse("");
        eq("admin: shown when opted in, with the clerk's voice",
                IN + "Ashfall invited Gil to join them. So it was done by order of the crown's clerk.", shown);
        eq("admin: opted in shows both", 2, renderer(true).render(List.of(admin, player)).size());
    }

    /** A made-up event kind, as a later milestone would add. */
    private record Mystery(long at) implements HistoryEvent {
    }

    private record Beacon(long at, OrgId org, int level) implements HistoryEvent {
    }

    static void unknownEventsRenderTheFallback() {
        String line = renderer(false).render(new History.Entry(new Mystery(AT), "test", false)).orElse("");
        eq("fallback: an event with no template", IN + "it was written that something happened: mystery.", line);

        TemplateSet bare = new TemplateSet();
        ChronicleRenderer none = new ChronicleRenderer(bare, new Calendar(0, Durations.DAY), NAMES, false);
        eq("fallback: even an empty template set does not crash", "Something was recorded in the second week of Frostfall.",
                none.render(new History.Entry(new Mystery(AT), "test", false)).orElse(""));

        // a later milestone plugs in by adding an entry, with no code change
        TemplateSet extended = DefaultTemplates.get().overlay(new TemplateSet().put("ChronicleTest.Beacon", "In {when}, {org} lit a beacon of level {level}."));
        ChronicleRenderer r = new ChronicleRenderer(extended, new Calendar(0, Durations.DAY), NAMES, false);
        eq("fallback: a new template entry renders a new event", IN + "Ashfall lit a beacon of level 3.",
                r.render(new History.Entry(new Beacon(AT, ASHFALL, 3), "test", false)).orElse(""));

        TemplateSet broken = new TemplateSet().put("ChronicleTest.Beacon", "{org} {nothing} {level");
        ChronicleRenderer b = new ChronicleRenderer(broken, new Calendar(0, Durations.DAY), NAMES, false);
        eq("fallback: a template with a bad hole still renders", "Ashfall unknown {level",
                b.render(new History.Entry(new Beacon(AT, ASHFALL, 3), "test", false)).orElse(""));
    }

    static void serverTemplatesOverlayTheDefaults() {
        TemplateSet mine = DefaultTemplates.get().overlay(TemplateSet.of(Map.of("OrgEvents.Left", "{player} walked out of {org}.")));
        ChronicleRenderer r = new ChronicleRenderer(mine, new Calendar(0, Durations.DAY), NAMES, false);
        eq("overlay: a server rewrites one line", "Gil walked out of Ashfall.",
                r.render(new History.Entry(new OrgEvents.Left(AT, ASHFALL, GIL), "Gil", false)).orElse(""));
        eq("overlay: the others keep the default voice", IN + "Gil was expelled from Ashfall.",
                r.render(new History.Entry(new OrgEvents.Expelled(AT, ASHFALL, GIL), "Gil", false)).orElse(""));
    }

    /** A short real history, run through the engine, rendered, and printed. */
    static void sampleChronicle() {
        Fixture f = new Fixture();
        long origin = f.clock.now();
        PlayerId kris = Fixture.player("Kris");
        PlayerId gil = Fixture.player("Gil");
        PlayerId cy = Fixture.player("Cy");
        f.state.touch(kris, "Kris", origin);
        f.state.touch(gil, "Gil", origin);
        f.state.touch(cy, "Cy", origin);

        OrgId c = f.found("Riverside", Preset.COMMUNE, kris, gil, cy);
        f.passDays(3, kris, gil, cy);
        f.as(kris, new Deposit(c, Currency.DIAMOND, 100));
        f.motion(kris, new Withdraw(c, Currency.DIAMOND, 30, kris), gil, cy);
        f.closeVotes(kris, gil, cy);
        f.motion(gil, new CreateOffice(c, "Treasurer"), kris, cy);
        f.closeVotes(kris, gil, cy);
        Office treasurer = f.office(c, "Treasurer");
        f.motion(gil, new Appoint(c, treasurer.id, cy), kris, gil);
        f.closeVotes(kris, gil, cy);

        ChronicleRenderer r = new ChronicleRenderer(DefaultTemplates.get(), Calendar.startingAt(origin), NameLookup.from(f.state), false);
        List<String> lines = r.render(f.state.history.entries());
        System.out.println("---- sample Chronicle ----");
        lines.forEach(System.out::println);
        System.out.println("---- end of sample ----");

        check("sample: the story has lines", lines.size() >= 8);
        check("sample: it starts with the founding", lines.get(0).endsWith("Kris founded Riverside as a commune."));
        check("sample: the vote is told", lines.stream().anyMatch(l -> l.contains("the vote of Riverside carried")));
        check("sample: the office is told", lines.stream().anyMatch(l -> l.contains("Cy took up the office of Treasurer in Riverside")));
        check("sample: the spend is told", lines.stream().anyMatch(l -> l.contains("Kris drew 30 diamond from the treasury of Riverside")));
        check("sample: no name is missing", lines.stream().noneMatch(l -> l.contains("unknown") || l.contains("someone") || l.contains("{")));
        check("sample: the admin invites are not in the story", lines.stream().noneMatch(l -> l.contains("invited")));
        long hidden = f.state.history.entries().stream().filter(History.Entry::admin).count();
        check("sample: the history did hold admin entries", hidden > 0);
        ChronicleRenderer all = new ChronicleRenderer(DefaultTemplates.get(), Calendar.startingAt(origin), NameLookup.from(f.state), true);
        eq("sample: opting in shows every entry", f.state.history.size(), all.render(f.state.history.entries()).size());
    }
}
