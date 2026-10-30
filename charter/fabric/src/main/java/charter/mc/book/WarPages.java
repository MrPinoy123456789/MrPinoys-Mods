package charter.mc.book;

import charter.engine.Outcome;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import charter.org.MemberRecord;
import charter.siege.WarBook;
import charter.siege.actions.DeclareWar;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * "War": the wars against the player's nation and by it, and the outposts of other nations it may name (protected
 * keeps, O1). The only goal is to capture an outpost; the page says what that takes, in plain words, before it asks.
 */
public final class WarPages {

    /** At most this many outposts are listed as targets. */
    private static final int TARGETS_MAX = 8;

    private WarPages() {
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    private static OrgId nation(ServerPlayer player) {
        return rt().state.membership(new PlayerId(player.getUUID())).map(MemberRecord::org).map(rt().state::root).orElse(null);
    }

    public static void page(ServerPlayer player, String notice) {
        CharterRuntime rt = rt();
        OrgId nation = nation(player);
        if (nation == null) {
            NewcomerPages.home(player, null);
            return;
        }
        long now = rt.state.siegeClock.now();
        List<String> body = new ArrayList<>();
        body.add(Text.get("book.war.intro"));
        for (WarBook.Goal goal : rt.state.wars.goals) {
            if (goal.defender.equals(nation)) body.add(against(rt, goal, now));
        }
        for (WarBook.Goal goal : rt.state.wars.goals) {
            if (goal.attacker.equals(nation)) body.add(by(rt, goal, now));
        }
        for (WarBook.Truce truce : rt.state.wars.truces) {
            if (now >= truce.untilSiege() || !(truce.a().equals(nation) || truce.b().equals(nation))) continue;
            OrgId other = truce.a().equals(nation) ? truce.b() : truce.a();
            body.add(Text.get("book.war.truce", "org", rt.describe.org(other), "time", Chat.duration(truce.untilSiege() - now)));
        }
        List<Core> mine = rt.state.cores().stream()
                .filter(c -> !c.isKeep() && c.activeCamp() && rt.state.root(c.owner).equals(nation)).toList();
        var land = new charter.land.LandGraph(rt.state, rt.knobs);
        List<ActionButton> buttons = new ArrayList<>();
        if (mine.isEmpty()) {
            body.add(Text.get("book.war.need_outpost"));
        } else {
            int shown = 0;
            for (Core target : rt.state.cores()) {
                if (shown >= TARGETS_MAX) break;
                if (target.isKeep() || !target.activeCamp()) continue;
                OrgId defender = rt.state.root(target.owner);
                if (defender.equals(nation) || rt.state.org(defender).map(o -> o.npc || o.adminKingdom).orElse(true)) continue;
                if (rt.state.wars.against(target.id).isPresent()) continue;
                // A war is fought between two touching outposts, so only a neighbour of one of yours can be named.
                if (mine.stream().noneMatch(m -> land.touching(m, target))) continue;
                CoreId id = target.id;
                String label = Text.get("book.war.declare_button", "org", rt.describe.org(defender), "x", target.center.x() * 16 + 8, "z", target.center.z() * 16 + 8);
                buttons.add(BookDialogs.act(player, label, null, p -> confirm(p, id)));
                shown++;
            }
            if (shown == 0) body.add(Text.get("book.war.no_targets"));
        }
        List<DialogBody> text = new ArrayList<>();
        for (String line : body) text.add(BookDialogs.text(line));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.war.title"), BookDialogs.withNotice(notice, text), buttons,
                BookDialogs.back(player, p -> MemberPages.home(p, null))));
    }

    private static String against(CharterRuntime rt, WarBook.Goal goal, long now) {
        String site = rt.describe.core(goal.outpost);
        String attacker = rt.describe.org(goal.attacker);
        if (!goal.opened) {
            return Text.get("book.war.against_waiting", "org", attacker, "site", site, "time", Chat.duration(goal.opensAt - now));
        }
        return Text.get("book.war.against_open", "org", attacker, "site", site, "minutes", rt.knobs.outpostCaptureMinutes,
                "time", Chat.duration(goal.expiresAt - now));
    }

    private static String by(CharterRuntime rt, WarBook.Goal goal, long now) {
        String site = rt.describe.core(goal.outpost);
        String defender = rt.describe.org(goal.defender);
        if (!goal.opened) {
            return Text.get("book.war.by_waiting", "org", defender, "site", site, "time", Chat.duration(goal.opensAt - now));
        }
        return Text.get("book.war.by_open", "org", defender, "site", site, "minutes", rt.knobs.outpostCaptureMinutes,
                "time", Chat.duration(goal.expiresAt - now));
    }

    /** What declaring means, in plain words, then the question. */
    private static void confirm(ServerPlayer player, CoreId outpost) {
        CharterRuntime rt = rt();
        Core target = rt.state.core(outpost).orElse(null);
        OrgId nation = nation(player);
        if (target == null || nation == null) {
            page(player, null);
            return;
        }
        String org = rt.describe.org(rt.state.root(target.owner));
        var land = new charter.land.LandGraph(rt.state, rt.knobs);
        Core from = rt.state.cores().stream().filter(c -> !c.isKeep() && c.activeCamp() && rt.state.root(c.owner).equals(nation)
                && land.touching(c, target)).findFirst().orElse(null);
        CoreId fromId = from == null ? null : from.id;
        List<DialogBody> body = List.of(
                BookDialogs.text(Text.get("book.war.confirm", "org", org, "site", rt.describe.core(outpost),
                        "from", from == null ? "?" : rt.describe.core(from.id))),
                BookDialogs.text(Text.get("book.war.confirm_skin")),
                BookDialogs.text(Text.get("book.war.confirm_notice", "hours", rt.knobs.warGoalNoticeHours, "minutes", rt.knobs.outpostCaptureMinutes,
                        "total", rt.knobs.warGoalHours)),
                BookDialogs.text(Text.get("book.war.confirm_lapse", "days", rt.knobs.truceDays)));
        ActionButton yes = BookDialogs.act(player, Text.get("book.war.declare_yes"), null, p -> {
            OrgId n = nation(p);
            if (n == null) {
                page(p, null);
                return;
            }
            BookRouter.run(p, new DeclareWar(n, outpost, fromId), line -> page(p, line != null ? line : Text.get("book.war.declared")));
        });
        BookDialogs.show(player, BookDialogs.confirm(Text.get("book.war.title"), body, yes, BookDialogs.back(player, p -> page(p, null))));
    }
}
