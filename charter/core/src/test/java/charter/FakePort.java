package charter;

import charter.api.PaymentPort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** An in-memory payment port for tests: real balances, and a record of every call made. */
public final class FakePort implements PaymentPort {

    public record Call(String kind, String org, long marks, String reason, boolean ok) {
    }

    public final Map<String, Long> balances = new HashMap<>();
    public final Map<java.util.UUID, Long> wallets = new HashMap<>();
    public final List<Call> calls = new ArrayList<>();

    @Override
    public long balance(String orgId) {
        return balances.getOrDefault(orgId, 0L);
    }

    @Override
    public boolean charge(String orgId, long marks, String reason) {
        boolean ok = balance(orgId) >= marks;
        if (ok) balances.merge(orgId, -marks, Long::sum);
        calls.add(new Call("charge", orgId, marks, reason, ok));
        return ok;
    }

    @Override
    public void credit(String orgId, long marks, String reason) {
        balances.merge(orgId, marks, Long::sum);
        calls.add(new Call("credit", orgId, marks, reason, true));
    }

    @Override
    public long balanceOfPlayer(java.util.UUID player) {
        return wallets.getOrDefault(player, 0L);
    }

    @Override
    public boolean chargePlayer(java.util.UUID player, long marks, String reason) {
        boolean ok = balanceOfPlayer(player) >= marks;
        if (ok) wallets.merge(player, -marks, Long::sum);
        calls.add(new Call("chargePlayer", player.toString(), marks, reason, ok));
        return ok;
    }

    /** The calls of one kind ("charge" or "credit") for one reason, in order. */
    public List<Call> calls(String kind, String reason) {
        return calls.stream().filter(c -> c.kind().equals(kind) && c.reason().equals(reason)).toList();
    }
}
