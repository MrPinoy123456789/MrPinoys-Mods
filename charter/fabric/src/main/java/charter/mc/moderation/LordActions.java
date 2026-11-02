package charter.mc.moderation;

import charter.engine.Action;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.ActAs;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import charter.moderation.LordRules;
import charter.moderation.actions.LordKick;
import charter.moderation.actions.LordMute;
import charter.moderation.actions.LordSuspend;
import charter.moderation.actions.LordUnmute;
import charter.moderation.actions.LordUnsuspend;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;

/**
 * The one path every Lord moderation takes, typed ({@code /charter lord}) or pressed (the book's Discipline pages): a
 * Lord acting, a known target, online when the action needs the world, not an operator, then the rules in core, then the
 * effect in the world (telling the citizen, the disconnect) and a word to the operators. Neither the commands nor the
 * book can skip a step, because neither has its own copy of them.
 */
public final class LordActions {

    public enum Kind {
        MUTE(true), SUSPEND(true), KICK(true), UNMUTE(false), UNSUSPEND(false);

        /** Whether the target must be online: a hold is put on someone in the world, a lift is not. */
        public final boolean needsOnline;

        Kind(boolean needsOnline) {
            this.needsOnline = needsOnline;
        }
    }

    /** What happened: whether it was done, and the line for the Lord (the done line or the refusal). */
    public record Result(boolean ok, String line) {
    }

    private LordActions() {
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    /**
     * Runs one moderation by the Lord on the player with this UUID. {@code millis} is the length of a mute or a suspension
     * (ignored otherwise); {@code reason} is cleaned here and checked by core.
     */
    public static Result run(ServerPlayer lord, Kind kind, UUID target, long millis, String reason) {
        if (!CharterServer.running()) return new Result(false, Text.get("book.not_running"));
        CharterRuntime rt = rt();
        PlayerId lordId = new PlayerId(lord.getUUID());
        Optional<OrgId> kingdom = LordRules.kingdomOf(rt.state, lordId);
        if (kingdom.isEmpty()) return new Result(false, ActAs.refusal(LordRules.NOT_A_LORD));
        String kingdomName = rt.describe.org(kingdom.get());
        PlayerId id = new PlayerId(target);
        if (rt.state.knownPlayer(id).isEmpty()) return new Result(false, Text.get("lord.no_player", "name", target));
        ServerPlayer online = lord.level().getServer().getPlayerList().getPlayer(target);
        String name = online != null ? online.getPlainTextName() : rt.describe.player(id);
        if (kind.needsOnline && online == null) return new Result(false, Text.get("lord.offline", "name", name));
        // Operators are beyond a Lord's reach. Asked before the rules run, and the same words as any other refusal of power.
        if (online != null && LordModeration.staff(online)) return new Result(false, Text.get("lord.no_power", "name", name));
        String clean = LordRules.cleanReason(reason);
        Action action = switch (kind) {
            case MUTE -> new LordMute(id, millis, clean);
            case SUSPEND -> new LordSuspend(id, millis, clean);
            case KICK -> new LordKick(id, clean);
            case UNMUTE -> new LordUnmute(id);
            case UNSUSPEND -> new LordUnsuspend(id);
        };
        Outcome outcome = rt.engine.run(new Actor.Player(lordId), action);
        if (outcome instanceof Outcome.Refused(Reason refusal)) return new Result(false, ActAs.refusal(refusal));

        String lordName = lord.getPlainTextName();
        long now = rt.clock.now();
        String done;
        String note;
        switch (kind) {
            case MUTE -> {
                String time = Chat.duration(rt.state.player(id).mutedUntil - now);
                done = Text.get("lord.muted_lord", "name", name, "time", time, "reason", clean);
                online.sendSystemMessage(Chat.bad(Text.get("lord.muted_target", "lord", lordName, "org", kingdomName, "time", time, "reason", clean)));
                note = Text.get("lord.op.muted", "lord", lordName, "org", kingdomName, "name", name, "time", time, "reason", clean);
            }
            case SUSPEND -> {
                String time = Chat.duration(rt.state.player(id).suspendedUntil - now);
                done = Text.get("lord.suspended_lord", "name", name, "time", time, "reason", clean);
                online.sendSystemMessage(Chat.bad(Text.get("lord.suspended_target", "lord", lordName, "org", kingdomName, "time", time, "reason", clean)));
                note = Text.get("lord.op.suspended", "lord", lordName, "org", kingdomName, "name", name, "time", time, "reason", clean);
            }
            case KICK -> {
                done = Text.get("lord.kicked_lord", "name", name, "reason", clean);
                online.connection.disconnect(Component.literal(Text.get("lord.kick_target", "lord", lordName, "org", kingdomName, "reason", clean)));
                note = Text.get("lord.op.kicked", "lord", lordName, "org", kingdomName, "name", name, "reason", clean);
            }
            case UNMUTE -> {
                done = Text.get("lord.unmuted_lord", "name", name);
                if (online != null) online.sendSystemMessage(Chat.good(Text.get("lord.unmuted_target", "lord", lordName, "org", kingdomName)));
                note = Text.get("lord.op.unmuted", "lord", lordName, "org", kingdomName, "name", name);
            }
            default -> {
                done = Text.get("lord.unsuspended_lord", "name", name);
                if (online != null) online.sendSystemMessage(Chat.good(Text.get("lord.unsuspended_target", "lord", lordName, "org", kingdomName)));
                note = Text.get("lord.op.unsuspended", "lord", lordName, "org", kingdomName, "name", name);
            }
        }
        LordModeration.tellOperators(lord.level().getServer(), note);
        return new Result(true, done);
    }
}
