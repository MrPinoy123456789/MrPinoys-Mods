package charter.gov;

import charter.grant.Power;

import java.util.List;

/**
 * The 18 powers, gathered into the 13 things a player reads about on their
 * Charter pages. Every power is in exactly one group (a test proves it), so a
 * new power can never be left off the page.
 */
public enum PowerGroup {
    MEMBERS(Power.MANAGE_MEMBERS),
    OFFICES(Power.APPOINT),
    TREASURY(Power.SPEND),
    LAND(Power.PLACE_CORE, Power.CLAIM),
    GRANARY_IN(Power.GRANARY_DEPOSIT),
    GRANARY_OUT(Power.GRANARY_WITHDRAW),
    GARRISON(Power.GARRISON),
    JUSTICE(Power.ARREST, Power.JUDGE),
    PARDON(Power.PARDON),
    WAR(Power.RAISE_CAMP, Power.DECLARE_WAR, Power.CHOOSE_FALL),
    DIPLOMACY(Power.NEGOTIATE),
    SUBORGS(Power.CREATE_SUBORG),
    CHARTER(Power.AMEND, Power.DISSOLVE);

    public final List<Power> powers;

    PowerGroup(Power... powers) {
        this.powers = List.of(powers);
    }

    public static PowerGroup of(Power power) {
        for (PowerGroup group : values()) {
            if (group.powers.contains(power)) return group;
        }
        throw new IllegalArgumentException("no group for " + power);
    }
}
