package charter.contract;

import charter.id.ContractId;
import charter.id.PlayerId;
import charter.treasury.Ledger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every contract, the punishment ledger, arrests in progress, each player's own
 * deposit and breach marks, in one block of {@code CharterState} (M6). Saved
 * with it. Time-aware questions live in {@link Justice}.
 */
public final class ContractBook {

    private final Map<ContractId, Contract> contracts = new LinkedHashMap<>();
    public final PunishmentLedger punishments = new PunishmentLedger();
    private final Map<PlayerId, Custody> custody = new LinkedHashMap<>();
    private final Map<PlayerId, Ledger> vault = new LinkedHashMap<>();
    private final Map<Party, Integer> breachMarks = new HashMap<>();

    public Optional<Contract> get(ContractId id) {
        return Optional.ofNullable(id == null ? null : contracts.get(id));
    }

    public Collection<Contract> all() {
        return Collections.unmodifiableCollection(contracts.values());
    }

    public List<Contract> live() {
        List<Contract> out = new ArrayList<>();
        for (Contract c : contracts.values()) {
            if (c.live()) out.add(c);
        }
        return out;
    }

    public void add(Contract contract) {
        contracts.put(contract.id, contract);
    }

    // ---- custody ----

    public Optional<Custody> custodyOf(PlayerId player) {
        return Optional.ofNullable(custody.get(player));
    }

    public void holdInCustody(Custody held) {
        custody.put(held.prisoner(), held);
    }

    public void releaseCustody(PlayerId player) {
        custody.remove(player);
    }

    public Collection<Custody> custodies() {
        return Collections.unmodifiableCollection(custody.values());
    }

    // ---- a player's own deposit (a fine comes from here and nowhere else) ----

    public Ledger vault(PlayerId player) {
        return vault.computeIfAbsent(player, p -> new Ledger());
    }

    // ---- marks ----

    public int breachMarks(Party party) {
        return breachMarks.getOrDefault(party, 0);
    }

    public void markBreach(Party party) {
        breachMarks.merge(party, 1, Integer::sum);
    }
}
