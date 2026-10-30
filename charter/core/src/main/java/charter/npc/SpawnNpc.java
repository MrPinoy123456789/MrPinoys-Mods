package charter.npc;

import charter.engine.Action;
import charter.engine.ActionContext;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.gov.Governance;
import charter.gov.Preset;
import charter.grant.Power;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.land.CampPhase;
import charter.land.LandGraph;
import charter.land.LandReasons;
import charter.org.OrgEvents;
import charter.org.OrgReasons;
import charter.org.OrgStatus;
import charter.org.Organization;
import charter.treasury.Currency;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The raiders found a faction of their own and raise a stronghold or an outpost with it (NPC strongholds, N1
 * and N2). Operators and the rules only. The faction is an ordinary organization with the {@code npc}
 * flag, an unreachable Lord, and a treasury stocked by tier: that treasury is the prize.
 */
public record SpawnNpc(String name, String banner, NpcBook.Kind kind, ChunkKey center, long diamonds, long cobblestone,
                      boolean replacement) implements Action {

    public SpawnNpc(String name, String banner, NpcBook.Kind kind, ChunkKey center, long diamonds, long cobblestone) {
        this(name, banner, kind, center, diamonds, cobblestone, false);
    }

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
        if (!ctx.admin() && !(ctx.actor instanceof Actor.System)) return Outcome.refuse(LandReasons.OPERATOR_ONLY);
        if (name == null || name.isBlank() || ctx.state.orgNamed(name.strip()).isPresent()) return Outcome.refuse(OrgReasons.NAME_TAKEN);
        var land = new LandGraph(ctx.state, ctx.knobs);
        var problem = kind == NpcBook.Kind.STRONGHOLD ? land.keepProblemForNew(center, true) : land.campProblem(center);
        if (problem.isPresent()) return Outcome.refuse(problem.get());
        return Outcome.OK;
    }

    @Override
    public List<HistoryEvent> apply(ActionContext ctx) {
        List<HistoryEvent> events = new ArrayList<>();
        Organization org = new Organization(ctx.state.ids.org(), name.strip(), null, new Governance(Preset.LORDSHIP),
                OrgStatus.ACTIVE, ctx.now);
        org.npc = true;
        org.people.name = name.strip();
        org.people.banner = banner == null ? "" : banner;
        ctx.state.addOrg(org);
        ctx.state.touch(NpcBook.LORD, name.strip(), ctx.now);
        ctx.state.join(NpcBook.LORD, org.id, ctx.now);
        events.add(new OrgEvents.Founded(ctx.now, org.id, org.name(), Preset.LORDSHIP, NpcBook.LORD));
        events.addAll(Preset.LORDSHIP.apply(ctx.state, org, NpcBook.LORD, ctx.knobs, ctx.now));
        org.ledger.add(Currency.DIAMOND, diamonds);
        org.ledger.add(Currency.COBBLESTONE, cobblestone);
        LandGraph land = new LandGraph(ctx.state, ctx.knobs);
        events.addAll(kind == NpcBook.Kind.STRONGHOLD ? land.placeKeep(org.id, center, ctx.now)
                : land.plantCamp(org.id, center, ctx.now, CampPhase.ACTIVE));
        events.add(replacement ? new NpcEvents.Replaced(ctx.now, org.id, kind, center) : new NpcEvents.Raised(ctx.now, org.id, kind, center));
        return events;
    }
}
