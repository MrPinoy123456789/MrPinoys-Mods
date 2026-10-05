package pocketdungeons;

import java.util.Set;
import java.util.UUID;

/**
 * Who in a party may make the trip's decisions (design D15): preview and commit a
 * door, choose a branch, pull the HOME lever. Any member by default; with the
 * leader's whitelist on, only the leader and listed members. Pure logic, no
 * Minecraft imports; the live wrapper is {@link PartyDecide}.
 */
final class PartyDecisions {

    private PartyDecisions() {}

    /**
     * @param whitelistOn the leader's "decide whitelist" setting
     * @param leader      the party leader (the record owner)
     * @param listed      the members the leader has allowed to decide
     * @param actor       the player trying to decide
     */
    static boolean mayDecide(boolean whitelistOn, UUID leader, Set<UUID> listed, UUID actor) {
        if (actor == null) {
            return false;
        }
        if (actor.equals(leader) || !whitelistOn) {
            return true;
        }
        return listed != null && listed.contains(actor);
    }
}
