package kamutotems.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public record Kamuy(
        String name,
        Construct construct,
        String bornDateKey,
        int kamuDrunk,
        int saves,
        int bossesSlain,
        List<Slot> pool) {

    /**
     * Kamu the player has bound to this Kamuy but not yet assigned to a slot.
     *
     * <p>A kamu exists in one of three states: a loose <b>item</b> in the
     * world, a <b>pooled</b> entry here once bound, or <b>slotted</b> in the
     * construct. Binding is one-way per step and always explicit, which is what
     * keeps the panel from having to mirror live inventory contents -- the
     * source of the earlier "I imbued it and it's still in my inventory"
     * confusion.
     */
    public Kamuy {
        pool = pool == null ? List.of() : List.copyOf(pool);
    }

    /** Six-arg form for callers that have no pool yet. */
    public Kamuy(String name, Construct construct, String bornDateKey,
                 int kamuDrunk, int saves, int bossesSlain) {
        this(name, construct, bornDateKey, kamuDrunk, saves, bossesSlain, List.of());
    }

    public Kamuy withName(String sanitised) {
        String born = bornDateKey;
        if (born == null && sanitised != null) {
            born = LocalDate.now().toString();
        }
        return new Kamuy(sanitised, construct, born, kamuDrunk, saves, bossesSlain, pool);
    }

    public Kamuy withConstruct(Construct c) {
        return new Kamuy(name, c, bornDateKey, kamuDrunk, saves, bossesSlain, pool);
    }

    public Kamuy withPool(List<Slot> next) {
        return new Kamuy(name, construct, bornDateKey, kamuDrunk, saves, bossesSlain, next);
    }

    /** Bind a kamu into the pool. Also counts toward the journal. */
    public Kamuy addToPool(Slot slot) {
        List<Slot> next = new ArrayList<>(pool);
        next.add(slot);
        return new Kamuy(name, construct, bornDateKey, kamuDrunk + 1, saves, bossesSlain, next);
    }

    /** Take one entry out of the pool by index. */
    public Kamuy removeFromPool(int index) {
        if (index < 0 || index >= pool.size()) {
            return this;
        }
        List<Slot> next = new ArrayList<>(pool);
        next.remove(index);
        return withPool(next);
    }

    public List<String> journal() {
        List<String> lines = new ArrayList<>();
        String spirit = name != null ? name : "an unnamed spirit";
        lines.add(spirit + ".");
        if (bornDateKey != null) {
            lines.add("Forged on " + bornDateKey + ".");
        } else {
            lines.add("Not yet forged.");
        }
        lines.add("Has drunk from " + kamuDrunk + " spirits.");
        lines.add("Pulled you back from death " + saves + " times.");
        if (bossesSlain > 0) {
            lines.add("Walked with you through " + bossesSlain + " boss hunts.");
        }
        return lines;
    }
}
