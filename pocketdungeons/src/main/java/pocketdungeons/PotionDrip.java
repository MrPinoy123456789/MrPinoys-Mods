package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Lush Caves potion drip (roadmap T23; boss workshop 2026-10-11, decision 2b): the witches brew on the
 * upper floors and the brews drip in from above on the lower ones. From the start floor of the run
 * (floor 3 by default) a splash potion falls on each party member every so often, heavier with each later
 * floor and heaviest during the boss fight ({@link #setBoss}). The pool is mostly harmful (poison, slowness,
 * weakness, nausea) with a few good brews, and harming only joins it from a high enough floor level.
 *
 * <p>Fairness: the potion is a vanilla thrown splash potion released under the ceiling and lowered at a
 * steady speed so it is seen falling for about two seconds ({@link PocketDungeonsConfig#potionDripFallTicks}),
 * with a sound and particles at the spot it will land, so a quick player can step out. It never drips in the
 * entrance room, the lobby rooms or near a lodestone pad, pauses while anyone is on the pad or the floor is
 * still loading, and never throws a harming brew at a player who could die of it. Poison cannot kill in
 * vanilla. Everything is tagged and removed with the floor.
 *
 * <p>It runs on the watch tick ({@link #tick}); the pure maths is {@link PotionDripRules}.
 */
final class PotionDrip {

    /** Tag on every potion the drip throws. */
    static final String TAG = PocketDungeonsMod.MOD_ID + ".drip";

    /** The dungeon this effect belongs to. */
    static final String DUNGEON_ID = PocketDungeonsMod.MOD_ID + ":rootworks";

    /** Ticks to wait before trying again when a player was in a bad spot. */
    static final int RETRY_TICKS = 100;
    /** Ticks after a floor starts before anything drips (the party is still arriving and loading). */
    static final int ARRIVAL_GRACE_TICKS = 200;
    /** The least free air above a player's head for a visible fall, and the most the fall uses. */
    static final int MIN_CLEARANCE = 3;
    static final int MAX_CLEARANCE = 8;

    private record Drop(UUID potion, BlockPos target, long expires) {}

    /** One slot's drip: when each member is next due, the potions in the air, and whether the boss fight is on. */
    private static final class Slot {
        final Map<UUID, Long> next = new HashMap<>();
        final List<Drop> drops = new ArrayList<>();
        boolean boss;
    }

    private static final Map<Integer, Slot> SLOTS = SessionState.clearedAtStop(new HashMap<>());

    private PotionDrip() {}

    /** Whether this run is a Lush Caves run (the namespace is optional in the compare). */
    static boolean isLush(InstanceRecord record) {
        if (record == null || record.interval == null || record.interval.dungeonId == null) {
            return false;
        }
        String id = record.interval.dungeonId;
        return id.equals(DUNGEON_ID) || id.equals("rootworks");
    }

    /** Speeds the drip up for the boss fight (or back to normal). Reschedules every member at the new rate. */
    static void setBoss(InstanceRecord record, boolean boss) {
        Slot slot = SLOTS.computeIfAbsent(record.slot, k -> new Slot());
        if (slot.boss != boss) {
            slot.boss = boss;
            slot.next.clear();
        }
    }

    /** Whether the boss rate is on for this slot (tests read it). */
    static boolean isBoss(InstanceRecord record) {
        Slot slot = SLOTS.get(record.slot);
        return slot != null && slot.boss;
    }

    /** The floor level the mobs scale to, which tracks the compass (harming brews wait for it). */
    static int levelOf(InstanceRecord record) {
        return record.layout.keystoneLevel() + record.floor.levelBonus;
    }

    // ---- the watch tick -----------------------------------------------------------------------------

    /** One watch tick of the drip for a floor in progress. */
    static void tick(MinecraftServer server, InstanceRecord record) {
        if (!PocketDungeonsConfig.potionDripEnabled() || !isLush(record) || record.layout == null
                || record.phase != RunSession.Phase.ACTIVE || !record.floor.completed.isEmpty()
                || record.tearingDown || record.homecoming != null) {
            return;
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return;
        }
        Slot slot = SLOTS.computeIfAbsent(record.slot, k -> new Slot());
        long now = server.getTickCount();
        tickDrops(level, slot, now);
        int rate = PotionDripRules.ratePercent(record.interval.floorIndex + 1,
                PocketDungeonsConfig.potionDripStartFloor(), PocketDungeonsConfig.potionDripRampPercent(),
                slot.boss, PocketDungeonsConfig.potionDripBossPercent());
        if (rate <= 0 || !record.floor.onPad.isEmpty()
                || server.overworld().getGameTime() - record.floor.startedAtTick < ARRIVAL_GRACE_TICKS) {
            return;
        }
        int interval = PotionDripRules.intervalTicks(PocketDungeonsConfig.potionDripIntervalSeconds(), rate,
                PocketDungeonsConfig.potionDripMinSeconds());
        RandomSource random = level.getRandom();
        for (ServerPlayer player : CapstoneFights.onlineMembers(server, record)) {
            Long due = slot.next.get(player.getUUID());
            if (due == null) {
                slot.next.put(player.getUUID(), now + PotionDripRules.jittered(interval,
                        PocketDungeonsConfig.potionDripJitterPercent(), random.nextDouble()));
                continue;
            }
            if (now < due) {
                continue;
            }
            boolean thrown = launch(level, record, slot, player, now);
            slot.next.put(player.getUUID(), now + (thrown ? PotionDripRules.jittered(interval,
                    PocketDungeonsConfig.potionDripJitterPercent(), random.nextDouble()) : RETRY_TICKS));
        }
    }

    /** Re-marks the landing spot of every potion still in the air and forgets the ones that landed. */
    private static void tickDrops(ServerLevel level, Slot slot, long now) {
        slot.drops.removeIf(drop -> {
            Entity potion = level.getEntity(drop.potion());
            if (potion == null || !potion.isAlive()) {
                return true;
            }
            if (now > drop.expires()) {
                potion.discard();
                return true;
            }
            mark(level, drop.target());
            return false;
        });
    }

    // ---- one brew -----------------------------------------------------------------------------------

    /**
     * Throws one brew at {@code player} if the spot allows it. Returns whether a potion was released; false
     * for a player who is in the entrance or a lobby room, near a pad, dead, or under a low ceiling.
     */
    private static boolean launch(ServerLevel level, InstanceRecord record, Slot slot, ServerPlayer player,
                                  long now) {
        RandomSource random = level.getRandom();
        PotionDripRules.Brew brew = PotionDripRules.pick(PocketDungeonsConfig.potionDripHarmfulPercent(),
                PotionDripRules.harmingAllowed(levelOf(record), PocketDungeonsConfig.potionDripHarmingMinLevel(),
                        player.getHealth()),
                random.nextDouble(), random.nextDouble());
        UUID id = release(level, record, player, brew, now, slot);
        return id != null;
    }

    /**
     * Releases a brew of this kind above {@code player}; the testable half of {@link #launch}. Returns the
     * potion's id, or {@code null} when the spot is not allowed.
     */
    static UUID release(ServerLevel level, InstanceRecord record, ServerPlayer player, PotionDripRules.Brew brew) {
        return release(level, record, player, brew, 0L, null);
    }

    private static UUID release(ServerLevel level, InstanceRecord record, ServerPlayer player,
                                PotionDripRules.Brew brew, long now, Slot slotOrNull) {
        if (!player.isAlive() || player.isSpectator() || player.level() != level) {
            return null;
        }
        BlockPos feet = player.blockPosition();
        if (excluded(record, feet) || padNear(level, feet)) {
            return null;
        }
        int clearance = 0;
        while (clearance < MAX_CLEARANCE && level.getBlockState(feet.above(2 + clearance)).isAir()) {
            clearance++;
        }
        if (clearance < MIN_CLEARANCE) {
            return null;
        }
        double x = player.getX();
        double z = player.getZ();
        double y = feet.getY() + 2 + clearance - 0.7;
        double distance = y - (feet.getY() + 1.8);
        int fall = PocketDungeonsConfig.potionDripFallTicks();
        ItemStack stack = new ItemStack(Items.SPLASH_POTION);
        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(),
                Optional.of(PotionDripRules.colour(brew)), List.of(effectOf(brew)), Optional.empty()));
        ThrownSplashPotion potion = new ThrownSplashPotion(level, x, y, z, stack);
        potion.setNoGravity(true);
        potion.setDeltaMovement(0.0, -PotionDripRules.fallSpeed(distance, fall), 0.0);
        potion.addTag(TAG);
        level.addFreshEntity(potion);
        BlockPos target = new BlockPos((int) Math.floor(x), feet.getY(), (int) Math.floor(z));
        level.playSound(null, target, SoundEvents.BREWING_STAND_BREW, SoundSource.AMBIENT, 1.0f, 1.2f);
        level.playSound(null, BlockPos.containing(x, y, z), SoundEvents.WITCH_THROW, SoundSource.AMBIENT, 0.8f,
                0.7f);
        mark(level, target);
        if (slotOrNull != null) {
            slotOrNull.drops.add(new Drop(potion.getUUID(), target, now + fall + 60L));
        }
        return potion.getUUID();
    }

    /** The effect a brew carries. Poison is vanilla poison, which never takes the last heart. */
    static MobEffectInstance effectOf(PotionDripRules.Brew brew) {
        int ticks = PotionDripRules.durationTicks(brew, PocketDungeonsConfig.potionDripEffectSeconds());
        return switch (brew) {
            case POISON -> new MobEffectInstance(MobEffects.POISON, ticks, 0);
            case SLOWNESS -> new MobEffectInstance(MobEffects.SLOWNESS, ticks, 0);
            case WEAKNESS -> new MobEffectInstance(MobEffects.WEAKNESS, ticks, 0);
            case NAUSEA -> new MobEffectInstance(MobEffects.NAUSEA, ticks, 0);
            case HARMING -> new MobEffectInstance(MobEffects.INSTANT_DAMAGE, 1, 0);
            case REGENERATION -> new MobEffectInstance(MobEffects.REGENERATION, ticks, 0);
            case SPEED -> new MobEffectInstance(MobEffects.SPEED, ticks, 0);
            case JUMP_BOOST -> new MobEffectInstance(MobEffects.JUMP_BOOST, ticks, 0);
        };
    }

    /** The tell at the landing spot: a ring of witch sparks and falling drops, seen from the first tick. */
    private static void mark(ServerLevel level, BlockPos target) {
        level.sendParticles(ParticleTypes.WITCH, target.getX() + 0.5, target.getY() + 0.1, target.getZ() + 0.5, 14,
                0.6, 0.05, 0.6, 0.02);
        level.sendParticles(ParticleTypes.DRIPPING_WATER, target.getX() + 0.5, target.getY() + 0.9,
                target.getZ() + 0.5, 6, 0.5, 0.2, 0.5, 0.0);
    }

    /** The entrance room, the safe and staging rooms and a door preview never get a drip; nor does anywhere off the plan. */
    static boolean excluded(InstanceRecord record, BlockPos pos) {
        String room = FloorRooms.roomAt(record, pos);
        if (room.equals(FloorRooms.SAFE_ROOM) || room.equals(FloorRooms.STAGING) || room.equals(FloorRooms.PREVIEW)) {
            return true;
        }
        if (record.layout.geometry() == null) {
            return true;
        }
        PlanCell here = record.layout.geometry().cellAt(pos);
        return here == null || here.equals(record.layout.geometry().cellAt(record.layout.entrance()));
    }

    /** Whether a lodestone (the exit pad) is within two blocks of the player's feet. */
    private static boolean padNear(ServerLevel level, BlockPos feet) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 0; dy++) {
                    if (level.getBlockState(feet.offset(dx, dy, dz)).is(Blocks.LODESTONE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---- cleanup ------------------------------------------------------------------------------------

    /** The floor ended: every potion still falling is removed and the schedule dropped. */
    static void floorEnded(ServerLevel level, InstanceRecord record) {
        if (record == null) {
            return;
        }
        clear(level, record.slot, record.layout);
    }

    /** Instance teardown, with only a slot and the layout to hand. */
    static void teardown(ServerLevel level, int slot, InstanceLayout layout) {
        clear(level, slot, layout);
    }

    private static void clear(ServerLevel level, int slotId, InstanceLayout layout) {
        Slot slot = SLOTS.remove(slotId);
        if (level == null) {
            return;
        }
        if (slot != null) {
            for (Drop drop : slot.drops) {
                Entity potion = level.getEntity(drop.potion());
                if (potion != null) {
                    potion.discard();
                }
            }
        }
        if (layout != null && layout.bounds() != null) {
            for (ThrownSplashPotion potion : level.getEntitiesOfClass(ThrownSplashPotion.class, layout.bounds())) {
                if (potion.entityTags().contains(TAG)) {
                    potion.discard();
                }
            }
        }
    }
}
