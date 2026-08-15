package kamutotems.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class KamuCatalog {
    private final Map<String, Kamu> byId;

    public KamuCatalog(List<Kamu> kamu) {
        Map<String, Kamu> map = new LinkedHashMap<>();
        if (kamu != null) {
            for (Kamu k : kamu) {
                if (k != null && k.id() != null) {
                    map.put(k.id(), k);
                }
            }
        }
        this.byId = Collections.unmodifiableMap(map);
    }

    public static KamuCatalog defaults() {
        Set<HostType> totem = Set.of(HostType.TOTEM);
        Set<HostType> totemBoss = Set.of(HostType.TOTEM, HostType.BOSS);

        List<Kamu> kamu = List.of(
                // ---- Deliveries -------------------------------------------
                new Kamu("hit", "Hit", Category.DELIVERY, Rarity.COMMON, 0, "hit",
                        Map.of(), Set.of("default"), totem, Polarity.NONE),
                // ---- Default modifier ---------------------------------------
                new Kamu("plain", "Plain", Category.BEHAVIOUR, Rarity.COMMON, 0, "plain",
                        Map.of(), Set.of("default"), totem, Polarity.NONE),
                // ---- Deliveries continued -----------------------------------
                new Kamu("echo", "Echo", Category.DELIVERY, Rarity.UNCOMMON, 2, "echo",
                        Map.of(), Set.of(), totem, Polarity.NONE),
                new Kamu("splash", "Splash", Category.DELIVERY, Rarity.UNCOMMON, 2, "splash",
                        Map.of(), Set.of(), totem, Polarity.NONE),
                // ---- Elements -------------------------------------------------
                // All bane. No resistances or immunities: dual (a kamu with a
                // friendly AND a hostile reading) is not used anywhere in this
                // catalog. The mechanism stays live in core -- see
                // CombatApiTest's synthetic dual kamu -- for a future kamu
                // that actually needs both readings.
                new Kamu("fire", "Fire", Category.ELEMENT, Rarity.COMMON, 1, "fire",
                        Map.of(), Set.of(), totemBoss, Polarity.BANE),
                new Kamu("ice", "Ice", Category.ELEMENT, Rarity.COMMON, 1, "ice",
                        Map.of(), Set.of(), totemBoss, Polarity.BANE),
                new Kamu("poison", "Poison", Category.ELEMENT, Rarity.RARE, 1, "poison",
                        Map.of(), Set.of(), totemBoss, Polarity.BANE),
                new Kamu("wither", "Wither", Category.ELEMENT, Rarity.RARE, 2, "wither",
                        Map.of(), Set.of(), totemBoss, Polarity.BANE),
                new Kamu("lightning", "Lightning", Category.ELEMENT, Rarity.UNCOMMON, 1, "lightning",
                        Map.of(), Set.of(), totemBoss, Polarity.BANE),
                // ---- Behaviours ---------------------------------------------
                new Kamu("heal", "Heal", Category.BEHAVIOUR, Rarity.RARE, 2, "heal",
                        Map.of(), Set.of(), totemBoss, Polarity.BOON),
                new Kamu("absorption", "Absorption", Category.BEHAVIOUR, Rarity.UNCOMMON, 1, "absorption",
                        Map.of(), Set.of(), totemBoss, Polarity.BOON));

        return new KamuCatalog(kamu);
    }

    public Kamu get(String id) {
        return byId.get(id);
    }

    public List<Kamu> byCategory(Category c) {
        List<Kamu> out = new ArrayList<>();
        for (Kamu k : byId.values()) {
            if (k.category() == c) {
                out.add(k);
            }
        }
        return out;
    }

    public List<Kamu> all() {
        return new ArrayList<>(byId.values());
    }

    public List<Kamu> bossPool() {
        List<Kamu> out = new ArrayList<>();
        for (Kamu k : byId.values()) {
            // Defaults are baseline, not loot -- a boss must never drop one.
            if (k.tags().contains("default")) {
                continue;
            }
            if (k.allowedHosts().contains(HostType.BOSS)
                    && (k.category() == Category.ELEMENT || k.category() == Category.BEHAVIOUR)) {
                out.add(k);
            }
        }
        return out;
    }

    /** All kamu that are legal aura modifiers. */
    public List<Kamu> auraModifiers() {
        List<Kamu> out = new ArrayList<>();
        for (Kamu k : byId.values()) {
            if (k.polarity() != Polarity.NONE) {
                out.add(k);
            }
        }
        return out;
    }

    /** All kamu that are deliveries. */
    public List<Kamu> deliveries() {
        List<Kamu> out = new ArrayList<>();
        for (Kamu k : byId.values()) {
            if (k.category() == Category.DELIVERY) {
                out.add(k);
            }
        }
        return out;
    }
}
