package chatdonkey.core;

/**
 * Everything {@link TriggerRules} needs to know about one player, as plain
 * timestamps in epoch milliseconds.
 *
 * <p>Deliberately not persisted (SPEC.md section 2): a restart resets every
 * cooldown, which is a harmless failure direction for a mod whose worst case is
 * one extra joke.
 */
public final class PlayerTriggerState {

    /** Sentinel for "this has never happened". */
    public static final long NEVER = Long.MIN_VALUE;

    private final long sessionStartedAt;
    private long lastMovedAt;
    private long lastCheckedAt;
    private long lastEventEndedAt = NEVER;
    private long graceUntil = NEVER;
    private long lastDamagedAt = NEVER;
    private boolean inEvent;
    private boolean optedIn;

    public PlayerTriggerState(long sessionStartedAt) {
        this.sessionStartedAt = sessionStartedAt;
        this.lastMovedAt = sessionStartedAt;
        this.lastCheckedAt = sessionStartedAt;
    }

    public long sessionStartedAt() {
        return sessionStartedAt;
    }

    public long lastMovedAt() {
        return lastMovedAt;
    }

    /** Called when the player has moved a meaningful distance (SPEC.md section 7's activity gate). */
    public void markMoved(long now) {
        this.lastMovedAt = now;
    }

    public long lastCheckedAt() {
        return lastCheckedAt;
    }

    public void markChecked(long now) {
        this.lastCheckedAt = now;
    }

    public long lastEventEndedAt() {
        return lastEventEndedAt;
    }

    /** Starts this player's cooldown (SPEC.md section 3, step 6). */
    public void markEventEnded(long now) {
        this.lastEventEndedAt = now;
        this.inEvent = false;
    }

    /**
     * When the player last took damage, for the {@code donkeyCanKill: false}
     * gate (SPEC.md section 7).
     *
     * <p>Tracked unconditionally rather than only when the setting is off, so
     * flipping it at runtime via {@code /donkey reload} takes effect
     * immediately instead of after the next fight.
     */
    public void markDamaged(long now) {
        this.lastDamagedAt = now;
    }

    public boolean tookDamageWithin(long now, int seconds) {
        return lastDamagedAt != NEVER && now - lastDamagedAt < seconds * 1000L;
    }

    public boolean optedIn() {
        return optedIn;
    }

    /**
     * Whether this player has signed up for random events (SPEC.md section 7).
     *
     * <p>Unlike everything else on this class, the answer is not owned here --
     * it is a persisted roster the fabric side refreshes onto the state each
     * poll, so an opt-out takes effect on the next check rather than on the
     * next login, and the rules stay a pure function of one struct.
     */
    public void setOptedIn(boolean optedIn) {
        this.optedIn = optedIn;
    }

    public long graceUntil() {
        return graceUntil;
    }

    /**
     * Buys immunity until {@code until} (SPEC.md section 7's one escape hatch).
     *
     * <p>Extends rather than replaces, so asking for grace twice does not
     * accidentally shorten it. Under the eventual Twitch integration this is the
     * streamer's side of a bidding war with chat, so it stays a first-class
     * path rather than a debug flag.
     */
    public void grantGraceUntil(long until) {
        if (graceUntil == NEVER || until > graceUntil) {
            this.graceUntil = until;
        }
    }

    /** Cancels any active grace -- chat outbidding the streamer, eventually. */
    public void revokeGrace() {
        this.graceUntil = NEVER;
    }

    public boolean hasGraceAt(long now) {
        return graceUntil != NEVER && now < graceUntil;
    }

    public boolean inEvent() {
        return inEvent;
    }

    public void markEventStarted() {
        this.inEvent = true;
    }

    /**
     * Ends an event without levying a cooldown -- the player logged out, died,
     * or an operator stepped in (SPEC.md section 6).
     *
     * <p>Distinct from {@link #markEventEnded} because it must leave any
     * <em>existing</em> cooldown untouched. Clearing it here would let a player
     * skip their cooldown by dying on purpose.
     */
    public void markEventAborted() {
        this.inEvent = false;
    }
}
