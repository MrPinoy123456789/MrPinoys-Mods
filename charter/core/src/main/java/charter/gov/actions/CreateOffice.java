package charter.gov.actions;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.gov.GovEvents;
import charter.gov.Office;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.OrgId;

import java.util.List;
import java.util.Optional;

/** Creates an ordinary office (Treasurer, Marshal, Sheriff). Reserved offices come only from presets. */
public record CreateOffice(OrgId org, String name) implements Action {

    @Override
    public OrgId actingFor() {
        return org;
    }

    @Override
    public Optional<Power> power() {
        return Optional.of(Power.APPOINT);
    }

    @Override
    public Outcome check(ActionContext ctx) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.length() < 2 || trimmed.length() > 24) return Outcome.refuse(new Reason("bad_office_name", "That office name is too short or too long."));
        if (ctx.state.officeNamed(org, trimmed).isPresent()) return Outcome.refuse(new Reason("office_exists", "There is already an office with that name."));
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        Office office = new Office(ctx.state.ids.office(), org, name.strip(), false, false);
        ctx.state.addOffice(office);
        return List.of(new GovEvents.OfficeCreated(ctx.now, office.id, org, office.name, false));
    }
}
