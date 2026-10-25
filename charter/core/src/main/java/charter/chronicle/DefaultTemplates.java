package charter.chronicle;

/**
 * The built-in Chronicle voice, one template per event record. The fabric side
 * copies these to {@code templates.json} on first boot so a server can reword
 * them. Later milestones add their own events in their own block below.
 */
public final class DefaultTemplates {

    private DefaultTemplates() {
    }

    public static TemplateSet get() {
        TemplateSet t = new TemplateSet();

        // ---- reserved ----
        t.put(TemplateSet.FALLBACK, "In {when}, it was written that something happened: {type}.");
        t.put(TemplateSet.ADMIN, "So it was done by order of the crown's clerk.");

        // ---- organizations and membership (OrgEvents) ----
        t.put("OrgEvents.Founded", "In {when}, {founder} founded {name} as a {preset}.");
        t.put("OrgEvents.Activated", "In {when}, {org} gained its second member and became a true body.");
        t.put("OrgEvents.SubOrgCreated", "In {when}, {leader} raised {name} as a {preset} under {parent}.");
        t.put("OrgEvents.Invited", "In {when}, {org} invited {player} to join them.");
        t.put("OrgEvents.Joined", "In {when}, {player} joined {org}{from? after leaving %}.");
        t.put("OrgEvents.Left", "In {when}, {player} left {org}.");
        t.put("OrgEvents.Expelled", "In {when}, {player} was expelled from {org}.");
        t.put("OrgEvents.Renamed", "In {when}, {from} took the name {to}.");

        // ---- votes and offices (GovEvents) ----
        t.put("GovEvents.VoteOpened", "In {when}, {proposer} put a {kind} before {org}: {about}. The members may vote until {closesAt}.");
        t.put("GovEvents.VotePassed", "In {when}, the vote of {org} carried, {yes} for and {no} against, with {abstain} abstaining.");
        t.put("GovEvents.VoteFailed", "In {when}, the vote of {org} failed: {why}.");
        t.put("GovEvents.VoteRefusedAtExecution", "In {when}, the vote of {org} carried, but could not be carried out, for the world had changed ({reasonKey}).");
        t.put("GovEvents.OfficeCreated", "In {when}, {org} created the office of {name}{reserved?, which only a vote may fill}.");
        t.put("GovEvents.OfficeFilled", "In {when}, {holder} took up the office of {office} in {org}.");
        t.put("GovEvents.OfficeVacated", "In {when}, {formerHolder} left the office of {office} in {org}: {why}.");

        // ---- grants (GrantEvents) ----
        t.put("GrantEvents.Granted", "In {when}, {subject} granted {recipient} the power to {power}{redelegable?, to be passed on as they see fit}.");
        t.put("GrantEvents.Revoked", "In {when}, {subject} took back the power to {power}.");

        // ---- treasury (TreasuryEvents) ----
        t.put("TreasuryEvents.Deposited", "In {when}, {by} paid {amount} {currency} into the treasury of {org}.");
        t.put("TreasuryEvents.Withdrawn", "In {when}, {to} drew {amount} {currency} from the treasury of {org}.");
        t.put("TreasuryEvents.Transferred", "In {when}, {from} sent {amount} {currency} to {to}.");

        return t;
    }
}
