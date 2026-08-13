package wayfarers;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import wayfarers.core.Wallet;

import java.util.List;

/**
 * Hostile encounter interaction: parley, payment, or fight.
 *
 * <p>Right-clicking a pillager begins parley. Gold armour lets the player pass
 * without a fight; carrying the demanded items lets them pay and pass;
 * otherwise the patrol is released to attack. Attacking first has the same
 * effect.
 */
public final class Hostile {

    private static Encounters encounters;
    private static Dialogue dialogue;

    private Hostile() {}

    public static void register(Encounters enc, Dialogue d) {
        encounters = enc;
        dialogue = d;

        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
                onUse(player, hand, entity));

        ServerLivingEntityEvents.AFTER_DAMAGE.register(Hostile::onDamage);
    }

    private static InteractionResult onUse(Player player, InteractionHand hand, Entity entity) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        if (hand != InteractionHand.MAIN_HAND) {
            return consumeIfTagged(entity);
        }

        if (!entity.entityTags().contains(Spawns.TAG)) {
            return InteractionResult.PASS;
        }

        Encounters.Active active = encounters.activeFor(serverPlayer.getUUID());
        if (active == null || !"hostile".equals(active.def.template())) {
            return InteractionResult.PASS;
        }

        if (isWearingGold(serverPlayer)) {
            dialogue.speak(active, "placated", false);
            encounters.endFor(serverPlayer.getUUID());
            return InteractionResult.SUCCESS;
        }

        if (tryPay(serverPlayer, active)) {
            dialogue.speak(active, "open", false);
            encounters.endFor(serverPlayer.getUUID());
            return InteractionResult.SUCCESS;
        }

        dialogue.speak(active, "attack", false);
        releaseMobs(active, serverPlayer);
        return InteractionResult.SUCCESS;
    }

    private static void onDamage(Entity entity, DamageSource source, float dealt, float taken, boolean blocked) {
        if (!entity.entityTags().contains(Spawns.TAG)) {
            return;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Encounters.Active active = encounters.activeFor(player.getUUID());
        if (active == null || !"hostile".equals(active.def.template())) {
            return;
        }
        dialogue.hush(player);
        if (!active.released) {
            dialogue.speak(active, "attack", false);
        }
        releaseMobs(active, player);
    }

    private static boolean tryPay(ServerPlayer player, Encounters.Active active) {
        String wants = active.def.wants();
        int cost = active.def.coin();
        if (wants == null || wants.isBlank() || cost <= 0) {
            return false;
        }

        Identifier key = Identifier.tryParse(wants);
        if (key == null) {
            return false;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        if (item == null) {
            return false;
        }

        int[] have = Payment.countsFor(player, item);
        if (Wallet.count(have) < cost) {
            return false;
        }

        int[] removal = Wallet.planRemoval(have, cost);
        if (removal == null) {
            return false;
        }

        Payment.applyRemovalPlan(player, item, removal);
        player.sendSystemMessage(Component.literal("You pay the toll.").withStyle(ChatFormatting.GREEN));
        return true;
    }

    private static void releaseMobs(Encounters.Active active, ServerPlayer player) {
        if (!(active.entity.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        AABB box = active.entity.getBoundingBox().inflate(8.0);
        List<Mob> mobs = level.getEntities(EntityTypeTest.forClass(Mob.class), box,
                e -> e.entityTags().contains(Spawns.TAG));
        for (Mob mob : mobs) {
            mob.setNoAi(false);
            mob.setTarget(player);
            mob.setAggressive(true);
        }
        active.released = true;
    }

    private static InteractionResult consumeIfTagged(Entity entity) {
        return entity.entityTags().contains(Spawns.TAG) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    private static boolean isWearingGold(ServerPlayer player) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
                continue;
            }
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.is(Items.GOLDEN_HELMET)
                    || stack.is(Items.GOLDEN_CHESTPLATE)
                    || stack.is(Items.GOLDEN_LEGGINGS)
                    || stack.is(Items.GOLDEN_BOOTS)) {
                return true;
            }
        }
        return false;
    }
}
