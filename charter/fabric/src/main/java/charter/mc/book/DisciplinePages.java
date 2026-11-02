package charter.mc.book;

import charter.grant.Power;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import charter.mc.justice.JusticePages;
import charter.mc.moderation.LordActions;
import charter.mc.moderation.LordModeration;
import charter.moderation.LordRules;
import charter.org.MemberRecord;
import charter.org.PlayerRecord;
import charter.time.Durations;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * "Discipline": a Lord's moderation of their citizens as buttons, for players who cannot type {@code /charter lord}. Pick a citizen, pick
 * what to do (mute, bar from the land, kick, or lift a hold), pick a time from presets within the cap, pick a reason
 * (or write one). Every press runs {@link LordActions#run}, the same path as the typed commands, so the operator
 * exemption, the online check, the rules in core and the word to the operators are never skipped. Only a Lord sees it
 * ({@link LordRules#isLord}); a Lord who also judges finds Prisoners and justice here too.
 */
public final class DisciplinePages {

    /** The lengths offered, before the cap cuts them: minutes for a mute, hours for a suspension. */
    static final long[] MUTE_MINUTES = {5, 10, 30, 60};
    static final long[] SUSPEND_HOURS = {1, 3, 6, 12};
    /** The reasons offered (book.order.reason.N); the last button writes one instead. */
    static final int REASONS = 5;
    private static final int HELD_LINES = 4;

    private DisciplinePages() {
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    private static PlayerId me(ServerPlayer player) {
        return new PlayerId(player.getUUID());
    }

    /** The Lord's kingdom, or empty after sending a player who no longer rules one back to the desk. */
    private static Optional<OrgId> kingdom(ServerPlayer player) {
        if (!CharterServer.running()) return Optional.empty();
        Optional<OrgId> kingdom = LordRules.kingdomOf(rt().state, me(player));
        if (kingdom.isEmpty()) LeaderPages.desk(player, charter.mc.ActAs.refusal(LordRules.NOT_A_LORD));
        return kingdom;
    }

    private static BookButton go(ServerPlayer player, String label, java.util.function.Consumer<ServerPlayer> page) {
        return BookDialogs.act(player, label, null, page);
    }

    /** What holds a citizen now, from their record: muted until, barred from this kingdom's land until. */
    private record Hold(long mutedLeft, long suspendedLeft) {
        boolean any() {
            return mutedLeft > 0 || suspendedLeft > 0;
        }
    }

    private static Hold hold(OrgId kingdom, PlayerId id) {
        long now = rt().clock.now();
        PlayerRecord r = rt().state.knownPlayer(id).orElse(null);
        if (r == null) return new Hold(0, 0);
        long muted = Math.max(0, r.mutedUntil - now);
        long suspended = r.suspendedUntil > now && kingdom.equals(r.suspendedFrom) ? r.suspendedUntil - now : 0;
        return new Hold(muted, suspended);
    }

    // ---- the page: who is held now, and the citizens ----

    public static void page(ServerPlayer player, String notice) {
        Optional<OrgId> found = kingdom(player);
        if (found.isEmpty()) return;
        OrgId kingdom = found.get();
        CharterRuntime rt = rt();
        List<String> held = new ArrayList<>();
        List<BookButton> heldButtons = new ArrayList<>();
        List<BookButton> others = new ArrayList<>();
        List<MemberRecord> members = new ArrayList<>(rt.state.members(kingdom));
        members.sort(Comparator.comparing(m -> rt.describe.player(m.player()).toLowerCase(java.util.Locale.ROOT)));
        for (MemberRecord m : members) {
            PlayerId id = m.player();
            if (id.equals(me(player)) || LordRules.isLord(rt.state, id)) continue;
            String name = rt.describe.player(id);
            Hold h = hold(kingdom, id);
            UUID uuid = id.value();
            if (h.any()) {
                if (h.mutedLeft > 0) held.add(Text.get("lord.list.muted", "name", name, "time", Chat.duration(h.mutedLeft)));
                if (h.suspendedLeft > 0) held.add(Text.get("lord.list.suspended", "name", name, "time", Chat.duration(h.suspendedLeft)));
                String tag = Text.get(h.mutedLeft > 0 ? "book.order.tag_muted" : "book.order.tag_suspended");
                heldButtons.add(go(player, BookDialogs.clip(name, 13) + " " + tag, p -> citizen(p, uuid, null)).icon(BookIcons.NO));
                continue;
            }
            ServerPlayer online = player.level().getServer().getPlayerList().getPlayer(uuid);
            if (online == null || LordModeration.staff(online)) continue;
            others.add(go(player, BookDialogs.clip(name, BookScreen.MAX_LABEL), p -> citizen(p, uuid, null)).icon(BookIcons.FACTION));
        }
        List<BookButton> buttons = new ArrayList<>();
        if (judges(player, kingdom)) buttons.add(go(player, Text.get("book.order.justice"), p -> JusticePages.home(p, null)).icon(BookIcons.JUSTICE));
        buttons.addAll(heldButtons);
        buttons.addAll(others);

        List<Component> body = new ArrayList<>();
        if (held.isEmpty()) {
            body.add(BookDialogs.text(Text.get("lord.list.none")));
        } else {
            List<String> shown = held.size() <= HELD_LINES ? held : held.subList(0, HELD_LINES - 1);
            String text = String.join("\n", shown);
            if (held.size() > HELD_LINES) text += "\n" + Text.get("book.stand.more", "n", held.size() - shown.size());
            body.add(BookDialogs.text(text));
        }
        body.add(BookDialogs.text(Text.get(heldButtons.isEmpty() && others.isEmpty() ? "book.order.nobody" : "book.order.pick",
                "mute", Chat.duration(LordRules.muteCap(rt.knobs)), "suspend", Chat.duration(LordRules.suspendCap(rt.knobs)))));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.order.page_title"), BookDialogs.withNotice(notice, body), buttons,
                BookDialogs.back(player, p -> LeaderPages.desk(p, null))));
    }

    private static boolean judges(ServerPlayer player, OrgId kingdom) {
        return LeaderPages.holds(player, kingdom, Power.ARREST) || LeaderPages.holds(player, kingdom, Power.JUDGE)
                || LeaderPages.holds(player, kingdom, Power.PARDON);
    }

    // ---- one citizen: what to do ----

    public static void citizen(ServerPlayer player, UUID uuid, String notice) {
        Optional<OrgId> found = kingdom(player);
        if (found.isEmpty()) return;
        OrgId kingdom = found.get();
        PlayerId id = new PlayerId(uuid);
        String name = rt().describe.player(id);
        boolean citizen = rt().state.membership(id).map(m -> rt().state.root(m.org()).equals(kingdom)).orElse(false);
        if (!citizen) {
            page(player, charter.mc.ActAs.refusal(LordRules.NOT_YOUR_CITIZEN));
            return;
        }
        ServerPlayer online = player.level().getServer().getPlayerList().getPlayer(uuid);
        Hold h = hold(kingdom, id);
        List<String> lines = new ArrayList<>();
        if (h.mutedLeft > 0) lines.add(Text.get("lord.list.muted", "name", name, "time", Chat.duration(h.mutedLeft)));
        if (h.suspendedLeft > 0) lines.add(Text.get("lord.list.suspended", "name", name, "time", Chat.duration(h.suspendedLeft)));
        if (lines.isEmpty()) lines.add(Text.get("book.order.free", "name", name));
        lines.add(Text.get(online != null ? "book.order.online" : "book.order.offline", "name", name));
        List<BookButton> buttons = new ArrayList<>();
        if (online != null && h.mutedLeft == 0) {
            buttons.add(go(player, Text.get("book.order.mute"), p -> time(p, uuid, LordActions.Kind.MUTE)).icon(BookIcons.NO));
        }
        if (online != null && h.suspendedLeft == 0) {
            buttons.add(go(player, Text.get("book.order.suspend"), p -> time(p, uuid, LordActions.Kind.SUSPEND)).icon(BookIcons.KEEP));
        }
        if (online != null) {
            buttons.add(go(player, Text.get("book.order.kick"), p -> reason(p, uuid, LordActions.Kind.KICK, 0, null)).icon(BookIcons.LEAVE));
        }
        if (h.mutedLeft > 0) {
            buttons.add(go(player, Text.get("book.order.unmute"), p -> done(p, uuid, LordActions.run(p, LordActions.Kind.UNMUTE, uuid, 0, "")))
                    .icon(BookIcons.YES));
        }
        if (h.suspendedLeft > 0) {
            buttons.add(go(player, Text.get("book.order.unsuspend"), p -> done(p, uuid, LordActions.run(p, LordActions.Kind.UNSUSPEND, uuid, 0, "")))
                    .icon(BookIcons.YES));
        }
        BookDialogs.show(player, BookDialogs.list(BookDialogs.clip(name, 32),
                BookDialogs.withNotice(notice, List.of(BookDialogs.text(String.join("\n", lines)))), buttons,
                BookDialogs.back(player, p -> page(p, null))));
    }

    /** After a press: done lands on the list with a green line, a refusal on the citizen with a red one. */
    private static void done(ServerPlayer player, UUID uuid, LordActions.Result result) {
        if (result.ok()) page(player, BookResult.ok(result.line()));
        else citizen(player, uuid, result.line());
    }

    // ---- how long ----

    /** The lengths a Lord may give, each within the cap; the cap itself is offered when no preset reaches it. */
    public static List<Long> lengths(LordActions.Kind kind) {
        long cap = kind == LordActions.Kind.MUTE ? LordRules.muteCap(rt().knobs) : LordRules.suspendCap(rt().knobs);
        List<Long> out = new ArrayList<>();
        for (long n : kind == LordActions.Kind.MUTE ? MUTE_MINUTES : SUSPEND_HOURS) {
            long millis = kind == LordActions.Kind.MUTE ? Durations.minutes(n) : Durations.hours(n);
            if (millis <= cap) out.add(millis);
        }
        if (cap > 0 && (out.isEmpty() || out.get(out.size() - 1) < cap)) out.add(cap);
        return out;
    }

    static void time(ServerPlayer player, UUID uuid, LordActions.Kind kind) {
        if (kingdom(player).isEmpty()) return;
        String name = rt().describe.player(new PlayerId(uuid));
        List<BookButton> buttons = new ArrayList<>();
        for (long millis : lengths(kind)) {
            buttons.add(go(player, span(millis), p -> reason(p, uuid, kind, millis, null)).icon(BookIcons.CALENDAR));
        }
        String key = kind == LordActions.Kind.MUTE ? "book.order.how_long_mute" : "book.order.how_long_suspend";
        BookDialogs.show(player, BookDialogs.list(Text.get("book.order.how_long"), List.of(BookDialogs.text(Text.get(key, "name", name))), buttons,
                BookDialogs.back(player, p -> citizen(p, uuid, null))));
    }

    /** "5 minutes", "1 hour", "12 hours". */
    static String span(long millis) {
        long minutes = millis / Durations.minutes(1);
        if (minutes % 60 == 0) {
            long hours = minutes / 60;
            return hours == 1 ? Text.get("book.time.hour") : Text.get("book.time.hours", "n", hours);
        }
        return Text.get("book.order.minutes", "n", minutes);
    }

    // ---- why ----

    /**
     * The reason: one press on a common one, or a text box for another. The citizen reads it and so do the operators.
     * A press here is the last one: it carries the action out.
     */
    static void reason(ServerPlayer player, UUID uuid, LordActions.Kind kind, long millis, String notice) {
        if (kingdom(player).isEmpty()) return;
        String name = rt().describe.player(new PlayerId(uuid));
        List<BookButton> buttons = new ArrayList<>();
        for (int i = 1; i <= REASONS; i++) {
            String reason = Text.get("book.order.reason." + i);
            buttons.add(go(player, reason, p -> done(p, uuid, LordActions.run(p, kind, uuid, millis, reason))));
        }
        buttons.add(go(player, Text.get("book.order.reason_other"), p -> writeReason(p, uuid, kind, millis, null)).icon(BookIcons.WORDS));
        String what = switch (kind) {
            case MUTE -> Text.get("book.order.why_mute", "name", name, "time", span(millis));
            case SUSPEND -> Text.get("book.order.why_suspend", "name", name, "time", span(millis));
            default -> Text.get("book.order.why_kick", "name", name);
        };
        BookDialogs.show(player, BookDialogs.list(Text.get("book.order.why"),
                BookDialogs.withNotice(notice, List.of(BookDialogs.text(what))), buttons,
                BookDialogs.back(player, p -> {
                    if (kind == LordActions.Kind.KICK) citizen(p, uuid, null);
                    else time(p, uuid, kind);
                })));
    }

    private static void writeReason(ServerPlayer player, UUID uuid, LordActions.Kind kind, long millis, String notice) {
        if (kingdom(player).isEmpty()) return;
        List<BookInput> inputs = List.of(BookDialogs.textInput("reason", Text.get("book.order.reason_label"), "", LordRules.REASON_MAX));
        BookButton submit = BookDialogs.submit(player, Text.get("book.order.reason_send"), null, inputs, form -> {
            ServerPlayer p = form.player();
            String reason = FormValues.split(form.raw(), 1).map(v -> v.get(0)).orElse("");
            if (LordRules.cleanReason(reason).length() < LordRules.REASON_MIN) {
                writeReason(p, uuid, kind, millis, charter.mc.ActAs.refusal(LordRules.NEEDS_REASON));
                return;
            }
            done(p, uuid, LordActions.run(p, kind, uuid, millis, reason));
        });
        BookDialogs.show(player, BookDialogs.form(Text.get("book.order.why"),
                BookDialogs.withNotice(notice, List.of(BookDialogs.text(Text.get("book.order.reason_intro",
                        "min", LordRules.REASON_MIN, "max", LordRules.REASON_MAX)))), inputs, submit,
                BookDialogs.back(player, p -> reason(p, uuid, kind, millis, null))));
    }
}
