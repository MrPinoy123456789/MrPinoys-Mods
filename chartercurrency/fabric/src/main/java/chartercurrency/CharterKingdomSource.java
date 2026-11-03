package chartercurrency;

import chartercurrency.core.KingdomSource;
import charter.api.KingdomView;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * A thin wrapper over the {@link KingdomView} Charter binds this mod with. Charter is
 * the source of truth: nothing is cached here, so a change in Charter is seen on the
 * next question.
 *
 * <p>Only loaded once Charter hands over its view (see {@link CharterPaymentProvider}),
 * so a server without Charter never touches {@code charter.api}.
 */
final class CharterKingdomSource implements KingdomSource {

    private final KingdomView view;

    CharterKingdomSource(KingdomView view) {
        this.view = view;
    }

    @Override
    public Optional<String> kingdomOf(UUID player) {
        return view.kingdomOf(player);
    }

    @Override
    public Optional<UUID> lordOf(String kingdomId) {
        return view.lordOf(kingdomId);
    }

    @Override
    public java.util.List<UUID> lordsOf(String kingdomId) {
        try {
            return view.lordsOf(kingdomId);
        } catch (NoSuchMethodError older) {
            // A Charter jar from before two Lords: it knows only the one.
            return view.lordOf(kingdomId).map(java.util.List::of).orElse(java.util.List.of());
        }
    }

    @Override
    public String displayName(String kingdomId) {
        String name = view.displayName(kingdomId);
        return name == null || name.isBlank() ? String.valueOf(kingdomId) : name;
    }

    @Override
    public Collection<String> kingdomIds() {
        return view.orgIds();
    }

    @Override
    public boolean knowsLand() {
        try {
            charter.api.CharterAreas.class.getName();
            return true;
        } catch (LinkageError older) {
            // A Charter jar from before the public land map.
            return false;
        }
    }

    /**
     * The kingdom holding a chunk, from Charter's public land map. The map names a kingdom,
     * not its id, so the name is matched back to an id here.
     */
    @Override
    public Optional<String> landOwner(String dimension, int chunkX, int chunkZ) {
        Optional<charter.api.Area> area;
        try {
            area = charter.api.CharterAreas.areaAt(dimension, chunkX, chunkZ);
        } catch (LinkageError older) {
            return Optional.empty();
        }
        if (area.isEmpty()) return Optional.empty();
        String name = area.get().kingdom();
        for (String id : view.orgIds()) {
            if (name.equals(view.displayName(id))) return Optional.of(id);
        }
        return Optional.empty();
    }
}
