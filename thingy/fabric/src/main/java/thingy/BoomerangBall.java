package thingy;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import thingy.api.VirtualTag;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * The Boomerang Pet Ball: an infinite snowball. Throw it; a real vanilla
 * {@link Snowball} does the flying, so the client needs nothing new, and it
 * deals no damage on impact, full stop, even to blazes (vanilla's own
 * {@code Snowball.onHitEntity} deals 1 damage to those; {@link #ALLOW_DAMAGE}
 * below cancels that too, since "no damage" should mean no damage, not "no
 * damage except the one vanilla carve-out"). Then it flies back to whoever
 * threw it, whether it hit anything or not, because a boomerang that doesn't
 * come back is just a rock you threw away.
 *
 * <p>No custom entity type: that would need a client-side renderer, and this
 * mod promises vanilla clients need nothing. A real {@code Snowball} is spawned
 * and tracked by object reference in {@link #inFlight}; when vanilla's own
 * {@code Snowball.onHit} discards it (same tick as any collision, verified in
 * the 26.2 source), or after {@link #MAX_LIFETIME_TICKS} / {@link #MAX_DISTANCE_SQ}
 * if it never hits anything, the next {@code END_SERVER_TICK} poll notices and
 * returns it to the owner's inventory.
 */
public final class BoomerangBall {

    public static final String ID = "boomerang_pet_ball";

    private static final float THROW_POWER = 1.4F;
    private static final int MAX_LIFETIME_TICKS = 60;
    private static final double MAX_DISTANCE_SQ = 24.0 * 24.0;

    private record Flight(UUID owner, Entity projectile, ItemStack returnStack, Vec3 spawnPos, int spawnTick) {}

    /** Server thread only: both the throw callback and the tick poll run there. */
    private static final List<Flight> inFlight = new ArrayList<>();

    private BoomerangBall() {}

    public static void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
                    || !(level instanceof ServerLevel serverLevel)) {
                return InteractionResult.PASS;
            }
            ItemStack held = serverPlayer.getItemInHand(hand);
            if (!VirtualTag.is(held, "wondrous", ID)) {
                return InteractionResult.PASS;
            }
            throwBall(serverPlayer, serverLevel, held);
            return InteractionResult.SUCCESS_SERVER;
        });

        // Blocks ALL damage dealt by our in-flight balls: vanilla's own
        // Snowball.onHitEntity deals 1 damage to blazes specifically; this
        // cancels that too, so "no damage" really means no damage to anything.
        //
        // Cancelling the hurt is also what breaks wolf aggro: a tamed wolf's
        // OwnerHurtTargetGoal fires off owner.getLastHurtMob(), which LivingEntity
        // only stamps from inside a damage application that actually lands. A
        // 0-damage projectile never gets there (same reason snowballs, splash
        // potions and flint-and-steel don't rally your wolves in vanilla), so we
        // stamp it by hand here; the goal only reads the field and its
        // timestamp, it never asks how much damage was dealt.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            Entity direct = source.getDirectEntity();
            for (Flight flight : inFlight) {
                if (flight.projectile() == direct) {
                    markAsOwnersTarget(flight, entity);
                    return false;
                }
            }
            return true;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    private static void throwBall(ServerPlayer player, ServerLevel level, ItemStack held) {
        ItemStack returnStack = held.copyWithCount(1);
        held.shrink(1);

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SNOWBALL_THROW, SoundSource.NEUTRAL, 0.4f, 1.2f);

        Snowball ball = Projectile.spawnProjectileFromRotation(
                Snowball::new, level, returnStack, player, 0.0F, THROW_POWER, 0.5F);

        inFlight.add(new Flight(player.getUUID(), ball, returnStack, ball.position(), ball.tickCount));
    }

    /**
     * Registers {@code target} as the last mob the thrower hurt, so tamed wolves
     * (and any other {@code OwnerHurtTargetGoal} holder) treat the boomerang as a
     * real attack even though it dealt nothing. No damage is applied here.
     */
    private static void markAsOwnersTarget(Flight flight, LivingEntity target) {
        if (!(target.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        ServerPlayer owner = serverLevel.getServer().getPlayerList().getPlayer(flight.owner());
        if (owner == null || owner == target) {
            return;
        }
        // Don't sic the pack on the thrower's own pets; a stray ball shouldn't
        // start a dog fight.
        if (target instanceof TamableAnimal tamed && tamed.isOwnedBy(owner)) {
            return;
        }
        owner.setLastHurtMob(target);
    }

    private static void tick() {
        if (inFlight.isEmpty()) {
            return;
        }
        Iterator<Flight> it = inFlight.iterator();
        while (it.hasNext()) {
            Flight flight = it.next();
            Entity projectile = flight.projectile();
            Vec3 pos = projectile.position();

            boolean alreadyDiscarded = !projectile.isAlive();
            boolean timedOut = !alreadyDiscarded
                    && (projectile.tickCount - flight.spawnTick() >= MAX_LIFETIME_TICKS
                        || pos.distanceToSqr(flight.spawnPos()) >= MAX_DISTANCE_SQ);

            if (!alreadyDiscarded && !timedOut) {
                continue;
            }

            if (timedOut) {
                projectile.discard();
            }
            returnToOwner(flight, pos);
            it.remove();
        }
    }

    private static void returnToOwner(Flight flight, Vec3 pos) {
        ServerPlayer owner = null;
        Entity projectile = flight.projectile();
        if (projectile.level() instanceof ServerLevel serverLevel) {
            owner = serverLevel.getServer().getPlayerList().getPlayer(flight.owner());
        }

        if (owner == null) {
            // Owner logged off mid-flight; leave it where it landed rather than
            // vanish an item into a player who isn't there to catch it.
            if (projectile.level() instanceof ServerLevel serverLevel) {
                ItemEntity dropped = new ItemEntity(serverLevel, pos.x, pos.y, pos.z, flight.returnStack().copy());
                serverLevel.addFreshEntity(dropped);
            }
            return;
        }

        ItemStack returning = flight.returnStack().copy();
        if (!owner.getInventory().add(returning)) {
            owner.drop(returning, false);
        }

        owner.connection.send(new ClientboundSoundPacket(
                SoundEvents.NOTE_BLOCK_CHIME, SoundSource.RECORDS,
                owner.getX(), owner.getY(), owner.getZ(), 0.3f, 1.4f, owner.level().getRandom().nextLong()));
    }
}
