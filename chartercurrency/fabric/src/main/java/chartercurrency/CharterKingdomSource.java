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
    public String displayName(String kingdomId) {
        String name = view.displayName(kingdomId);
        return name == null || name.isBlank() ? String.valueOf(kingdomId) : name;
    }

    @Override
    public Collection<String> kingdomIds() {
        return view.orgIds();
    }
}
