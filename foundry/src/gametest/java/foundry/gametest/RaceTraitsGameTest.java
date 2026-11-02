package foundry.gametest;

import foundry.core.RaceTraits;
import foundry.mc.RaceTraitsRuntime;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The race traits from race_traits.json (RaceTraitsRuntime), driven directly with the built in values: the strides,
 * the Human mount, the Fae's lighter landing and the senses. Movement is all server side (attribute modifiers and fall
 * damage), which is why Bedrock players get it too.
 */
public class RaceTraitsGameTest {
    private static final Identifier STRIDE = Identifier.fromNamespaceAndPath("foundry", "race_stride");
    private static final Identifier MOUNT_SPEED = Identifier.fromNamespaceAndPath("foundry", "race_mount_speed");

    private static ServerPlayer player(GameTestHelper helper, double x, double y, double z) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        var at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(x, y, z));
        player.snapTo(at.x, at.y, at.z, 0f, 0f);
        return player;
    }

    private static RaceTraits.Trait trait(String node) {
        return RaceTraits.defaults().of(node);
    }

    /** The folk of Mossbeard's Muddle walk faster on living ground (grass) and not on stone. */
    @GameTest(maxTicks = 40)
    public void mossbeardStridesOnLivingGround(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.GRASS_BLOCK);
        ServerPlayer p = player(helper, 2.5, 2, 2.5);
        p.setOnGround(true);
        var t = trait("race:mossbeard_s_muddle");
        RaceTraitsRuntime.stride(p, t);
        var speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        helper.assertTrue(speed.getModifier(STRIDE) != null && speed.getModifier(STRIDE).amount() == 0.15,
                "On grass the Old Forest walk should add 0.15 of base speed");
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE);
        RaceTraitsRuntime.stride(p, t);
        helper.assertTrue(speed.getModifier(STRIDE) == null, "On stone the stride stayed");
        RaceTraitsRuntime.stride(p, null);
        helper.succeed();
    }

    /** A Dwarf walks faster under a roof of blocks, not under open sky; a player of no race never does. */
    @GameTest(maxTicks = 60)
    public void dwarvesStrideUnderground(GameTestHelper helper) {
        ServerPlayer dwarf = player(helper, 2.5, 2, 2.5);
        ServerPlayer nobody = player(helper, 4.5, 2, 4.5);
        var t = trait("race:azrak");
        RaceTraitsRuntime.stride(dwarf, t);
        helper.assertTrue(dwarf.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(STRIDE) == null,
                "Under open sky a Dwarf should not get the underground stride");
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 6, z), Blocks.STONE);
        helper.runAfterDelay(10, () -> {
            RaceTraitsRuntime.stride(dwarf, t);
            RaceTraitsRuntime.stride(nobody, null);
            helper.assertTrue(dwarf.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(STRIDE) != null,
                    "Under a stone roof a Dwarf should get the underground stride");
            helper.assertTrue(nobody.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(STRIDE) == null,
                    "A player of no race got a stride");
            RaceTraitsRuntime.stride(dwarf, null);
            helper.succeed();
        });
    }

    /** A Human's horse runs faster while they ride it, and loses the boost the moment they get down. */
    @GameTest(maxTicks = 40)
    public void humansRideFaster(GameTestHelper helper) {
        ServerPlayer human = player(helper, 2.5, 2, 2.5);
        var horse = helper.spawn(EntityTypes.HORSE, new BlockPos(2, 2, 2));
        helper.assertTrue(human.startRiding(horse, true, false), "The Human could not get on the horse");
        var t = trait("race:aurelia");
        RaceTraitsRuntime.mount(human, t);
        var speed = horse.getAttribute(Attributes.MOVEMENT_SPEED);
        helper.assertTrue(speed.getModifier(MOUNT_SPEED) != null && speed.getModifier(MOUNT_SPEED).amount() == 0.2,
                "A Human's horse should get 0.2 more of its base speed");
        human.stopRiding();
        RaceTraitsRuntime.mount(human, t);
        helper.assertTrue(speed.getModifier(MOUNT_SPEED) == null, "The horse kept the boost after the Human got down");
        helper.succeed();
    }

    /**
     * The Fae take half the fall damage of anyone else from the same fall: the multiplier the LivingEntityFall mixin hands
     * vanilla is halved for a Fae and untouched for anyone else. (A mock player never takes fall damage, it counts as
     * creative, so the multiplier is checked rather than the health; the mixin itself is applied or the server would
     * not start, as mixins here are required.)
     */
    @GameTest(maxTicks = 40)
    public void theFaeLandLightly(GameTestHelper helper) {
        ServerPlayer fae = player(helper, 2.5, 2, 2.5);
        ServerPlayer other = player(helper, 4.5, 2, 4.5);
        RaceTraitsRuntime.assign(fae, trait("race:faeloria"));
        RaceTraitsRuntime.assign(other, null);
        float faeM = RaceTraitsRuntime.landing(fae, 1.0f), otherM = RaceTraitsRuntime.landing(other, 1.0f);
        RaceTraitsRuntime.assign(fae, null);
        helper.assertTrue(faeM == 0.5f && otherM == 1.0f, "Fall damage multiplier: the Fae " + faeM + ", anyone else " + otherM);
        helper.succeed();
    }

    /** A Dwarf smells a diamond ore close by and is pointed at it; nothing is found for a player of no race. */
    @GameTest(maxTicks = 40)
    public void dwarvesSmellOre(GameTestHelper helper) {
        helper.setBlock(new BlockPos(6, 2, 2), Blocks.DEEPSLATE_DIAMOND_ORE);
        helper.setBlock(new BlockPos(4, 2, 6), Blocks.IRON_ORE);
        ServerPlayer dwarf = player(helper, 2.5, 2, 2.5);
        var found = RaceTraitsRuntime.sense(dwarf, trait("race:azrak").sense());
        helper.assertTrue(helper.absolutePos(new BlockPos(6, 2, 2)).equals(found), "The Dwarf did not smell the diamond ore: " + found);
        helper.assertTrue(trait("race:nobody").sense() == null, "A player of no race has a sense");
        helper.succeed();
    }

    /** The Fae sense a rare herb (a torchflower) and not a common one (a dandelion). */
    @GameTest(maxTicks = 40)
    public void theFaeSenseHerbs(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.GRASS_BLOCK);
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.TORCHFLOWER);
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.GRASS_BLOCK);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.DANDELION);
        ServerPlayer fae = player(helper, 1.5, 2, 1.5);
        var found = RaceTraitsRuntime.sense(fae, trait("race:faeloria").sense());
        helper.assertTrue(helper.absolutePos(new BlockPos(5, 2, 5)).equals(found), "The Fae should be pointed at the torchflower: " + found);
        helper.succeed();
    }

    /** A Human senses a chest that still has its loot to roll, and not an ordinary chest. */
    @GameTest(maxTicks = 40)
    public void humansSenseUnopenedLoot(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.CHEST);
        helper.setBlock(new BlockPos(6, 2, 6), Blocks.CHEST);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(6, 2, 6)))
                instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity c) {
            c.setLootTable(net.minecraft.world.level.storage.loot.BuiltInLootTables.SIMPLE_DUNGEON);
        }
        ServerPlayer human = player(helper, 2.5, 2, 2.5);
        var found = RaceTraitsRuntime.sense(human, trait("race:aurelia").sense());
        helper.assertTrue(helper.absolutePos(new BlockPos(6, 2, 6)).equals(found), "The Human should sense the loot chest only: " + found);
        helper.succeed();
    }
}
