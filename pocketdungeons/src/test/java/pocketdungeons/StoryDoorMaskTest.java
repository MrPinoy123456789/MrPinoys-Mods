package pocketdungeons;

/**
 * Vertical floors phase 2: door masks carry one nibble per story. A one story mask must behave
 * exactly as it always did; a stair room's mask rotates both nibbles together.
 *
 * <p>Pure JDK.
 */
public class StoryDoorMaskTest {

    public static void main(String[] args) {
        oneStoryMasksAreUnchanged();
        lowerStoryRotatesWithTheTop();
        helpersRoundTrip();
        lettersShowTheLowerStory();
        System.out.println("StoryDoorMaskTest passed");
    }

    private static void oneStoryMasksAreUnchanged() {
        for (int mask = 0; mask <= 15; mask++) {
            for (int q = -5; q <= 9; q++) {
                int old = oldRotate(mask, q);
                check(DoorMask.rotateClockwise(mask, q) == old, "flat mask " + mask + " turn " + q);
            }
            check(DoorMask.toLetters(mask).equals(oldLetters(mask)), "flat letters " + mask);
            check(!DoorMask.hasLowerStory(mask), "flat has no lower story " + mask);
        }
    }

    private static void lowerStoryRotatesWithTheTop() {
        // In on the west, out on the east one story down.
        int straight = DoorMask.edge(DoorMask.Direction.WEST, 0) | DoorMask.edge(DoorMask.Direction.EAST, 1);
        check(straight == (DoorMask.WEST | (DoorMask.EAST << 4)), "straight mask value");
        int turned = DoorMask.rotateClockwise(straight, 1);
        check(DoorMask.story(turned, 0) == DoorMask.NORTH, "top west turns to north: " + turned);
        check(DoorMask.story(turned, 1) == DoorMask.SOUTH, "lower east turns to south: " + turned);
        check(DoorMask.rotateClockwise(straight, 4) == straight, "four turns come home");
        check(DoorMask.rotateClockwise(turned, 3) == straight, "1 + 3 turns come home");
        // A lower nibble never leaks into the top one.
        for (int q = 0; q < 4; q++) {
            int r = DoorMask.rotateClockwise(straight, q);
            check(Integer.bitCount(DoorMask.story(r, 0)) == 1 && Integer.bitCount(DoorMask.story(r, 1)) == 1,
                    "one door per story after turn " + q);
            check((r & ~DoorMask.ALL_BITS) == 0, "no stray bits");
        }
    }

    private static void helpersRoundTrip() {
        for (DoorMask.Direction d : DoorMask.Direction.values()) {
            check(DoorMask.edge(d, 0) == DoorMask.fromEdges(java.util.EnumSet.of(d)), "slot 0 edge " + d);
            check(DoorMask.story(DoorMask.edge(d, 1), 1) == DoorMask.edge(d, 0), "slot 1 edge " + d);
            check(DoorMask.hasLowerStory(DoorMask.edge(d, 1)), "lower story seen " + d);
        }
    }

    private static void lettersShowTheLowerStory() {
        int straight = DoorMask.edge(DoorMask.Direction.WEST, 0) | DoorMask.edge(DoorMask.Direction.EAST, 1);
        check(DoorMask.toLetters(straight).equals("W/E"), "letters: " + DoorMask.toLetters(straight));
    }

    private static int oldRotate(int mask, int quarterTurns) {
        int q = ((quarterTurns % 4) + 4) % 4;
        if (q == 0) return mask & 0xF;
        return ((mask << q) | (mask >>> (4 - q))) & 0xF;
    }

    private static String oldLetters(int mask) {
        StringBuilder sb = new StringBuilder();
        if ((mask & 1) != 0) sb.append('N');
        if ((mask & 2) != 0) sb.append('E');
        if ((mask & 4) != 0) sb.append('S');
        if ((mask & 8) != 0) sb.append('W');
        return sb.isEmpty() ? "none" : sb.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
