package ballot;

/**
 * One thing that can be voted for.
 *
 * <p>An owner is optional. A poll asking which colour the town hall roof should be has
 * four ownerless entries; a build competition has entries owned by whoever claimed the
 * plot. Ownership is what enables self-vote blocking and naming — without it, those
 * rules simply do not apply.
 */
public final class Entry {

    private int id;
    private String label;
    private String owner;       // UUID as string, or null
    private String ownerName;   // last seen, display only
    private long createdAt;

    Entry() {}   // Gson

    Entry(int id, String label, long createdAt) {
        this.id = id;
        this.label = label;
        this.createdAt = createdAt;
    }

    public int id() {
        return id;
    }

    public String label() {
        return label == null || label.isBlank() ? "Untitled" : label;
    }

    public boolean hasLabel() {
        return label != null && !label.isBlank();
    }

    void setLabel(String label) {
        this.label = label;
    }

    public String owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public boolean isClaimed() {
        return owner != null;
    }

    public boolean isOwnedBy(String uuid) {
        return owner != null && owner.equals(uuid);
    }

    void claim(String uuid, String name) {
        this.owner = uuid;
        this.ownerName = name;
    }

    void release() {
        this.owner = null;
        this.ownerName = null;
        this.label = null;
    }

    public long createdAt() {
        return createdAt;
    }
}
