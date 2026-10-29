package charter.grant;

/**
 * A power an organization holds over itself and can grant (DESIGN.md §18.1).
 *
 * <p>There is no "grant" power: passing a power on is done with a grant marked
 * redelegable, and a root grant (straight from the organization) only ever
 * comes from a vote of that organization.
 *
 * <p>Later milestones use the powers listed here; add a new one only when a
 * feature needs it, and say so in that milestone's report, since parallel
 * milestones share this file.
 */
public enum Power {
    MANAGE_MEMBERS,
    APPOINT,
    SPEND,
    CREATE_SUBORG,
    AMEND,
    DISSOLVE,
    PLACE_CORE,
    CLAIM,
    RAISE_CAMP,
    GRANARY_DEPOSIT,
    GRANARY_WITHDRAW,
    GARRISON,
    ARREST,
    JUDGE,
    PARDON,
    NEGOTIATE,
    DECLARE_WAR
}
