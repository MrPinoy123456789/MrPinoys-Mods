package pocketdungeons;

import java.util.ArrayList;
import java.util.List;

/**
 * The ruin around a wild lodestone, version 2 (owner, 2026-11-04: "upgrade the structures around the entrances"). A 9
 * by 9 plaza with its corners cut, a ring of edge stones, a carved inner square under the lodestone, a gateway arch
 * behind it (north) with a hanging light, and four corner pillars of different heights: the tallest (north west) carries
 * the clearing kingdom's banner, one holds a standing light, two are broken. Worn blocks are mixed in by a seed, so no
 * two ruins look the same. Every block comes from the dungeon's {@link Theme}, so a mine reads as a mine and the Gilded
 * Seam as the Nether. All real blocks: Bedrock players see it as Java players do.
 *
 * <p>This class is the pure plan ({@link #plan}, {@link #bannerOffset}, {@link Theme#of}); {@code WildLodestones.stamp}
 * places it, clears the space above, fills a foundation under the plaza down to solid ground, and never replaces a
 * player's block. {@code WildRuinTest} pins the plan.
 */
final class WildRuin {

    /** The ruin a newly placed lodestone gets. Version 1 was the old 5 by 5 ruin. */
    static final int VERSION = 2;
    /** Half the plaza's width: 9 by 9. */
    static final int RADIUS = 4;
    /** How high above the plaza is cleared of terrain (trees, grass, snow). */
    static final int CLEAR_HEIGHT = 7;
    /** How far under the plaza a foundation is filled in, so it never floats on a slope. */
    static final int FOUNDATION_DEPTH = 6;
    /** The share of floor and pillar blocks that are worn (cracked, mossy), in percent. */
    static final int WORN_PERCENT = 30;

    /** What a block in the plan is; the theme says which block it is made of. */
    enum Role { FLOOR, FLOOR_WORN, INNER, ACCENT, EDGE, PILLAR, PILLAR_WORN, CAP, POST, LINTEL, LIGHT_STANDING,
        LIGHT_HANGING, COBWEB }

    /** One block of the plan, relative to the lodestone block (the floor is at {@code dy} -1). */
    record Part(int dx, int dy, int dz, Role role) {}

    /** The look of a ruin, chosen from the dungeon it opens onto. */
    enum Theme {
        STONE, MINE, COPPER, NETHER, END, DEEP;

        /** The theme for an offering's {@code variety} and {@code type}. Unknown varieties are plain stone. */
        static Theme of(String variety, String type) {
            String v = variety == null ? "" : variety;
            return switch (v) {
                case "nether" -> NETHER;
                case "end" -> END;
                case "copper" -> COPPER;
                case "deepslate", "deep", "dive" -> DEEP;
                case "coal", "mineshaft" -> MINE;
                default -> WildDungeons.MINE.equals(type) ? MINE : STONE;
            };
        }
    }

    private WildRuin() {}

    /** Whether a column is part of the plaza: inside the square, the four corners cut off. */
    static boolean inFootprint(int dx, int dz) {
        return Math.abs(dx) <= RADIUS && Math.abs(dz) <= RADIUS && !(Math.abs(dx) == RADIUS && Math.abs(dz) == RADIUS);
    }

    /** Where the banner of the clearing kingdom stands, from the lodestone block, for a ruin of this version. */
    static int[] bannerOffset(int ruinVersion) {
        return ruinVersion >= 2 ? new int[] {-3, 4, -3} : new int[] {-2, 3, -2};
    }

    /** The whole plan. {@code seed} picks which blocks are worn. */
    static List<Part> plan(long seed) {
        List<Part> parts = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (!inFootprint(dx, dz)) {
                    continue;
                }
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                Role role;
                if (ring == 0) {
                    role = Role.INNER;
                } else if (ring == 1) {
                    role = Math.abs(dx) == 1 && Math.abs(dz) == 1 ? Role.ACCENT : Role.INNER;
                } else if (ring == RADIUS) {
                    role = Role.EDGE;
                } else {
                    role = worn(seed, dx, -1, dz) ? Role.FLOOR_WORN : Role.FLOOR;
                }
                parts.add(new Part(dx, -1, dz, role));
            }
        }
        // The gateway arch, two blocks behind the lodestone: two posts, a lintel and a light hung under it.
        for (int dy = 0; dy <= 3; dy++) {
            parts.add(new Part(-2, dy, -2, Role.POST));
            parts.add(new Part(2, dy, -2, Role.POST));
        }
        for (int dx = -2; dx <= 2; dx++) {
            parts.add(new Part(dx, 4, -2, Role.LINTEL));
        }
        parts.add(new Part(0, 3, -2, Role.LIGHT_HANGING));
        // Four corner pillars: the banner pillar (north west), a broken one with a cobweb (north east), one holding a
        // light (south west) and a stub (south east).
        pillar(parts, seed, -3, -3, 3, true);
        pillar(parts, seed, 3, -3, 3, false);
        parts.add(new Part(3, 3, -3, Role.COBWEB));
        pillar(parts, seed, -3, 3, 2, false);
        parts.add(new Part(-3, 2, 3, Role.LIGHT_STANDING));
        pillar(parts, seed, 3, 3, 1, false);
        return parts;
    }

    /** A pillar {@code height} blocks tall from the floor; a capped one has a cap stone on top of that. */
    private static void pillar(List<Part> parts, long seed, int dx, int dz, int height, boolean capped) {
        for (int dy = 0; dy < height; dy++) {
            parts.add(new Part(dx, dy, dz, worn(seed, dx, dy, dz) ? Role.PILLAR_WORN : Role.PILLAR));
        }
        if (capped) {
            parts.add(new Part(dx, height, dz, Role.CAP));
        }
    }

    /** Whether the block at this offset is a worn one, from the seed. */
    static boolean worn(long seed, int dx, int dy, int dz) {
        long h = seed ^ (dx * 0x9E3779B97F4A7C15L) ^ (dy * 0xC2B2AE3D27D4EB4FL) ^ (dz * 0x165667B19E3779F9L);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return Math.floorMod(h, 100) < WORN_PERCENT;
    }
}
