package charter.gov;

/** How a motion passes, counted over ballots cast (abstentions count toward quorum only). */
public enum VoteMethod {
    /** More yes than no. A tie fails. */
    MAJORITY,
    /** At least two thirds of yes and no ballots are yes. */
    SUPERMAJORITY,
    /** At least one yes and no no. */
    UNANIMOUS;

    public boolean passes(int yes, int no) {
        return switch (this) {
            case MAJORITY -> yes > no;
            case SUPERMAJORITY -> yes > no && yes * 3 >= (yes + no) * 2;
            case UNANIMOUS -> yes > 0 && no == 0;
        };
    }
}
