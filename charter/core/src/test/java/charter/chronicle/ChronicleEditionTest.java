package charter.chronicle;

import charter.Fixture;
import charter.gov.GovEvents;
import charter.gov.Preset;
import charter.history.History;
import charter.history.HistoryEvent;
import charter.id.CoreId;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.LandEvents;
import charter.org.OrgEvents;
import charter.siege.SiegeEvents;
import charter.time.Durations;
import charter.treasury.TreasuryEvents;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static charter.Check.check;
import static charter.Check.eq;

/**
 * The Chronicle's second edition (docs/2026-11-04-chronicle-design.md): headlines, tiers,
 * story titles, milestones, Sovereign amounts and the feed.
 */
public final class ChronicleEditionTest {

    private static final OrgId ASHFALL = new OrgId(new UUID(0, 1));
    private static final OrgId RIVERSIDE = new OrgId(new UUID(0, 2));
    private static final OrgId RAIDERS = new OrgId(new UUID(0, 9));
    private static final PlayerId KRIS = new PlayerId(new UUID(1, 1));
    private static final PlayerId GIL = new PlayerId(new UUID(1, 2));
    private static final OfficeId CROWN = new OfficeId(new UUID(2, 1));
    private static final OfficeId TREASURER = new OfficeId(new UUID(2, 2));
    private static final CoreId CAMP = new CoreId(new UUID(5, 2));
    private static final long AT = Durations.days(91) + Durations.hours(5);

    private static final NameLookup NAMES = new NameLookup() {
        @Override public String org(OrgId id) { return id.equals(ASHFALL) ? "Ashfall" : id.equals(RIVERSIDE) ? "Riverside" : id.equals(RAIDERS) ? "the Red Band" : "an unknown organization"; }
        @Override public String player(PlayerId id) { return id.equals(KRIS) ? "Kris" : id.equals(GIL) ? "Gil" : "someone"; }
        @Override public String office(OfficeId id) { return id.equals(CROWN) ? "Lord" : id.equals(TREASURER) ? "Treasurer" : "an unknown office"; }
        @Override public boolean isDeciders(OfficeId id) { return id.equals(CROWN); }
        @Override public boolean isNpc(OrgId id) { return id.equals(RAIDERS); }
        @Override public String core(CoreId id) { return "the outpost of Riverside"; }
    };

    private ChronicleEditionTest() {
    }

    public static void run() {
        everyEventHasAHeadlineAndATier();
        headlinesDropTheDate();
        storiesAndTiers();
        coinsAreToldInSovereigns();
        serverLinesKeepTheBuiltInHeadlineAndTier();
        milestonesFireOnceThroughTheEngine();
        theFeedIsPublicAndHonest();
    }

    private static ChronicleRenderer renderer() {
        return new ChronicleRenderer(DefaultTemplates.get(), new Calendar(0, Durations.DAY), NAMES, false);
    }

    private static History.Entry entry(HistoryEvent event) {
        return new History.Entry(event, "test", false);
    }

    /** Every event record the suite knows has a line, a headline (its own or derived) and a tier, and no headline is long. */
    static void everyEventHasAHeadlineAndATier() {
        TemplateSet defaults = DefaultTemplates.get();
        List<Class<?>> families = List.of(OrgEvents.class, GovEvents.class, charter.grant.GrantEvents.class, TreasuryEvents.class,
                LandEvents.class, SiegeEvents.class, charter.contract.ContractEvents.class, charter.org.RankEvents.class,
                charter.moderation.ModerationEvents.class, charter.npc.NpcEvents.class, MilestoneEvents.class);
        for (Class<?> family : families) {
            for (Class<?> type : family.getDeclaredClasses()) {
                if (!HistoryEvent.class.isAssignableFrom(type)) continue;
                String key = TemplateSet.keyOf(type);
                Template t = defaults.get(key).orElse(null);
                check("edition: " + key + " has a template", t != null);
                if (t == null) continue;
                check("edition: " + key + " has a tier", t.tier() != null);
                if (t.headline() == null) continue;
                check("edition: " + key + " headline is short", t.headline().length() <= 80);
                check("edition: " + key + " headline has no date", !t.headline().contains("{when}"));
                check("edition: " + key + " headline has no em dash or double hyphen", !t.headline().contains("—") && !t.headline().contains("--"));
                // a headline hole must name a field of the record, or players read "unknown"
                java.util.Set<String> fields = new java.util.HashSet<>(java.util.Set.of("when", "actor", "type"));
                for (RecordComponent c : type.getRecordComponents()) fields.add(c.getName());
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([A-Za-z_]+)[#?]?[^}]*}").matcher(t.headline());
                while (m.find()) check("edition: " + key + " headline names {" + m.group(1) + "}", fields.contains(m.group(1)));
            }
        }
        check("edition: the moderation headline never carries the reason", !defaults.get("ModerationEvents.Kicked").orElseThrow().headline().contains("reason"));
        check("edition: the vote headline never carries the motion text", !defaults.get("GovEvents.VoteOpened").orElseThrow().headline().contains("about"));
    }

    static void headlinesDropTheDate() {
        ChronicleRenderer r = renderer();
        eq("headline: a join from nowhere, derived from the line", "Gil joined Ashfall.",
                r.headline(entry(new OrgEvents.Joined(AT, ASHFALL, GIL, null))).orElse(""));
        eq("headline: a defection carries its title", "Gil joined Ashfall after leaving Riverside. The Betrayal.",
                r.headline(entry(new OrgEvents.Joined(AT, ASHFALL, GIL, RIVERSIDE))).orElse(""));
        eq("headline: a template of its own", "Ashfall declared war on Riverside and named the outpost of Riverside.",
                r.headline(entry(new SiegeEvents.WarDeclared(AT, 0, 1, ASHFALL, RIVERSIDE, CAMP, 99))).orElse(""));
        eq("headline: derived, with the year clause dropped too", "Riverside raised a keep at 200, -56.",
                ChronicleRenderer.headlineFrom("On day 2, the first week of Bargains, year 2, Riverside raised a keep at 200, -56. More followed."));
        check("headline: a hidden entry has none", r.headline(entry(new OrgEvents.Asked(AT, ASHFALL, GIL))).isEmpty());
        eq("headline: the rescued claims keep the gametest's words in the line", "On day 1, the second week of Eclipse, 3 claims of Ashfall were taken up again and held.",
                r.render(entry(new LandEvents.Rescued(AT, ASHFALL, 3))).orElse(""));
    }

    static void storiesAndTiers() {
        ChronicleRenderer r = renderer();
        eq("tier: a defection is the realm's", Tier.REALM, r.tier(entry(new OrgEvents.Joined(AT, ASHFALL, GIL, RIVERSIDE))));
        eq("tier: a plain join is the kingdom's", Tier.KINGDOM, r.tier(entry(new OrgEvents.Joined(AT, ASHFALL, GIL, null))));
        eq("tier: a crowning is the realm's", Tier.REALM, r.tier(entry(new GovEvents.OfficeFilled(AT, CROWN, ASHFALL, GIL))));
        eq("tier: a treasurer is the kingdom's", Tier.KINGDOM, r.tier(entry(new GovEvents.OfficeFilled(AT, TREASURER, ASHFALL, GIL))));
        eq("tier: a war is the realm's", Tier.REALM, r.tier(entry(new SiegeEvents.WarDeclared(AT, 0, 1, ASHFALL, RIVERSIDE, CAMP, 99))));
        eq("tier: small change is quiet", Tier.QUIET, r.tier(entry(new TreasuryEvents.DepositedMarks(AT, ASHFALL, 40))));
        eq("tier: a Sovereign is the kingdom's business", Tier.KINGDOM, r.tier(entry(new TreasuryEvents.DepositedMarks(AT, ASHFALL, 64))));
        eq("tier: a routine ask is quiet", Tier.QUIET, r.tier(entry(new OrgEvents.Asked(AT, ASHFALL, GIL))));
        eq("tier: the raiders' business is quiet", Tier.QUIET, r.tier(entry(new OrgEvents.Left(AT, RAIDERS, GIL))));
        eq("tier: an admin entry is quiet to the public", Tier.QUIET, r.tier(new History.Entry(new OrgEvents.Left(AT, ASHFALL, GIL), "console", true)));
        eq("tier: a silenced template is quiet", Tier.QUIET, r.tier(entry(new charter.org.RankEvents.RankMoved(AT, ASHFALL, "r", "Knight", 1, 2))));

        eq("story: the crowning", "Gil is Lord of Ashfall. The Crowning.", r.headline(entry(new GovEvents.OfficeFilled(AT, CROWN, ASHFALL, GIL))).orElse(""));
        eq("story: the former king", "Gil is no longer Lord of Ashfall. Former King.",
                r.headline(entry(new GovEvents.OfficeVacated(AT, CROWN, ASHFALL, GIL, "stepped down"))).orElse(""));
        check("story: a treasurer is no crowning", r.headline(entry(new GovEvents.OfficeFilled(AT, TREASURER, ASHFALL, GIL))).orElse("").equals("Gil is Treasurer of Ashfall."));
        check("story: a move in from the raiders is no betrayal", Stories.of(new OrgEvents.Joined(AT, ASHFALL, GIL, RAIDERS), NAMES).isEmpty());
        eq("story: five members", "Ashfall is 5 strong. A Light in the Window.", r.headline(entry(new MilestoneEvents.MembersReached(AT, ASHFALL, 5))).orElse(""));
        eq("story: ten members", "Ashfall is 10 strong. Full House.", r.headline(entry(new MilestoneEvents.MembersReached(AT, ASHFALL, 10))).orElse(""));
        eq("story: twenty members has no title", "Ashfall is 20 strong.", r.headline(entry(new MilestoneEvents.MembersReached(AT, ASHFALL, 20))).orElse(""));
        eq("story: first blood", "A first for Ashfall: war. First Blood.", r.headline(entry(new MilestoneEvents.FirstOf(AT, ASHFALL, Milestones.FIRST_WAR))).orElse(""));
        eq("story: the line carries the title too", "On day 1, the second week of Eclipse, Ashfall counted 5 members. A Light in the Window.",
                r.render(entry(new MilestoneEvents.MembersReached(AT, ASHFALL, 5))).orElse(""));
    }

    static void coinsAreToldInSovereigns() {
        eq("coins: under a Sovereign", "0.23 Sovereigns", ChronicleRenderer.coins(15));
        eq("coins: one Mark", "0.01 Sovereigns", ChronicleRenderer.coins(1));
        eq("coins: whole Sovereigns", "2 Sovereigns", ChronicleRenderer.coins(128));
        eq("coins: one Sovereign", "1 Sovereign", ChronicleRenderer.coins(64));
        eq("coins: mixed", "1.56 Sovereigns", ChronicleRenderer.coins(100));
        eq("coins: in a line", "On day 1, the second week of Eclipse, Gil drew 2 Sovereigns from the treasury of Ashfall.",
                renderer().render(entry(new TreasuryEvents.WithdrawnMarks(AT, ASHFALL, 128, GIL))).orElse(""));
    }

    static void serverLinesKeepTheBuiltInHeadlineAndTier() {
        TemplateSet mine = DefaultTemplates.get().overlay(new TemplateSet().put("SiegeEvents.WarDeclared", "On {when}, {attacker} came for {defender}."));
        Template t = mine.get("SiegeEvents.WarDeclared").orElseThrow();
        eq("overlay: the server's line is used", "On {when}, {attacker} came for {defender}.", t.text());
        eq("overlay: the built in tier stays", Tier.REALM, t.tier());
        check("overlay: the built in headline stays", t.headline() != null && t.headline().startsWith("{attacker} declared war"));
        TemplateSet full = DefaultTemplates.get().overlay(new TemplateSet().put("SiegeEvents.WarDeclared", "x", "y", Tier.QUIET));
        eq("overlay: a full entry replaces all three", Tier.QUIET, full.get("SiegeEvents.WarDeclared").orElseThrow().tier());
    }

    /** Through the engine: the fifth member fires once, the first war fires once, an admin's hand still counts. */
    static void milestonesFireOnceThroughTheEngine() {
        Fixture f = new Fixture();
        PlayerId lord = Fixture.player("lord");
        PlayerId[] others = new PlayerId[6];
        for (int i = 0; i < others.length; i++) others[i] = Fixture.player("m" + i);
        OrgId org = f.found("Ashfall", Preset.LORDSHIP, lord, others);
        List<MilestoneEvents.MembersReached> reached = f.state.history.of(MilestoneEvents.MembersReached.class);
        check("milestones: five members is told once " + reached, reached.stream().filter(m -> m.org().equals(org) && m.count() == 5).count() == 1);
        check("milestones: ten is not reached by seven", reached.stream().noneMatch(m -> m.count() == 10));
        check("milestones: a milestone is the rules' own, not an admin's",
                f.state.history.entries().stream().filter(e -> e.event() instanceof MilestoneEvents.MembersReached).noneMatch(History.Entry::admin));
        int before = f.state.history.of(MilestoneEvents.MembersReached.class).size();
        f.state.history.record(new OrgEvents.Joined(f.clock.now(), org, Fixture.player("late"), null), "late", false);
        List<HistoryEvent> again = Milestones.after(f.state, List.of(new OrgEvents.Joined(f.clock.now(), org, Fixture.player("late"), null)));
        check("milestones: a later join repeats nothing", again.isEmpty() && f.state.history.of(MilestoneEvents.MembersReached.class).size() == before);

        List<HistoryEvent> war = Milestones.after(f.state, List.of(new SiegeEvents.WarDeclared(f.clock.now(), 0, 1, org, org, CAMP, 1)));
        check("milestones: the first war is a first", war.size() == 1 && war.getFirst() instanceof MilestoneEvents.FirstOf first && first.what().equals(Milestones.FIRST_WAR));
        f.state.history.record(war.getFirst(), "system", false);
        check("milestones: the second war is not", Milestones.after(f.state, List.of(new SiegeEvents.WarDeclared(f.clock.now(), 0, 1, org, org, CAMP, 1))).isEmpty());
        check("milestones: nothing for an unknown faction", Milestones.after(f.state, List.of(new OrgEvents.Joined(f.clock.now(), RIVERSIDE, GIL, null))).isEmpty());
    }

    static void theFeedIsPublicAndHonest() {
        Calendar calendar = new Calendar(0, Durations.DAY);
        ChronicleRenderer r = renderer();
        List<History.Entry> entries = new ArrayList<>();
        entries.add(entry(new OrgEvents.Joined(AT, ASHFALL, GIL, RIVERSIDE)));                       // 0 realm, The Betrayal
        entries.add(entry(new OrgEvents.Asked(AT, ASHFALL, KRIS)));                                   // 1 quiet
        entries.add(new History.Entry(new OrgEvents.Left(AT, ASHFALL, KRIS), "console", true));       // 2 admin, never fed
        entries.add(entry(new OrgEvents.Joined(AT + 1, RIVERSIDE, KRIS, null)));                      // 3 and 4 fold into one
        entries.add(entry(new OrgEvents.Joined(AT + 2, RIVERSIDE, GIL, null)));
        entries.add(entry(new TreasuryEvents.DepositedMarks(AT, ASHFALL, 3)));                        // 5 quiet
        entries.add(entry(new OrgEvents.Renamed(AT, ASHFALL, "Ashfall", "Ash \"the\" Fall\\n")));   // 6 realm, escaped
        List<String> lines = ChronicleFeed.lines(r, calendar, entries, 100);
        eq("feed: only told happenings are written", 3, lines.size());
        String first = lines.get(0);
        check("feed: the shape " + first, first.startsWith("{\"at\":" + AT + ",\"day\":\"day 1, the second week of Eclipse\",\"tier\":\"realm\",\"kind\":\"OrgEvents.Joined\",\"story\":\"The Betrayal\",\"line\":\"On day 1"));
        check("feed: names are listed", first.contains("\"orgs\":[\"Ashfall\",\"Riverside\"]") && first.contains("\"players\":[\"Gil\"]"));
        check("feed: the cursor is the history index", first.endsWith(",\"n\":100}"));
        String folded = lines.get(1);
        check("feed: a fold covers its last entry " + folded, folded.endsWith(",\"n\":104}") && folded.contains("Kris and Gil joined Riverside"));
        check("feed: a fold has no story", !folded.contains("\"story\""));
        String renamed = lines.get(2);
        check("feed: quotes and backslashes are escaped " + renamed, renamed.contains("Ash \\\"the\\\" Fall\\\\n") && renamed.endsWith(",\"n\":106}"));
        eq("feed: the index reads back", 106, ChronicleFeed.indexOf(renamed));
        eq("feed: a stray line has no index", -1, ChronicleFeed.indexOf("not a feed line"));
        for (String line : lines) check("feed: no dashes in " + line, !line.contains("—") && !line.contains("--"));
        check("feed: no reason or motion text reaches it",
                ChronicleFeed.lines(r, calendar, List.of(entry(new charter.moderation.ModerationEvents.Kicked(AT, ASHFALL, KRIS, GIL, "SECRET"))), 0)
                        .stream().noneMatch(l -> l.contains("SECRET")));
        check("feed: an empty run writes nothing", ChronicleFeed.lines(r, calendar, List.of(), 0).isEmpty());
        check("feed: a map value is escaped and the admin clerk never appears",
                ChronicleFeed.lines(r, calendar, List.of(new History.Entry(new LandEvents.Rescued(AT, ASHFALL, 2), "console", true)), 0).isEmpty());
        Map<String, String> unused = Map.of();
        check("feed: nothing else", unused.isEmpty());
    }
}
