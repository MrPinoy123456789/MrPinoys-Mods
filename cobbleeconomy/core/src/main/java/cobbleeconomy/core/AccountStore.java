package cobbleeconomy.core;

import java.util.Map;
import java.util.UUID;

/**
 * Durable storage for balances. The economy owns the numbers; this owns the disk.
 *
 * <p>An account is now a map of currency id to balance, written whole. Writing the
 * whole account rather than one currency keeps the file format and this interface
 * from growing a currency dimension they would otherwise have to coordinate on.
 */
public interface AccountStore {

    /** Read every account at boot: player, then currency id, then balance. */
    Map<UUID, Map<String, Long>> load();

    /** Record one committed account state. Must be cheap and non-blocking. */
    void put(UUID player, Map<String, Long> balances);

    /** Block until everything recorded so far is on disk. */
    void flushNow();

    /** A store that forgets everything. Used by the test suite. */
    static AccountStore inMemory() {
        return new AccountStore() {
            @Override public Map<UUID, Map<String, Long>> load() { return Map.of(); }
            @Override public void put(UUID player, Map<String, Long> balances) { }
            @Override public void flushNow() { }
        };
    }
}
