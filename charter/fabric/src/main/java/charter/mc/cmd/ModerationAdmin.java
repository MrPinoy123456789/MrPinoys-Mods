package charter.mc.cmd;

import charter.history.History;
import charter.id.PlayerId;
import charter.mc.CharterPerms;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.moderation.LedgerGrants;
import charter.moderation.ModerationEvents;
import charter.org.PlayerRecord;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The operators' side of Lord moderation, under {@code /charter admin}.
 *
 * <pre>
 *   /charter admin moderation                  what Lords did lately, and who is held or has Ledger access now
 *   /charter admin ledger grant &lt;player&gt;       one hour of read only Ledger for a current Lord (LuckPerms temporary nodes)
 *   /charter admin ledger revoke &lt;player&gt;      take it back now
 *   /charter admin ledger status               who holds it and until when
 * </pre>
 *
 * Ledger cannot restrict a lookup to one kingdom, so a grant is a trust decision for that hour. Each handler asks
 * {@link CharterPerms#admin(CommandSourceStack)} again, because Brigadier keeps one requirement on the merged
 * {@code admin} node and it may be the plain operator level.
 */
final class ModerationAdmin {

    static final int RECENT = 15;

    private ModerationAdmin() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("moderation").executes(ModerationAdmin::moderation);
    }

    static LiteralArgumentBuilder<CommandSourceStack> ledgerNode() {
        return Commands.literal("ledger")
                .then(Commands.literal("grant").then(player().executes(ModerationAdmin::grant)))
                .then(Commands.literal("revoke").then(player().executes(ModerationAdmin::revoke)))
                .then(Commands.literal("status").executes(ModerationAdmin::status));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> player() {
        return Commands.argument("player", StringArgumentType.string())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b));
    }

    private static boolean allowed(CommandContext<CommandSourceStack> ctx) {
        if (!CharterPerms.admin(ctx.getSource())) {
            ctx.getSource().sendFailure(Chat.bad(Text.get("admin.menu.denied")));
            return false;
        }
        if (!CharterServer.running()) {
            ctx.getSource().sendFailure(Chat.bad(Text.get("book.not_running")));
            return false;
        }
        return true;
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    private static int grant(CommandContext<CommandSourceStack> ctx) {
        if (!allowed(ctx)) return 0;
        Optional<Resolve.Who> who = Resolve.player(ctx, rt());
        if (who.isEmpty()) {
            ctx.getSource().sendFailure(Chat.bad(Text.get("land.admin.no_player", "name", StringArgumentType.getString(ctx, "player"))));
            return 0;
        }
        String admin = ctx.getSource().getTextName();
        LedgerGrants.Result result = LedgerGrants.grant(ctx.getSource().getServer(), rt(), who.get().id(), who.get().name(), admin);
        return done(ctx, result);
    }

    private static int revoke(CommandContext<CommandSourceStack> ctx) {
        if (!allowed(ctx)) return 0;
        Optional<Resolve.Who> who = Resolve.player(ctx, rt());
        if (who.isEmpty()) {
            ctx.getSource().sendFailure(Chat.bad(Text.get("land.admin.no_player", "name", StringArgumentType.getString(ctx, "player"))));
            return 0;
        }
        String admin = ctx.getSource().getTextName();
        return done(ctx, LedgerGrants.revoke(ctx.getSource().getServer(), rt(), who.get().id(), who.get().name(), admin));
    }

    private static int done(CommandContext<CommandSourceStack> ctx, LedgerGrants.Result result) {
        if (!result.ok()) {
            ctx.getSource().sendFailure(Chat.bad(result.text()));
            return 0;
        }
        rt().siegeLog.admin(rt().clock.now(), ctx.getSource().getTextName(), ctx.getInput());
        ctx.getSource().sendSuccess(() -> Chat.good(result.text()), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        if (!allowed(ctx)) return 0;
        long now = rt().clock.now();
        int n = 0;
        for (PlayerRecord r : rt().state.allPlayers()) {
            if (r.ledgerUntil <= now) continue;
            n++;
            String line = Text.get("admin.ledger.status_line", "name", rt().describe.player(r.id), "until", LedgerGrants.clock(r.ledgerUntil),
                    "left", Chat.duration(r.ledgerUntil - now));
            ctx.getSource().sendSuccess(() -> Chat.line(line), false);
        }
        if (n == 0) ctx.getSource().sendSuccess(() -> Chat.line(Text.get("admin.ledger.status_none")), false);
        return n;
    }

    /** Who is held or has Ledger access now, then the last {@link #RECENT} moderation events, newest first. */
    private static int moderation(CommandContext<CommandSourceStack> ctx) {
        if (!allowed(ctx)) return 0;
        for (String line : lines(rt())) ctx.getSource().sendSuccess(() -> Chat.line(line), false);
        return 1;
    }

    /** The text of the moderation overview. Also the body of the menu's page. */
    public static List<String> lines(CharterRuntime rt) {
        long now = rt.clock.now();
        List<String> out = new ArrayList<>();
        out.add(Text.get("admin.moderation.now"));
        int held = 0;
        for (PlayerRecord r : rt.state.allPlayers()) {
            String name = rt.describe.player(r.id);
            if (r.mutedUntil > now) {
                held++;
                out.add(Text.get("admin.moderation.muted", "name", name, "time", Chat.duration(r.mutedUntil - now)));
            }
            if (r.suspendedUntil > now) {
                held++;
                out.add(Text.get("admin.moderation.suspended", "name", name, "org", r.suspendedFrom == null ? "?" : rt.describe.org(r.suspendedFrom),
                        "time", Chat.duration(r.suspendedUntil - now)));
            }
            if (r.ledgerUntil > now) {
                held++;
                out.add(Text.get("admin.moderation.ledger", "name", name, "until", LedgerGrants.clock(r.ledgerUntil)));
            }
        }
        if (held == 0) out.add(Text.get("admin.moderation.nobody"));
        out.add(Text.get("admin.moderation.recent"));
        List<History.Entry> all = rt.state.history.entries();
        int shown = 0;
        for (int i = all.size() - 1; i >= 0 && shown < RECENT; i--) {
            String text = describe(rt, all.get(i), now);
            if (text == null) continue;
            out.add(text);
            shown++;
        }
        if (shown == 0) out.add(Text.get("admin.moderation.none"));
        return out;
    }

    private static String describe(CharterRuntime rt, History.Entry entry, long now) {
        String ago = Chat.duration(now - entry.event().at());
        return switch (entry.event()) {
            case ModerationEvents.Muted e -> Text.get("admin.moderation.e_muted", "ago", ago, "lord", name(rt, e.lord()), "org", rt.describe.org(e.org()),
                    "name", name(rt, e.player()), "time", Chat.duration(e.durationMillis()), "reason", e.reason());
            case ModerationEvents.Unmuted e -> Text.get("admin.moderation.e_unmuted", "ago", ago, "lord", name(rt, e.lord()), "org", rt.describe.org(e.org()),
                    "name", name(rt, e.player()));
            case ModerationEvents.Suspended e -> Text.get("admin.moderation.e_suspended", "ago", ago, "lord", name(rt, e.lord()), "org", rt.describe.org(e.org()),
                    "name", name(rt, e.player()), "time", Chat.duration(e.durationMillis()), "reason", e.reason());
            case ModerationEvents.Unsuspended e -> Text.get("admin.moderation.e_unsuspended", "ago", ago, "lord", name(rt, e.lord()), "org", rt.describe.org(e.org()),
                    "name", name(rt, e.player()));
            case ModerationEvents.Kicked e -> Text.get("admin.moderation.e_kicked", "ago", ago, "lord", name(rt, e.lord()), "org", rt.describe.org(e.org()),
                    "name", name(rt, e.player()), "reason", e.reason());
            case ModerationEvents.LedgerGranted e -> Text.get("admin.moderation.e_ledger", "ago", ago, "admin", entry.actor(), "name", name(rt, e.player()));
            case ModerationEvents.LedgerRevoked e -> Text.get("admin.moderation.e_ledger_end", "ago", ago, "name", name(rt, e.player()), "why", e.why());
            default -> null;
        };
    }

    private static String name(CharterRuntime rt, PlayerId id) {
        return rt.describe.player(id);
    }
}
