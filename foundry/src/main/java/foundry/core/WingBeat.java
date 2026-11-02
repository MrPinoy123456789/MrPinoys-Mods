package foundry.core;

/**
 * How far a pair of flapping wings is folded back at a given moment, in degrees about the spine: positive folds the tips
 * further back, negative opens them forward. Pure, so it can be tested.
 *
 * <ul>
 *   <li>standing still: a slow breathing beat, about two and a half seconds a stroke;</li>
 *   <li>on the move: a livelier beat;</li>
 *   <li>in the air (jumping or falling): a fast, deep flutter;</li>
 *   <li>gliding: spread wide and held, with only a quiver.</li>
 * </ul>
 */
public final class WingBeat {
    public enum State { STILL, MOVING, AIRBORNE, GLIDING }

    private WingBeat() {}

    /** The fold for this tick; {@code phase} offsets each player so two Fairies never beat in step. */
    public static float fold(State state, long tick, int phase) {
        double t = tick + (phase & 0xff);
        return switch (state) {
            case STILL -> (float) (6 + 9 * Math.sin(t * 0.12));
            case MOVING -> (float) (4 + 14 * Math.sin(t * 0.25));
            case AIRBORNE -> (float) (2 + 26 * Math.sin(t * 0.55));
            case GLIDING -> (float) (-14 + 3 * Math.sin(t * 0.9));
        };
    }

    /** Which beat a player is in. */
    public static State state(boolean gliding, boolean onGround, double horizontalSpeed) {
        if (gliding) return State.GLIDING;
        if (!onGround) return State.AIRBORNE;
        return horizontalSpeed > 0.05 ? State.MOVING : State.STILL;
    }
}
