package pocketdungeons;

import java.util.List;

/** Q5: which mobs wear a dungeon\u0027s uniform, and how many pieces. */
public class MobUniformsTest {

    public static void main(String[] args) {
        eq(MobUniforms.kindOf("minecraft:zombie"), MobUniforms.Kind.MELEE);
        eq(MobUniforms.kindOf("minecraft:husk"), MobUniforms.Kind.MELEE);
        eq(MobUniforms.kindOf("minecraft:vindicator"), MobUniforms.Kind.MELEE);
        eq(MobUniforms.kindOf("minecraft:skeleton"), MobUniforms.Kind.RANGED);
        eq(MobUniforms.kindOf("minecraft:stray"), MobUniforms.Kind.RANGED);
        eq(MobUniforms.kindOf("minecraft:creeper"), MobUniforms.Kind.NONE);
        eq(MobUniforms.kindOf("minecraft:cave_spider"), MobUniforms.Kind.NONE);
        eq(MobUniforms.kindOf("minecraft:silverfish"), MobUniforms.Kind.NONE);
        eq(MobUniforms.kindOf("minecraft:slime"), MobUniforms.Kind.NONE);
        eq(MobUniforms.kindOf(""), MobUniforms.Kind.NONE);

        DungeonDef.MobUniform copper = new DungeonDef.MobUniform(
                List.of("minecraft:copper_helmet", "minecraft:copper_chestplate", "minecraft:copper_leggings",
                        "minecraft:copper_boots"), 1, 2, "minecraft:copper_sword", 1.0);
        eq(MobUniforms.pieces(MobUniforms.Kind.MELEE, copper), 1);
        eq(MobUniforms.pieces(MobUniforms.Kind.RANGED, copper), 2);
        eq(MobUniforms.pieces(MobUniforms.Kind.NONE, copper), 0);
        System.out.println("MobUniformsTest passed");
    }

    private static void eq(Object actual, Object expected) {
        if (!actual.equals(expected)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
