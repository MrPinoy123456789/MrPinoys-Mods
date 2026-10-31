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
    /** The kingdom whose race this mercenary took; null if they are not a mercenary. Cleared when they join an organization. */
    public charter.id.OrgId mercKingdom;
    /** Wall time of the last choice of race; 0 if never. */
    public long mercChosenAt;

    public PlayerRecord(PlayerId id, String name, long lastSeen) {
        this.id = id;
        this.name = name;
        this.lastSeen = lastSeen;
    }
}
