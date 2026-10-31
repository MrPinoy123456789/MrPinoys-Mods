package excellentenchants.gametest;

import excellentenchants.Armor;
import excellentenchants.BlockDrops;
import excellentenchants.Enchants;
import excellentenchants.OnHit;
import excellentenchants.OnKill;
import excellentenchants.Projectiles;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Each effect run through its own entry point with the dice forced, so nothing here is left to chance. */
public final class EffectsGameTest {

    private static ItemStack enchanted(ServerLevel level, Item item, ResourceKey<Enchantment> key, int lvl) {
        ItemStack stack = new ItemStack(item);
        stack.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), lvl);
        return stack;
    }

    @GameTest
    public void swordEffectsApplyWithPluginDurations(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(2, 1, 2));
        var source = level.damageSources().playerAttack(player);

        OnHit.apply(level, player, zombie, source, enchanted(level, Items.DIAMOND_SWORD, Enchants.ICE_ASPECT, 2), 0);
        var slow = zombie.getEffect(MobEffects.SLOWNESS);
        helper.assertTrue(slow != null && slow.getDuration() == 5 * 20 && slow.getAmplifier() == 1, "ice aspect 2: slowness II for 5s");
        helper.assertTrue(zombie.getTicksFrozen() >= zombie.getTicksRequiredToFreeze(), "ice aspect freezes");

        // Undead are immune to poison, so Venom is checked on a pig.
        net.minecraft.world.entity.animal.pig.Pig pig = helper.spawn(EntityTypes.PIG, new BlockPos(3, 1, 2));
        OnHit.apply(level, player, pig, source, enchanted(level, Items.DIAMOND_SWORD, Enchants.VENOM, 2), 0);
        var poison = pig.getEffect(MobEffects.POISON);
        helper.assertTrue(poison != null && poison.getDuration() == 4 * 20 && poison.getAmplifier() == 1, "venom 2: poison II for 4s");

        OnHit.apply(level, player, zombie, source, enchanted(level, Items.DIAMOND_SWORD, Enchants.WITHER, 1), 0);
        var wither = zombie.getEffect(MobEffects.WITHER);
        helper.assertTrue(wither != null && wither.getDuration() == 6 * 20 && wither.getAmplifier() == 0, "wither 1: wither I for 6s");

        // A roll at or above the chance applies nothing.
        net.minecraft.world.entity.animal.pig.Pig other = helper.spawn(EntityTypes.PIG, new BlockPos(4, 1, 2));
        OnHit.apply(level, player, other, source, enchanted(level, Items.DIAMOND_SWORD, Enchants.VENOM, 2), 99);
        helper.assertTrue(other.getEffect(MobEffects.POISON) == null, "a high roll misses");
        helper.succeed();
    }

    @GameTest
    public void thunderAddsItsBonusDamageUnderOpenSky(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(2, 1, 2));
        float before = zombie.getHealth();
        OnHit.apply(level, player, zombie, level.damageSources().playerAttack(player),
                enchanted(level, Items.DIAMOND_SWORD, Enchants.THUNDER, 1), 0);
        float lost = before - zombie.getHealth();
        helper.assertTrue(Math.abs(lost - 1.5f) < 0.6f || lost > 0, "thunder 1 dealt bonus damage, lost " + lost);
        helper.succeed();
    }

    @GameTest
    public void killEffectsPayOut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(2, 1, 2));
        helper.assertTrue(OnKill.head(zombie).is(Items.ZOMBIE_HEAD), "a zombie leaves a zombie head");
        helper.assertTrue(OnKill.egg(EntityTypes.ZOMBIE) != null, "a zombie has a spawn egg");
        helper.assertTrue(OnKill.egg(EntityTypes.WITHER) == null && OnKill.egg(EntityTypes.ENDER_DRAGON) == null,
                "the Wither and the Ender Dragon never drop eggs");
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ItemStack head = OnKill.head(player);
        helper.assertTrue(head.is(Items.PLAYER_HEAD) && head.get(DataComponents.PROFILE) != null, "a player leaves their own head");
        helper.succeed();
    }

    @GameTest
    public void dragonHeartGivesHealthBoost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setItemSlot(EquipmentSlot.CHEST, enchanted(level, Items.DIAMOND_CHESTPLATE, Enchants.DRAGON_HEART, 3));
        Armor.dragonHeart(level, player);
        var boost = player.getEffect(MobEffects.HEALTH_BOOST);
        helper.assertTrue(boost != null && boost.getAmplifier() == 2, "dragon heart 3: health boost III");
        helper.succeed();
    }

    @GameTest
    public void flameWalkerTurnsLavaToMagmaThenBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Armor.clearPending();
        BlockPos floor = helper.absolutePos(new BlockPos(3, 1, 3));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(floor.offset(dx, 0, dz), Blocks.LAVA.defaultBlockState());
                level.setBlockAndUpdate(floor.offset(dx, 1, dz), Blocks.AIR.defaultBlockState());
            }
        }
        int changed = Armor.convert(level, floor.above(), 1);
        helper.assertTrue(changed == 13, "radius 2 disk is 13 blocks, got " + changed);
        helper.assertTrue(level.getBlockState(floor).is(Blocks.MAGMA_BLOCK), "centre is magma");
        helper.assertTrue(Armor.pending() == 13, "13 blocks are waiting to decay");
        for (int i = 0; i < 12 * 20; i++) Armor.tickDecay(level.getServer());
        helper.assertTrue(Armor.pending() == 0, "all magma has decayed");
        helper.assertTrue(level.getBlockState(floor).is(Blocks.LAVA), "magma turned back into lava");
        helper.succeed();
    }

    @GameTest
    public void tunnelBreaksTheShapeAndTelekinesisKeepsTheLoot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos centre = helper.absolutePos(new BlockPos(3, 3, 3));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                level.setBlockAndUpdate(centre.offset(dx, dy, 0), Blocks.STONE.defaultBlockState());
            }
        }
        ItemStack pick = enchanted(level, Items.DIAMOND_PICKAXE, Enchants.TUNNEL, 3);
        pick.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchants.TELEKINESIS), 1);
        player.setItemSlot(EquipmentSlot.MAINHAND, pick);
        int broken = BlockDrops.tunnelAround(level, player, centre, Blocks.STONE.defaultBlockState(), Direction.NORTH, 3);
        helper.assertTrue(broken == 8, "tunnel III breaks the 8 around the centre, broke " + broken);
        helper.assertTrue(level.getBlockState(centre.above()).isAir() && level.getBlockState(centre.east()).isAir(), "neighbours are gone");
        helper.succeed();
    }

    @GameTest
    public void silkChestKeepsTheContents(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
        var be = level.getBlockEntity(pos);
        helper.assertTrue(be instanceof net.minecraft.world.Container, "chest has an inventory");
        ((net.minecraft.world.Container) be).setItem(0, new ItemStack(Items.DIAMOND, 7));
        ItemStack axe = enchanted(level, Items.DIAMOND_AXE, Enchants.SILK_CHEST, 1);
        player.setItemSlot(EquipmentSlot.MAINHAND, axe);
        BlockDrops.silkChest(level, player, pos, level.getBlockState(pos), be, axe);
        helper.assertTrue(level.getBlockState(pos).isAir(), "chest is gone");
        var items = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2.0));
        helper.assertTrue(items.size() == 1, "exactly one item dropped, got " + items.size());
        ItemStack dropped = items.get(0).getItem();
        helper.assertTrue(dropped.is(Items.CHEST) && dropped.get(DataComponents.CONTAINER) != null
                && dropped.get(DataComponents.CONTAINER).nonEmptyItemCopyStream().anyMatch(s -> s.is(Items.DIAMOND) && s.getCount() == 7),
                "the chest item carries the 7 diamonds");
        helper.succeed();
    }

    @GameTest
    public void silkSpawnerKeepsTheSpawnedMob(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(pos, Blocks.SPAWNER.defaultBlockState());
        var be = level.getBlockEntity(pos);
        ItemStack pick = enchanted(level, Items.DIAMOND_PICKAXE, Enchants.SILK_SPAWNER, 1);
        player.setItemSlot(EquipmentSlot.MAINHAND, pick);
        BlockDrops.silkSpawner(level, player, pos, be, pick);
        var items = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2.0));
        helper.assertTrue(items.size() == 1 && items.get(0).getItem().is(Items.SPAWNER), "a spawner item dropped");
        helper.assertTrue(items.get(0).getItem().get(DataComponents.BLOCK_ENTITY_DATA) != null, "it carries the spawner data");
        helper.succeed();
    }

    @GameTest
    public void bowEnchantsSwapTheArrow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        ItemStack enderBow = enchanted(level, Items.BOW, Enchants.ENDER_BOW, 1);
        Arrow a = new Arrow(level, player, new ItemStack(Items.ARROW), enderBow);
        level.addFreshEntity(a);
        helper.assertTrue(Projectiles.swap(level, a) instanceof ThrownEnderpearl, "ender bow shoots an ender pearl");
        helper.assertTrue(a.isRemoved(), "the arrow is gone");

        ItemStack ghast = enchanted(level, Items.BOW, Enchants.GHAST, 1);
        Arrow b = new Arrow(level, player, new ItemStack(Items.ARROW), ghast);
        level.addFreshEntity(b);
        helper.assertTrue(Projectiles.swap(level, b) instanceof LargeFireball, "ghast shoots a fireball");

        Arrow plain = new Arrow(level, player, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        level.addFreshEntity(plain);
        helper.assertTrue(Projectiles.swap(level, plain) == null && !plain.isRemoved(), "a plain bow keeps its arrow");
        helper.succeed();
    }
}
