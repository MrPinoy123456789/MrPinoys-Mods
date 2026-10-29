package foundry.core;

import java.util.List;
import java.util.Map;

/** A fully validated, immutable snapshot of all content. Reload swaps one of these in atomically. */
public record ContentSet(Map<Ident, ItemDef> items, List<Problem> problems) {
    public boolean hasErrors() {
        return problems.stream().anyMatch(Problem::blocking);
    }
}
