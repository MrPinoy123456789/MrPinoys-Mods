package pocketdungeons;

/**
 * What a cleared floor adds to its dealt step (design pass 2026-10-09, Q2): the act's bonus, plus the final
 * bonus on a dungeon's last floor; an Endless Mine floor earns a depth bonus instead of the act's. The
 * knobs live in {@link PocketDungeonsConfig}; the arithmetic is {@link ScrapMath}'s.
 */
final class FloorPay {

    private FloorPay() {}

    /**
     * @param def         the dungeon behind the floor, or {@code null} outside a dungeon graph
     * @param nodeId      the floor's node in that dungeon
     * @param mine        whether the floor is an Endless Mine floor
     * @param floorNumber for a Mine floor, how many floors deep it is (1 for the first)
     */
    static int bonus(DungeonDef def, String nodeId, boolean mine, int floorNumber) {
        if (mine) {
            return ScrapMath.endlessBonus(floorNumber, PocketDungeonsConfig.endlessDepthEvery(),
                    PocketDungeonsConfig.endlessDepthMax());
        }
        if (def == null) {
            return 0;
        }
        int bonus = ScrapMath.actBonus(def.act(), PocketDungeonsConfig.scrapPerAct());
        if (TripDoors.isFinal(def, nodeId)) {
            bonus += PocketDungeonsConfig.scrapFinalBonus();
        }
        return bonus;
    }
}
