package pocketdungeons;

import java.util.ArrayList;
import java.util.List;

/**
 * The Herobrine Cube's equip-cap arithmetic, with no Minecraft imports: same
 * discipline as {@link RerollMath} and {@link GambleMath}, for the same
 * reason.
 *
 * <p>M17: at most {@code cap} extracted powers active at once, counted by
 * distinct power id, not by slot. Wearing the same power on two imbued items
 * still counts once against the cap; it is "which powers do I run," not "how
 * many imbued items can I wear."
 */
final class PowerEquipMath {

    private PowerEquipMath() {}

    /**
     * The powers that are actually active, in slot order: the first {@code cap}
     * distinct, non-blank ids encountered. A slot whose power already made the
     * cut (worn twice) does not consume a second slot of it; a slot whose power
     * arrives after the cap is full is excess and reads as inactive.
     */
    static List<String> activePowers(List<String> presentInSlotOrder, int cap) {
        int clampedCap = Math.max(0, cap);
        List<String> active = new ArrayList<>();
        for (String power : presentInSlotOrder) {
            if (power == null || power.isBlank() || active.contains(power)) {
                continue;
            }
            if (active.size() >= clampedCap) {
                continue;
            }
            active.add(power);
        }
        return List.copyOf(active);
    }
}
