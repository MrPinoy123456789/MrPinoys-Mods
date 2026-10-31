package charter.mc;

import charter.api.PaymentPort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** An in-memory payment port for the fabric tests: real balances and a record of every call. */
public final class TestPort implements PaymentPort {

    public final Map<String, Long> balances = new HashMap<>();
    public final List<String> calls = new ArrayList<>();

    @Override
    public long balance(String orgId) {
        return balances.getOrDefault(orgId, 0L);
    }

    @Override
    public boolean charge(String orgId, long marks, String reason) {
        boolean ok = balance(orgId) >= marks;
        if (ok) balances.merge(orgId, -marks, Long::sum);
        calls.add("charge " + reason + " " + marks + " " + ok);
        return ok;
    }

    @Override
    public void credit(String orgId, long marks, String reason) {
        balances.merge(orgId, marks, Long::sum);
        calls.add("credit " + reason + " " + marks);
    }
}
