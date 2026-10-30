package foundry.core.mechanics;

import java.util.List;

/**
 * Trigger, conditions that must all pass, ordered actions, an optional cooldown in ticks and a probability from 0 to 1.
 * {@code name} labels the power in cooldown messages; {@code tag} links an on_bolt_hit mechanic to a launch_bolt action.
 * {@code chargeTicks} makes an on_use mechanic a hold-and-release one, like drawing a bow; 0 fires at once.
 * Conditions and actions name registered types only; no scripts, no commands.
 */
public record MechanicDef(Trigger trigger, List<Step> conditions, List<Step> actions, int cooldownTicks,
        double probability, String name, String tag, int chargeTicks) {
    public MechanicDef(Trigger trigger, List<Step> conditions, List<Step> actions, int cooldownTicks, double probability) {
        this(trigger, conditions, actions, cooldownTicks, probability, "", "", 0);
    }

    public MechanicDef(Trigger trigger, List<Step> conditions, List<Step> actions, int cooldownTicks,
            double probability, String name, String tag) {
        this(trigger, conditions, actions, cooldownTicks, probability, name, tag, 0);
    }

    /** True for an on_use mechanic that fires when the right click is released after being held for {@code chargeTicks}. */
    public boolean charged() {
        return chargeTicks > 0;
    }

    /** Hard limits so malformed content cannot cause unbounded work on the server thread. */
    public static final int MAX_MECHANICS_PER_ITEM = 8;
    public static final int MAX_ACTIONS = 16;
    public static final int MAX_CONDITIONS = 8;
    /** Longest hold a definition may ask for: ten seconds. */
    public static final int MAX_CHARGE_TICKS = 200;
}
