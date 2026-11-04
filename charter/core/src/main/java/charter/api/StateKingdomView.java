package charter.api;

import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.Organization;
import charter.state.CharterState;
import charter.grant.AuthorityResolver;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.time.Clock;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Charter's own {@link KingdomView}, read straight from the live state. */
public final class StateKingdomView implements KingdomView {

    private final CharterState state;
    private final Clock clock;

    /** A view whose spend answers use the wall clock for grant expiry. */
    public StateKingdomView(CharterState state) {
        this(state, System::currentTimeMillis);
    }

    public StateKingdomView(CharterState state, Clock clock) {
        this.state = state;
        this.clock = clock;
    }

    /** The id string a provider sees for an organization. */
    public static String idOf(OrgId org) {
        return org.toString();
    }

    private Optional<Organization> find(String orgId) {
        if (orgId == null || !orgId.startsWith("org:")) return Optional.empty();
        try {
            return state.org(new OrgId(UUID.fromString(orgId.substring(4))));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> kingdomOf(UUID player) {
        return state.membership(new PlayerId(player)).map(m -> idOf(state.root(m.org())));
    }

    @Override
    public Optional<UUID> lordOf(String orgId) {
        Optional<Organization> org = find(orgId);
        if (org.isEmpty() || org.get().governance.deciders == null) return Optional.empty();
        return state.office(org.get().governance.deciders)
                .filter(office -> !office.holders().isEmpty())
                .map(office -> office.holders().get(0).player().value());
    }

    @Override
    public java.util.List<UUID> lordsOf(String orgId) {
        Optional<Organization> org = find(orgId);
        if (org.isEmpty() || org.get().governance.deciders == null) return java.util.List.of();
        return state.office(org.get().governance.deciders)
                .map(office -> office.holders().stream().map(seat -> seat.player().value()).toList())
                .orElse(java.util.List.of());
    }

    @Override
    public boolean maySpend(String orgId, UUID player, long marks) {
        Optional<Organization> org = find(orgId);
        if (org.isEmpty() || player == null || marks <= 0) return false;
        return new AuthorityResolver(state, clock.now())
                .resolve(new Who.AsPlayer(new PlayerId(player)), org.get().id, Power.SPEND, ScopeNeed.spend(marks), false)
                .ok();
    }

    @Override
    public String displayName(String orgId) {
        return find(orgId).map(Organization::name).orElse("");
    }

    @Override
    public Collection<String> orgIds() {
        return state.orgs().stream().map(o -> idOf(o.id)).toList();
    }
}
