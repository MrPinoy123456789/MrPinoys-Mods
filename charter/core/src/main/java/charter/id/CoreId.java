package charter.id;

import java.util.UUID;

/** A permanent core id. A distinct type so ids of different kinds cannot be mixed up. */
public record CoreId(UUID value) {
    public CoreId {
        if (value == null) throw new IllegalArgumentException("CoreId needs a value");
    }

    @Override
    public String toString() {
        return "core:" + value;
    }
}
