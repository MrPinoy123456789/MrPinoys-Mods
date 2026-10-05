package pocketdungeons;

/**
 * Dungeon structure W4 (design D20): the one decision "may this player break this
 * block" inside a dungeon cell, as a pure function so a test can pin it without a
 * server.
 *
 * <p>Only three kinds of block ever break inside a cell (the shell is decided
 * before this is asked, and is never breakable):
 * <ul>
 *   <li>a block the player placed themselves (always, with any tool);</li>
 *   <li>a resource node (a position {@link NodeStamper} registered, or a block in
 *       the dungeon's {@code nodePalette}), with the correct tool as before;</li>
 *   <li>a soft mechanic block (an infested or gravel gate, a decorated pot, a
 *       cobweb: the {@code pocketdungeons:dungeon_breakable} tag or a registered
 *       gate position) that a room's puzzle expects the player to break, with the
 *       correct tool as before.</li>
 * </ul>
 * Everything else is unbreakable decoration. An Ordeal's lever and lamp stay
 * unbreakable even when the player placed nothing near them. Creative players
 * bypass the whole rule; that is decided by the caller.
 */
final class BreakRule {

    private BreakRule() {}

    /** What the rule says about one break attempt. */
    enum Verdict {
        /** The player's own block: breaks with any tool. */
        ALLOW_PLACED,
        /** A node or soft mechanic block and the held tool is right for it. */
        ALLOW_NODE,
        /** An Ordeal's lever or lamp. */
        REFUSE_FIXTURE,
        /** Neither a node, a soft mechanic block nor the player's own: decoration. */
        REFUSE_NOT_NODE,
        /** A node or soft mechanic block, but the held tool is wrong. */
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
        if (node || soft) {
            return correctTool ? Verdict.ALLOW_NODE : Verdict.REFUSE_WRONG_TOOL;
        }
        return Verdict.REFUSE_NOT_NODE;
    }

    /** The action bar line for a block that is decoration, not a node. */
    static final String NOT_A_NODE_MESSAGE =
            "Only resource nodes can be mined here. Place a block of your own to take it back.";
}
