package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.gov.Governance;
import charter.gov.Office;
import charter.gov.Preset;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.OrgEvents;
import charter.org.OrgReasons;
import charter.org.OrgStatus;
import charter.org.Organization;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Creates a subordinate organization under {@code parent}, with a member of the
 * parent moved in as its first member (its Lord, for a Lordship). The new body is
 * a full organization: its own treasury, rules and offices. The parent holds no
 * power over it except what is granted.
 */
public record CreateSubOrg(OrgId parent, String name, Preset preset, PlayerId leader) implements Action {

    @Override
    public OrgId actingFor() {
        return parent;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.CREATE_SUBORG);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        Outcome named = Found.checkName(ctx, name);
        if (named.refused()) return named;
        if (!ctx.state.isMember(leader, parent)) return Outcome.refuse(OrgReasons.NOT_A_MEMBER);
        for (Office office : ctx.state.offices(parent)) {
            if (leader.equals(office.holder())) return Outcome.refuse(OrgReasons.HOLDS_OFFICE);
        }
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Organization org = new Organization(ctx.state.ids.org(), name.strip(), parent, new Governance(preset),
                OrgStatus.ACTIVE, ctx.now);
        ctx.state.addOrg(org);
        ctx.state.leave(leader);
        ctx.state.join(leader, org.id, ctx.now);
        List<HistoryEvent> events = new ArrayList<>();
        events.add(new OrgEvents.SubOrgCreated(ctx.now, org.id, parent, org.name(), preset, leader));
        events.add(new OrgEvents.Joined(ctx.now, org.id, leader, parent));
        events.addAll(preset.apply(ctx.state, org, leader, ctx.knobs, ctx.now));
        return events;
    }
}
