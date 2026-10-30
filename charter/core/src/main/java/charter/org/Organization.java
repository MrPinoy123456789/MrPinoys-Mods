package charter.org;

import charter.gov.Governance;
import charter.id.OrgId;
import charter.treasury.Ledger;

/**
 * The entity that holds power (DESIGN.md §6.1). A leader exercises authority an
 * organization grants; nothing here belongs to any player. A subordinate is a
 * full organization with its own members, treasury and governance.
 */
public final class Organization {

    public final OrgId id;
    public final long foundedAt;
    public final Governance governance;
    public final Ledger ledger = new Ledger();
    public final People people = new People();

    /**
     * A stronghold or outpost of the raiders (NPC strongholds, N1): a normal organization that cannot be joined,
     * invited to, make war or plant camps, and can only be razed. Never raids players in this version (N6);
     * camps against player keeps come later with the Ragnarok season work, and nobody should wire them in early.
     */
    public boolean npc;

    /** Extra claims an operator granted to this faction on top of its member allowance (top level organizations only). */
    public int claimBonus;

    /**
     * An operator's own kingdom (for example the one that guards spawn): closed to new members, no claim cap, no keep upkeep, and its outposts
     * cannot be named in a war. Set with {@code /charter admin faction admin}; top level organizations only.
     */
    public boolean adminKingdom;

    private String name;
    private String description = "";
    private OrgId parent;
    private OrgStatus status;

    public Organization(OrgId id, String name, OrgId parent, Governance governance, OrgStatus status, long foundedAt) {
        this.id = id;
        this.name = name;
        this.parent = parent;
        this.governance = governance;
        this.status = status;
        this.foundedAt = foundedAt;
    }

    public String name() { return name; }
    public void rename(String newName) { name = newName; }
    public String description() { return description; }
    public void describe(String text) { description = text; }

    /** The parent organization, or null at the top of a tree. */
    public OrgId parent() { return parent; }

    /** Use {@code CharterState.setParent}, which refuses cycles. */
    public void setParent(OrgId newParent) { parent = newParent; }

    public OrgStatus status() { return status; }
    public void setStatus(OrgStatus newStatus) { status = newStatus; }

    public boolean isTopLevel() { return parent == null; }

    /** Forming and active organizations can act; dissolved ones are history. */
    public boolean exists() { return status != OrgStatus.DISSOLVED; }
}
