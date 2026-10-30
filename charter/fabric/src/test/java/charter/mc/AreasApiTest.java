package charter.mc;

import charter.api.Area;
import charter.api.CharterAreas;

import static charter.mc.FabricSuite.check;

/** The public land map: what it lists, how it answers queries, and that it empties when the world stops. */
final class AreasApiTest {

    private AreasApiTest() {
    }

    static void run() {
        FullState s = FullState.build();
        check("areas: empty before anything is published", CharterAreas.areas().isEmpty() && !CharterAreas.isHeld("minecraft:overworld", 0, 0));

        CharterAreas.publish(s.state);
        long first = CharterAreas.version();
        check("areas: the version rises when published", first > 0);
        check("areas: a keep, an outpost and loose claims are all listed", CharterAreas.areas().size() >= 3);

        Area keep = CharterAreas.areaAt("minecraft:overworld", 0, 0).orElseThrow();
        check("areas: the keep chunk belongs to a keep area", keep.kind() == Area.Kind.KEEP);
        check("areas: with the kingdom's name", keep.kingdom().length() > 0);
        check("areas: a claim assigned to the keep is part of it", CharterAreas.areaAt("minecraft:overworld", 2, 0).orElseThrow() == keep);
        check("areas: the bounds cover its chunks", keep.minChunkX() <= 0 && keep.maxChunkX() >= 2
                && keep.minBlockX() == keep.minChunkX() * 16 && keep.maxBlockX() == keep.maxChunkX() * 16 + 15);

        Area camp = CharterAreas.areaAt("minecraft:overworld", 6, 0).orElseThrow();
        check("areas: the outpost chunk belongs to an outpost area", camp.kind() == Area.Kind.OUTPOST);
        check("areas: contested land is still held", CharterAreas.isHeld("minecraft:overworld", 3, 3)
                && CharterAreas.areaAt("minecraft:overworld", 3, 3).orElseThrow().kind() == Area.Kind.CLAIM);

        check("areas: open ground is not held", !CharterAreas.isHeld("minecraft:overworld", 50, 50));
        check("areas: another dimension is not held", !CharterAreas.isHeld("minecraft:the_nether", 0, 0));
        check("areas: a block is asked by its chunk", CharterAreas.isHeldAtBlock("minecraft:overworld", 5, 5)
                && !CharterAreas.isHeldAtBlock("minecraft:overworld", 800, 800));
        check("areas: a box that touches held land is caught", CharterAreas.intersects("minecraft:overworld", 100, 100, 110, 110) == false
                && CharterAreas.intersects("minecraft:overworld", -40, -40, 20, 20)
                && CharterAreas.intersects("minecraft:overworld", 20, 20, -40, -40));
        check("areas: a box clear of everything is not", !CharterAreas.intersects("minecraft:overworld", 1000, 1000, 1100, 1100));
        check("areas: per dimension lists", CharterAreas.areas("minecraft:overworld").size() == CharterAreas.areas().size()
                && CharterAreas.areas("minecraft:the_nether").isEmpty());

        // A published snapshot never changes: an old Area keeps its answer after the map is replaced.
        CharterAreas.publish(s.state);
        check("areas: a newer publish has a higher version", CharterAreas.version() > first);
        check("areas: an Area kept from before still reads the same", keep.chunks().size() >= 2);

        CharterAreas.clear();
        check("areas: cleared when the world stops", CharterAreas.areas().isEmpty() && !CharterAreas.isHeld("minecraft:overworld", 0, 0));
    }
}
