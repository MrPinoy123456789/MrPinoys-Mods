package charter.gametest;

import charter.api.PaymentPort;
import charter.id.OrgId;
import charter.mc.CharterRuntime;

import java.util.HashMap;
import java.util.Map;

/** An in-memory payment port for the gametests, installed on the live runtime's state on first use. */
final class GamePort implements PaymentPort {

    private final Map<String, Long> balances = new HashMap<>();

    static GamePort of(CharterRuntime rt) {
        if (rt.state.payment instanceof GamePort port) return port;
        GamePort port = new GamePort();
        rt.state.payment = port;
        return port;
    }

    long marks(OrgId org) {
        return balance(org.toString());
    }

    void fund(OrgId org, long marks) {
        balances.merge(org.toString(), marks, Long::sum);
    }

    @Override
    public long balance(String orgId) {
        return balances.getOrDefault(orgId, 0L);
    }

    @Override
    public boolean charge(String orgId, long marks, String reason) {
        if (balance(orgId) < marks) return false;
        balances.merge(orgId, -marks, Long::sum);
        return true;
    }

    @Override
    public void credit(String orgId, long marks, String reason) {
        balances.merge(orgId, marks, Long::sum);
    }
}
