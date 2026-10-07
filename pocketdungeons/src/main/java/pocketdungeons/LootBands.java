package pocketdungeons;

/**
 * The one place a dungeon floor's loot tier is clamped into its act's band (design
 * D10). Every path that turns a keystone into a floor's loot tier goes through here
 * (the stamp via {@link DifficultyProfile#of(int, int, DungeonDef.LootBand)}, the
 * completion chests, the door label), so the label can never disagree with the
 * chests. Mob strength and affixes do not read it: they stay on the keystone and step.
 */
final class LootBands {

    private LootBands() {}

    /** The band of the dungeon with {@code dungeonId}, or null outside a dungeon graph. */
    static DungeonDef.LootBand of(String dungeonId) {
        if (dungeonId == null || dungeonId.isEmpty()) {
            return null;
        }
        DungeonDef def = DungeonDefs.current().byId(dungeonId);
        return def == null ? null : def.lootBand();
    }

    /**
     * The band the floor behind {@code offer} stamps with. An Endless Mine floor uses its depth
     * layer's band (D13); any other floor uses its dungeon's band.
     */
    static DungeonDef.LootBand forFloor(InstanceRecord record, Keystone.Offer offer, boolean mine) {
        if (mine) {
            int floor = (record == null ? 0 : record.interval.floorIndex) + 1;
            return EndlessMineRules.layerBand(EndlessMineRules.layerOf(floor));
        }
        return of(offer.dungeonId());
    }

    /** The band of the dungeon a trip is in, or null (an Endless Mine, an untripped run). */
    static DungeonDef.LootBand of(InstanceRecord record) {
        return record == null ? null : of(record.interval.dungeonId);
    }

    /** {@code tier} clamped into {@code band}; unchanged when there is no band. */
    static int clamp(DungeonDef.LootBand band, int tier) {
        return band == null ? tier : band.clamp(tier);
    }

    /** The tier the door behind {@code offer} will pay: the keystone's, clamped into the dungeon's band. */
    static int offerTier(Keystone.Offer offer) {
        return clamp(of(offer.dungeonId()), KeystoneMath.lootTier(offer.level()));
    }

    /**
     * The completion chest tier of the floor {@code record} has just cleared: the
     * keystone's tier, the zone's escalation ({@link ZoneRules#lootTier}, an Endless
     * Mine thing), then the dungeon's band.
     */
    static int floorTier(InstanceRecord record, ZoneRules rules, int floorsCleared) {
        if (EndlessMineRules.isMine(record)) {
            return EndlessMineRules.lootTier(floorsCleared);
        }
        int base = DifficultyProfile.of(record.layout.pathLength(), record.layout.keystoneLevel()).lootTier();
        return clamp(of(record), rules.lootTier(base, floorsCleared));
    }
}
