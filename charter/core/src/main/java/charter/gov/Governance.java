package charter.gov;

import charter.grant.Power;

import java.util.EnumMap;
import java.util.Map;

/**
 * How an organization makes decisions (DESIGN.md §6.3). Every power can always
 * be exercised by a vote of the members; offices and grants add other ways.
 * Presets fill this in at founding and nothing branches on the preset after.
 */
public final class Governance {

    public final Preset preset;
    public VoteMethod defaultMethod = VoteMethod.MAJORITY;
    /** Share of active members who must cast a ballot for a vote to count. */
    public double quorumShare = 0.5;
    private final EnumMap<Power, VoteMethod> methods = new EnumMap<>(Power.class);

    public Governance(Preset preset) {
        this.preset = preset;
    }

    /** The method for votes exercising this power; null power means a vote with no power (an election). */
    public VoteMethod method(Power power) {
        return power == null ? defaultMethod : methods.getOrDefault(power, defaultMethod);
    }

    public void setMethod(Power power, VoteMethod method) {
        methods.put(power, method);
    }

    public Map<Power, VoteMethod> methods() {
        return java.util.Collections.unmodifiableMap(new EnumMap<>(methods));
    }
}
