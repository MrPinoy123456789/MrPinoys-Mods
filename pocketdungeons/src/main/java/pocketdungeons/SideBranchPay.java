package pocketdungeons;

import java.util.UUID;

/**
 * Dungeon structure W5 (party scrap balance, the W3 gap): who pays for a side branch and
 * what each player is told about it. The member who commits the door (pulls the lever) pays
 * from their own scrap pool; the party owner's balance is never read on a member's behalf.
 * The door wall is shared text, so it names the cost only; a player's own balance appears in
 * the messages addressed to that player. Pure Java so a headless test can pin it.
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

    /** Whether a pool holding {@code carried} scrap covers {@code cost}. A free edge is always covered. */
    static boolean affordable(int cost, int carried) {
        return cost <= 0 || carried >= cost;
    }

    /** How much scrap short a pool is; 0 when covered. */
    static int shortfall(int cost, int carried) {
        return cost <= 0 ? 0 : Math.max(0, cost - Math.max(0, carried));
    }

    /** What the acting player is told about their own balance when they pick a costing door. */
    static String balanceLine(int cost, int carried) {
        return cost + " scrap. You carry " + carried + ".";
    }

    /** The refusal for a player who cannot cover the cost (design J1's wording). */
    static String refusal(int cost, int carried) {
        return ScrapMath.notEnough(cost, carried);
    }

    /** The short refusal for the door screen, naming the shortfall. */
    static String screenRefusal(int cost, int carried) {
        return shortfall(cost, carried) + " scrap short";
    }
}
