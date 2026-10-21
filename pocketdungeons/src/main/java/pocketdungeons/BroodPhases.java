package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Roadmap T43, boss workshop 2026-10-11 decision 6b: "spawner as phase" in the Brood Chamber. Each of
 * the room's four caged spawners frees one earlier Act 1 mechanic the moment it is done (broken, or
 * doused by the exhaust rule), and that mechanic then runs until the floor ends:
 * coffins, creeping bricks, the dark, and the drip, in the cage order {@link BroodPhaseRules#ORDER}
 * fixes. When all four cages are done the brood mother arrives with the last wave, a boss bar, the
 * Kennels handler beat on her half health, and the drip at double speed.
 *
 * <p>The fight is driven from {@link CapstoneFights#tickBrood} on the watch tick; the coffin and
 * brick clocks poll themselves ({@link MineGuardianCoffins#register}, {@link CreepingBricks#register}).
 * The pieces are the Act 1 classes reused: {@link MineGuardianCoffins#stand} stands the coffins,
 * {@link CreepingBricks#open} grows the bricks, the dark scan is {@link MineshaftFinale}'s rules on
 * the terminal cell only, and {@link PotionDrip#release} throws the brews. Every mob the phases make
 * carries {@link CapstoneFights#WAVE_TAG}, so {@link BroodWave}'s pad gate already counts them, and
 * {@link CapstoneFights#floorEnded}'s sweep already removes them. The whole of it is under the
 * {@code broodMobCap} knob: a full room makes new arrivals wait rather than pile up.
 */
final class BroodPhases {

    /** One chamber's phase state, keyed by slot and reset when the floor's layout changes. */
    static final class Phases {
        final long seed;
        final BlockPos terminal;
        /** The cages in {@link CapstoneFights#spawnerPositions} order; index i frees {@code ORDER[i]}. */
        final List<BlockPos> cages = new ArrayList<>();
        final boolean[] released = new boolean[BroodPhaseRules.ORDER.length];
        CreepingBricks.Field bricks;
        final ArrayDeque<BlockPos> brickQueue = new ArrayDeque<>();
        long darkAt = -1;
        boolean dark;
        final Map<BlockPos, BlockState> lights = new LinkedHashMap<>();
        long nextDrip = Long.MAX_VALUE;
        boolean dripFast;
        long nextWeb = Long.MAX_VALUE;
        UUID mother;
        ServerBossEvent bar;
        KennelsFinaleRules.Phase beat = KennelsFinaleRules.Phase.WAITING;
        long windupEnd;
        long nextAnger;
        /** The scale each empowered brood mob had, so {@link #shrink} can put it back. */
        final Map<UUID, Double> originals = new HashMap<>();

        Phases(long seed, BlockPos terminal) {
            this.seed = seed;
            this.terminal = terminal;
        }
    }

    private static final Map<Integer, Phases> PHASES = SessionState.clearedAtStop(new HashMap<>());

    private BroodPhases() {}

    private static Phases phases(InstanceRecord record, CapstoneFights.State state) {
        Phases ph = PHASES.get(record.slot);
        if (ph == null || ph.seed != state.seed || !ph.terminal.equals(state.terminal)) {
            ph = new Phases(state.seed, state.terminal);
            PHASES.put(record.slot, ph);
        }
        if (ph.cages.isEmpty() && !state.spawners.isEmpty()) {
            ph.cages.addAll(state.spawners.keySet());
        }
        return ph;
    }

    /** The chamber's phase state for tests, or {@code null} before the first tick. */
    static Phases phasesOf(int slot) {
        return PHASES.get(slot);
    }

    // ---- the watch tick -----------------------------------------------------------------

    /**
     * One watch tick of the chamber's phases. {@code exhausted} is the douse condition of
     * {@link BroodWave#spawnersDone}: a doused cage frees its mechanic the same as a broken one.
     */
    static void tick(MinecraftServer server, ServerLevel level, InstanceRecord record,
                     CapstoneFights.State state, List<ServerPlayer> members, List<ServerPlayer> inRoom,
                     boolean exhausted) {
        Phases ph = phases(record, state);
        long now = server.getTickCount();
        int cap = PocketDungeonsConfig.broodMobCap();
        int alive = CapstoneFights.broodAlive(level, ph.terminal);
        for (int i = 0; i < ph.cages.size() && i < BroodPhaseRules.ORDER.length; i++) {
            if (ph.released[i]) {
                continue;
            }
            if (!exhausted && level.getBlockState(ph.cages.get(i)).is(Blocks.SPAWNER)) {
                continue; // still standing and still lit
            }
            ph.released[i] = true;
            alive = release(level, record, ph, i, members, cap, now);
        }
        if (ph.bricks != null) {
            if (!ph.brickQueue.isEmpty()) {
                ph.bricks.plant(ph.brickQueue.poll(), now);
            }
            ph.bricks.releaseBudget = BroodPhaseRules.room(cap, alive);
            for (Mob fish : ph.bricks.collect()) {
                fish.addTag(CapstoneFights.WAVE_TAG);
                empowerIfNeeded(ph, fish, members);
            }
        }
        if (ph.darkAt >= 0 && !ph.dark && now >= ph.darkAt) {
            darken(level, record, ph, members, cap, alive);
        }
        if (now >= ph.nextDrip && PocketDungeonsConfig.potionDripEnabled()) {
            drip(level, record, ph, inRoom, now);
        }
        motherTick(server, level, record, ph, members, inRoom, now);
    }

    /** Frees cage {@code index}'s mechanic; returns the brood count after it stands. */
    private static int release(ServerLevel level, InstanceRecord record,
                               Phases ph, int index, List<ServerPlayer> members, int cap, long now) {
        int party = Math.max(1, members.size());
        switch (BroodPhaseRules.mechanicFor(index)) {
            case COFFINS -> {
                int count = Math.min(BroodPhaseRules.coffinsFor(party), BroodPhaseRules.room(cap,
                        CapstoneFights.broodAlive(level, ph.terminal)));
                if (count > 0) {
                    MineGuardianCoffins.stand(level, ph.terminal, count, MineGuardianRules.TELL_BASE_TICKS,
                            mob -> mob.addTag(CapstoneFights.WAVE_TAG));
                }
            }
            case BRICKS -> {
                ph.bricks = CreepingBricks.open(level, record.origin, PocketDungeonsConfig.creepingRoomCap(),
                        true);
                RandomSource random = level.getRandom();
                java.util.Set<BlockPos> taken = new java.util.HashSet<>();
                taken.add(ph.terminal.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2));
                for (int i = 0; i < BroodPhaseRules.brickSeedsFor(party); i++) {
                    BlockPos at = MineGuardians.freeSpotInCell(level, ph.terminal, taken, random);
                    if (at != null) {
                        ph.brickQueue.add(at);
                    }
                }
                overlay(members, "The mortar twitches.", ChatFormatting.GRAY);
            }
            case DARK -> {
                ph.darkAt = now + FinaleRules.darkTellTicks(PocketDungeonsConfig.mineshaftDarkTellSeconds());
                for (ServerPlayer member : members) {
                    level.playSound(null, member.blockPosition(), SoundEvents.WOOD_BREAK, SoundSource.AMBIENT,
                            1.5f, 0.5f);
                    member.sendOverlayMessage(Component.literal(MineshaftFinale.TELL)
                            .withStyle(ChatFormatting.GOLD));
                }
            }
            case DRIP -> {
                ph.nextDrip = now + BroodPhaseRules.dripTicks(baseDripTicks(), ph.dripFast);
                overlay(members, "Something drips from above.", ChatFormatting.GRAY);
            }
        }
        return CapstoneFights.broodAlive(level, ph.terminal);
    }

    // ---- the dark ----------------------------------------------------------------------

    /** The lights of the terminal cell go out (pad and cell middle spared) and the spiders come. */
    private static void darken(ServerLevel level, InstanceRecord record, Phases ph,
                               List<ServerPlayer> members, int cap, int alive) {
        ph.dark = true;
        BlockPos pad = record.layout.exitPad();
        BlockPos o = ph.terminal;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                int wx = o.getX() + x;
                int wz = o.getZ() + z;
                if (FinaleRules.nearPad(wx, wz, pad.getX(), pad.getZ())
                        || FinaleRules.nearPad(wx, wz, o.getX() + RoomGeometry.CELL / 2,
                                o.getZ() + RoomGeometry.CELL / 2)) {
                    continue;
                }
                for (int y = 0; y <= RoomGeometry.CEILING_Y; y++) {
                    cursor.set(wx, o.getY() + y, wz);
                    BlockState state = level.getBlockState(cursor);
                    String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                    if (DungeonLight.isLightSource(path) && !DungeonLight.isSolidLight(path)) {
                        BlockPos at = cursor.immutable();
                        ph.lights.put(at, state);
                        level.setBlock(at, Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                    }
                }
            }
        }
        for (ServerPlayer member : members) {
            level.playSound(null, member.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.AMBIENT,
                    1.0f, 0.6f);
            member.sendOverlayMessage(Component.literal(MineshaftFinale.HINT).withStyle(ChatFormatting.YELLOW));
        }
        int count = Math.min(BroodPhaseRules.darkSpidersFor(Math.max(1, members.size())),
                BroodPhaseRules.room(cap, alive));
        RandomSource random = level.getRandom();
        for (int i = 0; i < count; i++) {
            BlockPos at = CapstoneFights.freeSpot(level, ph.terminal, random);
            if (at == null) {
                continue;
            }
            Mob spider = EntityTypes.CAVE_SPIDER.spawn(level, at, EntitySpawnReason.TRIGGERED);
            if (spider == null) {
                continue;
            }
            spider.setPersistenceRequired();
            spider.addTag(CapstoneFights.WAVE_TAG);
            spider.setGlowingTag(true);
            empowerIfNeeded(ph, spider, members);
            aim(spider, members);
        }
    }

    /** Puts back every light the dark phase removed that is still air. */
    private static void restoreLights(ServerLevel level, Phases ph) {
        for (Map.Entry<BlockPos, BlockState> light : ph.lights.entrySet()) {
            BlockPos at = light.getKey();
            if (level.isLoaded(at) && level.getBlockState(at).isAir()) {
                level.setBlock(at, light.getValue(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        ph.lights.clear();
    }

    // ---- the drip ----------------------------------------------------------------------

    /** One brew over one member in the chamber, on the shared clock; retried soon with nobody there. */
    private static void drip(ServerLevel level, InstanceRecord record, Phases ph,
                             List<ServerPlayer> inRoom, long now) {
        if (inRoom.isEmpty()) {
            ph.nextDrip = now + PotionDrip.RETRY_TICKS;
            return;
        }
        RandomSource random = level.getRandom();
        ServerPlayer target = inRoom.get(random.nextInt(inRoom.size()));
        PotionDripRules.Brew brew = PotionDripRules.pick(PocketDungeonsConfig.potionDripHarmfulPercent(),
                PotionDripRules.harmingAllowed(PotionDrip.levelOf(record),
                        PocketDungeonsConfig.potionDripHarmingMinLevel(), target.getHealth()),
                random.nextDouble(), random.nextDouble());
        UUID thrown = PotionDrip.release(level, record, target, brew);
        ph.nextDrip = now + (thrown == null ? PotionDrip.RETRY_TICKS
                : BroodPhaseRules.dripTicks(baseDripTicks(), ph.dripFast));
    }

    /** The Lush drip's own interval, in ticks: the chamber borrows the same pace. */
    private static int baseDripTicks() {
        return PocketDungeonsConfig.potionDripIntervalSeconds() * 20;
    }

    // ---- the brood mother ----------------------------------------------------------------

    /**
     * The fourth cage is done and a member is in the room: the mother rises beside the wave
     * {@link CapstoneFights#tickBrood} has just released. A scaled cave spider with a boss bar, her
     * own party-scaled stats (she carries {@link CapstoneFights#UNSCALED_TAG}), the drip behind
     * her at double speed, and a web spit on its own clock that marks a member's ground through
     * {@link AoETelegraph} and then holds it: whoever stands in it is slowed and bitten.
     */
    static void motherArrives(MinecraftServer server, ServerLevel level, InstanceRecord record,
                              CapstoneFights.State state, List<ServerPlayer> members, int party) {
        Phases ph = phases(record, state);
        ph.dripFast = true;
        BlockPos spot = CapstoneFights.freeSpot(level, ph.terminal, level.getRandom());
        if (spot == null) {
            spot = ph.terminal.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
        }
        Mob mother = EntityTypes.CAVE_SPIDER.spawn(level, spot, EntitySpawnReason.TRIGGERED);
        if (mother == null) {
            PocketDungeonsMod.LOG.warn("The Brood Mother failed to spawn at {}", spot.toShortString());
            return;
        }
        mother.setPersistenceRequired();
        mother.addTag(CapstoneFights.WAVE_TAG);
        mother.addTag(CapstoneFights.UNSCALED_TAG);
        AttributeInstance scale = mother.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.setBaseValue(BroodPhaseRules.MOTHER_SCALE);
        }
        AttributeInstance health = mother.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(BroodPhaseRules.motherHealth(party));
        }
        mother.setHealth(mother.getMaxHealth());
        AttributeInstance damage = mother.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damage != null) {
            damage.setBaseValue(BroodPhaseRules.motherDamage(party));
        }
        mother.setCustomName(Component.literal(BroodPhaseRules.MOTHER_NAME).withStyle(ChatFormatting.DARK_RED));
        aim(mother, members);
        ph.mother = mother.getUUID();
        ph.bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(BroodPhaseRules.MOTHER_NAME),
                BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
        for (ServerPlayer member : members) {
            ph.bar.addPlayer(member);
        }
        level.playSound(null, ph.terminal.offset(RoomGeometry.CELL / 2, 2, RoomGeometry.CELL / 2),
                SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1.0f, 0.7f);
        CapstoneFights.tell(members, "The Brood Mother hauls herself out of the dark.", ChatFormatting.RED);
        for (ServerPlayer member : members) {
            StaggeredTitle.show(server, member.getUUID(),
                    Component.literal(BroodPhaseRules.MOTHER_NAME).withStyle(ChatFormatting.DARK_RED),
                    List.of("She has come for her cages."), ChatFormatting.RED);
        }
        ph.nextWeb = server.getTickCount() + BroodPhaseRules.WEB_FIRST_TICKS;
        PocketDungeonsMod.LOG.info("Brood mother arrived in slot {} at {}", record.slot,
                spot.toShortString());
    }

    /** Her boss bar, the Kennels beat on her half health, and her web spit on its own clock. */
    private static void motherTick(MinecraftServer server, ServerLevel level, InstanceRecord record,
                                   Phases ph, List<ServerPlayer> members, List<ServerPlayer> inRoom,
                                   long now) {
        if (ph.mother == null && ph.bar == null) {
            return;
        }
        Entity entity = ph.mother == null ? null : level.getEntity(ph.mother);
        Mob mother = entity instanceof Mob mob && mob.isAlive() ? mob : null;
        if (mother != null && now >= ph.nextWeb) {
            spit(level, record, ph, inRoom, now);
        }
        if (ph.bar != null) {
            for (ServerPlayer member : members) {
                ph.bar.addPlayer(member);
            }
            ph.bar.setProgress(mother == null ? 0.0f : mother.getHealth() / mother.getMaxHealth());
            if (mother == null) {
                ph.bar.removeAllPlayers();
                ph.bar = null;
            }
        }
        if (ph.beat == KennelsFinaleRules.Phase.SPENT) {
            return;
        }
        double health = mother == null ? 0.0 : mother.getHealth();
        double max = mother == null ? 1.0 : mother.getMaxHealth();
        KennelsFinaleRules.Step step = KennelsFinaleRules.step(ph.beat, now, ph.windupEnd,
                mother != null, mother != null, health, max, KennelsFinaleRules.TRIGGER_HEALTH_FRACTION);
        switch (step.action()) {
            case START_WINDUP -> {
                ph.windupEnd = now + BroodPhaseRules.windupTicks();
                level.playSound(null, ph.terminal.offset(RoomGeometry.CELL / 2, 2, RoomGeometry.CELL / 2),
                        SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1.2f, 0.8f);
                overlay(members, "The Brood Mother shrieks for her brood!", ChatFormatting.RED);
            }
            case EMPOWER -> empower(level, ph, members, now);
            case SHRINK -> shrink(level, ph);
            default -> { }
        }
        ph.beat = step.phase();
        if (ph.beat == KennelsFinaleRules.Phase.EMPOWERED && now >= ph.nextAnger) {
            ph.nextAnger = now + KennelsFinaleRules.ANGER_REFRESH_TICKS;
            for (Mob mob : brood(level, ph)) {
                if (!mob.getUUID().equals(ph.mother)) {
                    aim(mob, members);
                }
            }
        }
    }

    /** Her spit: one member's ground is marked, then held by a web for a while. */
    private static void spit(ServerLevel level, InstanceRecord record, Phases ph,
                             List<ServerPlayer> inRoom, long now) {
        ph.nextWeb = now + BroodPhaseRules.WEB_INTERVAL_TICKS;
        if (inRoom.isEmpty()) {
            return;
        }
        ServerPlayer target = inRoom.get(level.getRandom().nextInt(inRoom.size()));
        AoETelegraph.zone(level, record, target.blockPosition(), new AoEShapes.Circle(BroodPhaseRules.WEB_RADIUS),
                BroodPhaseRules.WEB_WARN_TICKS, BroodPhaseRules.WEB_LIVE_TICKS, BroodPhaseRules.WEB_TICK_TICKS,
                WEB);
    }

    /** A tick in her web: slowed and bitten. Only the party reads a web -- her brood crawls through it. */
    private static final AoETelegraph.Effect WEB = (level, entity) -> {
        if (entity instanceof ServerPlayer player) {
            player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, BroodPhaseRules.WEB_SLOW_TICKS, 1,
                    false, true));
            player.hurtServer(level, level.damageSources().generic(), BroodPhaseRules.WEB_DAMAGE);
        }
    };

    /** The pack grows: every brood mob but the mother scales up, glows and turns on the party. */
    private static void empower(ServerLevel level, Phases ph, List<ServerPlayer> members, long now) {
        for (Mob mob : brood(level, ph)) {
            if (!mob.getUUID().equals(ph.mother)) {
                empowerOne(ph, mob, members);
            }
        }
        ph.nextAnger = now + KennelsFinaleRules.ANGER_REFRESH_TICKS;
        overlay(members, "The brood grows!", ChatFormatting.RED);
    }

    /** Gives one mob the empowered look if the pack is empowered and it is not already. */
    private static void empowerIfNeeded(Phases ph, Mob mob, List<ServerPlayer> members) {
        if (ph.beat != KennelsFinaleRules.Phase.EMPOWERED || ph.originals.containsKey(mob.getUUID())) {
            return;
        }
        empowerOne(ph, mob, members);
    }

    /** Applies the empowerment to one mob, remembering the scale it had for {@link #shrink}. */
    private static void empowerOne(Phases ph, Mob mob, List<ServerPlayer> members) {
        if (ph.originals.containsKey(mob.getUUID())) {
            return;
        }
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        double base = scale == null ? 1.0 : scale.getBaseValue();
        ph.originals.put(mob.getUUID(), base);
        if (scale != null) {
            scale.setBaseValue(KennelsFinaleRules.empoweredScale(base));
        }
        mob.setGlowingTag(true);
        aim(mob, members);
    }

    /** Puts the empowered pack back to the size it had (the mother died after the windup). */
    private static void shrink(ServerLevel level, Phases ph) {
        for (Map.Entry<UUID, Double> entry : ph.originals.entrySet()) {
            Entity entity = level.getEntity(entry.getKey());
            if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                continue;
            }
            AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
            if (scale != null) {
                scale.setBaseValue(entry.getValue());
            }
            mob.setGlowingTag(false);
        }
        ph.originals.clear();
    }

    /** The living brood mobs in and around the chamber. */
    private static List<Mob> brood(ServerLevel level, Phases ph) {
        AABB bounds = CellGeometry.cellBounds(ph.terminal).inflate(RoomGeometry.CELL);
        List<Mob> out = new ArrayList<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds)) {
            if (mob.isAlive() && mob.entityTags().contains(CapstoneFights.WAVE_TAG)) {
                out.add(mob);
            }
        }
        return out;
    }

    private static void aim(Mob mob, List<ServerPlayer> members) {
        ServerPlayer nearest = null;
        double best = Double.MAX_VALUE;
        for (ServerPlayer member : members) {
            double d = member.distanceToSqr(mob);
            if (member.isAlive() && !member.isSpectator() && d < best) {
                best = d;
                nearest = member;
            }
        }
        if (nearest != null) {
            mob.setTarget(nearest);
        }
    }

    private static void overlay(List<ServerPlayer> members, String text, ChatFormatting colour) {
        for (ServerPlayer member : members) {
            member.sendOverlayMessage(Component.literal(text).withStyle(colour));
        }
    }

    // ---- ending the floor -----------------------------------------------------------------

    /** The floor ended: every phase's world work goes with it. */
    static void floorEnded(ServerLevel level, InstanceRecord record) {
        if (record == null) {
            return;
        }
        end(level, PHASES.remove(record.slot));
    }

    /** Instance teardown: the same with only the slot to hand. */
    static void teardown(ServerLevel level, int slot, InstanceLayout layout) {
        end(level, PHASES.remove(slot));
    }

    /**
     * Undoes the phases: the bar, the brick field (which restores standing bricks), the lights, and
     * the coffin clock. Tagged mobs and tagged potions are swept by {@link CapstoneFights#floorEnded}'s
     * own {@code discardTagged} and {@link PotionDrip#floorEnded}.
     */
    private static void end(ServerLevel level, Phases ph) {
        if (ph == null) {
            return;
        }
        if (ph.bar != null) {
            ph.bar.removeAllPlayers();
            ph.bar = null;
        }
        if (ph.bricks != null) {
            ph.bricks.close();
            ph.bricks = null;
        }
        if (level != null) {
            restoreLights(level, ph);
            MineGuardianCoffins.clear(ph.terminal);
        }
    }
}
