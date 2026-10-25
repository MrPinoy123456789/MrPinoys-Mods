package charter.id;

import java.util.UUID;

/** A permanent grant id. A distinct type so ids of different kinds cannot be mixed up. */
public record GrantId(UUID value) {
    public GrantId {
        if (value == null) throw new IllegalArgumentException("GrantId needs a value");
    }

    @Override
    public String toString() {
        return "grant:" + value;
    }
}
