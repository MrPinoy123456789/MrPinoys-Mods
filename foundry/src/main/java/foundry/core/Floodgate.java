package foundry.core;

import java.util.UUID;

/** Floodgate gives Bedrock players a UUID whose high half is zero; Java accounts never have one. */
public final class Floodgate {
    private Floodgate() {}

    /** Players a gametest treats as Bedrock (mock players always get Java ids). Empty on a real server. */
    private static final java.util.Set<UUID> TREATED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean isBedrock(UUID id) {
        return id.getMostSignificantBits() == 0L || (!TREATED.isEmpty() && TREATED.contains(id));
    }

    /** For gametests only: treat this player as a Bedrock player, or stop. */
    public static void treatAsBedrock(UUID id, boolean on) {
        if (on) TREATED.add(id);
        else TREATED.remove(id);
    }
}
