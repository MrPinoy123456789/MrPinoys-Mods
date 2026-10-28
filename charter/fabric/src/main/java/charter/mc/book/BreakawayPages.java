package charter.mc.book;

import charter.engine.Outcome;
import charter.engine.Reason;
import charter.gov.Deciders;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.id.VoteId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import charter.org.Breakaway;
import charter.org.MemberRecord;
import charter.org.Organization;
import charter.org.actions.BreakAway;
import charter.org.actions.Secede;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * "Break away": the page behind the button on a member's Freedoms page. Members of a Lordship or a
 * council cannot vote their ruler out; this is the way out. A sub-faction leaves as a whole by a
 * vote of its members; a group leaves a faction that has no sub-factions, with no vote. It says who
 * comes along, what it costs, how long there is and the cooldown, then asks to be sure.
 */
public final class BreakawayPages {

    private BreakawayPages() {
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    private static PlayerId me(ServerPlayer player) {
        return new PlayerId(player.getUUID());
    }

    /** The button on the Freedoms page: the joke label lives in one lang key so it can be swapped. */
    static ActionButton button(ServerPlayer player) {
        return BookDialogs.act(player, Text.get("book.breakaway.button"), Text.get("book.breakaway.line"), p -> page(p, null));
    }

    public static void page(ServerPlayer player, String notice) {
        CharterRuntime rt = rt();
        PlayerId me = me(player);
        OrgId org = rt.state.membership(me).map(MemberRecord::org).orElse(null);
        if (org == null) {
            NewcomerPages.home(player, null);
            return;
        }
        Organization o = rt.state.org(org).orElseThrow();
        long now = rt.clock.now();
        List<String> body = new ArrayList<>();
        List<ActionButton> buttons = new ArrayList<>();
        boolean sub = o.parent() != null;
        String hours = String.valueOf(rt.knobs.breakawayHours);
        if (sub) {
            String parent = rt.describe.org(o.parent());
            List<String> names = new ArrayList<>();
            for (MemberRecord m : rt.state.members(org)) names.add(rt.describe.player(m.player()));
            body.add(Text.get("book.breakaway.sub_intro", "org", o.name(), "parent", parent));
            body.add(Text.get("book.breakaway.sub_vote"));
            body.add(Text.get("book.breakaway.comes", "list", String.join(", ", names)));
            boolean keep = rt.state.coresOf(org).stream().anyMatch(c -> c.isKeep());
            body.add(keep ? Text.get("book.breakaway.cost_none")
                    : Text.get("book.breakaway.cost_nexus", "price", rt.knobs.keepPriceDiamonds, "hours", hours));
            body.add(Text.get("book.breakaway.stay", "parent", parent));
            Optional<Breakaway> pending = rt.state.pendingSecession(org);
            if (pending.isPresent()) {
                body.add(Text.get("book.breakaway.pending", "time", Chat.duration(pending.get().deadline() - now)));
            } else if (rt.engine.eligibleVoters(org, now).contains(me)) {
                buttons.add(BookDialogs.act(player, Text.get("book.breakaway.put_to_vote"), null, p -> confirm(p, org, true)));
            } else {
                body.add(Text.get("book.breakaway.cannot_vote_yet"));
            }
        } else if (!rt.state.children(org).isEmpty()) {
            body.add(Text.get("book.breakaway.has_subs"));
        } else if (Deciders.of(rt.state, org).map(d -> d.holds(me)).orElse(false)) {
            body.add(Text.get("book.breakaway.ruler"));
        } else {
            Optional<Breakaway> group = rt.state.groupLeaving(org);
            body.add(Text.get("book.breakaway.flat_intro", "org", o.name()));
            if (group.isPresent()) {
                List<String> names = new ArrayList<>();
                for (PlayerId p : group.get().members()) names.add(rt.describe.player(p));
                body.add(Text.get("book.breakaway.comes", "list", String.join(", ", names)));
                body.add(Text.get("book.breakaway.time_left", "time", Chat.duration(group.get().deadline() - now)));
            } else {
                body.add(Text.get("book.breakaway.flat_start", "hours", hours));
            }
            body.add(Text.get("book.breakaway.cost_diamonds", "price", rt.knobs.keepPriceDiamonds));
            body.add(Text.get("book.breakaway.starter_places"));
            boolean in = group.map(g -> g.members().contains(me)).orElse(false);
            if (!in) {
                buttons.add(BookDialogs.act(player, Text.get(group.isPresent() ? "book.breakaway.join" : "book.breakaway.start"),
                        null, p -> confirm(p, org, false)));
            } else {
                body.add(Text.get("book.breakaway.you_are_in"));
            }
        }
        long lastFounded = rt.state.knownPlayer(me).map(r -> r.lastFoundedAt).orElse(0L);
        long left = lastFounded == 0 ? 0 : lastFounded + charter.time.Durations.days(rt.knobs.foundSecedeCooldownDays) - now;
        body.add(Text.get("book.breakaway.cooldown", "days", rt.knobs.foundSecedeCooldownDays));
        if (left > 0) body.add(Text.get("book.found.cooldown", "time", Chat.duration(left)));
        List<DialogBody> text = new ArrayList<>();
        for (String line : body) text.add(BookDialogs.text(line));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.breakaway.title"), BookDialogs.withNotice(notice, text), buttons,
                BookDialogs.back(player, p -> MemberPages.freedoms(p, null))));
    }

    private static void confirm(ServerPlayer player, OrgId org, boolean vote) {
        ActionButton yes = BookDialogs.act(player, Text.get("book.button.yes"), null, p -> {
            if (vote) {
                Outcome outcome = rt().engine.propose(me(p), new Secede(org));
                if (outcome instanceof Outcome.Refused(Reason reason)) page(p, BookRouter.refusal(reason));
                else if (outcome instanceof Outcome.VoteOpened(VoteId id)) MemberPages.vote(p, id, Text.get("book.breakaway.proposed"));
                else page(p, null);
            } else {
                BookRouter.run(p, new BreakAway(me(p)), line -> page(p, line != null ? line : Text.get("book.breakaway.joined")));
            }
        });
        ActionButton no = BookDialogs.back(player, p -> page(p, null));
        BookDialogs.show(player, BookDialogs.confirm(Text.get("book.breakaway.title"),
                List.of(BookDialogs.text(Text.get(vote ? "book.breakaway.confirm_vote" : "book.breakaway.confirm_group"))), yes, no));
    }
}
