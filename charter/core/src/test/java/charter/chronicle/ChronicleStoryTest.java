package charter.chronicle;

import charter.contract.Basis;
import charter.contract.ContractEvents;
import charter.contract.Party;
import charter.contract.Term;
import charter.history.History;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.ContractId;
import charter.id.CoreId;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.CoreKind;
import charter.land.LandEvents;
import charter.siege.SiegeEvents;
import charter.time.Durations;
import charter.treasury.Currency;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static charter.Check.check;
import static charter.Check.eq;

/** M13: the default voice for land, siege and contract events, one golden sentence each. */
public final class ChronicleStoryTest {

    private static final OrgId ASHFALL = new OrgId(new UUID(0, 1));
    private static final OrgId EASTERN = new OrgId(new UUID(0, 2));
    private static final OrgId RIVERSIDE = new OrgId(new UUID(0, 3));
    private static final PlayerId KRIS = new PlayerId(new UUID(1, 1));
    private static final PlayerId GIL = new PlayerId(new UUID(1, 2));
    private static final CoreId KEEP = new CoreId(new UUID(5, 1));
    private static final CoreId CAMP = new CoreId(new UUID(5, 2));
    private static final CoreId OTHER = new CoreId(new UUID(5, 3));
    private static final ContractId CONTRACT = new ContractId(new UUID(6, 1));
    private static final ChunkKey CHUNK = new ChunkKey("minecraft:overworld", 12, -4);

    private static final long AT = Durations.days(91) + Durations.hours(5);
    private static final String IN = "In the second week of Frostfall, ";

    private static final NameLookup NAMES = new NameLookup() {
        @Override public String org(OrgId id) { return id.equals(ASHFALL) ? "Ashfall" : id.equals(EASTERN) ? "the Eastern Guard" : id.equals(RIVERSIDE) ? "Riverside" : "an unknown organization"; }
        @Override public String player(PlayerId id) { return id.equals(KRIS) ? "Kris" : id.equals(GIL) ? "Gil" : "someone"; }
        @Override public String office(OfficeId id) { return "an unknown office"; }
        @Override public String core(CoreId id) { return id.equals(KEEP) ? "the keep of Ashfall" : id.equals(CAMP) ? "the camp of the Eastern Guard" : "the keep of Riverside"; }
    };

    private static final Map<String, HistoryEvent> EVENTS = new LinkedHashMap<>();
    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

    private ChronicleStoryTest() {
    }

    public static void run() {
        goldenSentences();
        everyNewEventIsCovered();
        holesNameRealFields();
        noDashesAnywhere();
        quietEventsStayOutOfTheBooks();
        aSiegeReadsAsAStory();
    }

    private static ChronicleRenderer renderer() {
        return new ChronicleRenderer(DefaultTemplates.get(), new Calendar(0, Durations.DAY), NAMES, false);
    }

    private static void golden(HistoryEvent event, String sentence) {
        String key = TemplateSet.keyOf(event.getClass());
        EVENTS.put(key, event);
        EXPECTED.put(key, sentence);
    }

    private static String told(HistoryEvent event) {
        return renderer().render(new History.Entry(event, "test", false)).orElse("(hidden)");
    }

    static void goldenSentences() {
        // land
        golden(new LandEvents.NexusPlaced(AT, ASHFALL, KEEP, CHUNK), IN + "Ashfall raised a keep at 200, -56 and laid claim to the ground around it.");
        golden(new LandEvents.CampPlanted(AT, EASTERN, CAMP, CHUNK), IN + "the Eastern Guard planted a war banner at 200, -56.");
        golden(new LandEvents.Claimed(AT, ASHFALL, CHUNK, KEEP), IN + "Ashfall claimed the land at 200, -56.");
        golden(new LandEvents.Unclaimed(AT, ASHFALL, CHUNK), IN + "Ashfall gave up the land at 200, -56.");
        golden(new LandEvents.CoreLost(AT, ASHFALL, KEEP, CoreKind.KEEP, CHUNK), IN + "Ashfall lost its keep at 200, -56.");
        golden(new LandEvents.Reassigned(AT, ASHFALL, KEEP, OTHER, 3), IN + "3 claims of Ashfall were tied to the keep of Riverside after the keep of Ashfall was lost.");
        golden(new LandEvents.Contested(AT, ASHFALL, 1, 0), IN + "1 claim of Ashfall lost their anchor and stood contested. Unless something took them up again, they would be lost.");
        golden(new LandEvents.Rescued(AT, ASHFALL, 2), IN + "2 claims of Ashfall were taken up again and held.");
        golden(new LandEvents.Lapsed(AT, ASHFALL, 4), IN + "4 claims of Ashfall were lost for good.");
        // sieges
        golden(new SiegeEvents.CampActive(AT, 0, CAMP, EASTERN, Set.of(ASHFALL, RIVERSIDE)),
                IN + "the Eastern Guard finished raising a war camp against Ashfall and Riverside. The defenders were warned.");
        golden(new SiegeEvents.SiegeBegan(AT, 0, KEEP, ASHFALL, Set.of(EASTERN)),
                IN + "the Eastern Guard laid siege to the keep of Ashfall. Ashfall had only its granary to hold out on.");
        golden(new SiegeEvents.Encircled(AT, 0, KEEP), IN + "every road to the keep of Ashfall was cut. No food could reach its granary now.");
        golden(new SiegeEvents.RingBroken(AT, 0, KEEP), IN + "the ring around the keep of Ashfall was broken, and a road lay open again.");
        golden(new SiegeEvents.SiegeLifted(AT, 0, KEEP, Set.of(EASTERN)), IN + "the Eastern Guard gave up the siege of the keep of Ashfall, and it stood free.");
        golden(new SiegeEvents.Starving(AT, 0, KEEP), IN + "the granary of the keep of Ashfall ran low, and its people began to starve.");
        golden(new SiegeEvents.KeepFell(AT, 0, KEEP, ASHFALL, EASTERN, CHUNK),
                IN + "the keep of Ashfall fell to the Eastern Guard: the granary of Ashfall ran dry before any relief arrived.");
        golden(new SiegeEvents.Razed(AT, 0, KEEP, ASHFALL, EASTERN), IN + "the Eastern Guard razed the keep of Ashfall and left nothing standing.");
        golden(new SiegeEvents.Occupied(AT, 0, KEEP, ASHFALL, EASTERN, 99),
                IN + "the Eastern Guard chose to hold the keep of Ashfall rather than burn it. For a time it would answer to them.");
        golden(new SiegeEvents.OccupationEnded(AT, 0, KEEP, EASTERN, "the occupation ran its course"),
                IN + "the hold of the Eastern Guard on the keep of Ashfall ended: the occupation ran its course.");
        Map<Currency, Long> loot = new TreeMap<>();
        loot.put(Currency.DIAMOND, 25L);
        loot.put(Currency.COBBLESTONE, 1250L);
        golden(new SiegeEvents.Plundered(AT, KEEP, ASHFALL, CHUNK, loot),
                IN + "the treasury of Ashfall was broken open, and 1250 cobblestone and 25 diamond lay spilled where the keep fell.");
        golden(new SiegeEvents.CampStoreEmpty(AT, 0, CAMP), IN + "the stores of the camp of the Eastern Guard ran out.");
        golden(new SiegeEvents.CampCutOff(AT, 0, CAMP), IN + "the camp of the Eastern Guard was cut off from supply, and its stores began to burn faster.");
        golden(new SiegeEvents.CampExposed(AT, 0, CAMP),
                IN + "a sally emptied the hold beside the camp of the Eastern Guard. Its claims lost their guard, and its banner could be broken.");
        golden(new SiegeEvents.HoldEmptied(AT, 0, CHUNK, ASHFALL, EASTERN), IN + "the Eastern Guard raided a hold of Ashfall at 200, -56 and emptied it.");
        golden(new SiegeEvents.TruceBegan(AT, 0, KEEP, EASTERN, 99),
                IN + "a truce fell between the keep of Ashfall and the Eastern Guard, so that the war could not begin again at once.");
        golden(new SiegeEvents.FoodDeposited(AT, 0, KEEP, "cart:x", Map.of("minecraft:bread", 4), 12, true),
                IN + "food was carried into the keep of Ashfall past the besiegers.");
        golden(new SiegeEvents.ProtectionWaived(AT, RIVERSIDE), IN + "Riverside gave up the shelter owed to a new faction.");
        // contracts
        golden(new ContractEvents.Arrested(AT, ASHFALL, GIL, KRIS), IN + "Kris of Ashfall arrested Gil on their land.");
        golden(new ContractEvents.CustodyLapsed(AT, ASHFALL, GIL), IN + "Ashfall let Gil go, for no sentence was passed.");
        golden(new ContractEvents.Imposed(AT, CONTRACT, Basis.CUSTODY, Party.of(ASHFALL), Party.of(GIL),
                        List.of(new Term.Fine(4), new Term.Payment(Currency.DIAMOND, 1)), true, false, 1),
                IN + "Ashfall set 2 terms on Gil, by right of custody, though the terms were cut down to the limit.");
        golden(new ContractEvents.Fined(AT, CONTRACT, GIL, ASHFALL, 8, 1), IN + "Gil was fined 8 diamonds by Ashfall and paid 1.");
        golden(new ContractEvents.Executed(AT, CONTRACT, ASHFALL, GIL, Durations.minutes(30), Durations.days(1)),
                IN + "Ashfall put Gil to death. They would wait 30 minutes to rise again, and were banished for 1 day.");
        golden(new ContractEvents.Escaped(AT, CONTRACT, GIL, ASHFALL), IN + "Gil escaped from the cells of Ashfall.");
        golden(new ContractEvents.Pardoned(AT, CONTRACT, GIL, ASHFALL), IN + "Ashfall pardoned Gil.");
        golden(new ContractEvents.Released(AT, CONTRACT, GIL, ASHFALL), IN + "Ashfall released Gil.");
        golden(new ContractEvents.PrisonersFreed(AT, KEEP, List.of(KRIS, GIL)), IN + "the prisoners of the keep of Ashfall walked free: Kris and Gil.");
        golden(new ContractEvents.Fulfilled(AT, CONTRACT, Basis.WAR_GOAL), IN + "a contract made by right of war goal ran its course.");
        golden(new ContractEvents.Resisted(AT, CONTRACT, Basis.OCCUPATION, "the prisoner walked out"),
                IN + "a contract imposed by right of occupation ended in resistance (the prisoner walked out). No mark was set on anyone.");
        golden(new ContractEvents.Breached(AT, CONTRACT, Party.of(RIVERSIDE)), IN + "Riverside broke their word, and was marked for it.");
        golden(new ContractEvents.Ended(AT, CONTRACT, Basis.OCCUPATION, "the keep was lost"), IN + "a contract made by right of occupation ended early: the keep was lost.");

        for (Map.Entry<String, HistoryEvent> e : EVENTS.entrySet()) {
            eq("golden: " + e.getKey(), EXPECTED.get(e.getKey()), told(e.getValue()));
        }
        // the optional parts in their other state
        eq("golden: an outsider jailed", IN + "Ashfall set 1 term on Gil, by right of custody, and jailing a stranger was an act of war.",
                told(new ContractEvents.Imposed(AT, CONTRACT, Basis.CUSTODY, Party.of(ASHFALL), Party.of(GIL), List.of(new Term.Fine(4)), false, true, 1)));
        eq("golden: a player breaks their word", IN + "Gil broke their word, and was marked for it.",
                told(new ContractEvents.Breached(AT, CONTRACT, Party.of(GIL))));
    }

    static void everyNewEventIsCovered() {
        TemplateSet defaults = DefaultTemplates.get();
        for (Class<?> events : new Class<?>[]{LandEvents.class, SiegeEvents.class, ContractEvents.class}) {
            for (Class<?> type : events.getDeclaredClasses()) {
                if (!HistoryEvent.class.isAssignableFrom(type)) continue;
                String key = TemplateSet.keyOf(type);
                check("coverage: " + key + " has a default template", defaults.get(key).isPresent());
                // an event kept out of the books on purpose has an empty template and no sentence to test
                if (defaults.get(key).map(t -> t.text().isEmpty()).orElse(false)) continue;
                check("coverage: " + key + " has a golden test", EXPECTED.containsKey(key));
            }
        }
    }

    /** A hole that names no field would print "unknown" to players. */
    static void holesNameRealFields() {
        Pattern hole = Pattern.compile("\\{([A-Za-z_]+)[#?]?[^}]*}");
        for (Class<?> events : new Class<?>[]{LandEvents.class, SiegeEvents.class, ContractEvents.class}) {
            for (Class<?> type : events.getDeclaredClasses()) {
                if (!type.isRecord()) continue;
                String key = TemplateSet.keyOf(type);
                Set<String> fields = new java.util.HashSet<>(Set.of("when", "actor", "type"));
                for (RecordComponent c : type.getRecordComponents()) fields.add(c.getName());
                Matcher m = hole.matcher(DefaultTemplates.get().get(key).orElseThrow().text());
                while (m.find()) check("holes: " + key + " names {" + m.group(1) + "}, which is not a field", fields.contains(m.group(1)));
            }
        }
    }

    static void noDashesAnywhere() {
        for (Template t : DefaultTemplates.get().all().values()) {
            check("dashes: " + t.key() + " has no em dash", !t.text().contains("2014"));
            check("dashes: " + t.key() + " has no spaced double hyphen", !t.text().contains(" -- "));
            check("dashes: " + t.key() + " has no double hyphen", !t.text().contains("--"));
        }
        for (String line : EXPECTED.values()) {
            check("dashes: golden sentence has none: " + line, !line.contains("2014") && !line.contains("--"));
        }
    }

    static void quietEventsStayOutOfTheBooks() {
        ChronicleRenderer r = renderer();
        check("quiet: food carried in with no siege is not told",
                r.render(new History.Entry(new SiegeEvents.FoodDeposited(AT, 0, KEEP, "player:x", Map.of("minecraft:bread", 4), 12, false), "x", false)).isEmpty());
        check("quiet: a player's own vault deposit is not told",
                r.render(new History.Entry(new ContractEvents.VaultDeposited(AT, KRIS, Currency.DIAMOND, 5), "x", false)).isEmpty());
        TemplateSet silenced = DefaultTemplates.get().overlay(new TemplateSet().put("LandEvents.Claimed", ""));
        check("quiet: a server silences an event by emptying its template",
                new ChronicleRenderer(silenced, new Calendar(0, Durations.DAY), NAMES, false)
                        .render(new History.Entry(new LandEvents.Claimed(AT, ASHFALL, CHUNK, KEEP), "x", false)).isEmpty());
    }

    /** A short siege, printed, in the order it happened. */
    static void aSiegeReadsAsAStory() {
        List<HistoryEvent> siege = List.of(
                new SiegeEvents.CampActive(AT, 0, CAMP, EASTERN, Set.of(ASHFALL)),
                new SiegeEvents.SiegeBegan(AT + 1, 0, KEEP, ASHFALL, Set.of(EASTERN)),
                new SiegeEvents.Encircled(AT + 2, 0, KEEP),
                new SiegeEvents.Starving(AT + 3, 0, KEEP),
                new SiegeEvents.KeepFell(AT + 4, 0, KEEP, ASHFALL, EASTERN, CHUNK),
                new SiegeEvents.Occupied(AT + 5, 0, KEEP, ASHFALL, EASTERN, 99));
        System.out.println("---- sample siege ----");
        for (HistoryEvent e : siege) {
            String line = told(e);
            System.out.println(line);
            check("siege story: no hole is left open or unknown", !line.contains("{") && !line.contains("unknown"));
        }
        System.out.println("---- end of sample ----");
    }
}
