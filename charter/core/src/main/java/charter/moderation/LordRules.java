package charter.moderation;

import charter.config.Knobs;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.gov.Deciders;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.Organization;
import charter.state.CharterState;
import charter.time.Durations;

import java.util.Optional;

/**
 * The line a Lord's moderation may not cross. A Lord is a holder of the decision makers office of a top level kingdom,
 * and acts only on a member of that same kingdom (a member of one of its sub-factions counts). Anyone else, in
 * another kingdom or in none, is refused with the same words, so the refusal does not tell a Lord who belongs where.
 * Whether the target is an operator is not known here (that is a permissions mod's business); the fabric layer asks
 * for it before it runs any of these actions.
 */
public final class LordRules {

    public static final Reason NOT_A_LORD = new Reason("not_a_lord", "Only a Lord of a kingdom can do that.");
    public static final Reason NOT_YOUR_CITIZEN = new Reason("not_your_citizen", "That player is not a citizen of your kingdom.");
    public static final Reason A_LORD = new Reason("target_is_lord", "A Lord cannot be moderated by a Lord.");
    public static final Reason YOURSELF = new Reason("target_is_self", "You cannot do that to yourself.");
    public static final Reason NEEDS_REASON = new Reason("needs_reason", "Say why, in a few words, so your citizen and the operators can read it.");
    public static final Reason BAD_DURATION = new Reason("bad_duration", "That length of time does not read. Use 10m, 2h or 1h30m.");

    public static final Reason HELD_ELSEWHERE = new Reason("held_elsewhere",
            "That player is already barred from another kingdom's land. That is not yours to change; it ends on its own.");

    public static final int REASON_MIN = 3;
    public static final int REASON_MAX = 80;

    private LordRules() {
    }

    /** The kingdom this player rules (a top level kingdom whose decision office they hold), if any. */
    public static Optional<OrgId> kingdomOf(CharterState state, PlayerId player) {
        return state.membership(player).map(m -> state.root(m.org())).filter(root -> rules(state, root, player));
    }

    public static boolean isLord(CharterState state, PlayerId player) {
        return kingdomOf(state, player).isPresent();
    }

    private static boolean rules(CharterState state, OrgId root, PlayerId player) {
        Organization org = state.org(root).orElse(null);
        if (org == null || !org.exists() || !org.isTopLevel()) return false;
        return Deciders.of(state, root).map(office -> office.holds(player)).orElse(false);
    }

    /** The longest mute a Lord may give, in milliseconds. */
    public static long muteCap(Knobs knobs) {
        return Durations.minutes(knobs.lordMuteMaxMinutes);
    }

    /** The longest suspension a Lord may give, in milliseconds. */
    public static long suspendCap(Knobs knobs) {
        return Durations.hours(knobs.lordSuspendMaxHours);
    }

    /** Trims a reason and strips control characters; never longer than {@link #REASON_MAX}. */
    public static String cleanReason(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        return s.length() > REASON_MAX ? s.substring(0, REASON_MAX).strip() : s;
    }

    /**
     * The checks every Lord action shares: the actor is a player who rules a kingdom, and the target is a different
     * citizen of that kingdom who does not rule it. On success the kingdom is {@link #kingdomOf} for the actor.
     */
    public static Outcome checkTarget(ActionContext ctx, PlayerId target) {
        PlayerId actor = ctx.player();
        if (actor == null) return Outcome.refuse(NOT_A_LORD);
        Optional<OrgId> kingdom = kingdomOf(ctx.state, actor);
        if (kingdom.isEmpty()) return Outcome.refuse(NOT_A_LORD);
        if (actor.equals(target)) return Outcome.refuse(YOURSELF);
        boolean citizen = ctx.state.membership(target).map(m -> ctx.state.root(m.org()).equals(kingdom.get())).orElse(false);
        if (!citizen) return Outcome.refuse(NOT_YOUR_CITIZEN);
        if (isLord(ctx.state, target)) return Outcome.refuse(A_LORD);
        return Outcome.OK;
    }

    /** The reason refused when it is too short, otherwise OK. */
    public static Outcome checkReason(String reason) {
        return cleanReason(reason).length() < REASON_MIN ? Outcome.refuse(NEEDS_REASON) : Outcome.OK;
    }

    /** OK when {@code millis} is above zero and within {@code cap}; the refusal names the cap. */
    public static Outcome checkDuration(long millis, long cap) {
        if (millis <= 0) return Outcome.refuse(BAD_DURATION);
        if (millis > cap) {
            return Outcome.refuse(new Reason("too_long", "That is longer than a Lord may give. The most is " + span(cap) + "."));
        }
        return Outcome.OK;
    }

    static String span(long millis) {
        long minutes = millis / 60_000;
        if (minutes >= 60 && minutes % 60 == 0) return (minutes / 60) + (minutes / 60 == 1 ? " hour" : " hours");
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
