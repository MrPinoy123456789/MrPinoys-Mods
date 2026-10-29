package charter.chronicle;

import charter.id.CoreId;
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

    /** "the keep of Ashfall": a core is named by its kind and owner. Default for lookups that do not know cores. */
    default String core(CoreId id) {
        return "a keep";
    }

    /** True when the core belongs to a faction of the raiders, even a core that has since fallen. */
    default boolean isNpcCore(CoreId id) {
        return false;
    }

    /** True for a faction of the raiders; the Chronicle tells the players' story, not theirs. Default for lookups that do not know. */
    default boolean isNpc(OrgId id) {
        return false;
    }

    /** Names read from the live state, including dissolved organizations. */
    static NameLookup from(CharterState state) {
        return new NameLookup() {
            @Override
            public boolean isNpc(OrgId id) {
                return state.orgIncludingDissolved(id).map(o -> o.npc).orElse(false);
            }

            @Override
            public String org(OrgId id) {
                return state.orgIncludingDissolved(id).map(o -> o.name()).orElse("an unknown organization");
            }

            @Override
            public String player(PlayerId id) {
                return state.knownPlayer(id).map(p -> p.name).filter(n -> !n.isEmpty()).orElse("someone");
            }

            private java.util.Map<CoreId, OrgId> owners;

            @Override
            public boolean isNpcCore(CoreId id) {
                var live = state.core(id);
                if (live.isPresent()) return isNpc(live.get().owner);
                if (owners == null) {
                    owners = new java.util.HashMap<>();
                    for (var entry : state.history.entries()) {
                        if (entry.event() instanceof charter.land.LandEvents.NexusPlaced e) owners.put(e.core(), e.org());
                        else if (entry.event() instanceof charter.land.LandEvents.CampPlanted e) owners.put(e.core(), e.org());
                    }
                }
                OrgId owner = owners.get(id);
                return owner != null && isNpc(owner);
            }

            /** Cores that are gone (razed, lost) are found again through the history that placed them. */
            private java.util.Map<CoreId, String> placed;

            @Override
            public String core(CoreId id) {
                var live = state.core(id);
                if (live.isPresent()) return kind(live.get().kind) + " of " + org(live.get().owner);
                if (placed == null) placed = history(state);
                return placed.getOrDefault(id, "a lost keep");
            }

            private String kind(charter.land.CoreKind kind) {
                return kind == charter.land.CoreKind.KEEP ? "the keep" : "the camp";
            }

            private java.util.Map<CoreId, String> history(CharterState s) {
                java.util.Map<CoreId, String> out = new java.util.HashMap<>();
                for (var entry : s.history.entries()) {
                    if (entry.event() instanceof charter.land.LandEvents.NexusPlaced e) out.put(e.core(), "the keep of " + org(e.org()));
                    else if (entry.event() instanceof charter.land.LandEvents.CampPlanted e) out.put(e.core(), "the outpost of " + org(e.org()));
                }
                return out;
            }

            @Override
            public String office(OfficeId id) {
                return state.allOffices().stream().filter(o -> o.id.equals(id)).findFirst().map(o -> o.name).orElse("an unknown office");
            }
        };
    }
}
