package charter.org.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.gov.GovEvents;
import charter.gov.Governance;
import charter.gov.Office;
import charter.gov.Preset;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.LandGraph;
import charter.org.OrgEvents;
import charter.org.OrgReasons;
import charter.org.OrgStatus;
import charter.org.Organization;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * An operator sets a kingdom: a Lordship with its keep at a chosen chunk and nobody in the Lord's seat yet.
 * No one founds it and no one pays for the keep; {@link AppointLeader} seats the leader later. Operators only.
 */
public record CreateKingdom(String name, ChunkKey center) implements Action {

    /** Stands in the Chronicle as the founder of a kingdom the operators set. */
    public static final PlayerId THE_SERVER = new PlayerId(new UUID(0x5345525645524B47L, 1));

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
        if (!ctx.admin()) return Outcome.refuse(OrgReasons.NOT_YOU);
        Outcome named = Found.checkName(ctx, name);
        if (named.refused()) return named;
        var problem = new LandGraph(ctx.state, ctx.knobs).keepProblemForNew(center);
        if (problem.isPresent()) return Outcome.refuse(problem.get());
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        List<HistoryEvent> events = new ArrayList<>();
        Organization org = new Organization(ctx.state.ids.org(), name.strip(), null, new Governance(Preset.LORDSHIP),
                OrgStatus.ACTIVE, ctx.now);
        org.people.name = name.strip();
        ctx.state.addOrg(org);
        ctx.state.touch(THE_SERVER, "The server", ctx.now);
        events.add(new OrgEvents.Founded(ctx.now, org.id, org.name(), Preset.LORDSHIP, THE_SERVER));

        // The Lord's office with its grants, and no one in the seat.
        Office lord = new Office(ctx.state.ids.office(), org.id, Preset.LORD, true, true, 1);
        ctx.state.addOffice(lord);
        org.governance.deciders = lord.id;
        events.add(new GovEvents.OfficeCreated(ctx.now, lord.id, org.id, lord.name, true));
        Preset.addLordGrants(ctx.state, org.id, lord.id, ctx.knobs, ctx.now);

        events.addAll(new LandGraph(ctx.state, ctx.knobs).placeKeep(org.id, center, ctx.now));
        return events;
    }
}
