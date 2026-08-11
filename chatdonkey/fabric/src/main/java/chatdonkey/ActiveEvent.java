package chatdonkey;

import chatdonkey.core.DonkeyBehavior;
import chatdonkey.core.EventContext;
import chatdonkey.core.HitReactions;
import chatdonkey.core.LinePools;
import chatdonkey.core.RateLimit;
import chatdonkey.core.Treat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * One running event: a donkey, a target, a behavior, and a tick counter.
 *
 * <p>This is the {@link EventContext} implementation -- the only place where a
 * behavior's intent ("get closer", "say this") becomes an entity call. Movement
 * is external steering through the public navigation, per SPEC.md section 6; no
 * Mixin and no access widener, and the vanilla wander goals fighting back
 * between calls reads as character rather than breakage.
 */
public final class ActiveEvent implements EventContext {

    /** Who hears the donkey: the target, plus anyone close enough to enjoy it. */
    private static final double CHAT_RADIUS = 16.0;

    private final ServerPlayer player;
    private final Donkey donkey;
    private final DonkeyBehavior behavior;
    private final LinePools lines;
    private final String name;
    private final int durationTicks;
    private final Random random;
    private final Voice voice;
    private final HitReactions hits = new HitReactions();

    /** Same three-second beat the hit pool uses, for the same reason. */
    private final RateLimit refusals = new RateLimit(HitReactions.LINE_COOLDOWN_TICKS);

    private int elapsedTicks;
    private Treat fedTreat;

    public ActiveEvent(ServerPlayer player, Donkey donkey, DonkeyBehavior behavior,
                       LinePools lines, String name, int durationTicks, Random random,
                       Voice voice) {
        this.player = player;
        this.donkey = donkey;
        this.behavior = behavior;
        this.lines = lines;
        this.name = name;
        this.durationTicks = durationTicks;
        this.random = random;
        this.voice = voice;
    }

    public ServerPlayer player() {
        return player;
    }

    public UUID playerId() {
        return player.getUUID();
    }

    public Donkey donkey() {
        return donkey;
    }

    public DonkeyBehavior behavior() {
        return behavior;
    }

    public void advanceTick() {
        elapsedTicks++;
    }

    /**
     * Records a player hit and fires an indignant line if one is due.
     *
     * <p>The damage itself is cancelled by the caller -- hitting the donkey is a
     * dialogue trigger, never a solution (SPEC.md section 2).
     */
    public void registerHit() {
        if (hits.record(elapsedTicks)) {
            String line = lines.pick("hit", random);
            if (!line.isEmpty()) {
                say(line);
            }
        }
    }

    /** Records what the player fed the donkey. The first treat is the one that counts. */
    public void feed(Treat treat) {
        if (fedTreat == null) {
            fedTreat = treat;
        }
    }

    /**
     * Says a line from a shared (not behavior-scoped) pool, rate-limited.
     *
     * <p>Used for {@code deny}, which fires on every rejected right-click. A
     * player can click far faster than they can swing, so without this the
     * refusal line -- and its animalese -- floods the chat exactly the way the
     * {@code hit} pool's own limiter exists to prevent.
     */
    public void sayFromPool(String poolKey) {
        if (!refusals.allow(elapsedTicks)) {
            return;
        }
        String line = lines.pick(poolKey, random);
        if (!line.isEmpty()) {
            say(line);
        }
    }

    /** The satisfied crunch of a donkey being fed. */
    public void eat() {
        donkey.level().playSound(null, donkey.getX(), donkey.getY(), donkey.getZ(),
                SoundEvents.DONKEY_EAT, SoundSource.NEUTRAL, 1.0f, 1.0f);
    }

    public boolean expired() {
        return elapsedTicks >= durationTicks;
    }

    /** True once the donkey has been removed from the world by anything at all. */
    public boolean donkeyGone() {
        return donkey.isRemoved();
    }

    /**
     * True if the player and the donkey are no longer in the same world.
     *
     * <p>Happens the moment a player steps through a portal. The event has to
     * end rather than follow: the donkey cannot be dragged between dimensions,
     * and leaving it running would have the behavior steering toward
     * coordinates in a world the donkey is not in.
     */
    public boolean separated() {
        return player.level() != donkey.level();
    }

    // ------------------------------------------------------------- EventContext

    @Override
    public int elapsedTicks() {
        return elapsedTicks;
    }

    @Override
    public int durationTicks() {
        return durationTicks;
    }

    @Override
    public String donkeyName() {
        return name;
    }

    @Override
    public double distanceToPlayer() {
        return Math.sqrt(donkey.distanceToSqr(player));
    }

    @Override
    public int hitCount() {
        return hits.count();
    }

    @Override
    public Treat fedTreat() {
        return fedTreat;
    }

    @Override
    public void say(String line) {
        Component message = Component.literal("<" + name + "> ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(line).withStyle(ChatFormatting.YELLOW));

        // Not server-wide: the event is *for* someone, and the suite's other
        // mods are already chatty (SPEC.md section 8).
        List<ServerPlayer> audience = new ArrayList<>();
        audience.add(player);
        if (player.level() instanceof ServerLevel level) {
            for (ServerPlayer nearby : level.players()) {
                if (nearby != player && nearby.distanceToSqr(donkey) <= CHAT_RADIUS * CHAT_RADIUS) {
                    audience.add(nearby);
                }
            }
        }

        for (ServerPlayer listener : audience) {
            listener.sendSystemMessage(message);
        }
        // Everyone who can read it can hear it being said.
        voice.speak(audience, line, donkey.getUUID(), name);
    }

    @Override
    public void steerTowardPlayer(double stopDistance, double speed) {
        // Aim at the player rather than at a computed stand-off point: the
        // navigation stops on contact anyway, and a stand-off point behind the
        // player is what makes external steering read as jittery.
        donkey.getNavigation().moveTo(player.getX(), player.getY(), player.getZ(), speed);
    }

    @Override
    public void steerTo(double x, double y, double z, double speed) {
        donkey.getNavigation().moveTo(x, y, z, speed);
    }

    /**
     * Clingy's escape-proofing. Particles on both ends so it reads as the
     * donkey doing something deliberate rather than the server rubber-banding.
     */
    @Override
    public void teleportOntoPlayer() {
        if (donkey.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.POOF,
                    donkey.getX(), donkey.getY() + 0.7, donkey.getZ(),
                    12, 0.3, 0.4, 0.3, 0.02);
        }
        donkey.teleportTo(player.getX(), player.getY(), player.getZ());
        if (donkey.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.POOF,
                    donkey.getX(), donkey.getY() + 0.7, donkey.getZ(),
                    12, 0.3, 0.4, 0.3, 0.02);
        }
    }

    @Override
    public double playerX() {
        return player.getX();
    }

    @Override
    public double playerY() {
        return player.getY();
    }

    @Override
    public double playerZ() {
        return player.getZ();
    }

    @Override
    public double playerFacingX() {
        return horizontalFacing().x;
    }

    @Override
    public double playerFacingZ() {
        return horizontalFacing().z;
    }

    /**
     * The player's facing flattened onto the ground and re-normalised.
     *
     * <p>Roadblock plants itself in front of the player's feet; using the raw
     * look vector would send the donkey skyward when the player looks up, and
     * would shorten the block distance to nothing when they look straight down.
     */
    private Vec3 horizontalFacing() {
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        return flat.lengthSqr() < 1.0e-6 ? new Vec3(0.0, 0.0, 1.0) : flat.normalize();
    }

    @Override
    public double donkeyX() {
        return donkey.getX();
    }

    @Override
    public double donkeyZ() {
        return donkey.getZ();
    }

    @Override
    public void lookAtPlayer() {
        donkey.getLookControl().setLookAt(player);
    }

    @Override
    public void bray() {
        // Loud, from the entity, heard by everyone nearby. A performance, not a
        // confirmation (SPEC.md section 8).
        donkey.level().playSound(null, donkey.getX(), donkey.getY(), donkey.getZ(),
                SoundEvents.DONKEY_AMBIENT, SoundSource.NEUTRAL, 1.0f, 1.0f);
    }

    @Override
    public LinePools lines() {
        return lines;
    }

    @Override
    public Random random() {
        return random;
    }

    /** The puff of particles the donkey leaves behind (SPEC.md section 6, cleanup). */
    public void poof() {
        if (donkey.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.POOF,
                    donkey.getX(), donkey.getY() + 0.7, donkey.getZ(),
                    30, 0.4, 0.5, 0.4, 0.02);
        }
    }
}
