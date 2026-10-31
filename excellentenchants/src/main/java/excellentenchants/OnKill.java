package excellentenchants;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.AABB;

/** Wisdom, Decapitator and Thrifty: what a kill with the enchanted weapon pays out. */
public final class OnKill {

    private OnKill() {
    }

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((victim, source) -> {
            if (!(victim.level() instanceof ServerLevel level)) return;
            if (!(source.getEntity() instanceof ServerPlayer killer)) return;
            ItemStack weapon = source.getWeaponItem();
            if (weapon == null || weapon.isEmpty()) return;
            apply(level, killer, victim, source, weapon, -1);
        });
    }

    /** {@code forcedRoll} below zero rolls normally; otherwise it is the percent roll, for tests. */
    public static void apply(ServerLevel level, ServerPlayer killer, LivingEntity victim, DamageSource source,
                      ItemStack weapon, double forcedRoll) {
        int wisdom = Enchants.level(level, weapon, Enchants.WISDOM);
        if (wisdom > 0) wisdom(level, victim, wisdom);

        int decap = Enchants.level(level, weapon, Enchants.DECAPITATOR);
        if (decap > 0 && OnHit.roll(level, Numbers.decapitatorChance(decap), forcedRoll)) {
            ItemStack head = head(victim);
            if (head != null) victim.spawnAtLocation(level, head);
        }
        int thrifty = Enchants.level(level, weapon, Enchants.THRIFTY);
        if (thrifty > 0 && OnHit.roll(level, Numbers.thriftyChance(thrifty), forcedRoll)) {
            ItemStack egg = egg(victim.getType());
            if (egg != null) victim.spawnAtLocation(level, egg);
        }
    }

    /** Adds the missing experience as one more orb, so every orb the kill spawned counts. */
    public static void wisdom(ServerLevel level, LivingEntity victim, int enchantLevel) {
        AABB box = victim.getBoundingBox().inflate(3.0);
        int total = 0;
        for (ExperienceOrb orb : level.getEntitiesOfClass(ExperienceOrb.class, box, o -> o.tickCount == 0)) {
            total += orb.getValue();
        }
        int extra = Numbers.wisdomXp(total, enchantLevel) - total;
        if (extra > 0) ExperienceOrb.award(level, victim.position(), extra);
    }

    /** The head a mob or player leaves, or null when there is none for this type. */
    public static ItemStack head(LivingEntity victim) {
        EntityType<?> type = victim.getType();
        if (victim instanceof ServerPlayer player) {
            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(player.getGameProfile()));
            head.set(DataComponents.CUSTOM_NAME, Component.literal(player.getGameProfile().name() + "'s Head")
                    .withStyle(ChatFormatting.YELLOW));
            return head;
        }
        if (type == EntityTypes.ZOMBIE) return new ItemStack(Items.ZOMBIE_HEAD);
        if (type == EntityTypes.SKELETON) return new ItemStack(Items.SKELETON_SKULL);
        if (type == EntityTypes.CREEPER) return new ItemStack(Items.CREEPER_HEAD);
        if (type == EntityTypes.WITHER_SKELETON) return new ItemStack(Items.WITHER_SKELETON_SKULL);
        if (type == EntityTypes.PIGLIN) return new ItemStack(Items.PIGLIN_HEAD);
        if (type == EntityTypes.ENDER_DRAGON) return new ItemStack(Items.DRAGON_HEAD);
        return null;
    }

    /** The spawn egg of a mob, or null for the Wither, the Ender Dragon and anything without one. */
    public static ItemStack egg(EntityType<?> type) {
        if (type == EntityTypes.WITHER || type == EntityTypes.ENDER_DRAGON) return null;
        return SpawnEggItem.byId(type).map(holder -> new ItemStack(holder.value())).orElse(null);
    }
}
