package charter.org;

import charter.id.PlayerId;

/**
 * What the rules know about a player outside any organization: their last known
 * name, when they were last seen (the active window, the absent Lord rule), and
 * when they last founded or seceded (the 7 day cooldown).
 */
public final class PlayerRecord {

    public final PlayerId id;
    public String name;
    public long lastSeen;
    /** Wall time of the last found or secede; 0 if never. */
    public long lastFoundedAt;
    /**
     * The kingdom whose race this player has; null until they join a kingdom or a mercenary chooses one. Kept for good:
     * leaving a kingdom does not change it, and only an operator can set it again.
     */
    public charter.id.OrgId race;
    /** True when an operator removed the player's race entirely: they have none, even while they belong to a kingdom. */
    public boolean noRace;

    public PlayerRecord(PlayerId id, String name, long lastSeen) {
        this.id = id;
        this.name = name;
        this.lastSeen = lastSeen;
    }
}
