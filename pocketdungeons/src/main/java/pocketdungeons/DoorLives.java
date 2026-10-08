package pocketdungeons;

/**
 * What a side door costs and how it is worded: a life, not scrap. Lives are shared by the
 * party (the trip's omen), so a side door is a party decision; the last life is never for
 * sale. Pure Java so a headless test can pin the rule and every string.
 */
final class DoorLives {

    private DoorLives() {}

    /** The most lives a door may cost. */
    static final int MAX_LIVES = 2;

    /** Whether a party with {@code livesLeft} can pay a door costing {@code lives}: at least one must remain. */
    static boolean affordable(int livesLeft, int lives) {
        return lives <= 0 || livesLeft > lives;
    }

    /** {@code "Costs 1 life"} or {@code "Costs 2 lives"}: the door board's price. */
    static String costText(int lives) {
        return "Costs " + lives + (lives == 1 ? " life" : " lives");
    }

    /** The door board's price when it cannot be paid. */
    static String screenRefusal(int livesLeft, int lives) {
        return costText(lives) + ". Lives " + livesLeft + ": none to spare.";
    }

    /** The chat refusal at the lever. */
    static String refusal(int livesLeft, int lives) {
        return "This door costs " + lives + (lives == 1 ? " life" : " lives") + " and you have " + livesLeft
                + " left. The last life is never for sale.";
    }

    /** The party line when a door takes its life. */
    static String paidLine(String name, int livesLeft) {
        return name + " paid a life for the side door. Lives " + livesLeft + ".";
    }

    /** The on-screen line when a door takes its life. */
    static String takenLine(int livesLeft) {
        return "The door takes a life. Lives " + livesLeft + ".";
    }
}
