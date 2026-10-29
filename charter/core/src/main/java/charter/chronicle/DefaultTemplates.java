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
        t.put(TemplateSet.FALLBACK, "On {when}, it was written that something happened: {type}.");
        t.put(TemplateSet.ADMIN, "So it was done by order of the crown's clerk.");

        // ---- organizations and membership (OrgEvents) ----
        t.put("OrgEvents.Founded", "On {when}, {founder} founded {name} as a {preset}.");
        t.put("OrgEvents.Activated", "On {when}, {org} gained its second member and became a true body.");
        t.put("OrgEvents.SubOrgCreated", "On {when}, {leader} raised {name} as a {preset} under {parent}.");
        t.put("OrgEvents.Invited", "On {when}, {org} invited {player} to join them.");
        t.put("OrgEvents.Joined", "On {when}, {player} joined {org}{from? after leaving %}.");
        t.put("OrgEvents.Left", "On {when}, {player} left {org}.");
        t.put("OrgEvents.Expelled", "On {when}, {player} was expelled from {org}.");
        t.put("OrgEvents.Renamed", "On {when}, {from} took the name {to}.");

        // ---- votes and offices (GovEvents) ----
        t.put("GovEvents.VoteOpened", "On {when}, {proposer} put a {kind} before {org}: {about}. The members may vote until {closesAt}.");
        t.put("GovEvents.VotePassed", "On {when}, the vote of {org} carried, {yes} for and {no} against, with {abstain} abstaining.");
        t.put("GovEvents.VoteFailed", "On {when}, the vote of {org} failed: {why}.");
        t.put("GovEvents.VoteRefusedAtExecution", "On {when}, the vote of {org} carried, but could not be carried out, for the world had changed ({reasonKey}).");
        t.put("GovEvents.OfficeCreated", "On {when}, {org} created the office of {name}{reserved?, which only a vote may fill}.");
        t.put("GovEvents.OfficeFilled", "On {when}, {holder} took up the office of {office} in {org}.");
        t.put("NpcEvents.Raised", "On {when}, a band calling itself {org} raised a {kind} at {center}.");
        t.put("NpcEvents.Replaced", "On {when}, a fallen {kind} was replaced: {org} raised another at {center}.");
        t.put("OrgEvents.Dissolved", "On {when}, {org} was dissolved. {members#member} went free{keeps?, and its keeps fell}.");
        t.put("OrgEvents.BreakawayBegan", "On {when}, {org} began to break away.");
        t.put("OrgEvents.BreakawayLapsed", "On {when}, the break away of {org} came to nothing: its time ran out.");
        t.put("OrgEvents.Seceded", "On {when}, {org} broke away from {from} and stood alone.");
        t.put("OrgEvents.GroupBrokeAway", "On {when}, {count} left {from} and founded {org}.");
        t.put("OrgEvents.Admitted", "On {when}, {org} took in {player}.");
        // Routine (ChronicleRenderer.routine hides them); the templates keep the coverage rule whole.
        t.put("OrgEvents.Asked", "On {when}, {player} asked to join {org}.");
        t.put("OrgEvents.Rejected", "On {when}, {org} turned {player} away.");
        t.put("OrgEvents.RequestLapsed", "On {when}, the request of {player} to join {org} lapsed.");
        t.put("GovEvents.SeatsChanged", "On {when}, {org} changed how it is ruled: {seats} seats, deciding {deciding}.");
        t.put("GovEvents.OfficeVacated", "On {when}, {formerHolder} left the office of {office} in {org}: {why}.");

        // ---- grants (GrantEvents) ----
        t.put("GrantEvents.Granted", "On {when}, {subject} granted {recipient} the power to {power}{redelegable?, to be passed on as they see fit}.");
        t.put("GrantEvents.Revoked", "On {when}, {subject} took back the power to {power}.");

        // ---- treasury (TreasuryEvents) ----
        t.put("TreasuryEvents.Deposited", "On {when}, {by} paid {amount} {currency} into the treasury of {org}.");
        t.put("TreasuryEvents.Withdrawn", "On {when}, {to} drew {amount} {currency} from the treasury of {org}.");
        t.put("TreasuryEvents.Transferred", "On {when}, {from} sent {amount} {currency} to {to}.");

        // ---- land (LandEvents) ----
        t.put("LandEvents.NexusPlaced", "On {when}, {org} raised a keep at {center} and laid claim to the ground around it.");
        t.put("LandEvents.CampPlanted", "On {when}, {org} planted a war banner at {center}.");
        t.put("LandEvents.Claimed", "On {when}, {org} claimed the land at {chunk}.");
        t.put("LandEvents.Unclaimed", "On {when}, {org} gave up the land at {chunk}.");
        t.put("LandEvents.CoreLost", "On {when}, {org} lost its {kind} at {center}.");
        t.put("LandEvents.Reassigned", "On {when}, {count#claim} of {org} were tied to {to} after {from} was lost.");
        t.put("LandEvents.Contested", "On {when}, {count#claim} of {org} lost their anchor and stood contested. Unless something took them up again, they would be lost.");
        t.put("LandEvents.Rescued", "On {when}, {count#claim} of {org} were taken up again and held.");
        t.put("LandEvents.Lapsed", "On {when}, {count#claim} of {org} were lost for good.");

        // ---- sieges (SiegeEvents) ----
        t.put("SiegeEvents.CampActive", "On {when}, {owner} finished raising a camp.");
        t.put("SiegeEvents.KeepRunningLow", "On {when}, the granary of {keep} was down to {daysLeft} days of food. {owner} was told to feed it.");
        t.put("SiegeEvents.KeepLapsed", "On {when}, the granary of {keep} ran dry and its claim lapsed. The land lay open again, though every block stood where it was.");
        t.put("SiegeEvents.CampStoreEmpty", "On {when}, the stores of {camp} ran out.");
        t.put("SiegeEvents.CampCutOff", "On {when}, {camp} was cut off from supply, and its stores began to burn faster.");
        t.put("SiegeEvents.FoodDeposited", "On {when}, food was carried into {site}.");

        // ---- contracts and justice (ContractEvents) ----
        t.put("ContractEvents.Arrested", "On {when}, {officer} of {faction} arrested {prisoner} on their land.");
        t.put("ContractEvents.CustodyLapsed", "On {when}, {faction} let {prisoner} go, for no sentence was passed.");
        t.put("ContractEvents.Imposed", "On {when}, {imposer} set {terms} on {subject}, by right of {basis}{cut?, though the terms were cut down to the limit}{outsider?, and jailing a stranger was an act of war}.");
        t.put("ContractEvents.Fined", "On {when}, {prisoner} was fined {asked#diamond} by {to} and paid {paid}.");
        t.put("ContractEvents.Executed", "On {when}, {faction} put {prisoner} to death. They would wait {respawnDelayMillis} to rise again, and were banished for {banishmentMillis}.");
        t.put("ContractEvents.Escaped", "On {when}, {prisoner} escaped from the cells of {faction}.");
        t.put("ContractEvents.Pardoned", "On {when}, {by} pardoned {prisoner}.");
        t.put("ContractEvents.Released", "On {when}, {by} released {prisoner}.");
        t.put("ContractEvents.PrisonersFreed", "On {when}, the prisoners of {keep} walked free: {prisoners}.");
        t.put("ContractEvents.Fulfilled", "On {when}, a contract made by right of {basis} ran its course.");
        t.put("ContractEvents.Resisted", "On {when}, a contract imposed by right of {basis} ended in resistance ({how}). No mark was set on anyone.");
        t.put("ContractEvents.Breached", "On {when}, {by} broke their word, and was marked for it.");
        t.put("ContractEvents.Ended", "On {when}, a contract made by right of {basis} ended early: {why}.");
        // A player's own deposit is their business. An empty template keeps an event out of the books.
        t.put("ContractEvents.VaultDeposited", "");

        return t;
    }
}
