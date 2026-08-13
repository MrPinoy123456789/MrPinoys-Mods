package chatdonkey;

import chatdonkey.core.ChatLine;
import chatdonkey.core.Demand;
import chatdonkey.core.DemandBehavior;
import chatdonkey.core.DonkeyBehavior;
import chatdonkey.core.EventContext;
import chatdonkey.core.HitReactions;
import chatdonkey.core.LinePools;
import chatdonkey.core.RateLimit;
import chatdonkey.core.Settings;
import chatdonkey.core.Offering;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
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

    /** The records he might turn up with. Vanilla discs, no resource pack needed. */
    private static final Holder<SoundEvent>[] DISCS = discs();

    /**
     * The phrase he sings over the record: up, up, up, down, down — five notes,
     * so it never lines up with a four-bar backing track.
     *
     * <p>Pitches are a rough major-ish run rather than exact semitones. Precision
     * would be wasted: every note gets detuned on the way out.
     */
    private static final float[] MELODY = {0.85f, 1.0f, 1.12f, 1.26f, 1.0f, 0.9f, 1.19f};

    @SuppressWarnings("unchecked")
    private static Holder<SoundEvent>[] discs() {
        return new Holder[] {
                SoundEvents.MUSIC_DISC_CAT,
                SoundEvents.MUSIC_DISC_BLOCKS,
                SoundEvents.MUSIC_DISC_CHIRP,
                SoundEvents.MUSIC_DISC_FAR,
                SoundEvents.MUSIC_DISC_MALL,
                SoundEvents.MUSIC_DISC_MELLOHI,
                SoundEvents.MUSIC_DISC_STAL,
                SoundEvents.MUSIC_DISC_STRAD,
                SoundEvents.MUSIC_DISC_WARD,
                SoundEvents.MUSIC_DISC_13,
                SoundEvents.MUSIC_DISC_11
        };
    }

    private final ServerPlayer player;
    private final Donkey donkey;
    private final DonkeyBehavior behavior;
    private final LinePools lines;
    private final String name;
    private final Random random;
    private final double heraldChance;
    private final Voice voice;
    private final HitReactions hits = new HitReactions();

    /** Same three-second beat the hit pool uses, for the same reason. */
    private final RateLimit refusals = new RateLimit(HitReactions.LINE_COOLDOWN_TICKS);

    private final DonkeyCoat coat;
    private final BooleanSupplier mayHoldScreen;

    /** Not final: {@code /donkey extend} lengthens a running event (SPEC.md §11). */
    private int durationTicks;

    private int elapsedTicks;
    private Offering fedOffering;
    private ItemStack duplicatedStack;
    private int duplicatedCount;

    public ActiveEvent(ServerPlayer player, Donkey donkey, DonkeyBehavior behavior,
                       LinePools lines, String name, int durationTicks, Random random,
                       double heraldChance, Voice voice, BooleanSupplier mayHoldScreen) {
        this.player = player;
        this.donkey = donkey;
        this.behavior = behavior;
        this.lines = lines;
        this.name = name;
        this.durationTicks = durationTicks;
        this.random = random;
        this.heraldChance = heraldChance;
        this.voice = voice;
        this.mayHoldScreen = mayHoldScreen;
        this.coat = new DonkeyCoat(donkey, player, random);
    }

    /** The donkey's chest inventory. Only the Burrs event touches it. */
    @Override
    public DonkeyCoat coat() {
        return coat;
    }

    @Override
    public boolean mayHoldScreen() {
        return mayHoldScreen.getAsBoolean();
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

    /** Records what the player handed over. The first offering is the one that counts. */
    public void feed(Offering offering) {
        if (fedOffering == null) {
            fedOffering = offering;
        }
    }

    /**
     * Records a resource handed to a duplicating donkey, and how much was taken.
     *
     * <p>The stack is kept so the payout can be the same item back -- a
     * duplicator pays in what it was given, not from the drop table.
     */
    public void feedForDuplication(ItemStack taken, int count) {
        if (fedOffering != null) {
            return;
        }
        fedOffering = Offering.DUPLICATED;
        duplicatedStack = taken;
        duplicatedCount = count;
    }

    /** The stack a duplicating donkey was given, or {@code null}. */
    public ItemStack duplicatedStack() {
        return duplicatedStack;
    }

    public int duplicatedCount() {
        return duplicatedCount;
    }

    /**
     * Lengthens a running event -- "chat pays to extend" (SPEC.md section 11).
     *
     * <p>Clamped to {@link Settings#MAX_EVENT_SECONDS} in total: an unbounded
     * extend is a griefing tool with a price tag.
     *
     * @return the seconds actually added, which may be fewer than asked for, or
     *         zero if the event is already at the ceiling
     */
    public int extendBy(int seconds) {
        int ceiling = Settings.MAX_EVENT_SECONDS * 20;
        int wanted = Math.max(0, seconds) * 20;
        int granted = Math.min(wanted, Math.max(0, ceiling - durationTicks));
        durationTicks += granted;
        return granted / 20;
    }

    /**
     * Puts an externally-supplied line in the donkey's mouth.
     *
     * <p>Sanitised rather than trusted -- it originates in Twitch chat, and
     * upstream moderation is not something this side can verify.
     *
     * @return false if nothing usable survived sanitising
     */
    public boolean sayExternal(String rawLine) {
        String clean = ChatLine.sanitise(rawLine);
        if (clean.isEmpty()) {
            return false;
        }
        say(clean);
        return true;
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

    /**
     * Puts a record on. A real music disc, played from the donkey at full
     * volume, so the Serenade has a backing track to sing over.
     *
     * <p>Discs are long -- far longer than the event -- so this is fire and
     * forget; the event ends well before the track does and the sound simply
     * carries on for whoever is still standing there. That is funnier than
     * cutting it off, and there is no server-side stop for a sound anyway
     * short of a stop-sound packet, which would need the disc's id tracked.
     */
    @Override
    public void startMusic() {
        donkey.level().playSound(null, donkey.getX(), donkey.getY(), donkey.getZ(),
                DISCS[random.nextInt(DISCS.length)], SoundSource.RECORDS, 0.2f, 1.0f);
    }

    /**
     * One note of the donkey's own accompaniment, sung over the record.
     *
     * <p>The tune is a real ascending-then-descending phrase so it is
     * recognisably *a melody* -- but every note is detuned by a per-note wobble,
     * and the phrase length does not divide evenly into the disc's bar, so it
     * drifts against the backing track. Random noise would just sound like a
     * mistake; a melody that is confidently slightly wrong sounds like singing.
     */
    @Override
    public void singNote(int step) {
        float base = MELODY[Math.floorMod(step, MELODY.length)];
        // Up to a semitone out, alternating sharp and flat so it never settles.
        float wobble = (step % 2 == 0 ? 1f : -1f) * (0.02f + random.nextFloat() * 0.04f);
        float pitch = Math.max(0.5f, Math.min(2.0f, base + wobble));

        donkey.level().playSound(null, donkey.getX(), donkey.getY(), donkey.getZ(),
                SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), SoundSource.RECORDS, 0.15f, pitch);
    }

    @Override
    public void keepMouthOpen() {
        if (!donkey.isEating()) {
            donkey.setEating(true);
        }
    }

    /** The satisfied crunch of a donkey being fed. */
    public void eat() {
        donkey.level().playSound(null, donkey.getX(), donkey.getY(), donkey.getZ(),
                SoundEvents.DONKEY_EAT, SoundSource.NEUTRAL, 0.25f, 1.0f);
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
    public Offering fedOffering() {
        return fedOffering;
    }

    @Override
    public Demand demand() {
        return behavior instanceof DemandBehavior demanding
                ? demanding.demand()
                : Demand.NONE;
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
        puff();
        donkey.teleportTo(player.getX(), player.getY(), player.getZ());
        puff();
    }

    /** A small cloud at the donkey's current position, both ends of a teleport. */
    private void puff() {
        if (donkey.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.POOF,
                    donkey.getX(), donkey.getY() + 0.7, donkey.getZ(),
                    12, 0.3, 0.4, 0.3, 0.02);
        }
    }

    /**
     * The catch-up teleport. Particles at both ends, like Clingy's, so it reads
     * as the donkey doing something rather than the server rubber-banding --
     * but no line, so turning up unannounced stays the joke.
     */
    @Override
    public boolean teleportNearPlayer() {
        puff();
        if (!DonkeySpawn.relocateNear(donkey, player, random)) {
            return false;
        }
        puff();
        return true;
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
        // From the entity, heard by everyone nearby. A performance, not a
        // confirmation (SPEC.md section 8), but kept quiet so it does not dominate.
        donkey.level().playSound(null, donkey.getX(), donkey.getY(), donkey.getZ(),
                SoundEvents.DONKEY_AMBIENT, SoundSource.NEUTRAL, 0.25f, 1.0f);
    }

    @Override
    public LinePools lines() {
        return lines;
    }

    @Override
    public Random random() {
        return random;
    }

    @Override
    public double heraldChance() {
        return heraldChance;
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
