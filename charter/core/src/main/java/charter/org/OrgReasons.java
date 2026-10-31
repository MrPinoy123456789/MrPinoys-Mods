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
    public static final Reason ALREADY_ASKING = new Reason("already_asking", "You have already asked to join a faction. Wait for an answer first.");
    public static final Reason NO_REQUEST = new Reason("no_request", "That player has not asked to join.");
    public static final Reason CLOSED = new Reason("closed_kingdom", "That kingdom is closed to new members.");
    public static final Reason NOT_JOINABLE = new Reason("not_joinable", "That faction cannot be asked to take new members.");
    public static final Reason NOT_A_SUBFACTION = new Reason("not_a_subfaction", "Only a sub-faction can secede as a whole.");
    public static final Reason ALREADY_BREAKING = new Reason("already_breaking", "That faction is already breaking away.");
    public static final Reason HAS_SUBFACTIONS = new Reason("has_subfactions", "A faction with sub-factions cannot be left by a group. Its sub-factions secede by a vote.");
    public static final Reason RULER_STAYS = new Reason("ruler_stays", "A ruler abdicates before breaking away.");
    public static final Reason NEEDS_GROUP = new Reason("needs_group", "At least two must break away together.");
    public static final Reason FOUNDING_CLOSED = new Reason("founding_closed", "Kingdoms are set by the server's operators for now. Ask one to make yours.");
    public static final Reason MERC_IN_ORG = new Reason("merc_in_org", "Only a player without an organization can be a mercenary. Leave yours first.");
    public static final Reason MERC_NOT_A_RACE = new Reason("merc_not_a_race", "That is not a race a mercenary can take.");
    public static final Reason RACE_CHOSEN = new Reason("race_chosen", "You already have a race and it cannot be changed. Only an operator can.");
    public static final Reason NOT_YOU = new Reason("not_you", "Only that player can do this.");
}
