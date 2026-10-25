package charter.org;

import charter.engine.Reason;

/** Refusals for organization and membership actions. */
public final class OrgReasons {

    private OrgReasons() {
    }

    public static final Reason ALREADY_MEMBER = new Reason("already_member", "You already belong to an organization. Leave it first.");
    public static final Reason NAME_TAKEN = new Reason("name_taken", "Another organization already has that name.");
    public static final Reason BAD_NAME = new Reason("bad_name", "That name is too short or too long.");
    public static final Reason FOUND_COOLDOWN = new Reason("found_cooldown", "You founded or left to found an organization too recently.");
    public static final Reason NOT_A_MEMBER = new Reason("not_a_member", "That player is not a member here.");
    public static final Reason NO_INVITE = new Reason("no_invite", "You have no invitation from that organization.");
    public static final Reason OTHER_TREE = new Reason("other_tree", "You belong to another organization. Leave it before joining this one.");
    public static final Reason HOLDS_OFFICE = new Reason("holds_office", "Someone holding an office here cannot be moved out of it this way.");
    public static final Reason NOT_YOU = new Reason("not_you", "Only that player can do this.");
}
