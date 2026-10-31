package charter.api;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The port Charter uses when no provider is installed: balance is 0, a charge succeeds because the cost
 * is waived, a credit does nothing. The first waiver of each reason (the action kind) is reported once
 * to the warning sink, so an operator sees what is free without a line per action.
 */
public final class NoPayment implements PaymentPort {

    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final Consumer<String> warn;

    public NoPayment(Consumer<String> warn) {
        this.warn = warn;
    }

    public NoPayment() {
        this(message -> java.util.logging.Logger.getLogger("charter").warning(message));
    }

    @Override
    public long balance(String orgId) {
        return 0;
    }

    @Override
    public boolean charge(String orgId, long marks, String reason) {
        if (marks > 0 && warned.add(reason)) {
            warn.accept("No payment provider is installed, so the cost of '" + reason + "' (" + marks + " Marks) is waived.");
        }
        return true;
    }

    @Override
    public void credit(String orgId, long marks, String reason) {
    }
}
