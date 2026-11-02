package charter.org;

import charter.Fixture;
import charter.engine.Outcome;
import charter.gov.Governance;
import charter.gov.Office;
import charter.gov.Preset;
import charter.gov.Standing;
import charter.grant.Grant;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.grant.actions.GrantPower;
import charter.grant.actions.RevokeGrant;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.actions.AddRank;
import charter.org.actions.GiveRank;
import charter.org.actions.Leave;
import charter.org.actions.MoveRank;
import charter.org.actions.RemoveRank;
import charter.org.actions.RenameRank;
import charter.org.actions.SetRankPower;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/**
 * Ranks (titles): the default ladder with nothing stored, name cleaning, ordering and its limits, who may change
 * them, giving a rank, the powers a rank carries (live, never passable, never forbidden ones), and the Lord of an
 * older kingdom being given the power to manage ranks on load.
 */
public final class RankTest {

    static final PlayerId HEATHER = player("Heather");
    static final PlayerId BUCK = player("Buck");
    static final PlayerId KIERA = player("Kiera");
    static final PlayerId LEON = player("Leon");

    private RankTest() {
    }

    public static void run() {
        defaultsNeedNothingStored();
        namesAreCleaned();
        orderingAndLimits();
        whoMayChangeRanks();
        givingARank();
        rankPowersAreLiveGrants();
        forbiddenPowersNeverReachARank();
        onlyTheLordTouchesPoweredRanks();
        olderKingdomsGetTheNewPower();
        savedLaddersAreMended();
        aBedrockPlayerHoldsARank();
    }

    private static OrgId faeloria(Fixture f) {
        f.seen(HEATHER, BUCK, KIERA, LEON);
        return f.found("Faeloria", Preset.LORDSHIP, HEATHER, BUCK, KIERA);
    }

    private static String id(Fixture f, OrgId k, String name) {
        return f.state.ranks.find(k, name).orElseThrow().id();
    }

    static void defaultsNeedNothingStored() {
        Fixture f = new Fixture();
        OrgId k = faeloria(f);
        Ranks ranks = f.state.ranks;
        check("1: a kingdom's ladder is not stored until edited", !ranks.edited(k));
        eq("1: the default ladder", List.of(Ranks.TOP_NAME, Ranks.DEFAULT_NAME), ranks.ladder(k).stream().map(Ranks.Rank::name).toList());
        eq("1: the Lord holds the top rank", Optional.of(Ranks.TOP), ranks.rankOf(f.state, HEATHER).map(Ranks.Rank::id));
        eq("1: a member holds the default rank", Optional.of(Ranks.DEFAULT), ranks.rankOf(f.state, BUCK).map(Ranks.Rank::id));
        check("1: someone with no kingdom holds no rank", ranks.rankOf(f.state, LEON).isEmpty());
        eq("1: the default rank's holders", List.of(BUCK, KIERA), ranks.holders(f.state, k, Ranks.DEFAULT));
        check("1: no rank carries a power", Ranks.powers(f.state, f.clock.now(), k, Ranks.DEFAULT).isEmpty());
        check("1: still nothing stored after reading", !ranks.edited(k) && ranks.held().isEmpty());
    }

    static void namesAreCleaned() {
        eq("2: ends are cut", Optional.of("King"), Ranks.clean("  King  "));
        eq("2: runs of spaces become one", Optional.of("Royal Commander"), Ranks.clean("Royal    Commander"));
        eq("2: lower case is kept as typed", Optional.of("goated commoner"), Ranks.clean("goated commoner"));
        eq("2: letters beyond English are fine", Optional.of("Königin"), Ranks.clean("Königin"));
        eq("2: the longest name", Optional.of("a".repeat(Ranks.MAX_NAME)), Ranks.clean("a".repeat(Ranks.MAX_NAME)));
        check("2: one too long", Ranks.clean("a".repeat(Ranks.MAX_NAME + 1)).isEmpty());
        check("2: blank", Ranks.clean("   ").isEmpty());
        check("2: null", Ranks.clean(null).isEmpty());
        check("2: a colour code", Ranks.clean("§cKing").isEmpty());
        check("2: brackets that would fake a tag", Ranks.clean("[Mercenary]").isEmpty());
        check("2: braces of a placeholder", Ranks.clean("{player}").isEmpty());
        check("2: the form separator", Ranks.clean("King | Queen").isEmpty());
        check("2: a tab", Ranks.clean("King\tQueen").isEmpty());
        check("2: a newline", Ranks.clean("King\nQueen").isEmpty());
        check("2: a right to left override", Ranks.clean("‮gniK").isEmpty());
        check("2: a zero width space", Ranks.clean("Ki​ng").isEmpty());
        check("2: a non breaking space", Ranks.clean("Queen Bee").isEmpty());
        check("2: a private use character", Ranks.clean("King").isEmpty());
        check("2: a lone surrogate", Ranks.clean("King\uD800").isEmpty());
    }

    static void orderingAndLimits() {
        Fixture f = new Fixture();
        OrgId k = faeloria(f);
        check("3: add Princess", f.as(HEATHER, new AddRank(k, "Princess")).done());
        check("3: the ladder is stored now", f.state.ranks.edited(k));
        check("3: add Royal Commander", f.as(HEATHER, new AddRank(k, "Royal Commander")).done());
        check("3: add goated commoner", f.as(HEATHER, new AddRank(k, "goated commoner")).done());
        eq("3: new ranks go just above the default", List.of("Lord", "Princess", "Royal Commander", "goated commoner", "Commoner"),
                names(f, k));
        String commander = id(f, k, "Royal Commander");
        check("3: move up", f.as(HEATHER, new MoveRank(k, commander, true)).done());
        eq("3: moved", List.of("Lord", "Royal Commander", "Princess", "goated commoner", "Commoner"), names(f, k));
        eq("3: nothing goes above the top", "rank_cannot_move", f.as(HEATHER, new MoveRank(k, commander, true)).reasonKey());
        String goated = id(f, k, "goated commoner");
        eq("3: nothing goes below the default", "rank_cannot_move", f.as(HEATHER, new MoveRank(k, goated, false)).reasonKey());
        eq("3: the top rank does not move", "rank_top_fixed", f.as(HEATHER, new MoveRank(k, Ranks.TOP, false)).reasonKey());
        eq("3: the default rank does not move", "rank_default_fixed", f.as(HEATHER, new MoveRank(k, Ranks.DEFAULT, true)).reasonKey());
        eq("3: a name is taken in any case", "rank_name_taken", f.as(HEATHER, new AddRank(k, "PRINCESS")).reasonKey());
        eq("3: a bad name", "rank_bad_name", f.as(HEATHER, new AddRank(k, "§4Evil")).reasonKey());
        check("3: the top rank is renamed Queen", f.as(HEATHER, new RenameRank(k, Ranks.TOP, "Queen")).done());
        check("3: the default rank is renamed too", f.as(HEATHER, new RenameRank(k, Ranks.DEFAULT, "commoner")).done());
        eq("3: the same name again changes nothing", "rank_unchanged", f.as(HEATHER, new RenameRank(k, Ranks.DEFAULT, "commoner")).reasonKey());
        eq("3: the top rank stays", "rank_top_fixed", f.as(HEATHER, new RemoveRank(k, Ranks.TOP)).reasonKey());
        eq("3: the default rank stays", "rank_default_fixed", f.as(HEATHER, new RemoveRank(k, Ranks.DEFAULT)).reasonKey());
        for (int i = 0; f.state.ranks.ladder(k).size() < Ranks.MAX_RANKS; i++) {
            check("3: fill " + i, f.as(HEATHER, new AddRank(k, "Knight " + i)).done());
        }
        eq("3: at most " + Ranks.MAX_RANKS + " ranks", "rank_too_many", f.as(HEATHER, new AddRank(k, "One More")).reasonKey());
        check("3: remove one", f.as(HEATHER, new RemoveRank(k, goated)).done());
        check("3: then there is room", f.as(HEATHER, new AddRank(k, "One More")).done());
        check("3: a removed id is never reused", !id(f, k, "One More").equals(goated));
        check("3: a rank event for each change", f.state.history.entries().stream().filter(e -> e.event() instanceof RankEvents.RankAdded).count() >= 3);
    }

    private static List<String> names(Fixture f, OrgId k) {
        return f.state.ranks.ladder(k).stream().map(Ranks.Rank::name).toList();
    }

    static void whoMayChangeRanks() {
        Fixture f = new Fixture();
        OrgId k = faeloria(f);
        eq("4: a plain member is refused", "needs_vote", f.as(BUCK, new RenameRank(k, Ranks.TOP, "King")).reasonKey());
        check("4: and nothing changed", !f.state.ranks.edited(k));
        eq("4: an outsider is refused", "no_authority", f.as(LEON, new AddRank(k, "Duke")).reasonKey());
        check("4: an operator may", f.admin(new AddRank(k, "Duke")).done());
        check("4: the Lord may", f.as(HEATHER, new RenameRank(k, Ranks.TOP, "Queen")).done());
        check("4: someone given the power may", f.as(HEATHER, new GrantPower(k, Power.MANAGE_RANKS, new Holder.OfPlayer(BUCK),
                GrantScope.ANY, false, 0)).done());
        check("4: so Buck renames a rank", f.as(BUCK, new RenameRank(k, id(f, k, "Duke"), "Duchess")).done());
        OrgId gone = f.state.ids.org();
        eq("4: a faction that does not exist has no ranks", "no_such_org", f.admin(new AddRank(gone, "Squire")).reasonKey());
    }

    static void givingARank() {
        Fixture f = new Fixture();
        OrgId k = faeloria(f);
        f.as(HEATHER, new AddRank(k, "Princess"));
        String princess = id(f, k, "Princess");
        check("5: the Lord names Buck Princess", f.as(HEATHER, new GiveRank(k, BUCK, princess)).done());
        eq("5: Buck holds it", Optional.of(princess), f.state.ranks.rankOf(f.state, BUCK).map(Ranks.Rank::id));
        eq("5: Kiera does not", Optional.of(Ranks.DEFAULT), f.state.ranks.rankOf(f.state, KIERA).map(Ranks.Rank::id));
        eq("5: twice changes nothing", "rank_unchanged", f.as(HEATHER, new GiveRank(k, BUCK, princess)).reasonKey());
        eq("5: the top rank is never given", "rank_top_fixed", f.as(HEATHER, new GiveRank(k, BUCK, Ranks.TOP)).reasonKey());
        eq("5: the Lord's rank never changes", "rank_ruler", f.as(HEATHER, new GiveRank(k, HEATHER, princess)).reasonKey());
        eq("5: an outsider has no rank here", "rank_not_in_kingdom", f.as(HEATHER, new GiveRank(k, LEON, princess)).reasonKey());
        eq("5: an unknown rank", "no_rank", f.as(HEATHER, new GiveRank(k, KIERA, "r99")).reasonKey());
        check("5: the Chronicle tells it", f.state.history.entries().stream().anyMatch(e -> e.event() instanceof RankEvents.RankGiven g
                && g.player().equals(BUCK) && g.name().equals("Princess") && g.from().equals("Commoner")));
        check("5: back to the default rank", f.as(HEATHER, new GiveRank(k, BUCK, Ranks.DEFAULT)).done());
        check("5: nothing stored for the default rank", f.state.ranks.given(BUCK).isEmpty());
        f.as(HEATHER, new GiveRank(k, KIERA, princess));
        check("5: removing a rank sends its holders back", f.as(HEATHER, new RemoveRank(k, princess)).done());
        eq("5: Kiera holds the default rank", Optional.of(Ranks.DEFAULT), f.state.ranks.rankOf(f.state, KIERA).map(Ranks.Rank::id));
        check("5: the removal names who held it", f.state.history.entries().stream().anyMatch(e -> e.event() instanceof RankEvents.RankRemoved r
                && r.holders().equals(List.of(KIERA))));
        f.as(HEATHER, new AddRank(k, "Knight"));
        f.as(HEATHER, new GiveRank(k, KIERA, id(f, k, "Knight")));
        check("5: Kiera leaves", f.as(KIERA, new Leave(KIERA)).done());
        check("5: and her rank is forgotten", f.state.ranks.given(KIERA).isEmpty());
        // The Lord's seat carries the top rank: whoever sits there holds it, and nobody else does.
        Office lord = charter.gov.Deciders.of(f.state, k).orElseThrow();
        lord.unseatAll();
        lord.seat(BUCK, f.clock.now());
        eq("5: the new Lord holds the top rank at once", Optional.of(Ranks.TOP), f.state.ranks.rankOf(f.state, BUCK).map(Ranks.Rank::id));
        eq("5: the old one holds the default rank", Optional.of(Ranks.DEFAULT), f.state.ranks.rankOf(f.state, HEATHER).map(Ranks.Rank::id));
    }

    private static boolean holds(Fixture f, PlayerId who, OrgId k, Power power) {
        return f.engine.state().org(k).isPresent()
                && new charter.grant.AuthorityResolver(f.state, f.clock.now()).resolve(new Who.AsPlayer(who), k, power, ScopeNeed.NONE, false).ok();
    }

    static void rankPowersAreLiveGrants() {
        Fixture f = new Fixture();
        OrgId k = faeloria(f);
        f.as(HEATHER, new AddRank(k, "Royal Commander"));
        String commander = id(f, k, "Royal Commander");
        f.as(HEATHER, new GiveRank(k, BUCK, commander));
        check("6: a new rank carries nothing", !holds(f, BUCK, k, Power.DECLARE_WAR));
        check("6: the Lord gives it a power", f.as(HEATHER, new SetRankPower(k, commander, Power.DECLARE_WAR, true)).done());
        check("6: Buck may declare war now", holds(f, BUCK, k, Power.DECLARE_WAR));
        check("6: Kiera may not", !holds(f, KIERA, k, Power.DECLARE_WAR));
        eq("6: the rank's powers", java.util.Set.of(Power.DECLARE_WAR), Ranks.powers(f.state, f.clock.now(), k, commander));
        Standing.View view = Standing.of(f.state, f.engine, BUCK, f.clock.now()).orElseThrow();
        check("6: Where I stand says it comes with the rank", view.rights().stream().anyMatch(l -> l.kind() == Standing.HolderKind.YOU
                && l.name().equals("Royal Commander")));
        eq("6: a rank's power is never passable", "needs_vote", f.as(BUCK, new GrantPower(k, Power.DECLARE_WAR,
                new Holder.OfPlayer(KIERA), GrantScope.ANY, false, 0)).reasonKey());
        eq("6: nor given straight to a rank", "grant_to_rank", f.as(HEATHER, new GrantPower(k, Power.SPEND,
                new Holder.OfRank(k, commander), GrantScope.ANY, false, 0)).reasonKey());
        f.as(HEATHER, new GiveRank(k, BUCK, Ranks.DEFAULT));
        check("6: off the rank, off the power", !holds(f, BUCK, k, Power.DECLARE_WAR));
        f.as(HEATHER, new GiveRank(k, BUCK, commander));
        check("6: back on", holds(f, BUCK, k, Power.DECLARE_WAR));
        // The Lord's own grant goes: the rank's grant hangs from it and goes too, at once.
        Grant lords = f.state.grants().stream().filter(g -> g.subject.equals(k) && g.power == Power.DECLARE_WAR && g.isRoot()
                && g.recipient instanceof Holder.OfOffice).findFirst().orElseThrow();
        check("6: an operator takes the Lord's war power", f.admin(new RevokeGrant(k, Power.DECLARE_WAR, lords.id)).done());
        check("6: the rank loses it with no other step", !holds(f, BUCK, k, Power.DECLARE_WAR));
        check("6: and carries nothing now", Ranks.powers(f.state, f.clock.now(), k, commander).isEmpty());
        check("6: the Lord takes a power away", f.as(HEATHER, new SetRankPower(k, commander, Power.DECLARE_WAR, false)).done());
        check("6: then nothing is left to take", "rank_unchanged".equals(f.as(HEATHER, new SetRankPower(k, commander, Power.DECLARE_WAR, false)).reasonKey()));
        check("6: the Chronicle has both", f.state.history.entries().stream().anyMatch(e -> e.event() instanceof RankEvents.RankPowerGiven)
                && f.state.history.entries().stream().anyMatch(e -> e.event() instanceof RankEvents.RankPowerTaken));
        check("6: removing a powered rank takes its grants back", f.as(HEATHER, new SetRankPower(k, commander, Power.CLAIM, true)).done()
                && f.as(HEATHER, new RemoveRank(k, commander)).done() && Ranks.rankGrants(f.state, k, commander).isEmpty());
    }

    static void forbiddenPowersNeverReachARank() {
        Fixture f = new Fixture();
        OrgId k = faeloria(f);
        f.as(HEATHER, new AddRank(k, "Regent"));
        String regent = id(f, k, "Regent");
        f.as(HEATHER, new GiveRank(k, BUCK, regent));
        for (Power power : List.of(Power.APPOINT, Power.CREATE_SUBORG, Power.MANAGE_RANKS)) {
            eq("7: " + power + " is refused", "rank_forbidden_power", f.as(HEATHER, new SetRankPower(k, regent, power, true)).reasonKey());
            eq("7: " + power + " is refused to an operator too", "rank_forbidden_power", f.admin(new SetRankPower(k, regent, power, true)).reasonKey());
        }
        eq("7: the top rank carries nothing extra", "rank_top_fixed", f.as(HEATHER, new SetRankPower(k, Ranks.TOP, Power.SPEND, true)).reasonKey());
        // Even a grant that reached the state some other way does not work.
        Grant forged = new Grant(f.state.ids.grant(), k, Power.APPOINT, new Holder.OfRank(k, regent), new Holder.OfOrg(k), null,
                GrantScope.ANY, false, f.clock.now(), 0);
        f.state.addGrant(forged);
        check("7: a forbidden power on a rank never holds", !new charter.grant.AuthorityResolver(f.state, f.clock.now()).valid(forged));
        check("7: Buck cannot appoint", !holds(f, BUCK, k, Power.APPOINT));
        check("7: Buck cannot dissolve", !holds(f, BUCK, k, Power.DISSOLVE));
    }

    static void onlyTheLordTouchesPoweredRanks() {
        Fixture f = new Fixture();
        OrgId k = faeloria(f);
        f.as(HEATHER, new AddRank(k, "Treasurer"));
        f.as(HEATHER, new AddRank(k, "Bard"));
        String treasurer = id(f, k, "Treasurer");
        String bard = id(f, k, "Bard");
        f.as(HEATHER, new SetRankPower(k, treasurer, Power.SPEND, true));
        // Buck may manage ranks and pass on claiming land, but he is not the Lord.
        f.as(HEATHER, new GrantPower(k, Power.MANAGE_RANKS, new Holder.OfPlayer(BUCK), GrantScope.ANY, false, 0));
        check("8: Buck may pass on claiming land", f.as(HEATHER, new GrantPower(k, Power.CLAIM, new Holder.OfPlayer(BUCK), GrantScope.ANY, true, 0)).done());
        check("8: Buck gives a plain rank", f.as(BUCK, new GiveRank(k, KIERA, bard)).done());
        eq("8: not a powered one", "rank_powered", f.as(BUCK, new GiveRank(k, KIERA, treasurer)).reasonKey());
        eq("8: nor removes one", "rank_powered", f.as(BUCK, new RemoveRank(k, treasurer)).reasonKey());
        eq("8: nor sets what a rank may do", "rank_not_lord", f.as(BUCK, new SetRankPower(k, bard, Power.CLAIM, true)).reasonKey());
        check("8: the Lord may", f.as(HEATHER, new GiveRank(k, KIERA, treasurer)).done());
        eq("8: and Buck cannot take it back", "rank_powered", f.as(BUCK, new GiveRank(k, KIERA, bard)).reasonKey());
    }

    /** A kingdom saved before ranks: its Lord's office has every routine power but the new one. */
    private static OrgId oldKingdom(Fixture f) {
        f.seen(HEATHER, BUCK);
        long now = f.clock.now();
        OrgId id = f.state.ids.org();
        Organization org = new Organization(id, "Oldvale", null, new Governance(Preset.LORDSHIP), OrgStatus.ACTIVE, now);
        f.state.addOrg(org);
        f.state.join(HEATHER, id, now);
        f.state.join(BUCK, id, now);
        Office office = new Office(f.state.ids.office(), id, Preset.LORD, true, true, 1);
        f.state.addOffice(office);
        org.governance.deciders = office.id;
        office.seat(HEATHER, now);
        for (Power p : Preset.LORD_POWERS) {
            if (Preset.LATER_LORD_POWERS.contains(p)) continue;
            f.state.addGrant(new Grant(f.state.ids.grant(), id, p, new Holder.OfOffice(office.id), new Holder.OfOrg(id), null,
                    GrantScope.ANY, true, now, 0));
        }
        return id;
    }

    static void olderKingdomsGetTheNewPower() {
        Fixture f = new Fixture();
        OrgId k = oldKingdom(f);
        eq("9: before: the Lord of an old kingdom cannot", "needs_vote", f.as(HEATHER, new RenameRank(k, Ranks.TOP, "Queen")).reasonKey());
        List<Grant> added = Preset.addLaterLordGrants(f.state, f.clock.now());
        eq("9: one grant added", 1, added.size());
        check("9: to the Lord's office, passable", added.get(0).power == Power.MANAGE_RANKS && added.get(0).redelegable
                && added.get(0).recipient instanceof Holder.OfOffice && added.get(0).isRoot());
        eq("9: run again, nothing more", 0, Preset.addLaterLordGrants(f.state, f.clock.now()).size());
        check("9: after: the Lord renames the top rank", f.as(HEATHER, new RenameRank(k, Ranks.TOP, "Queen")).done());
        eq("9: the defaults came through untouched", Optional.of(Ranks.DEFAULT), f.state.ranks.rankOf(f.state, BUCK).map(Ranks.Rank::id));
        f.admin(new RevokeGrant(k, Power.MANAGE_RANKS, added.get(0).id));
        eq("9: a revoked grant is not given again", 0, Preset.addLaterLordGrants(f.state, f.clock.now()).size());
        Fixture fresh = new Fixture();
        faeloria(fresh);
        eq("9: a kingdom founded today needs nothing", 0, Preset.addLaterLordGrants(fresh.state, fresh.clock.now()).size());
    }

    static void savedLaddersAreMended() {
        Ranks ranks = new Ranks();
        OrgId k = new OrgId(new UUID(7, 7));
        List<String> mended = ranks.restore(k, List.of(new Ranks.Rank("r3", "Duke"), new Ranks.Rank("r4", "duke"),
                new Ranks.Rank("r5", "§bad"), new Ranks.Rank(Ranks.DEFAULT, "Peasant")), 2);
        eq("10: the top comes back, the bad and doubled go", List.of("Lord", "Duke", "Peasant"),
                ranks.ladder(k).stream().map(Ranks.Rank::name).toList());
        check("10: and that is reported", mended.size() >= 3);
        eq("10: ids are never reused", 4, ranks.ladders().get(k).nextId);
        List<String> clean = ranks.restore(k, List.of(new Ranks.Rank(Ranks.TOP, "Queen"), new Ranks.Rank(Ranks.DEFAULT, "Commoner")), 1);
        check("10: a sound ladder is taken as it is", clean.isEmpty());
    }

    static void aBedrockPlayerHoldsARank() {
        Fixture f = new Fixture();
        OrgId k = faeloria(f);
        // Floodgate gives a Bedrock player a UUID whose high half is zero.
        PlayerId xbox = new PlayerId(new UUID(0, 0x0009_0000_1234_5678L));
        f.seen(xbox);
        f.as(HEATHER, new charter.org.actions.InviteMember(k, xbox));
        check("11: the Bedrock player joins", f.as(xbox, new charter.org.actions.AcceptInvite(xbox, k)).done());
        f.as(HEATHER, new AddRank(k, "Princess"));
        check("11: and is named Princess", f.as(HEATHER, new GiveRank(k, xbox, id(f, k, "Princess"))).done());
        eq("11: holds it", Optional.of("Princess"), f.state.ranks.rankOf(f.state, xbox).map(Ranks.Rank::name));
        Outcome o = f.as(xbox, new RenameRank(k, Ranks.TOP, "King"));
        eq("11: and is refused like any member", "needs_vote", o.reasonKey());
    }
}
