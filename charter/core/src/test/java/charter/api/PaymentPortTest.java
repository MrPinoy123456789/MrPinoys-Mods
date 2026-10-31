package charter.api;

import charter.FakePort;
import charter.Fixture;
import charter.engine.Outcome;
import charter.gov.Preset;
import charter.grant.GrantScope;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.actions.LoseCore;
import charter.land.actions.PlaceNexus;
import charter.land.actions.PlantCamp;
import charter.treasury.Currency;
import charter.treasury.TreasuryEvents;
import charter.treasury.actions.Deposit;
import charter.treasury.actions.Transfer;
import charter.treasury.actions.Withdraw;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/**
 * The payment port: what a server with no provider does (costs are waived, Charter still works), what a provider sees
 * (one charge before one credit, never a Mark made by a move), the organization id format, and the old grant scopes.
 */
public final class PaymentPortTest {

    private static final PlayerId KRIS = player("Kris");
    private static final PlayerId BOB = player("Bob");
    private static final PlayerId GIL = player("Gil");
    private static final PlayerId ZED = player("Zed");

    private PaymentPortTest() {
    }

    public static void run() {
        noProviderWaivesEveryCost();
        aProviderSeesTheKeepTheCampAndTheRefund();
        aMoveChargesThenCreditsAndNeverMints();
        aWithdrawalIsTheProvidersToMake();
        theKingdomViewAnswersByOrganizationId();
        oldGrantScopesKeepTheirLimit();
    }

    private static ChunkKey ch(int x, int z) {
        return new ChunkKey("minecraft:overworld", x, z);
    }

    static void noProviderWaivesEveryCost() {
        Fixture f = new Fixture();
        List<String> warnings = new ArrayList<>();
        f.state.payment = new NoPayment(warnings::add);
        OrgId ashfall = f.found("Ashfall", Preset.LORDSHIP, KRIS, BOB);

        check("none: a keep is placed with an empty treasury", f.as(KRIS, new PlaceNexus(ashfall, ch(0, 0))).done());
        check("none: so is an outpost", f.as(KRIS, new PlantCamp(ashfall, ch(40, 0))).done());
        var camp = f.state.coresOf(ashfall).stream().filter(c -> !c.isKeep()).findFirst().orElseThrow();
        eq("none: a waived deposit is not held, so losing the outpost refunds nothing", 0L, camp.depositMarks);
        check("none: losing the outpost works", f.admin(new LoseCore(camp.id)).done());
        eq("none: each kind of waived cost is told once", List.of("keep", "outpost"), kinds(warnings));
        f.as(KRIS, new PlantCamp(ashfall, ch(40, 0)));
        eq("none: and not again", 2, warnings.size());

        eq("none: a withdrawal is refused, there is no coin", "no_currency", f.as(KRIS, new Withdraw(ashfall, 5, KRIS)).reasonKey());
        OrgId brine = f.found("Brinewatch", Preset.LORDSHIP, ZED);
        eq("none: so is a transfer", "no_currency", f.as(KRIS, new Transfer(ashfall, brine, 5)).reasonKey());
        check("none: an operator's deposit does nothing and breaks nothing", f.admin(new Deposit(ashfall, 50)).done());
        eq("none: the port still reports nothing held", 0L, f.state.payment.balance(ashfall.toString()));
        check("none: the old item treasury was never touched", f.org(ashfall).ledger.snapshot().isEmpty());
        check("none: founding a keep needs no payer", f.as(GIL, new charter.org.actions.FoundKeep(GIL, "Gilhold", "", ch(200, 200))).done());
    }

    private static List<String> kinds(List<String> warnings) {
        List<String> kinds = new ArrayList<>();
        for (String w : warnings) {
            int open = w.indexOf('\'');
            kinds.add(w.substring(open + 1, w.indexOf('\'', open + 1)));
        }
        return kinds;
    }

    static void aProviderSeesTheKeepTheCampAndTheRefund() {
        Fixture f = new Fixture();
        OrgId ashfall = f.found("Ashfall", Preset.LORDSHIP, KRIS, BOB);
        f.admin(new Deposit(ashfall, 1000));
        String id = ashfall.toString();
        check("id: an organization id is org: and its UUID", id.startsWith("org:") && id.length() == 40);

        check("keep: placed", f.as(KRIS, new PlaceNexus(ashfall, ch(0, 0))).done());
        List<FakePort.Call> keep = f.port.calls("charge", "keep");
        eq("keep: one charge", 1, keep.size());
        eq("keep: to the organization by id", id, keep.get(0).org());
        eq("keep: for the keep price", (long) f.knobs.keepPriceMarks, keep.get(0).marks());

        check("camp: planted", f.as(KRIS, new PlantCamp(ashfall, ch(40, 0))).done());
        List<FakePort.Call> camp = f.port.calls("charge", "outpost");
        eq("camp: one charge for the base deposit", List.of((long) f.knobs.outpostDepositMarks), camp.stream().map(FakePort.Call::marks).toList());
        long before = f.marks(ashfall);
        var core = f.state.coresOf(ashfall).stream().filter(c -> !c.isKeep()).findFirst().orElseThrow();
        eq("camp: the outpost holds the deposit", (long) f.knobs.outpostDepositMarks, core.depositMarks);
        f.admin(new LoseCore(core.id));
        eq("camp: losing it credits the deposit back", before + f.knobs.outpostDepositMarks, f.marks(ashfall));
        eq("camp: as one refund credit", 1, f.port.calls("credit", "outpost_refund").size());
    }

    static void aMoveChargesThenCreditsAndNeverMints() {
        Fixture f = new Fixture();
        OrgId ashfall = f.found("Ashfall", Preset.LORDSHIP, KRIS, BOB);
        OrgId brine = f.found("Brinewatch", Preset.LORDSHIP, ZED);
        f.admin(new Deposit(ashfall, 30));
        int base = f.port.calls.size();
        eq("move: more than the treasury holds is refused", "treasury_short", f.as(KRIS, new Transfer(ashfall, brine, 31)).reasonKey());
        eq("move: and touches nothing", base, f.port.calls.size());
        check("move: within the limit and the balance it goes", f.as(KRIS, new Transfer(ashfall, brine, 30)).done());
        List<FakePort.Call> moved = f.port.calls.subList(base, f.port.calls.size());
        eq("move: a charge, then a credit", List.of("charge", "credit"), moved.stream().map(FakePort.Call::kind).toList());
        eq("move: the same amount", moved.get(0).marks(), moved.get(1).marks());
        eq("move: the payer is charged", ashfall.toString(), moved.get(0).org());
        eq("move: the payee is credited", brine.toString(), moved.get(1).org());
        eq("move: no Mark was made", 30L, f.marks(ashfall) + f.marks(brine));
    }

    static void aWithdrawalIsTheProvidersToMake() {
        Fixture f = new Fixture();
        OrgId ashfall = f.found("Ashfall", Preset.LORDSHIP, KRIS, BOB);
        f.admin(new Deposit(ashfall, 100));
        int base = f.port.calls.size();
        Outcome done = f.as(KRIS, new Withdraw(ashfall, 20, KRIS));
        check("withdraw: the Lord may within the limit", done.done());
        eq("withdraw: Charter itself charges nothing, the provider's give route debits", base, f.port.calls.size());
        check("withdraw: it is recorded for the payout", f.state.history.of(TreasuryEvents.WithdrawnMarks.class).size() == 1);
        eq("withdraw: over the Lord's limit needs a vote", "needs_vote", f.as(KRIS, new Withdraw(ashfall, f.knobs.lordSpendLimitMarks + 1, KRIS)).reasonKey());
        eq("withdraw: a player cannot deposit coin through Charter", "deposit_elsewhere", f.as(KRIS, new Deposit(ashfall, 5)).reasonKey());
    }

    static void theKingdomViewAnswersByOrganizationId() {
        Fixture f = new Fixture();
        OrgId ashfall = f.found("Ashfall", Preset.LORDSHIP, KRIS, BOB);
        OrgId guard = f.state.orgNamed("Ashfall").orElseThrow().id;
        f.as(KRIS, new charter.org.actions.CreateSubOrg(ashfall, "Guard", Preset.LORDSHIP, BOB));
        OrgId sub = f.state.orgNamed("Guard").orElseThrow().id;
        StateKingdomView view = new StateKingdomView(f.state);
        eq("view: a member's kingdom is the top level organization", java.util.Optional.of(ashfall.toString()), view.kingdomOf(KRIS.value()));
        eq("view: a sub-faction member too", java.util.Optional.of(ashfall.toString()), view.kingdomOf(BOB.value()));
        eq("view: a stranger has none", java.util.Optional.empty(), view.kingdomOf(player("Nobody").value()));
        eq("view: the Lord", java.util.Optional.of(KRIS.value()), view.lordOf(ashfall.toString()));
        eq("view: the sub-faction's own Lord", java.util.Optional.of(BOB.value()), view.lordOf(sub.toString()));
        eq("view: the display name", "Ashfall", view.displayName(guard.toString()));
        check("view: every organization is listed by id", view.orgIds().containsAll(List.of(ashfall.toString(), sub.toString())));
        eq("view: an unknown id has no Lord", java.util.Optional.empty(), view.lordOf("org:not-a-uuid"));
        eq("view: and no name", "", view.displayName("nonsense"));
    }

    static void oldGrantScopesKeepTheirLimit() {
        GrantScope old = new GrantScope(Map.of(Currency.COBBLESTONE, 2048L, Currency.DIAMOND, 32L), null);
        eq("scope: a grant saved with the old map keeps its diamond limit at the reference rate", 1024L, old.limit());
        eq("scope: a new limit is in Marks", 10L, GrantScope.spendUpTo(10).limit());
        eq("scope: no limit is unlimited", Long.MAX_VALUE, GrantScope.ANY.limit());
        check("scope: within compares the Marks limits", GrantScope.spendUpTo(10).within(old) && !old.within(GrantScope.spendUpTo(10)));
        check("scope: a scope read with no map at all is safe", new GrantScope(null, 5L).limit() == 5);
    }
}
