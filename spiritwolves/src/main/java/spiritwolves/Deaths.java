package spiritwolves;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.ItemStack;

/**
 * Intercepts lethal damage to a bound spirit wolf: cancels death, heals to
 * full, serializes back into the registry, and burns one charge from a bound
 * stone in the owner's inventory. If every bound stone is out of charges, the
 * wolf <em>truly</em> dies -- SPEC.md section 16.7.
 */
public final class Deaths {

    private Deaths() {}

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DEATH.register(Deaths::allowDeath);
    }

    private static boolean allowDeath(LivingEntity entity, DamageSource damageSource, float amount) {
        if (!(entity instanceof Wolf wolf) || !(entity.level() instanceof ServerLevel level)) {
            return true;
        }

        ServerPlayer owner = Tracker.findOwner(level, wolf.getUUID());
        if (owner == null) {
            return true;
        }
        WolfRecord record = PlayerWolfRegistry.get(owner.getUUID());
        if (record == null) {
            return true;
        }

        ItemStack chargedStone = findChargedStone(owner);
        if (chargedStone == null) {
            trueDeath(owner, record, wolf);
            return false;
        }

        wolf.setHealth(wolf.getMaxHealth());
        wolf.invulnerableTime = 20;

        // Dying ends the outing -- the streak dies with it.
        Streak.onReturn(wolf, record);
        RecallLock.forget(wolf.getUUID());
        AbilityProcs.forget(wolf.getUUID());

        record.wolfTag = WolfCapture.capture(wolf, level);
        record.wolfName = WolfCapture.nameOf(wolf);
        record.collar = WolfCapture.collarOf(wolf);
        record.summoned = false;
        record.saveCount++;
        Journal.deathSaved(record, damageSource, record.saveCount);
        SpiritStone.consumeCharge(chargedStone);
        PlayerWolfRegistry.markDirty(owner.getUUID());
        SpiritStone.refreshLore(chargedStone, record);

        wolf.discard();

        String name = record.wolfName != null ? record.wolfName : "Your wolf";
        int remaining = SpiritStone.chargesRemaining(chargedStone);
        if (remaining <= 0) {
            Chime.lastCharge(owner);
            owner.sendSystemMessage(Component.literal(
                            "The stone cracks -- " + name + " is saved, but the stone is spent.")
                    .withStyle(ChatFormatting.GOLD));
        } else {
            Chime.deathSaved(owner);
            owner.sendSystemMessage(Component.literal(
                            "The stone flares -- " + name + " is saved. " + remaining + " charge(s) remain.")
                    .withStyle(ChatFormatting.AQUA));
        }

        return false;
    }

    /** The wolf truly dies: registry record deleted, every bound stone reverts to unbound. */
    private static void trueDeath(ServerPlayer owner, WolfRecord record, Wolf wolf) {
        String name = record.wolfName != null ? record.wolfName : "Your wolf";

        wolf.setHealth(wolf.getMaxHealth());
        wolf.invulnerableTime = 20;
        Streak.forget(wolf.getUUID());
        RecallLock.forget(wolf.getUUID());
        AbilityProcs.forget(wolf.getUUID());
        Senses.forget(wolf.getUUID());
        wolf.discard();

        PlayerWolfRegistry.remove(owner.getUUID());
        Tracker.forEachBoundStone(owner, SpiritStone::revertToUnbound);

        owner.sendSystemMessage(Component.literal(name + " is gone.").withStyle(ChatFormatting.DARK_RED));
    }

    /** The first bound stone in the player's inventory with a charge left, if any. */
    private static ItemStack findChargedStone(ServerPlayer player) {
        ItemStack[] found = new ItemStack[1];
        Tracker.forEachBoundStone(player, stack -> {
            if (found[0] == null && SpiritStone.chargesRemaining(stack) > 0) {
                found[0] = stack;
            }
        });
        return found[0];
    }
}
