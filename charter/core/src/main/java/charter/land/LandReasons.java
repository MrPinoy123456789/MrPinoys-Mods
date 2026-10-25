package charter.land;

import charter.engine.Reason;

/** Refusals for land actions. */
public final class LandReasons {

    private LandReasons() {
    }

    public static final Reason WRONG_DIMENSION = new Reason("wrong_dimension", "Land cannot be claimed in this dimension.");
    public static final Reason CLAIMED = new Reason("claimed", "That chunk is already inside a claim.");
    public static final Reason KEEP_NEEDS_ROOM = new Reason("keep_needs_room", "A keep needs a free 3 by 3 block of chunks, and part of that is already claimed.");
    public static final Reason KEEP_GAP = new Reason("keep_gap", "That is too close to another faction's keep.");
    public static final Reason NOT_ADJACENT = new Reason("not_adjacent", "A claim must share an edge with land your faction already holds.");
    public static final Reason OUT_OF_RANGE = new Reason("out_of_range", "That chunk is too far from any of your keeps or camps.");
    public static final Reason CORE_FULL = new Reason("core_full", "The keeps and camps in reach of that chunk cannot support any more land.");
    public static final Reason MEMBER_CAP = new Reason("member_cap", "Your faction holds as much land as its active members allow.");
    public static final Reason NO_FUNDS = new Reason("no_funds", "The treasury cannot pay for that.");
    public static final Reason NOT_YOUR_CLAIM = new Reason("not_your_claim", "That chunk is not yours.");
    public static final Reason PART_OF_CORE = new Reason("part_of_core", "That chunk is part of a keep or camp and cannot be given up on its own.");
    public static final Reason NO_SUCH_CORE = new Reason("no_such_core", "That keep or camp no longer exists.");
    public static final Reason OPERATOR_ONLY = new Reason("operator_only", "Only the rules or an operator can do that.");
}
