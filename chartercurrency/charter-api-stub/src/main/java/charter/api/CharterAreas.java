package charter.api;

import java.util.Optional;

/**
 * Compile stand-in for Charter's {@code charter.api.CharterAreas}: only the method this mod
 * calls, with the real signature. Never shipped; Charter's own class answers at runtime.
 */
public final class CharterAreas {

    private CharterAreas() {
    }

    /** The area holding this chunk, if any. */
    public static Optional<Area> areaAt(String dimension, int chunkX, int chunkZ) {
        throw new UnsupportedOperationException("stub");
    }
}
