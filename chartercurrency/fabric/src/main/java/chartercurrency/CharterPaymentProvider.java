package chartercurrency;

import charter.api.KingdomView;
import charter.api.PaymentPort;
import charter.api.PaymentProvider;

/**
 * What Charter finds under the Fabric entrypoint key {@code charter:payment}.
 *
 * <p>Charter owns the rights (who may spend, how much without a vote); this mod owns the
 * money. Every organization's balance is its treasury in Marks, so a Charter charge and
 * a member's {@code /treasury deposit} move the same number. Charter looks the provider
 * up itself, so this class is only loaded on a server that has Charter, and a server
 * without Charter never touches {@code charter.api}.
 */
public final class CharterPaymentProvider implements PaymentProvider {

    private final PaymentPort port = new PaymentPort() {
        @Override
        public long balance(String orgId) {
            Services s = Services.current;
            return s == null ? 0L : s.treasury.balance(orgId);
        }

        @Override
        public boolean charge(String orgId, long marks, String reason) {
            Services s = Services.current;
            if (s == null || marks <= 0) return false;
            boolean ok = s.treasury.charge(orgId, marks, "charter", "Charter", reason);
            if (ok) s.log.tx("Charter charged " + marks + " Marks to the " + orgId + " treasury: " + reason);
            return ok;
        }

        @Override
        public void credit(String orgId, long marks, String reason) {
            Services s = Services.current;
            if (s == null || marks <= 0) return;
            if (s.treasury.credit(orgId, marks, "charter", "Charter", reason)) {
                s.log.tx("Charter credited " + marks + " Marks to the " + orgId + " treasury: " + reason);
            }
        }
    };

    @Override
    public PaymentPort port() {
        return port;
    }

    @Override
    public void bind(KingdomView view) {
        Services s = Services.current;
        if (s == null) {
            CharterCurrencyMod.LOG.warn("Charter bound its kingdom view before Charter Currency was ready");
            return;
        }
        s.kingdoms.use(new CharterKingdomSource(view), "Charter");
        CharterCurrencyMod.LOG.info("Kingdoms, members and Lords now come from Charter "
                + "(kingdoms.json is ignored)");
    }
}
