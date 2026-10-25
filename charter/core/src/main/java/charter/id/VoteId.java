package charter.id;

import java.util.UUID;

/** A permanent vote id. A distinct type so ids of different kinds cannot be mixed up. */
public record VoteId(UUID value) {
    public VoteId {
        if (value == null) throw new IllegalArgumentException("VoteId needs a value");
    }

    @Override
    public String toString() {
        return "vote:" + value;
    }
}
