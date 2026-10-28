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
        t.put("OrgEvents.Admitted", "In {when}, {org} took in {player}.");
        // Routine (ChronicleRenderer.routine hides them); the templates keep the coverage rule whole.
        t.put("OrgEvents.Asked", "In {when}, {player} asked to join {org}.");
        t.put("OrgEvents.Rejected", "In {when}, {org} turned {player} away.");
        t.put("OrgEvents.RequestLapsed", "In {when}, the request of {player} to join {org} lapsed.");
        t.put("GovEvents.SeatsChanged", "In {when}, {org} changed how it is ruled: {seats} seats, deciding {deciding}.");
        t.put("GovEvents.OfficeVacated", "In {when}, {formerHolder} left the office of {office} in {org}: {why}.");

        // ---- grants (GrantEvents) ----
        t.put("GrantEvents.Granted", "In {when}, {subject} granted {recipient} the power to {power}{redelegable?, to be passed on as they see fit}.");
        t.put("GrantEvents.Revoked", "In {when}, {subject} took back the power to {power}.");

        // ---- treasury (TreasuryEvents) ----
        t.put("TreasuryEvents.Deposited", "In {when}, {by} paid {amount} {currency} into the treasury of {org}.");
        t.put("TreasuryEvents.Withdrawn", "In {when}, {to} drew {amount} {currency} from the treasury of {org}.");
        t.put("TreasuryEvents.Transferred", "In {when}, {from} sent {amount} {currency} to {to}.");

        // ---- land (LandEvents) ----
        t.put("LandEvents.NexusPlaced", "In {when}, {org} raised a keep at {center} and laid claim to the ground around it.");
        t.put("LandEvents.CampPlanted", "In {when}, {org} planted a war banner at {center}.");
        t.put("LandEvents.Claimed", "In {when}, {org} claimed the land at {chunk}.");
        t.put("LandEvents.Unclaimed", "In {when}, {org} gave up the land at {chunk}.");
        t.put("LandEvents.CoreLost", "In {when}, {org} lost its {kind} at {center}.");
        t.put("LandEvents.Reassigned", "In {when}, {count#claim} of {org} were tied to {to} after {from} was lost.");
        t.put("LandEvents.Contested", "In {when}, {count#claim} of {org} lost their anchor and stood contested. Unless something took them up again, they would be lost.");
        t.put("LandEvents.Rescued", "In {when}, {count#claim} of {org} were taken up again and held.");
        t.put("LandEvents.Lapsed", "In {when}, {count#claim} of {org} were lost for good.");

        // ---- sieges (SiegeEvents) ----
        t.put("SiegeEvents.CampActive", "In {when}, {owner} finished raising a war camp against {targets}. The defenders were warned.");
        t.put("SiegeEvents.SiegeBegan", "In {when}, {besiegers} laid siege to {keep}. {defender} had only its granary to hold out on.");
        t.put("SiegeEvents.Encircled", "In {when}, every road to {keep} was cut. No food could reach its granary now.");
        t.put("SiegeEvents.RingBroken", "In {when}, the ring around {keep} was broken, and a road lay open again.");
        t.put("SiegeEvents.SiegeLifted", "In {when}, {besiegers} gave up the siege of {keep}, and it stood free.");
        t.put("SiegeEvents.Starving", "In {when}, the granary of {keep} ran low, and its people began to starve.");
        t.put("SiegeEvents.KeepFell", "In {when}, {keep} fell to {besieger}: the granary of {owner} ran dry before any relief arrived.");
        t.put("SiegeEvents.Razed", "In {when}, {by} razed {keep} and left nothing standing.");
        t.put("SiegeEvents.Occupied", "In {when}, {by} chose to hold {keep} rather than burn it. For a time it would answer to them.");
        t.put("SiegeEvents.OccupationEnded", "In {when}, the hold of {occupier} on {keep} ended: {why}.");
        t.put("SiegeEvents.Plundered", "In {when}, the treasury of {from} was broken open, and {amounts} lay spilled where the keep fell.");
        t.put("SiegeEvents.CampStoreEmpty", "In {when}, the stores of {camp} ran out.");
        t.put("SiegeEvents.CampCutOff", "In {when}, {camp} was cut off from supply, and its stores began to burn faster.");
        t.put("SiegeEvents.CampExposed", "In {when}, a sally emptied the hold beside {camp}. Its claims lost their guard, and its banner could be broken.");
        t.put("SiegeEvents.HoldEmptied", "In {when}, {raider} raided a hold of {owner} at {chunk} and emptied it.");
        t.put("SiegeEvents.TruceBegan", "In {when}, a truce fell between {keep} and {attacker}, so that the war could not begin again at once.");
        t.put("SiegeEvents.FoodDeposited", "In {when}, food was carried into {site} past the besiegers.");
        t.put("SiegeEvents.ProtectionWaived", "In {when}, {faction} gave up the shelter owed to a new faction.");

        // ---- contracts and justice (ContractEvents) ----
        t.put("ContractEvents.Arrested", "In {when}, {officer} of {faction} arrested {prisoner} on their land.");
        t.put("ContractEvents.CustodyLapsed", "In {when}, {faction} let {prisoner} go, for no sentence was passed.");
        t.put("ContractEvents.Imposed", "In {when}, {imposer} set {terms} on {subject}, by right of {basis}{cut?, though the terms were cut down to the limit}{outsider?, and jailing a stranger was an act of war}.");
        t.put("ContractEvents.Fined", "In {when}, {prisoner} was fined {asked#diamond} by {to} and paid {paid}.");
        t.put("ContractEvents.Executed", "In {when}, {faction} put {prisoner} to death. They would wait {respawnDelayMillis} to rise again, and were banished for {banishmentMillis}.");
        t.put("ContractEvents.Escaped", "In {when}, {prisoner} escaped from the cells of {faction}.");
        t.put("ContractEvents.Pardoned", "In {when}, {by} pardoned {prisoner}.");
        t.put("ContractEvents.Released", "In {when}, {by} released {prisoner}.");
        t.put("ContractEvents.PrisonersFreed", "In {when}, the prisoners of {keep} walked free: {prisoners}.");
        t.put("ContractEvents.Fulfilled", "In {when}, a contract made by right of {basis} ran its course.");
        t.put("ContractEvents.Resisted", "In {when}, a contract imposed by right of {basis} ended in resistance ({how}). No mark was set on anyone.");
        t.put("ContractEvents.Breached", "In {when}, {by} broke their word, and was marked for it.");
        t.put("ContractEvents.Ended", "In {when}, a contract made by right of {basis} ended early: {why}.");
        // A player's own deposit is their business. An empty template keeps an event out of the books.
        t.put("ContractEvents.VaultDeposited", "");

        return t;
    }
}
