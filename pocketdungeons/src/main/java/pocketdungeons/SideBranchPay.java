package pocketdungeons;

import java.util.UUID;

/**
 * Dungeon structure W5 (party shard balance, the W3 gap): who pays for a side branch and
 * what each player is told about it. The member who commits the door (pulls the lever) pays
 * from their own pack; the party owner's balance is never read on a member's behalf. The door
 * wall is shared text, so it names the cost only; a player's own balance appears in the
 * messages addressed to that player. Pure Java so a headless test can pin it.
 */
final class SideBranchPay {

    private SideBranchPay() {}

    /**
     * Who pays: the member who commits. The owner is only the fallback for a commit with no
     * acting player (an operator command), never the answer for a member who pulled the lever.
     */
    static UUID payer(UUID committer, UUID owner) {
        return committer != null ? committer : owner;
    }

    /** Whether a pack holding {@code carried} shards covers {@code cost}. A free edge is always covered. */
    static boolean affordable(int cost, int carried) {
        return cost <= 0 || carried >= cost;
    }

    /** How many shards short a pack is; 0 when covered. */
    static int shortfall(int cost, int carried) {
        return cost <= 0 ? 0 : Math.max(0, cost - Math.max(0, carried));
    }

    /** What the shared door wall says about a side branch: the cost and who pays, no balance. */
    static String wallLine(int cost) {
        return "Side branch: " + shards(cost) + ", paid from the pack of whoever pulls the lever";
    }

    /** What the acting player is told about their own balance when they pick a side branch door. */
    static String balanceLine(int cost, int carried) {
        return "Side branch: " + shards(cost) + ". You carry " + carried + ".";
    }

    /** The refusal for a player who cannot cover the cost, naming the shortfall. */
    static String refusal(int cost, int carried) {
        int missing = shortfall(cost, carried);
        return "Not enough echo shards: this side branch needs " + cost + ", you carry " + carried
                + " (" + missing + " short).";
    }

    /** The short refusal for the door screen, naming the shortfall. */
    static String screenRefusal(int cost, int carried) {
        return "Side branch: needs " + shards(cost) + ", you are " + shortfall(cost, carried) + " short";
    }

    private static String shards(int n) {
        return n + (n == 1 ? " echo shard" : " echo shards");
    }
}
