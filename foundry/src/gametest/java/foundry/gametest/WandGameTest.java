package foundry.gametest;

import foundry.core.mechanics.Step;
import foundry.mc.Bolts;
import foundry.mc.MechanicHost;
import foundry.mc.Swings;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** The demo fairy wand: three inputs, bolts that land on a later tick, an area heal and the swing detection. */
public final class WandGameTest {
    /** Looked up on use: the class loads during test discovery, and a missing id would quietly resolve to air. */
    private static Item wand() {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse("demo:fairy_wand"));
    }
    private static long now(ServerPlayer p) {
        return p.level().getServer().getTickCount();
    }

    @SuppressWarnings("removal")
    private static ServerPlayer holder(GameTestHelper helper, double x, double z) {
        var player = helper.makeMockServerPlayerInLevel();
        var at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(x, 2, z));
        player.snapTo(at.x, at.y, at.z, 0f, 0f);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(wand()));
        return player;
    }

    private static void leftClick(ServerPlayer player) {
        player.swing(InteractionHand.MAIN_HAND);
        Swings.observe(player, now(player));
    }

    @GameTest
    public void theWandIsRegisteredWithItsFiveMechanics(GameTestHelper helper) {
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(Identifier.parse("demo:fairy_wand")), "demo:fairy_wand is registered");
        helper.assertValueEqual(foundry.mc.FoundryMod.loaded().items().get(foundry.core.Ident.parse("demo:fairy_wand")).mechanics().size(), 5, "mechanics");
        helper.succeed();
    }

    @GameTest
    public void aLeftClickLaunchesOneSparkleBoltAndHoldingDoesNotRepeat(GameTestHelper helper) {
        var player = holder(helper, 1.5, 1.5);
        leftClick(player);
        helper.assertValueEqual(Bolts.liveFor(player.getUUID()), 1, "a left click launches a bolt");
        Swings.observe(player, now(player));
        Swings.observe(player, now(player) + 1);
        helper.assertValueEqual(Bolts.liveFor(player.getUUID()), 1, "the same swing does not launch again");
        helper.succeed();
    }

    @GameTest
    public void aRightClickCastsMischiefAndItsArmSwingIsNotALeftClick(GameTestHelper helper) {
        var player = holder(helper, 1.5, 1.5);
        wand().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertValueEqual(Bolts.liveFor(player.getUUID()), 1, "Mischief launches its bolt");
        player.swing(InteractionHand.MAIN_HAND);
        Swings.observe(player, now(player));
        helper.assertValueEqual(Bolts.liveFor(player.getUUID()), 1, "the swing that follows a right click launches no sparkle bolt");
        helper.succeed();
    }

    @GameTest
    public void aSwingRightAfterHittingAnEntityOrStartingToMineIsNotALeftClick(GameTestHelper helper) {
        var player = holder(helper, 1.5, 1.5);
        Swings.suppress(player);
        leftClick(player);
        helper.assertValueEqual(Bolts.liveFor(player.getUUID()), 0, "a suppressed swing launches nothing");
        helper.succeed();
    }

    @GameTest
    public void sneakingRightClickHealsInsteadOfLaunchingAndOnlyNearbyPlayersGetIt(GameTestHelper helper) {
        var caster = holder(helper, 1.5, 1.5);
        var near = holder(helper, 3.5, 1.5);
        var far = holder(helper, 1.5, 30.5);
        caster.setShiftKeyDown(true);
        wand().use(helper.getLevel(), caster, InteractionHand.MAIN_HAND);
        helper.assertTrue(caster.hasEffect(MobEffects.REGENERATION), "the caster is healed");
        helper.assertTrue(near.hasEffect(MobEffects.REGENERATION), "a player within five blocks is healed");
        helper.assertTrue(!far.hasEffect(MobEffects.REGENERATION), "a player far away is not");
        helper.assertValueEqual(Bolts.liveFor(caster.getUUID()), 0, "sneaking launches no Mischief bolt");
        helper.succeed();
    }

    @GameTest
    public void plainRightClickDoesNotHeal(GameTestHelper helper) {
        var caster = holder(helper, 1.5, 1.5);
        wand().use(helper.getLevel(), caster, InteractionHand.MAIN_HAND);
        helper.assertTrue(!caster.hasEffect(MobEffects.REGENERATION), "Healing Light needs sneaking");
        helper.succeed();
    }

    @GameTest
    public void aSparkleBoltDamagesWhatItHitsAndNeverTheCaster(GameTestHelper helper) {
        var player = holder(helper, 1.5, 1.5);
        LivingEntity zombie = helper.spawn(EntityTypes.ZOMBIE, 1, 2, 5);
        ((net.minecraft.world.entity.Mob) zombie).setNoAi(true);
        float before = zombie.getHealth();
        float mine = player.getHealth();
        leftClick(player);
        helper.runAfterDelay(12, () -> {
            helper.assertTrue(zombie.getHealth() < before, "the zombie took damage from the bolt");
            helper.assertTrue(player.getHealth() == mine, "the caster was not hurt");
            helper.assertValueEqual(Bolts.liveFor(player.getUUID()), 0, "the bolt is gone after landing");
            helper.succeed();
        });
    }

    @GameTest
    public void aMischiefBoltBlindsAndSlowsWhatItHits(GameTestHelper helper) {
        var player = holder(helper, 1.5, 1.5);
        LivingEntity zombie = helper.spawn(EntityTypes.ZOMBIE, 1, 2, 4);
        ((net.minecraft.world.entity.Mob) zombie).setNoAi(true);
        wand().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.runAfterDelay(12, () -> {
            helper.assertTrue(zombie.hasEffect(MobEffects.BLINDNESS), "blinded");
            helper.assertTrue(zombie.hasEffect(MobEffects.SLOWNESS), "slowed");
            helper.assertTrue(!player.hasEffect(MobEffects.BLINDNESS), "the caster is untouched");
            helper.succeed();
        });
    }

    @GameTest
    public void aBoltStopsShortOfItsRange(GameTestHelper helper) {
        var player = holder(helper, 1.5, 1.5);
        LivingEntity zombie = helper.spawn(EntityTypes.ZOMBIE, 1, 2, 12);
        ((net.minecraft.world.entity.Mob) zombie).setNoAi(true);
        wand().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.runAfterDelay(12, () -> {
            helper.assertTrue(!zombie.hasEffect(MobEffects.BLINDNESS), "Mischief reaches six blocks, not ten");
            helper.succeed();
        });
    }

    @GameTest
    public void permissionFiltersRequireAndExcludeByNode(GameTestHelper helper) {
        var player = holder(helper, 1.5, 1.5);
        var node = Identifier.parse("test:allowed");
        var before = MechanicHost.permissionCheck;
        try {
            MechanicHost.permissionCheck = (entity, id) -> entity == player && id.equals(node);
            var require = new Step("apply_effect", Map.of("require_permission", "test:allowed"));
            var exclude = new Step("apply_effect", Map.of("exclude_permission", "test:allowed"));
            var none = new Step("apply_effect", Map.of());
            helper.assertTrue(MechanicHost.passesFilters(require, player), "require passes a holder");
            helper.assertTrue(!MechanicHost.passesFilters(exclude, player), "exclude refuses a holder");
            var other = helper.spawn(EntityTypes.ZOMBIE, 3, 2, 3);
            helper.assertTrue(!MechanicHost.passesFilters(require, other), "require refuses everyone else");
            helper.assertTrue(MechanicHost.passesFilters(exclude, other), "exclude lets everyone else through");
            helper.assertTrue(MechanicHost.passesFilters(none, other), "no filter passes everyone");
        } finally {
            MechanicHost.permissionCheck = before;
        }
        helper.succeed();
    }
}
