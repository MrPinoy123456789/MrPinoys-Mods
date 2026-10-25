package charter.id;

import java.util.UUID;

/** A permanent org id. A distinct type so ids of different kinds cannot be mixed up. */
public record OrgId(UUID value) {
    public OrgId {
        if (value == null) throw new IllegalArgumentException("OrgId needs a value");
    }

    @Override
    public String toString() {
        return "org:" + value;
    }
}
