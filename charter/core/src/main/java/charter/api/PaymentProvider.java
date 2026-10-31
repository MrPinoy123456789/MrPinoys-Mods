package charter.api;

/** What a currency mod implements and registers under the Fabric entrypoint key {@code "charter:payment"}. */
public interface PaymentProvider {
    PaymentPort port();

    /** Called once at server start with Charter's own view of the world. */
    void bind(KingdomView view);
}
