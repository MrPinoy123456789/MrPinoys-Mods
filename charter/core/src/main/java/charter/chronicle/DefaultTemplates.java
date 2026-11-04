package charter.chronicle;

import static charter.chronicle.Tier.KINGDOM;
import static charter.chronicle.Tier.QUIET;
import static charter.chronicle.Tier.REALM;

/**
 * The built-in Chronicle voice: for every event record a book line, a headline and
 * a tier (docs/2026-11-04-chronicle-design.md 3.1 and 3.2). The fabric side copies
 * these to {@code templates.json} on first boot so a server can reword them; a
 * server line given as a plain string keeps the built in headline and tier.
 *
 * <p>The voice is a town crier who cannot keep a straight face: short, the name
 * first, plain words, no swearing. Lines open with the day so a book reads as a
 * record; headlines carry no date. Phrases a gametest pins are kept: "was down to
 * N days of food", "its claim lapsed", "every block stood where it was", "N claims".
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
        t.put("OrgEvents.Founded", "On {when}, {founder} founded {name} as a {preset}.",
                "{founder} founded {name}.", REALM);
        t.put("OrgEvents.Activated", "On {when}, {org} gained its second member and became a true body.",
                "{org} is two strong and counts as a kingdom now.", REALM);
        t.put("OrgEvents.SubOrgCreated", "On {when}, {leader} raised {name} as a {preset} under {parent}.",
                "{leader} raised {name} under {parent}.", KINGDOM);
        t.put("OrgEvents.Invited", "On {when}, {org} invited {player} to join them.",
                "{org} sent {player} an invitation.", KINGDOM);
        t.put("OrgEvents.Joined", "On {when}, {player} joined {org}{from? after leaving %}.",
                null, KINGDOM); // either "joined X" or "joined X after leaving Y": the line, dated clause dropped, is the headline
        t.put("OrgEvents.Left", "On {when}, {player} left {org}.",
                "{player} left {org}.", KINGDOM);
        t.put("OrgEvents.Expelled", "On {when}, {player} was expelled from {org}.",
                "{org} showed {player} the door.", KINGDOM);
        t.put("OrgEvents.Renamed", "On {when}, {from} took the name {to}.",
                "{from} is now called {to}.", REALM);
        t.put("OrgEvents.Dissolved", "On {when}, {org} was dissolved. {members#member} went free{keeps?, and its keeps fell}.",
                "{org} is no more. {members#member} went free.", REALM);
        t.put("OrgEvents.BreakawayBegan", "On {when}, {org} began to break away.",
                "{org} has begun to break away.", KINGDOM);
        t.put("OrgEvents.BreakawayLapsed", "On {when}, the break away of {org} came to nothing: its time ran out.",
                "{org} ran out of time to break away. Nothing changed.", KINGDOM);
        t.put("OrgEvents.Seceded", "On {when}, {org} broke away from {from} and stood alone.",
                "{org} broke away from {from} and stands alone.", REALM);
        t.put("OrgEvents.GroupBrokeAway", "On {when}, {count} left {from} and founded {org}.",
                "{count} left {from} and founded {org}.", REALM);
        t.put("OrgEvents.Admitted", "On {when}, {org} took in {player}.",
                "{org} took in {player}.", KINGDOM);
        // Routine (ChronicleRenderer.routine hides them); the templates keep the coverage rule whole.
        t.put("OrgEvents.Asked", "On {when}, {player} asked to join {org}.", "{player} asked to join {org}.", QUIET);
        t.put("OrgEvents.Rejected", "On {when}, {org} turned {player} away.", "{org} turned {player} away.", QUIET);
        t.put("OrgEvents.RequestLapsed", "On {when}, the request of {player} to join {org} lapsed.",
                "{player} waited too long on {org}.", QUIET);

        // ---- Lord discipline (ModerationEvents): the reason stays in history and out of every book ----
        t.put("ModerationEvents.Muted", "On {when}, {lord} of {org} muted {player} for {durationMillis}.",
                "{lord} muted {player} for {durationMillis}.", KINGDOM);
        t.put("ModerationEvents.Unmuted", "On {when}, {lord} of {org} let {player} speak again.",
                "{player} may speak again.", QUIET);
        t.put("ModerationEvents.Suspended", "On {when}, {lord} of {org} barred {player} from the kingdom's land for {durationMillis}.",
                "{lord} barred {player} from {org} for {durationMillis}.", KINGDOM);
        t.put("ModerationEvents.Unsuspended", "On {when}, {lord} of {org} lifted the bar on {player}.",
                "{player} is welcome back in {org}.", QUIET);
        t.put("ModerationEvents.Kicked", "On {when}, {lord} of {org} sent {player} away from the realm for a moment.",
                "{lord} sent {player} out to cool off.", KINGDOM);
        t.put("ModerationEvents.LedgerGranted", "On {when}, {player} was given a lookout's ledger for {durationMillis}.",
                "{player} holds a lookout's ledger for {durationMillis}.", QUIET);
        t.put("ModerationEvents.LedgerRevoked", "On {when}, {player} gave up the lookout's ledger: {why}.",
                "{player} gave up the lookout's ledger.", QUIET);

        // ---- votes and offices (GovEvents) ----
        // The motion's text is a player's own words and stays in the kingdom book, never in a headline.
        t.put("GovEvents.VoteOpened", "On {when}, {proposer} put a {kind} before {org}: {about}. The members may vote until {closesAt}.",
                "{proposer} put a {kind} before {org}. Votes close {closesAt}.", KINGDOM);
        t.put("GovEvents.VotePassed", "On {when}, the vote of {org} carried, {yes} for and {no} against, with {abstain} abstaining.",
                "{org} voted yes, {yes} to {no}.", KINGDOM);
        t.put("GovEvents.VoteFailed", "On {when}, the vote of {org} failed: {why}.",
                "{org} voted no.", KINGDOM);
        t.put("GovEvents.VoteRefusedAtExecution", "On {when}, the vote of {org} carried, but could not be carried out, for the world had changed ({reasonKey}).",
                "{org} voted yes, but the world had moved on.", KINGDOM);
        t.put("GovEvents.OfficeCreated", "On {when}, {org} created the office of {name}{reserved?, which only a vote may fill}.",
                "{org} has a new office: {name}.", KINGDOM);
        t.put("GovEvents.OfficeFilled", "On {when}, {holder} took up the office of {office} in {org}.",
                "{holder} is {office} of {org}.", KINGDOM);
        t.put("GovEvents.OfficeVacated", "On {when}, {formerHolder} left the office of {office} in {org}: {why}.",
                "{formerHolder} is no longer {office} of {org}.", KINGDOM);
        t.put("GovEvents.SeatsChanged", "On {when}, {org} changed how it is ruled: {seats} seats, deciding {deciding}.",
                "{org} is ruled differently now: {seats} seats.", KINGDOM);

        // ---- raiders (NpcEvents): told to operators only; the renderer leaves raider bands out of the story ----
        t.put("NpcEvents.Raised", "On {when}, a band calling itself {org} raised a {kind} at {center}.",
                "Raiders calling themselves {org} raised a {kind} at {center}.", QUIET);
        t.put("NpcEvents.Replaced", "On {when}, a fallen {kind} was replaced: {org} raised another at {center}.",
                "{org} raised another {kind} at {center}.", QUIET);

        // ---- grants (GrantEvents) ----
        t.put("GrantEvents.Granted", "On {when}, {subject} granted {recipient} the power to {power}{redelegable?, to be passed on as they see fit}.",
                "{recipient} may now {power} for {subject}.", QUIET);
        t.put("GrantEvents.Revoked", "On {when}, {subject} took back the power to {power}.",
                "{subject} took back the power to {power}.", QUIET);

        // ---- treasury (TreasuryEvents): Marks are told in Sovereigns; under one Sovereign is quiet ----
        t.put("TreasuryEvents.Deposited", "On {when}, {by} paid {amount} {currency} into the treasury of {org}.",
                "{by} paid {amount} {currency} into the treasury of {org}.", KINGDOM);
        t.put("TreasuryEvents.Withdrawn", "On {when}, {to} drew {amount} {currency} from the treasury of {org}.",
                "{to} drew {amount} {currency} from the treasury of {org}.", KINGDOM);
        t.put("TreasuryEvents.Transferred", "On {when}, {from} sent {amount} {currency} to {to}.",
                "{from} sent {amount} {currency} to {to}.", KINGDOM);
        t.put("TreasuryEvents.DepositedMarks", "On {when}, {marks#coin} were paid into the treasury of {org}.",
                "The treasury of {org} grew by {marks#coin}.", KINGDOM);
        t.put("TreasuryEvents.WithdrawnMarks", "On {when}, {to} drew {marks#coin} from the treasury of {org}.",
                "{to} drew {marks#coin} from the treasury of {org}.", KINGDOM);
        t.put("TreasuryEvents.TransferredMarks", "On {when}, {from} sent {marks#coin} to {to}.",
                "{from} sent {marks#coin} to {to}.", KINGDOM);

        // ---- land (LandEvents) ----
        t.put("LandEvents.NexusPlaced", "On {when}, {org} raised a keep at {center} and laid claim to the ground around it.",
                "{org} raised a keep at {center}.", REALM);
        t.put("LandEvents.KeepMoved", "On {when}, {org} moved its keep from {fromChunk} to {toChunk}.",
                "{org} moved its keep to {toChunk}.", KINGDOM);
        t.put("LandEvents.NexusMoved", "On {when}, the Nexus of {org} was moved by an operator.",
                "The Nexus of {org} was moved.", KINGDOM);
        t.put("LandEvents.CampPlanted", "On {when}, {org} planted a war banner at {center}.",
                "{org} planted a war banner at {center}.", KINGDOM);
        t.put("LandEvents.Claimed", "On {when}, {org} claimed the land at {chunk}.",
                "{org} claimed the land at {chunk}.", KINGDOM);
        t.put("LandEvents.Unclaimed", "On {when}, {org} gave up the land at {chunk}.",
                "{org} gave up the land at {chunk}.", KINGDOM);
        t.put("LandEvents.CoreLost", "On {when}, {org} lost its {kind} at {center}.",
                "{org} lost its {kind} at {center}.", REALM);
        t.put("LandEvents.Reassigned", "On {when}, {count#claim} of {org} were tied to {to} after {from} was lost.",
                "{count#claim} of {org} now hang on {to}.", KINGDOM);
        t.put("LandEvents.Contested", "On {when}, {count#claim} of {org} lost their anchor and stood contested. Unless something took them up again, they would be lost.",
                "{count#claim} of {org} stand contested.", KINGDOM);
        t.put("LandEvents.Rescued", "On {when}, {count#claim} of {org} were taken up again and held.",
                "{org} held on to {count#claim}.", KINGDOM);
        t.put("LandEvents.Lapsed", "On {when}, {count#claim} of {org} were lost for good.",
                "{org} lost {count#claim} for good.", KINGDOM);
        t.put("LandEvents.LecternMoved", "On {when}, the keep lectern of {org} was moved by an operator.",
                "The keep lectern of {org} was moved.", QUIET);

        // ---- sieges (SiegeEvents) ----
        t.put("SiegeEvents.CampActive", "On {when}, {owner} finished raising an outpost.",
                "{owner} finished raising an outpost.", KINGDOM);
        t.put("SiegeEvents.WarDeclared", "On {when}, {attacker} declared war on {defender} and named {outpost}. Nothing may be done to it until the notice has run out.",
                "{attacker} declared war on {defender} and named {outpost}.", REALM);
        t.put("SiegeEvents.WarOpened", "On {when}, the notice was over: {attacker} may now try to take {outpost}, if they hold it with none of its people there.",
                "The notice is over. {attacker} may now try for {outpost}.", REALM);
        t.put("SiegeEvents.OutpostCaptured", "On {when}, {attacker} took {outpost}, banner and land.",
                "{attacker} took {outpost}, banner and land.", REALM);
        t.put("SiegeEvents.WarLapsed", "On {when}, the war of {attacker} on {outpost} ran out and nothing was taken. They may not fight again for a while.",
                "{attacker} ran out of time at {outpost}. {defender} keeps it.", REALM);
        t.put("SiegeEvents.KeepRunningLow", "On {when}, the granary of {keep} was down to {daysLeft} days of food. {owner} was told to feed it.",
                "The granary of {keep} is down to {daysLeft} days of food.", KINGDOM);
        t.put("SiegeEvents.KeepLapsed", "On {when}, the granary of {keep} ran dry and its claim lapsed. The land lay open again, though every block stood where it was.",
                "The granary of {keep} ran dry. Its claim lapsed.", REALM);
        t.put("SiegeEvents.FoodDeposited", "On {when}, food was carried into {site}.",
                "Food was carried into {site}.", KINGDOM);

        // ---- contracts and justice (ContractEvents) ----
        t.put("ContractEvents.Arrested", "On {when}, {officer} of {faction} arrested {prisoner} on their land.",
                "{officer} of {faction} arrested {prisoner}.", KINGDOM);
        t.put("ContractEvents.CustodyLapsed", "On {when}, {faction} let {prisoner} go, for no sentence was passed.",
                "{faction} let {prisoner} go. No sentence was passed.", KINGDOM);
        t.put("ContractEvents.Imposed", "On {when}, {imposer} set {terms} on {subject}, by right of {basis}{cut?, though the terms were cut down to the limit}{outsider?, and jailing a stranger was an act of war}.",
                "{imposer} set {terms} on {subject}{outsider?, an act of war}.", KINGDOM);
        t.put("ContractEvents.Fined", "On {when}, {prisoner} was fined {asked#diamond} by {to} and paid {paid}.",
                "{to} fined {prisoner} {asked#diamond}.", KINGDOM);
        t.put("ContractEvents.FinedMarks", "On {when}, {prisoner} was fined {asked#coin} by {to} and paid {paid#coin}.",
                "{to} fined {prisoner} {asked#coin}.", KINGDOM);
        t.put("ContractEvents.Bailed", "On {when}, {payer} paid {marks#coin} to {to} to bail {prisoner} out.",
                "{payer} bailed {prisoner} out for {marks#coin}.", KINGDOM);
        t.put("ContractEvents.Executed", "On {when}, {faction} put {prisoner} to death. They would wait {respawnDelayMillis} to rise again, and were banished for {banishmentMillis}.",
                "{faction} put {prisoner} to death. Banished for {banishmentMillis}.", REALM);
        t.put("ContractEvents.Escaped", "On {when}, {prisoner} escaped from the cells of {faction}.",
                "{prisoner} escaped the cells of {faction}.", REALM);
        t.put("ContractEvents.Pardoned", "On {when}, {by} pardoned {prisoner}.",
                "{by} pardoned {prisoner}.", KINGDOM);
        t.put("ContractEvents.Released", "On {when}, {by} released {prisoner}.",
                "{by} released {prisoner}.", KINGDOM);
        t.put("ContractEvents.PrisonersFreed", "On {when}, the prisoners of {keep} walked free: {prisoners}.",
                "The prisoners of {keep} walked free: {prisoners}.", REALM);
        t.put("ContractEvents.Fulfilled", "On {when}, a contract made by right of {basis} ran its course.",
                "A contract by right of {basis} ran its course.", QUIET);
        t.put("ContractEvents.Resisted", "On {when}, a contract imposed by right of {basis} ended in resistance ({how}). No mark was set on anyone.",
                "A contract by right of {basis} ended in resistance.", KINGDOM);
        t.put("ContractEvents.Breached", "On {when}, {by} broke their word, and was marked for it.",
                "{by} broke their word.", REALM);
        t.put("ContractEvents.Ended", "On {when}, a contract made by right of {basis} ended early: {why}.",
                "A contract by right of {basis} ended early.", QUIET);
        // A player's own deposit is their business. An empty template keeps an event out of the books.
        t.put("ContractEvents.VaultDeposited", "", "", QUIET);

        // ---- ranks (RankEvents) ----
        t.put("RankEvents.RankAdded", "On {when}, {org} made a new rank: {name}.",
                "{org} has a new rank: {name}.", KINGDOM);
        t.put("RankEvents.RankRenamed", "On {when}, the rank of {from} in {org} was renamed {to}.",
                "In {org}, {from} is now {to}.", KINGDOM);
        t.put("RankEvents.RankRemoved", "On {when}, {org} did away with the rank of {name}.",
                "{org} did away with the rank of {name}.", KINGDOM);
        // Where a rank sits in the order is housekeeping, not news.
        t.put("RankEvents.RankMoved", "", "", QUIET);
        t.put("RankEvents.RankGiven", "On {when}, {player} was named {name} of {org}.",
                "{player} is now {name} of {org}.", KINGDOM);
        t.put("RankEvents.RankPowerGiven", "On {when}, {org} gave the rank of {name} the power to {power}.",
                "The {name} of {org} may now {power}.", QUIET);
        t.put("RankEvents.RankPowerTaken", "On {when}, {org} took from the rank of {name} the power to {power}.",
                "The {name} of {org} may no longer {power}.", QUIET);

        // ---- milestones (MilestoneEvents): the realm's news whoever caused them ----
        t.put("MilestoneEvents.MembersReached", "On {when}, {org} counted {count} members.",
                "{org} is {count} strong.", REALM);
        t.put("MilestoneEvents.FirstOf", "On {when}, {org} saw its first {what}.",
                "A first for {org}: {what}.", REALM);

        return t;
    }
}
