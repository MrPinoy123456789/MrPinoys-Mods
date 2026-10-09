package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;

import java.util.List;

/** Q4 and Q5: the authored finales and uniform name real mobs and items, and the uniform dresses the right mobs. */
public final class FinaleWaveGameTest {

    @GameTest(maxTicks = 40)
    public void everyAuthoredFinaleNamesRealMobsAndItems(GameTestHelper helper) {
        int checked = 0;
        for (DungeonDef def : DungeonDefs.current().all()) {
            DungeonDef.Finale finale = def.finale();
            if (finale != null) {
                checked++;
                for (DungeonDef.FinaleMob mob : finale.mobs()) {
                    helper.assertTrue(BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(mob.type())).isPresent(),
                            def.id() + ": unknown finale mob " + mob.type());
                }
                if (finale.elite() != null) {
                    helper.assertTrue(BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(finale.elite().type()))
                            .isPresent(), def.id() + ": unknown elite " + finale.elite().type());
                    if (!finale.elite().mainhand().isEmpty()) {
                        helper.assertTrue(BuiltInRegistries.ITEM.getOptional(Identifier.parse(finale.elite().mainhand()))
                                .isPresent(), def.id() + ": unknown elite item " + finale.elite().mainhand());
                    }
                }
            }
            DungeonDef.MobUniform uniform = def.mobUniform();
            if (uniform != null) {
                for (String id : uniform.armour()) {
                    helper.assertTrue(BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).isPresent(),
                            def.id() + ": unknown uniform armour " + id);
                }
                if (!uniform.weapon().isEmpty()) {
                    helper.assertTrue(BuiltInRegistries.ITEM.getOptional(Identifier.parse(uniform.weapon())).isPresent(),
                            def.id() + ": unknown uniform weapon " + uniform.weapon());
                }
            }
        }
        helper.assertTrue(checked >= 7, "the data holds at least seven finales, found " + checked);
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void theCopperUniformDressesMeleeAndRangedMobsDifferently(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DungeonDef.MobUniform uniform = DungeonDefs.current().byId("pocketdungeons:copper_works").mobUniform();
        helper.assertTrue(uniform != null, "Copper Works has a uniform");
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));

        Mob husk = (Mob) EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
        Mob skeleton = (Mob) EntityTypes.SKELETON.create(level, EntitySpawnReason.COMMAND);
        Mob creeper = (Mob) EntityTypes.CREEPER.create(level, EntitySpawnReason.COMMAND);
        for (Mob mob : List.of(husk, skeleton, creeper)) {
            mob.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            MobUniforms.apply(mob, uniform);
        }
        int huskPieces = pieces(husk);
        int skeletonPieces = pieces(skeleton);
        helper.assertValueEqual(huskPieces, 1, "a melee mob wears one copper piece");
        helper.assertValueEqual(skeletonPieces, 2, "a ranged mob wears two");
        helper.assertValueEqual(pieces(creeper), 0, "a creeper wears nothing");
        helper.assertTrue(husk.getItemBySlot(EquipmentSlot.MAINHAND).is(net.minecraft.world.item.Items.COPPER_SWORD),
                "a melee mob holds the copper sword");
        helper.assertTrue(!skeleton.getItemBySlot(EquipmentSlot.MAINHAND).is(net.minecraft.world.item.Items.COPPER_SWORD),
                "a skeleton keeps its own weapon");
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET}) {
            if (!husk.getItemBySlot(slot).isEmpty()) {
                helper.assertTrue(husk.getDropChances().byEquipment(slot) == 0f, "nothing it wears drops");
            }
        }
        helper.succeed();
    }

    private static int pieces(Mob mob) {
        int n = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET}) {
            if (mob.getItemBySlot(slot).getItem().toString().contains("copper")) {
                n++;
            }
        }
        return n;
    }
}
