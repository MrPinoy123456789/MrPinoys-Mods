package excellentenchants;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/** Thunder, Ice Aspect, Venom and Wither: effects that fire when a weapon lands a hit. */
public final class OnHit {

    /** Stops Thunder's bonus hit from triggering the enchantments again. */
    private static boolean busy;

    private OnHit() {
    }

    public static void register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((victim, source, base, taken, blocked) -> {
            if (blocked || busy) return;
            if (!(victim.level() instanceof ServerLevel level)) return;
            if (!(source.getEntity() instanceof LivingEntity attacker)) return;
            ItemStack weapon = source.getWeaponItem();
            if (weapon == null || weapon.isEmpty()) return;
            apply(level, attacker, victim, source, weapon, -1);
        });
    }

    /** {@code forcedRoll} below zero rolls normally; otherwise it is the percent roll, for tests. */
    public static void apply(ServerLevel level, LivingEntity attacker, LivingEntity victim, DamageSource source,
                      ItemStack weapon, double forcedRoll) {
        int thunder = Enchants.level(level, weapon, Enchants.THUNDER);
        if (thunder > 0 && roll(level, Numbers.thunderChance(thunder), forcedRoll)) strike(level, victim, source, thunder);

        int ice = Enchants.level(level, weapon, Enchants.ICE_ASPECT);
        if (ice > 0) {
            effect(victim, MobEffects.SLOWNESS, Numbers.iceAspectSeconds(ice), Numbers.amplifier(ice));
            victim.setTicksFrozen(victim.getTicksRequiredToFreeze());
        }
        int venom = Enchants.level(level, weapon, Enchants.VENOM);
        if (venom > 0 && roll(level, Numbers.venomChance(venom), forcedRoll)) {
            effect(victim, MobEffects.POISON, Numbers.venomSeconds(venom), Numbers.amplifier(venom));
        }
        int wither = Enchants.level(level, weapon, Enchants.WITHER);
        if (wither > 0 && roll(level, Numbers.witherChance(wither), forcedRoll)) {
            effect(victim, MobEffects.WITHER, Numbers.witherSeconds(wither), Numbers.amplifier(wither));
        }
    }

    public static boolean roll(ServerLevel level, double percent, double forcedRoll) {
        double r = forcedRoll >= 0 ? forcedRoll : level.getRandom().nextDouble() * 100.0;
        return r < percent;
    }

    public static void effect(LivingEntity victim, Holder<MobEffect> type, int seconds, int amplifier) {
        victim.addEffect(new MobEffectInstance(type, seconds * 20, amplifier));
    }

    /** Thunder: a visual-only bolt, then the flat bonus damage, only where the sky is open. */
    public static void strike(ServerLevel level, LivingEntity victim, DamageSource source, int enchantLevel) {
        BlockPos pos = victim.blockPosition();
        if (level.getBrightness(LightLayer.SKY, pos) != 15) return;
        LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
        if (bolt != null) {
            bolt.snapTo(Vec3.atBottomCenterOf(pos));
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
        }
        busy = true;
        try {
            victim.setInvulnerableTime(0);
            victim.hurtServer(level, source, (float) Numbers.thunderDamage(enchantLevel));
        } finally {
            busy = false;
        }
    }
}
