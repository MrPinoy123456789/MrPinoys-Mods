package kamutotems.core;

import java.util.ArrayList;
import java.util.List;

/**
 * An arrangement of kamu. The frozen contract's section 3.3 shape:
 * one Delivery, two Modifiers.
 *
 * <p><b>Slot layout is fixed and typed</b> (see {@link SlotRole}):
 *
 * <pre>
 *   index 0  DELIVERY    optional -- empty means the default "hit" delivery
 *   index 1  MODIFIER
 *   index 2  MODIFIER
 * </pre>
 */
public record Construct(
        HostType host,
        List<Slot> slots) {

    /** One delivery plus two modifiers. */
    public static final int SLOT_COUNT = 3;

    public static SlotRole roleOf(int index) {
        return switch (index) {
            case 0 -> SlotRole.DELIVERY;
            case 1, 2 -> SlotRole.MODIFIER;
            default -> throw new IllegalArgumentException("No such slot: " + index);
        };
    }

    /** The baseline kamu that occupies a slot when nothing better is in it. */
    public static final String DEFAULT_DELIVERY = "hit";
    public static final String DEFAULT_MODIFIER = "plain";

    /** The default kamu for a slot role. */
    public static Slot defaultFor(int index) {
        return switch (roleOf(index)) {
            case DELIVERY -> new Slot(DEFAULT_DELIVERY, 1);
            case MODIFIER -> new Slot(DEFAULT_MODIFIER, 1);
        };
    }

    /** True if this slot holds only its baseline kamu. */
    public static boolean isDefault(int index, Slot slot) {
        Slot def = defaultFor(index);
        if (slot == null) {
            return def == null;
        }
        return def.kamuId().equals(slot.kamuId());
    }

    /**
     * A fresh construct: defaults in all three slots.
     * A totem is never blank -- see the note on the default kamu in
     * {@link KamuCatalog#defaults()}.
     */
    public static Construct empty(HostType host) {
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots.add(defaultFor(i));
        }
        return new Construct(host, slots);
    }

    /** A construct with every slot genuinely null. Used by tests and loaders. */
    public static Construct blank(HostType host) {
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots.add(null);
        }
        return new Construct(host, slots);
    }

    /** The kamu id in the delivery slot, or {@code "hit"} for an empty slot. */
    public String deliveryKamuId() {
        if (slots == null || slots.isEmpty()) {
            return DEFAULT_DELIVERY;
        }
        Slot s = slots.get(0);
        return s == null ? DEFAULT_DELIVERY : s.kamuId();
    }

    /** The two modifier slots. */
    public List<Slot> modifiers() {
        List<Slot> out = new ArrayList<>();
        if (slots == null) {
            return out;
        }
        for (int i = 1; i < slots.size() && i < SLOT_COUNT; i++) {
            out.add(slots.get(i));
        }
        return out;
    }

    /** Complexity counts all slotted kamu. */
    public int complexity(KamuCatalog catalog) {
        if (catalog == null || slots == null) {
            return 0;
        }
        int total = 0;
        for (Slot s : slots) {
            if (s == null) {
                continue;
            }
            Kamu k = catalog.get(s.kamuId());
            if (k != null) {
                total += k.complexity();
            }
        }
        return total;
    }
}
