package charter.org;

/**
 * The identity a top-level organization claims (DESIGN.md §7). Identity only: no
 * mechanical traits. Owns nothing and grants no authority.
 */
public final class People {

    public String name = "";
    public String originStory = "";
    public String description = "";
    public String traditions = "";
    public String beliefs = "";
    /** The war banner pattern, as a stored component string the fabric side reads. */
    public String banner = "";
    public int revision = 0;
    /** The organization this identity was forked from, for lineage only. */
    public String derivedFrom = "";
}
