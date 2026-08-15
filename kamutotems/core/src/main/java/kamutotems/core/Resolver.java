package kamutotems.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Resolver {

    private static final double[] TIER_MULT = { 1.0, 1.6, 2.5 };

    private final KamuCatalog catalog;
    private final ReactionEngine reactions;

    public Resolver(KamuCatalog catalog, ReactionEngine reactions) {
        this.catalog = catalog;
        this.reactions = reactions;
    }

    public ResolutionResult resolve(Construct construct, Context context, long seed) {
        List<String> faults = new ArrayList<>();

        if (construct == null || construct.slots() == null) {
            return new ResolutionResult(false,
                    "Nothing is slotted. Add a kamu to begin.",
                    List.of(), Construct.DEFAULT_DELIVERY);
        }

        boolean any = false;
        for (Slot s : construct.slots()) {
            if (s != null) {
                any = true;
                break;
            }
        }
        if (!any) {
            return new ResolutionResult(false,
                    "Nothing is slotted. Add a kamu to begin.",
                    List.of(), Construct.DEFAULT_DELIVERY);
        }

        List<Slot> all = construct.slots();
        for (int i = 0; i < all.size(); i++) {
            Slot s = all.get(i);
            if (s == null) {
                continue;
            }
            Kamu k = catalog.get(s.kamuId());
            if (k == null) {
                faults.add("One of these spirits isn't something this totem recognises.");
                continue;
            }
            if (!k.allowedHosts().contains(construct.host())) {
                faults.add(k.displayName() + " can't be used here.");
                continue;
            }
            // Typed slots: a delivery cannot hide in a modifier slot, etc.
            SlotRole role = Construct.roleOf(i);
            if (!role.accepts(k.category())) {
                faults.add(k.displayName() + " doesn't belong in the "
                        + role.label() + " slot.");
            }
        }

        if (!faults.isEmpty()) {
            return new ResolutionResult(false, faults.get(0), List.of(),
                    Construct.DEFAULT_DELIVERY);
        }

        // Only the two modifier slots produce effects; the delivery slot selects
        // how and when those effects land.
        List<Effect> base = new ArrayList<>();
        for (Slot s : construct.modifiers()) {
            if (s == null) {
                continue;
            }
            Kamu k = catalog.get(s.kamuId());
            if (k == null) {
                continue;
            }
            Map<String, Double> scaled = new LinkedHashMap<>();
            double mult = TIER_MULT[s.tier() - 1];
            for (Map.Entry<String, Double> e : k.parameters().entrySet()) {
                scaled.put(e.getKey(), e.getValue() * mult);
            }
            base.add(new Effect(k.effectId(), context == null ? null : context.targetId(), scaled));
        }

        ReactionOutcome outcome = reactions.react(base, context);
        String deliveryId = construct.deliveryKamuId();
        return new ResolutionResult(true, null, outcome.effects(), deliveryId);
    }
}
