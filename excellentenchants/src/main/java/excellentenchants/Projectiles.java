package excellentenchants;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

/**
 * Ghast and Ender Bow: the arrow a bow or crossbow just loosed is swapped, on the next
 * tick, for a fireball or an ender pearl travelling the same way.
 */
public final class Projectiles {

    private static final List<AbstractArrow> PENDING = new ArrayList<>();

    private Projectiles() {
    }

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof AbstractArrow arrow && arrow.getOwner() instanceof LivingEntity) PENDING.add(arrow);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (PENDING.isEmpty()) return;
            List<AbstractArrow> batch = new ArrayList<>(PENDING);
            PENDING.clear();
            for (AbstractArrow arrow : batch) {
                if (arrow.level() instanceof ServerLevel level && !arrow.isRemoved()) swap(level, arrow);
            }
        });
    }

    /** Swaps one arrow if its weapon carries Ghast or Ender Bow; returns the replacement, or null. */
    public static net.minecraft.world.entity.Entity swap(ServerLevel level, AbstractArrow arrow) {
        ItemStack weapon = arrow.getWeaponItem();
        if (weapon == null || weapon.isEmpty()) return null;
        if (!(arrow.getOwner() instanceof LivingEntity owner)) return null;
        Vec3 velocity = arrow.getDeltaMovement();
        net.minecraft.world.entity.Entity replacement = null;

        if (Enchants.level(level, weapon, Enchants.ENDER_BOW) > 0) {
            ThrownEnderpearl pearl = new ThrownEnderpearl(level, owner, new ItemStack(Items.ENDER_PEARL));
            pearl.setPos(arrow.position());
            pearl.setDeltaMovement(velocity);
            replacement = pearl;
        } else {
            int ghast = Enchants.level(level, weapon, Enchants.GHAST);
            if (ghast > 0) {
                boolean multishot = multishot(level, weapon);
                if (multishot) {
                    SmallFireball small = new SmallFireball(level, owner, velocity.normalize().scale(0.5));
                    small.setPos(arrow.position());
                    replacement = small;
                } else {
                    LargeFireball big = new LargeFireball(level, owner, velocity.normalize().scale(0.1),
                            (int) Numbers.ghastYield(ghast));
                    big.setPos(arrow.position());
                    replacement = big;
                }
            }
        }
        if (replacement == null) return null;
        arrow.discard();
        level.addFreshEntity(replacement);
        return replacement;
    }

    private static boolean multishot(ServerLevel level, ItemStack weapon) {
        return level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .get(Enchantments.MULTISHOT)
                .map(h -> net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(h, weapon) > 0)
                .orElse(false);
    }
}
