package kamutotems;

import kamutotems.core.AuraSpec;
import kamutotems.core.BossRoll;
import kamutotems.core.Kamu;
import kamutotems.core.KamuCatalog;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A summoned boss: a vanilla mob carrying a rolled construct of kamu and a boss bar.
 */
public final class Boss {

    final UUID owner;
    final int tier;
    final BossRoll roll;
    final List<Kamu> kamu;
    final Entity entity;
    final ServerBossEvent bar;
    final AuraSpec aura;
    final boolean fromSigil;
    final int purchaseCounter;
    final long seed;

    Boss(UUID owner, int tier, BossRoll roll, List<Kamu> kamu, AuraSpec aura,
         Entity entity, ServerBossEvent bar, boolean fromSigil, int purchaseCounter,
         long seed) {
        this.owner = owner;
        this.tier = tier;
        this.roll = roll;
        this.kamu = kamu;
        this.aura = aura;
        this.entity = entity;
        this.bar = bar;
        this.fromSigil = fromSigil;
        this.purchaseCounter = purchaseCounter;
        this.seed = seed;
    }

    public UUID owner() {
        return owner;
    }

    public int tier() {
        return tier;
    }

    public Entity entity() {
        return entity;
    }

    public ServerBossEvent bar() {
        return bar;
    }

    public AuraSpec aura() {
        return aura;
    }

    public BossRoll roll() {
        return roll;
    }

    /** The carried kamu, resolved from {@link #roll()} against the catalog. */
    public List<Kamu> kamu() {
        return kamu;
    }

    public boolean fromSigil() {
        return fromSigil;
    }

    public int purchaseCounter() {
        return purchaseCounter;
    }

    public long seed() {
        return seed;
    }

    /**
     * Spawns a boss at a short distance in front of the player.
     */
    public static Boss spawn(ServerPlayer player, int tier, BossRoll roll,
                             boolean fromSigil, int purchaseCounter, long seed,
                             KamuCatalog catalog) {
        ServerLevel level = player.level();
        String mobId = KamuTotemsConfig.section("boss").has("mob")
                ? KamuTotemsConfig.section("boss").get("mob").getAsString()
                : "minecraft:zombie";

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(mobId))
                .orElse(EntityTypes.ZOMBIE);

        Entity entity = type.create(level, EntitySpawnReason.COMMAND);
        if (entity == null) {
            return null;
        }

        Vec3 pos = player.position().add(player.getLookAngle().scale(2.0));
        entity.setPos(pos.x, pos.y, pos.z);
        // snapTo verified present on Entity in 26.2.
        entity.snapTo(pos.x, pos.y, pos.z, player.getYRot(), 0.0f);

        // Difficulty. The first pass was far too soft -- tier IV was a 60 HP
        // zombie with double damage, for 28 diamonds. These are geometric, so a
        // tier IV is a genuine fight rather than a slightly chunky mob:
        //
        //   tier I    80 HP   x1.8 damage    the free daily, still beatable solo
        //   tier II  160 HP   x2.6 damage
        //   tier III 320 HP   x3.4 damage
        //   tier IV  640 HP   x4.2 damage    expect to bring friends
        //
        // Both curves are config so this can be retuned without a rebuild --
        // and it will need retuning, these are first guesses (DESIGN.md 5).
        double healthBase = KamuTotemsConfig.d("boss", "health_base", 40.0);
        double healthGrowth = KamuTotemsConfig.d("boss", "health_growth", 2.0);
        double health = healthBase * Math.pow(healthGrowth, tier);

        double damageBase = KamuTotemsConfig.d("boss", "damage_base", 1.0);
        double damageStep = KamuTotemsConfig.d("boss", "damage_step", 0.8);
        double damageMult = damageBase + damageStep * tier;

        double knockbackResist = Math.min(0.9, 0.2 * tier);

        if (entity instanceof LivingEntity living) {
            AttributeInstance maxHealth = living.getAttribute(Attributes.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(health);
                living.setHealth((float) health);
            }
            AttributeInstance attack = living.getAttribute(Attributes.ATTACK_DAMAGE);
            if (attack != null) {
                double base = attack.getBaseValue();
                // A zombie's base attack is small; a flat floor stops a low-base
                // mob from being harmless no matter the multiplier.
                attack.setBaseValue(Math.max(base * damageMult, 3.0 + 2.0 * tier));
            }
            AttributeInstance armour = living.getAttribute(Attributes.ARMOR);
            if (armour != null) {
                armour.setBaseValue(Math.min(20.0, 4.0 * tier));
            }
            AttributeInstance kb = living.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            if (kb != null) {
                // Without this a boss is trivially chain-knocked into a corner,
                // which makes every fight the same fight regardless of tier.
                kb.setBaseValue(knockbackResist);
            }
            AttributeInstance speed = living.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) {
                speed.addOrUpdateTransientModifier(new AttributeModifier(
                        Identifier.fromNamespaceAndPath("kamutotems", "boss_speed"),
                        tier * 0.05,
                        AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            }

            if (entity instanceof Mob mob) {
                mob.setTarget(player);
                mob.setPersistenceRequired();
            }
        }

        List<Kamu> carried = roll.kamuIds().stream()
                .map(catalog::get)
                .filter(Objects::nonNull)
                .toList();

        AuraSpec aura = BossAura.forBoss(tier, carried, seed, catalog);
        Component barName = BossNames.build(tier, type, aura, carried);
        // 26.2 ServerBossEvent takes a UUID first. Verified against the merged jar.
        ServerBossEvent bar = new ServerBossEvent(
                java.util.UUID.randomUUID(),
                barName,
                BossEvent.BossBarColor.PURPLE,
                BossEvent.BossBarOverlay.PROGRESS);
        bar.addPlayer(player);
        bar.setProgress(1.0f);

        if (entity instanceof LivingEntity living) {
            living.setCustomName(barName);
            living.setCustomNameVisible(true);
        }

        level.addFreshEntity(entity);
        return new Boss(player.getUUID(), tier, roll, carried, aura, entity, bar,
                fromSigil, purchaseCounter, seed);
    }

    public void updateBar() {
        if (entity instanceof LivingEntity living) {
            float progress = Math.max(0.0f, Math.min(1.0f, living.getHealth() / living.getMaxHealth()));
            bar.setProgress(progress);
        }
    }

    public void removeBar(ServerPlayer player) {
        bar.removePlayer(player);
    }

    public void removeBarAll() {
        bar.removeAllPlayers();
    }
}
