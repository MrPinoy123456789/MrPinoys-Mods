package charter.id;

import java.util.UUID;

/** A permanent contract id. A distinct type so ids of different kinds cannot be mixed up. */
public record ContractId(UUID value) {
    public ContractId {
        if (value == null) throw new IllegalArgumentException("ContractId needs a value");
    }

    @Override
    public String toString() {
        return "contract:" + value;
    }
}
