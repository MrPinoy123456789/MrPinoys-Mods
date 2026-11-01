package foundry.core;

import java.util.UUID;

/** Floodgate gives Bedrock players a UUID whose high half is zero; Java accounts never have one. */
public final class Floodgate {
    private Floodgate() {}

    public static boolean isBedrock(UUID id) {
        return id.getMostSignificantBits() == 0L;
    }
}
