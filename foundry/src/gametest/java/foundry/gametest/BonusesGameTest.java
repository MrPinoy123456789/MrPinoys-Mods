package foundry.gametest;

import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * The race gathering bonuses through the real events: ore broken by a Dwarf gives more than the same ore broken by anyone
 * else, a Fairy's mob drops gain only dust, and a player with no race is untouched. The shipped example bonus files are
 * the data. The numbers are statistical, so each check uses enough rolls that a miss cannot happen by chance.
 */
public final class BonusesGameTest {
    private static final int ORE_BREAKS = 80;
    private static final int MOBS = 200;

    private static ServerPlayer player(GameTestHelper helper, String node) {
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        var at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 2, 2.5));
        player.snapTo(at.x, at.y, at.z, 0f, 0f);
        if (node != null) TestPerms.grant(player, node);
        return player;
    }

    /** Counts and removes every item near the arena, by item id. */
    private static Map<String, Integer> sweep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        AABB box = new AABB(origin).inflate(24);
        for (int cx = (origin.getX() - 24) >> 4; cx <= (origin.getX() + 24) >> 4; cx++) {
            for (int cz = (origin.getZ() - 24) >> 4; cz <= (origin.getZ() + 24) >> 4; cz++) {
                level.getChunk(cx, cz);
                level.waitForEntities(new ChunkPos(cx, cz), 0);
            }
        }
        Map<String, Integer> counts = new HashMap<>();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
            counts.merge(BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString(), item.getItem().getCount(), Integer::sum);
            item.discard();
        }
        return counts;
    }

    private static int breakIronOre(GameTestHelper helper, ServerPlayer player) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        sweep(helper);
        for (int i = 0; i < ORE_BREAKS; i++) {
            level.setBlockAndUpdate(pos, Blocks.IRON_ORE.defaultBlockState());
            player.gameMode.destroyBlock(pos);
        }
        return sweep(helper).getOrDefault("minecraft:raw_iron", 0);
    }

    @GameTest(maxTicks = 200)
    public void aDwarfGetsMoreOreThanAnyoneElse(GameTestHelper helper) {
        int plain = breakIronOre(helper, player(helper, null));
        helper.assertTrue(plain == ORE_BREAKS, "Without a race " + ORE_BREAKS + " ores gave " + plain + " raw iron");
        int dwarf = breakIronOre(helper, player(helper, "race:azrak"));
        helper.assertTrue(dwarf > ORE_BREAKS, "A Dwarf got no extra raw iron: " + dwarf + " from " + ORE_BREAKS + " ores");
        int fairy = breakIronOre(helper, player(helper, "race:everdelle"));
        helper.assertTrue(fairy == ORE_BREAKS, "A Fairy got extra iron, which belongs to the Dwarves: " + fairy);
        helper.succeed();
    }

    private static Map<String, Integer> kill(GameTestHelper helper, ServerPlayer player, EntityType<? extends LivingEntity> type) {
        ServerLevel level = helper.getLevel();
        var at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(6.5, 2, 6.5));
        sweep(helper);
        for (int i = 0; i < MOBS; i++) {
            LivingEntity mob = type.create(level, EntitySpawnReason.COMMAND);
            mob.snapTo(at.x, at.y, at.z, 0f, 0f);
            level.addFreshEntity(mob);
            mob.hurtServer(level, level.damageSources().playerAttack(player), 1000.0F);
        }
        return sweep(helper);
    }

    @GameTest(maxTicks = 400)
    public void aFairyGetsMoreGunpowderButNoOtherWitchDrop(GameTestHelper helper) {
        Map<String, Integer> plain = kill(helper, player(helper, null), EntityTypes.CREEPER);
        Map<String, Integer> fairy = kill(helper, player(helper, "race:everdelle"), EntityTypes.CREEPER);
        int plainPowder = plain.getOrDefault("minecraft:gunpowder", 0);
        int fairyPowder = fairy.getOrDefault("minecraft:gunpowder", 0);
        helper.assertTrue(plainPowder > 0, "The control creepers dropped no gunpowder at all");
        helper.assertTrue(fairyPowder > plainPowder + 10,
                "A Fairy got no extra gunpowder: " + fairyPowder + " against " + plainPowder + " for " + MOBS + " creepers");

        Map<String, Integer> plainWitch = kill(helper, player(helper, null), EntityTypes.WITCH);
        Map<String, Integer> fairyWitch = kill(helper, player(helper, "race:everdelle"), EntityTypes.WITCH);
        int plainSticks = plainWitch.getOrDefault("minecraft:stick", 0);
        int fairySticks = fairyWitch.getOrDefault("minecraft:stick", 0);
        helper.assertTrue(plainSticks > 0, "The control witches dropped no sticks, so the check proves nothing");
        helper.assertTrue(fairySticks < plainSticks + plainSticks / 4 + 20,
                "A Fairy got extra sticks from witches: " + fairySticks + " against " + plainSticks);
        helper.succeed();
    }
}
