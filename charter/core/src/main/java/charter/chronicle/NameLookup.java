package charter.chronicle;

import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.state.CharterState;

/**
 * Where the renderer gets names. Lookups never return null: an id nobody knows
 * gets a plain stand-in, so a Chronicle line never breaks on old history.
 */
public interface NameLookup {
    String org(OrgId id);

    String player(PlayerId id);

    String office(OfficeId id);

    /** Names read from the live state, including dissolved organizations. */
    static NameLookup from(CharterState state) {
        return new NameLookup() {
            @Override
            public String org(OrgId id) {
                return state.orgIncludingDissolved(id).map(o -> o.name()).orElse("an unknown organization");
            }

            @Override
            public String player(PlayerId id) {
                return state.knownPlayer(id).map(p -> p.name).filter(n -> !n.isEmpty()).orElse("someone");
            }

            @Override
            public String office(OfficeId id) {
                return state.allOffices().stream().filter(o -> o.id.equals(id)).findFirst().map(o -> o.name).orElse("an unknown office");
            }
        };
    }
}
