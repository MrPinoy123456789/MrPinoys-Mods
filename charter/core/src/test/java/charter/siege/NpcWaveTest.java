package charter.siege;

import charter.config.Knobs;
import charter.id.ChunkKey;
import charter.npc.NpcBook;
import charter.npc.NpcScheduler;
import charter.time.Durations;

import static charter.Check.check;
import static charter.Check.eq;

/** The raiders' waves, drain, fall and failsafe as bookkeeping (protected keeps, N). */
public final class NpcWaveTest {

    private NpcWaveTest() {
    }

    public static void run() {
        aSiteStartsWithTheSupplyOfItsTier();
        wavesTopUpToThePoolAndCostSupply();
        aSiteFallsWhenTheSupplyIsSpentAndNoRaiderLives();
        aSiteNobodyFoughtExpires();
        retiringAwaitsAReplacement();
    }

    private static NpcBook.Site site(NpcBook book, NpcBook.Kind kind, int tier, long now) {
        NpcBook.Site site = new NpcBook.Site(book.nextId++, kind, null, null, new ChunkKey("minecraft:overworld", 100, 100), tier, now);
        book.sites.add(site);
        return site;
    }

    static void aSiteStartsWithTheSupplyOfItsTier() {
        Knobs k = new Knobs();
        NpcBook book = new NpcBook();
        NpcBook.Live near = NpcScheduler.live(book, site(book, NpcBook.Kind.STRONGHOLD, 0, 0), k);
        NpcBook.Live far = NpcScheduler.live(book, site(book, NpcBook.Kind.STRONGHOLD, 2, 0), k);
        NpcBook.Live camp = NpcScheduler.live(book, site(book, NpcBook.Kind.OUTPOST, 1, 0), k);
        eq("1: near supply", 24, near.supply);
        eq("1: far supply", 96, far.supply);
        eq("1: near pool", 3, near.pool);
        eq("1: far pool", 8, far.pool);
        eq("1: outpost supply by tier", 9, camp.supply);
        eq("1: outpost pool by tier", 2, camp.pool);
        check("1: the state is made once", NpcScheduler.live(book, book.sites.getFirst(), k) == near);
    }

    static void wavesTopUpToThePoolAndCostSupply() {
        Knobs k = new Knobs();
        NpcBook book = new NpcBook();
        NpcBook.Live live = NpcScheduler.live(book, site(book, NpcBook.Kind.STRONGHOLD, 0, 0), k);
        long now = 1_000;
        eq("2: the first wave fills the pool", 3, NpcScheduler.wave(live, 0, k, now));
        eq("2: and costs supply", 21, live.supply);
        eq("2: not due again at once", 0, NpcScheduler.wave(live, 0, k, now + 1));
        long next = now + Durations.minutes(k.npcWaveMinutes);
        eq("2: one killed, one replaced", 1, NpcScheduler.wave(live, 2, k, next));
        eq("2: a kill is not refunded", 20, live.supply);
        eq("2: nothing to add when the pool stands", 0, NpcScheduler.wave(live, 3, k, next + Durations.minutes(k.npcWaveMinutes)));
        live.supply = 2;
        eq("2: a short supply spawns what is left", 2, NpcScheduler.wave(live, 0, k, next + Durations.minutes(2 * k.npcWaveMinutes)));
        eq("2: the supply is spent", 0, live.supply);
    }

    static void aSiteFallsWhenTheSupplyIsSpentAndNoRaiderLives() {
        Knobs k = new Knobs();
        NpcBook book = new NpcBook();
        NpcBook.Live live = NpcScheduler.live(book, site(book, NpcBook.Kind.STRONGHOLD, 0, 0), k);
        live.supply = 0;
        check("3: raiders left, it stands", !NpcScheduler.step(live, 2, 0, k, 10).fell());
        check("3: none left and nothing to spawn, it falls", NpcScheduler.step(live, 0, 2, k, 20).fell());
        live.supply = 1;
        check("3: supply left, it does not fall", !NpcScheduler.step(live, 0, 0, k, 30).fell());
    }

    static void aSiteNobodyFoughtExpires() {
        Knobs k = new Knobs();
        NpcBook book = new NpcBook();
        NpcBook.Live live = NpcScheduler.live(book, site(book, NpcBook.Kind.STRONGHOLD, 0, 0), k);
        long tenDays = Durations.days(k.npcExpireDays);
        check("4: not expired a minute early", !NpcScheduler.step(live, 3, 0, k, tenDays - 1).expired());
        check("4: expired at ten days", NpcScheduler.step(live, 3, 0, k, tenDays).expired());
        check("4: a death resets the clock", !NpcScheduler.step(live, 2, 1, k, tenDays + 5).expired());
        check("4: and it runs from then", NpcScheduler.step(live, 2, 0, k, tenDays + 5 + tenDays).expired());
    }

    static void retiringAwaitsAReplacement() {
        Knobs k = new Knobs();
        NpcBook book = new NpcBook();
        NpcBook.Site site = site(book, NpcBook.Kind.OUTPOST, 0, 0);
        NpcScheduler.live(book, site, k);
        NpcScheduler.retire(book, site, k, 100);
        check("5: the site and its state are gone", book.sites.isEmpty() && book.live.isEmpty());
        eq("5: a replacement waits", 1, book.waiting(NpcBook.Kind.OUTPOST));
    }
}
