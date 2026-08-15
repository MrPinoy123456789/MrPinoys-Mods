package kamutotems.core;

import java.util.Set;

/** What a finished quest pays. NEVER a kamu -- see PLAN_V2.md section 1. */
public record QuestReward(
        String kind,        // "diamond" | "cobblestone" | "sigil" | "item"
        String itemId,      // for kind "item"; null otherwise
        int amount,         // count, or sigil tier for kind "sigil"
        String note) {      // optional player-facing line

    private static final Set<String> ALLOWED_KINDS = Set.of("diamond", "cobblestone", "sigil", "item");

    /** Guard: rejects any reward that would hand over a kamu. */
    public boolean isLegal() {
        if (kind == null || !ALLOWED_KINDS.contains(kind)) {
            return false;
        }
        if (amount <= 0) {
            return false;
        }
        if ("item".equals(kind)) {
            if (itemId == null || itemId.isEmpty()) {
                return false;
            }
            // A quest definition may only ever hand over a real item id. A
            // kamu component id (e.g. "bolt", "fire") smuggled in through the
            // "item" kind is the one loophole this record must close: kamu
            // come only from bosses, never from quest rewards.
            if (KamuCatalog.defaults().get(itemId) != null) {
                return false;
            }
        } else if (itemId != null) {
            // diamond / cobblestone / sigil never carry an item id.
            return false;
        }
        return true;
    }
}
