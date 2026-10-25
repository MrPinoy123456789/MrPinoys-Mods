package charter.id;

import java.util.UUID;

/** A permanent player id. A distinct type so ids of different kinds cannot be mixed up. */
public record PlayerId(UUID value) {
    public PlayerId {
        if (value == null) throw new IllegalArgumentException("PlayerId needs a value");
    }

    @Override
    public String toString() {
        return "player:" + value;
    }
}
