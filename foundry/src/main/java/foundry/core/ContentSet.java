package foundry.core;

import java.util.List;
import java.util.Map;

/**
 * A fully validated, immutable snapshot of all content. Reload swaps one of these in atomically.
 * {@code sources} maps every accepted definition id to the file that defined it (used by the orphan ledger).
 */
public record ContentSet(Map<Ident, ItemDef> items, Map<Ident, RecipeDef> recipes, List<Problem> problems,
        Map<Ident, String> sources) {
    public boolean hasErrors() {
        return problems.stream().anyMatch(Problem::blocking);
    }
}
