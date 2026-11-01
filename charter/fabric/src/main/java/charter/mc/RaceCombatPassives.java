package charter.mc;

import charter.config.RaceCombat;
import charter.id.OrgId;
import charter.id.PlayerId;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;

import java.util.Optional;

/**
 * The race combat passives, always on and mild. The rules and numbers are in {@link RaceCombat} and the
 * {@code raceCombat} knobs; this class only reads the world and applies the answer. A passive is held as a transient
 * attribute modifier that is refreshed every {@link #EVERY_TICKS} ticks and removed when it stops applying.
 *
 * Not compiled or run when written (the build host could not reach the Fabric Maven repository); the rules it calls
 * are covered by RaceCombatTest. Needs a gametest before it ships.
 */
public final class RaceCombatPassives {

    public static final int EVERY_TICKS = 10;

    private static final Identifier KNOCKBACK = Identifier.fromNamespaceAndPath("charter", "race_knockback");
    private static final Identifier DAMAGE = Identifier.fromNamespaceAndPath("charter", "race_damage");
    private static final Identifier SUBMERGED_MINING = Identifier.fromNamespaceAndPath("charter", "race_submerged_mining");
    /** Vanilla slows submerged mining to a fifth (the attribute's base is 0.2); +0.8 brings it back to 1. */
    private static final double SUBMERGED_MINING_RESTORE = 0.8;

    private RaceCombatPassives() {
    }

    /** Hooks the damage rules (fall immunity and the dodge). Call once at start-up. */
    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer player)) return true;
            CharterRuntime rt = CharterServer.runtime();
            if (rt == null) return true;
            String race = raceOf(rt, player).orElse("");
            if (source.is(DamageTypeTags.IS_FALL)) return !RaceCombat.ignoresFall(rt.knobs, race);
            boolean melee = source.getDirectEntity() instanceof LivingEntity && !source.is(DamageTypeTags.IS_PROJECTILE);
            return !(melee && RaceCombat.dodges(rt.knobs, race, player.getRandom().nextDouble()));
        });
    }

    public static void apply(MinecraftServer server, CharterRuntime rt) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) applyTo(player, rt);
    }

    public static void applyTo(ServerPlayer player, CharterRuntime rt) {
        if (!player.isAlive()) return;
        String race = raceOf(rt, player).orElse("");

        set(player, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK,
                RaceCombat.sneakKnockbackResist(rt.knobs, race, player.isShiftKeyDown()));

        double damage = RaceCombat.waterDamageBonus(rt.knobs, race, player.isInWater())
                + RaceCombat.rallyBonus(rt.knobs, race, alliesNear(player, rt));
        set(player, Attributes.ATTACK_DAMAGE, DAMAGE, damage);

        set(player, Attributes.SUBMERGED_MINING_SPEED, SUBMERGED_MINING,
                rt.knobs.raceCombat && RaceCombat.MERFOLK.equals(race) ? SUBMERGED_MINING_RESTORE : 0);

        if (rt.knobs.raceCombat && RaceCombat.FAE.equals(race)) glamour(player, rt, race);
    }

    /** Fae Glamour: hostile mobs that target a Fae from beyond the range lose that target. */
    private static void glamour(ServerPlayer player, CharterRuntime rt, String race) {
        long sinceAttack = player.tickCount - player.getLastHurtMobTimestamp();
        double reach = Math.max(rt.knobs.faeGlamourRange, 1) + 32;
        for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(reach),
                m -> m instanceof Enemy && m.getTarget() == player)) {
            if (RaceCombat.glamourDropsTarget(rt.knobs, race, mob.distanceTo(player), sinceAttack)) mob.setTarget(null);
        }
    }

    /** Members of the player's own kingdom, other than the player, within the Rally radius. */
    private static int alliesNear(ServerPlayer player, CharterRuntime rt) {
        if (!rt.knobs.raceCombat || rt.knobs.humanRallyPerAlly <= 0) return 0;
        Optional<OrgId> own = rootOrgOf(rt, player);
        if (own.isEmpty()) return 0;
        double radius = rt.knobs.humanRallyRadius;
        int count = 0;
        for (ServerPlayer other : player.level().getEntitiesOfClass(ServerPlayer.class,
                player.getBoundingBox().inflate(radius), p -> p != player && p.isAlive())) {
            if (other.distanceTo(player) <= radius && rootOrgOf(rt, other).equals(own)) count++;
        }
        return count;
    }

    private static Optional<OrgId> rootOrgOf(CharterRuntime rt, ServerPlayer player) {
        return rt.state.membership(new PlayerId(player.getUUID())).map(m -> rt.state.root(m.org()));
    }

    private static Optional<String> raceOf(CharterRuntime rt, ServerPlayer player) {
        return rt.state.raceOf(new PlayerId(player.getUUID())).flatMap(rt.state::org).map(o -> RaceNodes.slug(o.name()));
    }

    /** Holds {@code amount} as a modifier under {@code id}, or removes it when the amount is zero. */
    private static void set(ServerPlayer player, Holder<Attribute> attribute, Identifier id, double amount) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) return;
        AttributeModifier held = instance.getModifier(id);
        if (amount == 0) {
            if (held != null) instance.removeModifier(id);
            return;
        }
        if (held != null && held.amount() == amount) return;
        instance.addOrUpdateTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
    }
}
