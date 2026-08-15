package kamutotems;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Totem charge bookkeeping, the death save, and the anvil-combine guard.
 *
 * <p>Charges burn on death only. At zero charges the totem goes dormant:
 * no save, no attack modification, kamu intact, no debuff.
 */
public final class TotemCharges {

    private static final Set<UUID> dormantNotified = new HashSet<>();

    private TotemCharges() {}

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DEATH.register(TotemCharges::allowDeath);
    }

    public static void tick(MinecraftServer server) {
        // ⚠ SPEC §16.4: block two Kamu Totems from combining on a vanilla anvil.
        // SPEC 16.4: two Kamu Totems on a vanilla anvil would repair each other
        // for free AND silently discard one Kamuy's slots. Named constants
        // verified present on AnvilMenu in 26.2.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player.containerMenu instanceof AnvilMenu menu)) {
                continue;
            }
            ItemStack left = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem();
            ItemStack right = menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem();
            if (Totem.is(left) && Totem.is(right)) {
                menu.getSlot(AnvilMenu.RESULT_SLOT).set(ItemStack.EMPTY);
                menu.getSlot(AnvilMenu.RESULT_SLOT).setChanged();
                player.sendSystemMessage(Component.literal(
                                "Two Kamu Totems cannot be combined on an anvil.")
                        .withStyle(ChatFormatting.RED));
            }

            // Clear the dormant nag once the totem has been repaired.
            ItemStack totem = Totem.find(player);
            if (totem != null && Totem.chargesRemaining(totem) > 0) {
                dormantNotified.remove(player.getUUID());
            }
        }
    }

    /**
     * Cancels lethal damage and burns one charge if the player is wearing a
     * charged Kamu Totem in the offhand. A real totem of undying in the main
     * hand is left for vanilla to consume, so only one is ever spent.
     */
    private static boolean allowDeath(LivingEntity entity, DamageSource damageSource, float amount) {
        if (!(entity instanceof ServerPlayer player)) {
            return true;
        }

        // ⚠ SPEC §16.2: real totem in main hand + Kamu Totem in offhand must burn only one.
        ItemStack main = player.getMainHandItem();
        if (main.is(Items.TOTEM_OF_UNDYING) && !Totem.is(main)) {
            return true; // let vanilla totem of undying trigger
        }

        ItemStack off = player.getOffhandItem();
        if (!Totem.is(off) || Totem.chargesRemaining(off) <= 0) {
            return true;
        }

        player.setHealth(1.0f);
        player.invulnerableTime = 60;
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0, false, false));

        Totem.consumeCharge(off);
        TotemHost.KamuyStore.advanceSaves(player);

        int remaining = Totem.chargesRemaining(off);
        player.sendSystemMessage(Component.literal(
                        "Your Kamu burns a charge to save you. " + remaining + " remain.")
                .withStyle(ChatFormatting.AQUA));

        // SoundEvents.TOTEM_USE may be a bare SoundEvent rather than a Holder; Chime overloads both, so either resolves.
        Chime.play(player, SoundEvents.TOTEM_USE, 1.0f, 1.0f);

        Totem.refreshLore(off, TotemHost.KamuyStore.getOrCreate(player));

        if (remaining <= 0 && !dormantNotified.contains(player.getUUID())) {
            dormantNotified.add(player.getUUID());
            player.sendSystemMessage(Component.literal(
                            "Your Kamu Totem is now dormant. Repair it at an anvil with diamonds.")
                    .withStyle(ChatFormatting.RED));
        }

        return false;
    }
}
