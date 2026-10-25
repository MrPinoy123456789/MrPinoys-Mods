package charter.id;

import java.util.UUID;

/** A permanent office id. A distinct type so ids of different kinds cannot be mixed up. */
public record OfficeId(UUID value) {
    public OfficeId {
        if (value == null) throw new IllegalArgumentException("OfficeId needs a value");
    }

    @Override
    public String toString() {
        return "office:" + value;
    }
}
