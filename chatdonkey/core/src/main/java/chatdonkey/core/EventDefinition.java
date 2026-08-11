package chatdonkey.core;

/**
 * One entry in {@code config/chatdonkey/events.json} (SPEC.md section 4): a
 * behavior id, how often it should come up, and how long it runs.
 *
 * <p>The behavior id is code; everything else is data, so an operator can retune
 * the whole mod -- drop a behavior they dislike to weight 0, make the Serenade
 * twice as long -- without a rebuild.
 *
 * <p>The id doubles as the {@code lines.json} pool prefix, which is why there is
 * no separate line-pool reference field: {@code serenade} means
 * {@code serenade.open}. One name, one place to change it.
 */
public record EventDefinition(String behavior, int weight, int minSeconds, int maxSeconds) {

    /** Clamps operator typos into something usable rather than throwing. */
    public EventDefinition sanitised() {
        int min = Math.max(1, minSeconds);
        int max = Math.max(min, maxSeconds);
        return new EventDefinition(behavior, Math.max(0, weight), min, max);
    }

    /** Weight 0 means "registered but never rolled" -- the way to disable one. */
    public boolean isEnabled() {
        return weight > 0;
    }
}
