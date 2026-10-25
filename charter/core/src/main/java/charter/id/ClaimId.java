package charter.id;

import java.util.UUID;

/** A permanent claim id. A distinct type so ids of different kinds cannot be mixed up. */
public record ClaimId(UUID value) {
    public ClaimId {
        if (value == null) throw new IllegalArgumentException("ClaimId needs a value");
    }

    @Override
    public String toString() {
        return "claim:" + value;
    }
}
