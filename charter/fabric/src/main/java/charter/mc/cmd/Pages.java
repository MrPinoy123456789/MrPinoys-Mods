package charter.mc.cmd;

import charter.chronicle.Calendar;
import charter.chronicle.ChronicleRenderer;
import charter.chronicle.DefaultTemplates;
import charter.chronicle.NameLookup;
import charter.gov.Office;
import charter.gov.Vote;
import charter.history.History;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Describe;
import charter.mc.Text;
import charter.org.Invite;
import charter.org.MemberRecord;
import charter.org.Organization;
import charter.org.actions.AcceptInvite;
import charter.org.actions.Leave;
import charter.treasury.Currency;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The chat pages: the faction page, open votes, the vault. Every action on them is a one-shot link. */
final class Pages {

    private static final int RECENT_LINES = 5;

    private Pages() {
    }

    /** The short number a vote is known by: ids come from a counter, so this is small and never repeats. */
    static long number(Vote vote) {
        return vote.id.value().getLeastSignificantBits();
    }

    static String result(Vote vote) {
        return Text.get("vote.state." + vote.state().name().toLowerCase(Locale.ROOT));
    }

    // ---- /charter ----

    static void faction(ServerPlayer player, CharterRuntime rt) {
        PlayerId me = CharterCommands.id(player);
        Optional<MemberRecord> membership = rt.state.membership(me);
        if (membership.isEmpty()) {
            noFaction(player, rt, me);
            return;
        }
        Organization org = rt.state.org(membership.get().org()).orElseThrow();
        Describe d = rt.describe;
        player.sendSystemMessage(Chat.header(org.name()));
        player.sendSystemMessage(Chat.dim(Text.get("page.kind",
                "preset", Text.get("preset." + org.governance.preset.name().toLowerCase(Locale.ROOT)),
                "status", Text.get("status." + org.status().name().toLowerCase(Locale.ROOT)))));
        if (org.parent() != null) player.sendSystemMessage(Chat.dim(Text.get("page.under", "org", d.org(org.parent()))));

        List<String> members = new ArrayList<>();
        for (MemberRecord m : rt.state.members(org.id)) {
            List<String> held = rt.state.offices(org.id).stream().filter(o -> m.player().equals(o.holder())).map(o -> o.name).toList();
            members.add(held.isEmpty() ? d.player(m.player()) : d.player(m.player()) + " (" + String.join(", ", held) + ")");
        }
        player.sendSystemMessage(Chat.line(Text.get("page.members", "count", members.size(), "list", String.join(", ", members))));
        for (Office office : rt.state.offices(org.id)) {
            if (office.vacant()) player.sendSystemMessage(Chat.dim(Text.get("page.office_vacant", "office", office.name)));
        }

        player.sendSystemMessage(Chat.line(Text.get("page.treasury", "balances", balances(org)))
                .append(" ").append(Chat.run(Text.get("link.vault"), "/charter vault")));

        List<Core> cores = rt.state.coresOf(org.id);
        long keeps = cores.stream().filter(Core::isKeep).count();
        player.sendSystemMessage(Chat.line(Text.get("page.land", "keeps", keeps, "camps", cores.size() - keeps,
                "claims", rt.state.claimsOf(org.id).size())));

        long open = rt.state.votes().stream().filter(v -> v.open() && v.org.equals(org.id)).count();
        player.sendSystemMessage(Chat.line(Text.get("page.votes", "count", open)).append(" ").append(Chat.run(Text.get("link.votes"), "/charter vote")));

        List<String> recent = recent(rt, org.id);
        if (!recent.isEmpty()) {
            player.sendSystemMessage(Chat.dim(Text.get("page.recent")));
            for (String line : recent) player.sendSystemMessage(Chat.dim("  " + line));
        }

        player.sendSystemMessage(Component.literal("")
                .append(Chat.suggest(Text.get("link.invite"), "/charter invite ")).append(" ")
                .append(Chat.suggest(Text.get("link.grant"), "/charter grant ")).append(" ")
                .append(Chat.act(player, Text.get("link.leave"), Text.get("link.leave_hover", "org", org.name()),
                        p -> CharterCommands.perform(p, new Leave(me), Text.get("leave.done", "org", org.name())))));
    }

    private static void noFaction(ServerPlayer player, CharterRuntime rt, PlayerId me) {
        player.sendSystemMessage(Chat.header(Text.get("page.title")));
        player.sendSystemMessage(Chat.line(Text.get("page.no_org")));
        for (Invite invite : rt.state.invites()) {
            if (!invite.player().equals(me) || rt.state.org(invite.org()).isEmpty()) continue;
            String name = rt.describe.org(invite.org());
            player.sendSystemMessage(Chat.line(Text.get("page.invited", "org", name)).append(" ")
                    .append(Chat.act(player, Text.get("link.join"), Text.get("link.join_hover", "org", name), p -> {
                        if (CharterCommands.perform(p, new AcceptInvite(me, invite.org()), Text.get("join.done", "org", name)) == 1) {
                            faction(p, CharterServer.runtime());
                        }
                    })));
        }
        player.sendSystemMessage(Component.literal("").append(Chat.suggest(Text.get("link.found"), "/charter found ")));
    }

    private static String balances(Organization org) {
        List<String> parts = new ArrayList<>();
        for (Currency c : Currency.values()) {
            long n = org.ledger.balance(c);
            parts.add(Text.get("vault.amount", "amount", n, "currency", Describe.currency(c, n)));
        }
        return String.join(", ", parts);
    }

    /** The organization's last few Chronicle lines: entries whose event names it. */
    private static List<String> recent(CharterRuntime rt, OrgId org) {
        List<History.Entry> mine = new ArrayList<>();
        List<History.Entry> all = rt.state.history.entries();
        for (int i = all.size() - 1; i >= 0 && mine.size() < RECENT_LINES; i--) {
            if (mentions(all.get(i).event(), org)) mine.addFirst(all.get(i));
        }
        long origin = all.isEmpty() ? 0 : all.getFirst().event().at();
        return new ChronicleRenderer(DefaultTemplates.get(), Calendar.startingAt(origin), NameLookup.from(rt.state), false).render(mine);
    }

    private static boolean mentions(Object event, OrgId org) {
        if (!event.getClass().isRecord()) return false;
        for (RecordComponent c : event.getClass().getRecordComponents()) {
            try {
                if (org.equals(c.getAccessor().invoke(event))) return true;
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }
        return false;
    }

    // ---- /charter vote ----

    static void votes(ServerPlayer player, CharterRuntime rt) {
        PlayerId me = CharterCommands.id(player);
        long now = rt.clock.now();
        List<Vote> mine = rt.state.votes().stream().filter(v -> v.open() && v.electorate.contains(me)).toList();
        player.sendSystemMessage(Chat.header(Text.get("votes.title")));
        if (mine.isEmpty()) {
            player.sendSystemMessage(Chat.dim(Text.get("votes.none")));
            return;
        }
        for (Vote vote : mine) {
            player.sendSystemMessage(Chat.line(Text.get("votes.line", "number", number(vote), "org", rt.describe.org(vote.org),
                    "about", rt.notices.about(vote))));
            player.sendSystemMessage(Chat.dim("  " + Text.get("votes.tally", "yes", vote.count(Vote.Choice.YES),
                    "no", vote.count(Vote.Choice.NO), "abstain", vote.count(Vote.Choice.ABSTAIN),
                    "left", Chat.duration(vote.closesAt - now))));
            Vote.Ballot cast = vote.ballots().get(me);
            if (cast != null) player.sendSystemMessage(Chat.dim("  " + Text.get("votes.yours", "choice", ballotText(rt, cast))));
            MutableComponent links = Component.literal("  ");
            if (vote.kind == Vote.Kind.ELECTION) {
                for (MemberRecord m : rt.state.members(vote.org)) {
                    String name = rt.describe.player(m.player());
                    links.append(Chat.act(player, name, Text.get("votes.for_hover", "name", name),
                            p -> cast(p, vote, Vote.Ballot.candidate(m.player())))).append(" ");
                }
            } else {
                links.append(Chat.act(player, Text.get("link.yes"), Text.get("votes.yes_hover"), p -> cast(p, vote, Vote.Ballot.YES))).append(" ")
                        .append(Chat.act(player, Text.get("link.no"), Text.get("votes.no_hover"), p -> cast(p, vote, Vote.Ballot.NO))).append(" ");
            }
            links.append(Chat.act(player, Text.get("link.abstain"), Text.get("votes.abstain_hover"), p -> cast(p, vote, Vote.Ballot.ABSTAIN)));
            player.sendSystemMessage(links);
        }
    }

    private static String ballotText(CharterRuntime rt, Vote.Ballot ballot) {
        return ballot.choice() == Vote.Choice.CANDIDATE ? rt.describe.player(ballot.candidate())
                : Text.get("ballot." + ballot.choice().name().toLowerCase(Locale.ROOT));
    }

    static void cast(ServerPlayer player, Vote vote, Vote.Ballot ballot) {
        CharterRuntime rt = CharterServer.runtime();
        var outcome = rt.engine.cast(CharterCommands.id(player), vote.id, ballot);
        if (outcome instanceof charter.engine.Outcome.Refused(var reason)) {
            player.sendSystemMessage(Chat.bad(Chat.reason(reason)));
            return;
        }
        player.sendSystemMessage(Chat.good(vote.open()
                ? Text.get("votes.cast", "number", number(vote), "choice", ballotText(rt, ballot))
                : Text.get("votes.cast_and_closed", "number", number(vote), "result", result(vote))));
    }

    // ---- /charter vault ----

    static void vault(ServerPlayer player, CharterRuntime rt) {
        PlayerId me = CharterCommands.id(player);
        player.sendSystemMessage(Chat.header(Text.get("vault.title")));
        Optional<Organization> org = rt.state.membership(me).flatMap(m -> rt.state.org(m.org()));
        if (org.isPresent()) {
            player.sendSystemMessage(Chat.line(Text.get("page.treasury", "balances", balances(org.get()))));
            MutableComponent links = Component.literal("");
            for (Currency c : Currency.values()) {
                String name = c.name().toLowerCase(Locale.ROOT);
                links.append(Chat.suggest(Text.get("link.deposit", "currency", Describe.currency(c, 2)), "/charter vault deposit " + name + " ")).append(" ");
                links.append(Chat.suggest(Text.get("link.withdraw", "currency", Describe.currency(c, 2)), "/charter vault withdraw " + name + " ")).append(" ");
            }
            player.sendSystemMessage(links);
        } else {
            player.sendSystemMessage(Chat.dim(Text.get("page.no_org")));
        }
        Map<String, Integer> owed = rt.mail.owedTo(me);
        if (!owed.isEmpty()) {
            List<String> parts = new ArrayList<>();
            owed.forEach((item, n) -> parts.add(n + " " + item));
            player.sendSystemMessage(Chat.line(Text.get("vault.owed", "items", String.join(", ", parts))).append(" ")
                    .append(Chat.act(player, Text.get("link.collect"), Text.get("link.collect_hover"), CharterServer::give)));
        }
    }
}
