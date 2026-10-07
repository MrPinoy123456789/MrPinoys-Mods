package pocketdungeons;

import java.util.UUID;

/**
 * Dungeon structure W5 (party shard balance): the member who commits a side branch pays from
 * their own pack, affordability and the refusal use that member's own balance, and the shared
 * door wall names the cost without showing anyone's balance.
 */
public class SideBranchPayTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    public static void main(String[] args) {
        testPayer();
        testAffordable();
        testShortfall();
        testMessages();
        System.out.println("SideBranchPayTest passed");
    }

    private static void testPayer() {
        check(SideBranchPay.payer(MEMBER, OWNER).equals(MEMBER), "the committing member pays, not the owner");
        check(SideBranchPay.payer(OWNER, OWNER).equals(OWNER), "the owner pays when the owner commits");
        check(SideBranchPay.payer(null, OWNER).equals(OWNER), "no acting player falls back to the owner");
    }

    private static void testAffordable() {
        check(SideBranchPay.affordable(0, 0), "a free edge is always covered");
        check(SideBranchPay.affordable(1, 1), "exactly enough covers it");
        check(SideBranchPay.affordable(1, 5), "more than enough covers it");
        check(!SideBranchPay.affordable(2, 1), "one short is refused");
        // The owner is rich and the member is broke: the member's own balance decides.
        int ownerBalance = 9;
        int memberBalance = 0;
        check(SideBranchPay.affordable(1, ownerBalance), "the owner could afford it");
        check(!SideBranchPay.affordable(1, memberBalance), "the member who commits cannot, whatever the owner carries");
    }

    private static void testShortfall() {
        check(SideBranchPay.shortfall(3, 1) == 2, "3 needed, 1 carried is 2 short");
        check(SideBranchPay.shortfall(1, 4) == 0, "covered is 0 short");
        check(SideBranchPay.shortfall(0, 0) == 0, "free is 0 short");
        check(SideBranchPay.shortfall(2, -3) == 2, "a negative balance reads as none");
    }

    private static void testMessages() {
        String refusal = SideBranchPay.refusal(4, 3);
        check(refusal.equals("Not enough scrap: 4 needed, 3 carried."),
                "the refusal names the need and the balance: " + refusal);
        String screen = SideBranchPay.screenRefusal(2, 0);
        check(screen.equals("2 scrap short"),
                "the screen refusal names the shortfall: " + screen);
        check(SideBranchPay.balanceLine(2, 4).equals("2 scrap. You carry 4."), "the personal line");
        for (String line : new String[]{refusal, screen, SideBranchPay.balanceLine(2, 0)}) {
            check(!line.contains("--") && !line.contains(String.valueOf((char) 0x2014)),
                    "no dash punctuation: " + line);
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
