package charter.moderation;

import charter.Fixture;
import charter.contract.Justice;
import charter.gov.Preset;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.moderation.actions.LordKick;
import charter.moderation.actions.LordMute;
import charter.moderation.actions.LordSuspend;
import charter.moderation.actions.LordUnmute;
import charter.moderation.actions.LordUnsuspend;
import charter.org.actions.AppointCoLeader;
import charter.time.Durations;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/** A Lord moderates their own citizens and nobody else, within the caps, and every act is in the history. */
public final class LordModerationTest {

    private LordModerationTest() {
    }

    public static void run() {
        aLordMutesTheirOwnCitizen();
        nobodyOutsideTheKingdom();
        notALordCannot();
        lordsAreBeyondReach();
        theCapsHold();
        aReasonIsRequired();
        aNewMuteReplacesTheOld();
        suspensionIsTheBanishmentPush();
        liftingEarly();
        kickChangesNothing();
        reasonsAreCleaned();
    }

    /** Ann rules Azrak with citizens Bob and Cy; Dee rules Aurelia with citizen Eve. */
    private record Realm(Fixture f, OrgId azrak, OrgId aurelia, PlayerId ann, PlayerId bob, PlayerId cy, PlayerId dee, PlayerId eve) {
    }

    private static Realm realm(String tag) {
        Fixture f = new Fixture();
        PlayerId ann = player(tag + "Ann");
        PlayerId bob = player(tag + "Bob");
        PlayerId cy = player(tag + "Cy");
        PlayerId dee = player(tag + "Dee");
        PlayerId eve = player(tag + "Eve");
        OrgId azrak = f.found(tag + "Azrak", Preset.LORDSHIP, ann, bob, cy);
        OrgId aurelia = f.found(tag + "Aurelia", Preset.LORDSHIP, dee, eve);
        return new Realm(f, azrak, aurelia, ann, bob, cy, dee, eve);
    }

    static void aLordMutesTheirOwnCitizen() {
        Realm r = realm("M");
        check("1: Ann is a Lord of Azrak", LordRules.kingdomOf(r.f.state, r.ann).map(r.azrak::equals).orElse(false));
        check("1: a citizen is not a Lord", !LordRules.isLord(r.f.state, r.bob));
        check("1: Ann mutes Bob for 10 minutes", r.f.as(r.ann, new LordMute(r.bob, Durations.minutes(10), "spamming the chat")).done());
        eq("1: until ten minutes from now", r.f.clock.now() + Durations.minutes(10), r.f.state.player(r.bob).mutedUntil);
        ModerationEvents.Muted muted = r.f.state.history.of(ModerationEvents.Muted.class).get(0);
        eq("1: journaled with the kingdom", r.azrak, muted.org());
        eq("1: journaled with the Lord", r.ann, muted.lord());
        eq("1: journaled with the reason", "spamming the chat", muted.reason());
        check("1: not an admin event", r.f.state.history.entries().stream()
                .filter(e -> e.event() instanceof ModerationEvents.Muted).noneMatch(e -> e.admin()));
    }

    static void nobodyOutsideTheKingdom() {
        Realm r = realm("O");
        eq("2: another kingdom's citizen", "not_your_citizen", r.f.as(r.ann, new LordMute(r.eve, Durations.minutes(5), "rude words")).reasonKey());
        eq("2: another kingdom's Lord", "not_your_citizen", r.f.as(r.ann, new LordSuspend(r.dee, Durations.hours(1), "rude words")).reasonKey());
        PlayerId loner = player("OLoner");
        r.f.seen(loner);
        eq("2: someone in no kingdom", "not_your_citizen", r.f.as(r.ann, new LordKick(loner, "rude words")).reasonKey());
        eq("2: nothing was set on Eve", 0L, r.f.state.player(r.eve).mutedUntil);
        check("2: nothing journaled", r.f.state.history.of(ModerationEvents.Muted.class).isEmpty()
                && r.f.state.history.of(ModerationEvents.Suspended.class).isEmpty() && r.f.state.history.of(ModerationEvents.Kicked.class).isEmpty());
    }

    static void notALordCannot() {
        Realm r = realm("N");
        eq("3: a citizen cannot mute", "not_a_lord", r.f.as(r.bob, new LordMute(r.cy, Durations.minutes(5), "rude words")).reasonKey());
        eq("3: an operator is not a Lord here", "not_a_lord", r.f.admin(new LordMute(r.cy, Durations.minutes(5), "rude words")).reasonKey());
    }

    static void lordsAreBeyondReach() {
        Realm r = realm("L");
        check("4: setup, Bob is the second Lord", r.f.admin(new AppointCoLeader(r.azrak, r.bob)).done());
        eq("4: one Lord cannot mute the other", "target_is_lord", r.f.as(r.ann, new LordMute(r.bob, Durations.minutes(5), "rude words")).reasonKey());
        eq("4: nor suspend", "target_is_lord", r.f.as(r.bob, new LordSuspend(r.ann, Durations.hours(1), "rude words")).reasonKey());
        eq("4: a Lord cannot mute themself", "target_is_self", r.f.as(r.ann, new LordMute(r.ann, Durations.minutes(5), "rude words")).reasonKey());
        check("4: either Lord may moderate a citizen", r.f.as(r.bob, new LordMute(r.cy, Durations.minutes(5), "rude words")).done());
    }

    static void theCapsHold() {
        Realm r = realm("C");
        eq("5: the mute cap is an hour", Durations.minutes(60), LordRules.muteCap(r.f.knobs));
        eq("5: the suspension cap is 12 hours", Durations.hours(12), LordRules.suspendCap(r.f.knobs));
        check("5: exactly an hour is fine", r.f.as(r.ann, new LordMute(r.bob, Durations.hours(1), "rude words")).done());
        eq("5: a minute over is refused", "too_long", r.f.as(r.ann, new LordMute(r.cy, Durations.minutes(61), "rude words")).reasonKey());
        eq("5: a suspension over 12 hours is refused", "too_long", r.f.as(r.ann, new LordSuspend(r.cy, Durations.hours(13), "rude words")).reasonKey());
        eq("5: zero is refused", "bad_duration", r.f.as(r.ann, new LordMute(r.cy, 0, "rude words")).reasonKey());
        eq("5: an unreadable time (-1) is refused", "bad_duration", r.f.as(r.ann, new LordSuspend(r.cy, -1, "rude words")).reasonKey());
        eq("5: nothing set on Cy", 0L, r.f.state.player(r.cy).suspendedUntil);
    }

    static void aReasonIsRequired() {
        Realm r = realm("R");
        eq("6: no reason", "needs_reason", r.f.as(r.ann, new LordMute(r.bob, Durations.minutes(5), "")).reasonKey());
        eq("6: two letters", "needs_reason", r.f.as(r.ann, new LordKick(r.bob, " ok ")).reasonKey());
        eq("6: suspension too", "needs_reason", r.f.as(r.ann, new LordSuspend(r.bob, Durations.hours(1), null)).reasonKey());
    }

    static void aNewMuteReplacesTheOld() {
        Realm r = realm("A");
        check("7: an hour", r.f.as(r.ann, new LordMute(r.bob, Durations.hours(1), "rude words")).done());
        r.f.pass(Durations.minutes(50), r.ann, r.bob, r.cy);
        check("7: another hour", r.f.as(r.ann, new LordMute(r.bob, Durations.hours(1), "still rude")).done());
        eq("7: an hour from now, not an hour and ten", r.f.clock.now() + Durations.hours(1), r.f.state.player(r.bob).mutedUntil);
    }

    static void suspensionIsTheBanishmentPush() {
        Realm r = realm("S");
        long now = r.f.clock.now();
        check("8: Ann suspends Cy for two hours", r.f.as(r.ann, new LordSuspend(r.cy, Durations.hours(2), "griefing the farm")).done());
        check("8: Cy is kept off Azrak's land", Justice.banished(r.f.state, r.f.knobs, r.cy, r.azrak, now));
        check("8: but not off Aurelia's", !Justice.banished(r.f.state, r.f.knobs, r.cy, r.aurelia, now));
        check("8: Cy is still a member", r.f.state.isMember(r.cy, r.azrak));
        check("8: after two hours it is over", !Justice.banished(r.f.state, r.f.knobs, r.cy, r.azrak, now + Durations.hours(2)));
        eq("8: journaled", 1, r.f.state.history.of(ModerationEvents.Suspended.class).size());
    }

    static void liftingEarly() {
        Realm r = realm("U");
        eq("9: nothing to unmute", "not_muted", r.f.as(r.ann, new LordUnmute(r.bob)).reasonKey());
        eq("9: nothing to unsuspend", "not_suspended", r.f.as(r.ann, new LordUnsuspend(r.bob)).reasonKey());
        r.f.as(r.ann, new LordMute(r.bob, Durations.minutes(30), "rude words"));
        r.f.as(r.ann, new LordSuspend(r.bob, Durations.hours(3), "rude words"));
        eq("9: Dee cannot unmute Ann's citizen", "not_your_citizen", r.f.as(r.dee, new LordUnmute(r.bob)).reasonKey());
        check("9: unmute", r.f.as(r.ann, new LordUnmute(r.bob)).done());
        check("9: unsuspend", r.f.as(r.ann, new LordUnsuspend(r.bob)).done());
        eq("9: unmuted", 0L, r.f.state.player(r.bob).mutedUntil);
        check("9: unsuspended", !Justice.banished(r.f.state, r.f.knobs, r.bob, r.azrak, r.f.clock.now()));
        check("9: both journaled", r.f.state.history.of(ModerationEvents.Unmuted.class).size() == 1
                && r.f.state.history.of(ModerationEvents.Unsuspended.class).size() == 1);
    }

    static void kickChangesNothing() {
        Realm r = realm("K");
        check("10: Ann kicks Bob", r.f.as(r.ann, new LordKick(r.bob, "cool off")).done());
        check("10: still a member", r.f.state.isMember(r.bob, r.azrak));
        eq("10: no mute", 0L, r.f.state.player(r.bob).mutedUntil);
        eq("10: journaled", "cool off", r.f.state.history.of(ModerationEvents.Kicked.class).get(0).reason());
    }

    static void reasonsAreCleaned() {
        eq("11: control characters and runs of space go", "a b c", LordRules.cleanReason("a\n\tb   c\u0007"));
        eq("11: at most 80 characters", LordRules.REASON_MAX, LordRules.cleanReason("x".repeat(200)).length());
        eq("11: null is empty", "", LordRules.cleanReason(null));
    }
}
