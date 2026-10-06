package pocketdungeons;

/**
 * Dungeon structure W4 (design D20): the one decision "may this player break this
 * block" inside a dungeon cell, as a pure function so a test can pin it without a
 * server.
 *
 * <p>Everything inside a cell breaks with the correct tool (the shell is decided
 * before this is asked, and is never breakable):
 * <ul>
 *   <li>a block the player placed themselves (always, with any tool);</li>
 *   <li>any interior block: a resource node, a soft mechanic block (the
 *       {@code pocketdungeons:dungeon_breakable} tag or a registered gate
 *       position) or plain furnishing, all with the correct tool as before.</li>
 * </ul>
 * An Ordeal's lever and lamp stay unbreakable even when the player placed
 * nothing near them. Creative players bypass the whole rule; that is decided
 * by the caller. Revised 2026-10-05 (playtest 2026-10-05-1): interior
 * furnishings mineable again; the cell shell still walls the run in.
 */
final class BreakRule {

    private BreakRule() {}

    /** What the rule says about one break attempt. */
    enum Verdict {
        /** The player's own block: breaks with any tool. */
        ALLOW_PLACED,
        /** An interior block and the held tool is right for it. */
        ALLOW_NODE,
        /** An Ordeal's lever or lamp. */
        REFUSE_FIXTURE,
        /** An interior block, but the held tool is wrong. */
        REFUSE_WRONG_TOOL;

        boolean allowed() {
            return this == ALLOW_PLACED || this == ALLOW_NODE;
        }
    }

    /**
     * @param fixture      an Ordeal fixture ({@link Ordeals#isFixture})
     * @param playerPlaced placed by this very player
     * @param node         a registered resource node position
     * @param soft         a soft mechanic block (tag or registered gate position)
     * @param correctTool  the held tool is correct for the block
     *                     ({@link DungeonTools#isCorrectTool})
     */
    static Verdict decide(boolean fixture, boolean playerPlaced, boolean node, boolean soft,
                          boolean correctTool) {
        if (fixture) {
            return Verdict.REFUSE_FIXTURE;
        }
        if (playerPlaced) {
            return Verdict.ALLOW_PLACED;
        }
        return correctTool ? Verdict.ALLOW_NODE : Verdict.REFUSE_WRONG_TOOL;
    }
}
