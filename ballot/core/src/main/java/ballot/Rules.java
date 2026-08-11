package ballot;

/**
 * Per-poll switches. Every one of these was a design argument, so each carries the
 * reason it defaults the way it does.
 *
 * @param kindChosen      false until someone picks. A new poll cannot open without it.
 * @param claimable       true turns on the plot workflow: entries are claimed, named
 *                        and built by players. False means the organiser writes the
 *                        options and there is nothing physical to claim.
 * @param allowVoteChange a two-week ballot is a long time to be locked into a day-two
 *                        opinion. Late swinging is a real cost but a small one.
 * @param blockSelfVote   without it, a claimable poll is a test of who has friends
 *                        rather than who built well.
 * @param voteFromChat    false forces people to walk to the stations, which is the
 *                        point of a gallery. Defaults false when claimable, true
 *                        otherwise, since ownerless options have nothing to walk to.
 */
public record Rules(
        boolean kindChosen,
        boolean claimable,
        boolean allowVoteChange,
        boolean blockSelfVote,
        boolean voteFromChat,
        int maxVotesPerPlayer
) {
    public static Rules forClaimable() {
        return new Rules(true, true, true, true, false, 1);
    }

    public static Rules forOwnerless() {
        return new Rules(true, false, true, true, true, 1);
    }

    /**
     * @deprecated the kind is chosen by which command created the vote, so there is no
     *             longer an unconfigured state. Kept so old files still load.
     */
    @Deprecated
    public static Rules unconfigured() {
        return forOwnerless();
    }
}
