package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.gov.Governance;
import charter.gov.Preset;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.OrgEvents;
import charter.org.OrgReasons;
import charter.org.OrgStatus;
import charter.org.Organization;
import charter.time.Durations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A player founds a new top-level organization. It stays FORMING until a second
 * member joins (DESIGN.md §16.1: at least two founders), and the founder cannot
 * found again for the cooldown.
 */
public record Found(PlayerId founder, String name, Preset preset) implements Action {

    @Override
    public OrgId actingFor() {
        return null;
    }

    @Override
    public Optional<Power> power() {
        return Optional.empty();
    }

    @Override
    public Outcome check(ActionContext ctx) {
        if (!ctx.admin() && !founder.equals(ctx.player())) return Outcome.refuse(OrgReasons.NOT_YOU);
        if (ctx.state.membership(founder).isPresent()) return Outcome.refuse(OrgReasons.ALREADY_MEMBER);
        Outcome named = checkName(ctx, name);
        if (named.refused()) return named;
        return cooldownProblem(ctx, founder);
    }

    /** Refuses a founder who founded too recently; shared with {@link FoundKeep}. */
    static Outcome cooldownProblem(ActionContext ctx, PlayerId founder) {
        long lastFounded = ctx.state.knownPlayer(founder).map(r -> r.lastFoundedAt).orElse(0L);
        long cooldown = Durations.days(ctx.knobs.foundSecedeCooldownDays);
        if (!ctx.admin() && lastFounded != 0 && ctx.now - lastFounded < cooldown) {
            return Outcome.refuse(OrgReasons.FOUND_COOLDOWN);
        }
        return Outcome.OK;
    }

    public static Outcome checkName(ActionContext ctx, String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.length() < 3 || trimmed.length() > ctx.knobs.orgNameMaxLength) return Outcome.refuse(OrgReasons.BAD_NAME);
        if (ctx.state.orgNamed(trimmed).isPresent()) return Outcome.refuse(OrgReasons.NAME_TAKEN);
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Organization org = new Organization(ctx.state.ids.org(), name.strip(), null, new Governance(preset),
                OrgStatus.FORMING, ctx.now);
        ctx.state.addOrg(org);
        ctx.state.join(founder, org.id, ctx.now);
        ctx.state.player(founder).lastFoundedAt = ctx.now;
        List<HistoryEvent> events = new ArrayList<>();
        events.add(new OrgEvents.Founded(ctx.now, org.id, org.name(), preset, founder));
        events.addAll(preset.apply(ctx.state, org, founder, ctx.knobs, ctx.now));
        return events;
    }
}
