package charter.mc;

import charter.api.NoPayment;
import charter.api.PaymentProvider;
import charter.api.StateKingdomView;
import charter.state.CharterState;
import net.fabricmc.loader.api.FabricLoader;

import java.util.List;
import java.util.function.Consumer;

/**
 * Finds the payment provider a currency mod registers under the Fabric entrypoint key {@code charter:payment}
 * and installs its port on the state. Charter never names a provider class; with none, or a broken one, every
 * cost is waived ({@link NoPayment}).
 */
public final class PaymentLookup {

    public static final String ENTRYPOINT = "charter:payment";

    private PaymentLookup() {
    }

    public static void install(CharterState state, charter.time.Clock clock, Consumer<String> info, Consumer<String> warn) {
        state.payment = new NoPayment(warn);
        List<PaymentProvider> found;
        try {
            found = FabricLoader.getInstance().getEntrypoints(ENTRYPOINT, PaymentProvider.class);
        } catch (RuntimeException | LinkageError e) {
            warn.accept("Could not read the " + ENTRYPOINT + " entrypoints, so costs are waived: " + e);
            return;
        }
        if (found.isEmpty()) {
            warn.accept("No payment provider is installed: every Charter cost is waived and treasuries are not available.");
            return;
        }
        if (found.size() > 1) warn.accept(found.size() + " payment providers are installed; only the first is used.");
        try {
            PaymentProvider provider = found.get(0);
            provider.bind(new StateKingdomView(state, clock));
            state.payment = provider.port();
            info.accept("Charter pays through " + provider.getClass().getName() + ".");
        } catch (RuntimeException | LinkageError e) {
            state.payment = new NoPayment(warn);
            warn.accept("The payment provider failed to start, so costs are waived: " + e);
        }
    }
}
