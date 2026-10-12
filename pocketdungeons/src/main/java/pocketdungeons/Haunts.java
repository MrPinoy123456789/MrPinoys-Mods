package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The haunt director (docs/haunts-draft-2026-10-11.md): one roaming creature per floor with a rule the player
 * can learn. Pressure rises with the seconds the party spends on a haunted floor; when it reaches the wake
 * threshold the haunt walks in, and its stage comes from the floor number ({@link HauntRules#stageFor}): a
 * sighting on the first floor (it stalks, never attacks, and vanishes), a near miss on the second, a strike from
 * the third. After each episode it stays away for the retreat time.
 *
 * <p>The one behaviour built so far is {@code STALK_WHEN_UNSEEN} (The Counted): the body moves only while no
 * member looks at it, using the look vector and a line of sight check on the server. Fairness rules, fixed for
 * every haunt: a tell (particles and a sound) comes first and runs at least {@code hauntTellSeconds} before an
 * act; a strike is a small fixed hit plus a short slowness and can never take the last health; the haunt
 * retreats after any act; it never walks in the entrance room, the terminal (pad) room, a room with an armed
 * Ordeal, or on a floor in a finale or a capstone fight. The first real tell writes one rule line to each
 * member, once per trip.
 *
 * <p>The body is an ordinary tagged mob. It is never scaled, never counted by a clear gate (those count
 * spawners), pays no drops or experience ({@code OmenWaveNoDropsMixin}), cannot rise again ({@link Restless}),
 * and is discarded when the floor ends and at teardown.
 */
final class Haunts {

    /** Tag on every haunt body. */
    static final String TAG = PocketDungeonsMod.HAUNT_TAG;

    /** Blocks within which a stalking haunt gives its tell and starts the tell clock. */
    private static final double TELL_RANGE = 18.0;
    /** Blocks within which a member hears the tell and is taught the rule. */
    private static final double HEAR_RANGE = 24.0;
    private static final int TELL_PARTICLE_PERIOD = 6;
    private static final int TELL_SOUND_PERIOD = 40;
    private static final int PATH_PERIOD = 5;

    private static final class State {
        final long seed;
        final HauntDefinition def;
        double pressure;
        long retreatUntil;
        UUID mob;
        long spawnedTick;
        long tellStart = -1;
        int vanishes;
        boolean spent;

        State(long seed, HauntDefinition def) {
            this.seed = seed;
            this.def = def;
        }
    }

    private static final Map<Integer, State> STATES = SessionState.clearedAtStop(new HashMap<>());
    /** The members already taught the rule this trip, by instance slot. */
    private static final Map<Integer, Set<UUID>> TAUGHT = SessionState.clearedAtStop(new HashMap<>());

    /** Test seam: when set, this level stands in for the dungeon dimension, which the gametest world lacks. */
    static ServerLevel levelForTesting;
    /** Test seam: records the body tick may resolve without being in the live registry. */
    private static final Map<Integer, InstanceRecord> TEST_RECORDS = SessionState.clearedAtStop(new HashMap<>());

    private Haunts() {}

    /** Test seam: lets a gametest run the director and the body tick on a record it built itself. */
    static void registerForTesting(ServerLevel level, InstanceRecord record) {
        levelForTesting = level;
        TEST_RECORDS.put(record.slot, record);
    }

    /** Test seam: the end of {@link #registerForTesting}; drops the record and its haunt state. */
    static void forgetForTesting(InstanceRecord record) {
        TEST_RECORDS.remove(record.slot);
        STATES.remove(record.slot);
        TAUGHT.remove(record.slot);
        if (TEST_RECORDS.isEmpty()) {
            levelForTesting = null;
        }
    }

    private static InstanceRecord recordOf(int slot) {
        InstanceRecord live = InstanceRegistry.bySlot.get(slot);
        return live != null ? live : TEST_RECORDS.get(slot);
    }

    private static ServerLevel dungeonLevel(MinecraftServer server) {
        return levelForTesting != null ? levelForTesting : server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
    }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(Haunts::tickBodies);
    }

    // ---- which floors are haunted -----------------------------------------------------------------

    /** The haunt a floor carries, or {@code null}: the dungeon has one, the run is high enough, haunts are on. */
    static HauntDefinition hauntOf(InstanceRecord record) {
        if (!PocketDungeonsConfig.hauntsEnabled() || record == null || record.layout == null) {
            return null;
        }
        DungeonDef def = TripView.def(record);
        if (def == null || def.kind() == DungeonDef.Kind.CAPSTONE) {
            return null;
        }
        HauntDefinition haunt = HauntManifest.current().forDungeon(def.id());
        if (haunt == null || record.layout.keystoneLevel() < haunt.minCompass()) {
            return null;
        }
        return haunt;
    }

    /** The line the floor title adds on a haunted floor, or {@code null}. */
    static String titleLine(InstanceRecord record) {
        return hauntOf(record) == null ? null : HauntRules.TITLE_LINE;
    }

    /** The floor number in the dungeon, 1 for its first floor. */
    static int floorNumber(InstanceRecord record) {
        DungeonDef def = TripView.def(record);
        DungeonDef.Node node = def == null ? null : def.node(record.interval.nodeId);
        if (node != null && node.layer() >= 1) {
            return node.layer();
        }
        return Math.max(1, record.interval.path.size());
    }

    static HauntRules.Stage stageOf(InstanceRecord record) {
        return HauntRules.stageFor(floorNumber(record), PocketDungeonsConfig.hauntNearMissFloor(),
                PocketDungeonsConfig.hauntStrikeFloor());
    }

    // ---- where it may not walk --------------------------------------------------------------------

    /** The origin of the plan cell that holds {@code pos}, or {@code null} outside every cell. */
    static BlockPos cellOriginAt(InstanceRecord record, BlockPos pos) {
        for (PlanCell cell : record.layout.geometry().cells()) {
            BlockPos origin = record.layout.geometry().cellOrigin(cell);
            if (CellGeometry.insideAnyCell(pos, List.of(origin))) {
                return origin;
            }
        }
        return null;
    }

    /**
     * Whether a haunt may not be at {@code pos}: outside the layout, in the entrance room, in the terminal room
     * (the lodestone pad), or in a room whose Ordeal is armed and unresolved.
     */
    static boolean excluded(InstanceRecord record, BlockPos pos) {
        BlockPos cell = cellOriginAt(record, pos);
        if (cell == null) {
            return true;
        }
        BlockPos entranceCell = cellOriginAt(record, record.layout.entrance());
        if (cell.equals(entranceCell) || cell.equals(record.layout.terminal())) {
            return true;
        }
        return Ordeals.objectiveAt(cell) != null;
    }

    /** Whether a finale or a capstone fight owns the floor, which a haunt must stay out of. */
    static boolean floorInFight(InstanceRecord record) {
        return CapstoneFights.fightOf(record) != CapstoneFights.Fight.NONE || FinaleWave.stirring(record);
    }

    // ---- the director (watch tick, once a second) -------------------------------------------------

    /** One watch tick of the floor's haunt: pressure, waking, lingering, ending. */
    static void tick(MinecraftServer server, InstanceRecord record) {
        HauntDefinition def = hauntOf(record);
        if (def == null || record.phase != RunSession.Phase.ACTIVE || !record.floor.completed.isEmpty()) {
            return;
        }
        ServerLevel level = dungeonLevel(server);
        if (level == null) {
            return;
        }
        State state = STATES.get(record.slot);
        if (state == null || state.seed != record.layout.seed()) {
            discard(level, state);
            state = new State(record.layout.seed(), def);
            STATES.put(record.slot, state);
        }
        if (state.spent) {
            return;
        }
        long now = server.getTickCount();
        List<ServerPlayer> members = livingMembers(server, record);
        if (floorInFight(record)) {
            banish(level, state, true);
            return;
        }
        if (state.mob != null) {
            Entity body = level.getEntity(state.mob);
            if (body == null || !body.isAlive()) {
                // Killed (or lost): a slain haunt stays gone for the floor and pays nothing.
                state.mob = null;
                state.spent = true;
                return;
            }
            if (now - state.spawnedTick > PocketDungeonsConfig.hauntLingerSeconds() * 20L
                    || excluded(record, body.blockPosition())) {
                vanish(level, record, state, (Mob) body);
            }
            return;
        }
        if (members.isEmpty() || now < state.retreatUntil) {
            return;
        }
        boolean anyoneOpen = false;
        for (ServerPlayer member : members) {
            if (!excluded(record, member.blockPosition())) {
                anyoneOpen = true;
                break;
            }
        }
        if (!anyoneOpen) {
            return;
        }
        state.pressure += PocketDungeonsConfig.hauntPressurePerSecond()
                * PocketDungeonsConfig.watchIntervalTicks() / 20.0;
        if (HauntRules.wakes(state.pressure, PocketDungeonsConfig.hauntWakePressure())) {
            wake(level, record, state, members, now);
        }
    }

    private static void wake(ServerLevel level, InstanceRecord record, State state, List<ServerPlayer> members,
                             long now) {
        HauntRules.Stage stage = stageOf(record);
        RandomSource random = level.getRandom();
        ServerPlayer around = members.get(random.nextInt(members.size()));
        BlockPos at = pickSpot(level, record, members, around, stage == HauntRules.Stage.SIGHTING, random);
        if (at == null) {
            return;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(state.def.body())).orElse(null);
        Entity made = type == null ? null : type.create(level, EntitySpawnReason.TRIGGERED);
        if (!(made instanceof Mob mob)) {
            PocketDungeonsMod.LOG.warn("Haunt {} could not make a body of {}", state.def.id(), state.def.body());
            state.spent = true;
            return;
        }
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
                (float) Math.toDegrees(Math.atan2(around.getZ() - at.getZ(), around.getX() - at.getX())) - 90.0f, 0.0f);
        mob.addTag(TAG);
        mob.addTag(CapstoneFights.UNSCALED_TAG);
        mob.setPersistenceRequired();
        mob.setCanPickUpLoot(false);
        mob.setCustomName(Component.literal(state.def.label()));
        mob.setCustomNameVisible(false);
        if (mob.getAttribute(Attributes.ATTACK_DAMAGE) != null) {
            mob.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(0.0);
        }
        level.addFreshEntity(mob);
        state.mob = mob.getUUID();
        state.spawnedTick = now;
        state.tellStart = -1;
        state.pressure = 0.0;
        tell(level, record, state.def, mob, members);
        PocketDungeonsMod.LOG.info("Haunt {} wakes in slot {} (stage {}, floor {})", state.def.id(), record.slot,
                stage, floorNumber(record));
    }

    // ---- the body (every tick) --------------------------------------------------------------------

    private static void tickBodies(MinecraftServer server) {
        if (STATES.isEmpty()) {
            return;
        }
        ServerLevel level = dungeonLevel(server);
        if (level == null) {
            return;
        }
        long now = server.getTickCount();
        for (Map.Entry<Integer, State> entry : new ArrayList<>(STATES.entrySet())) {
            State state = entry.getValue();
            if (state.mob == null) {
                continue;
            }
            InstanceRecord record = recordOf(entry.getKey());
            Entity body = level.getEntity(state.mob);
            if (record == null || record.tearingDown || record.layout == null || !(body instanceof Mob mob)
                    || !mob.isAlive()) {
                continue;
            }
            List<ServerPlayer> members = livingMembers(server, record);
            stepBody(level, record, state, mob, members, now);
        }
    }

    private static void stepBody(ServerLevel level, InstanceRecord record, State state, Mob mob,
                                 List<ServerPlayer> members, long now) {
        if (members.isEmpty()) {
            hold(mob);
            return;
        }
        ServerPlayer nearest = members.get(0);
        for (ServerPlayer member : members) {
            if (member.distanceToSqr(mob) < nearest.distanceToSqr(mob)) {
                nearest = member;
            }
        }
        double distance = Math.sqrt(nearest.distanceToSqr(mob));
        boolean seen = seenByAny(members, mob);
        long lead = PocketDungeonsConfig.hauntTellSeconds() * 20L;
        HauntRules.Stage stage = stageOf(record);
        HauntRules.Move move = HauntRules.decide(stage, seen, distance,
                PocketDungeonsConfig.hauntSightingApproachBlocks(), HauntRules.tellDone(now, state.tellStart, lead));
        if ((move == HauntRules.Move.STRIKE || move == HauntRules.Move.NEAR_MISS)
                && excluded(record, nearest.blockPosition())) {
            move = HauntRules.Move.FREEZE;
        }
        switch (move) {
            case FREEZE -> hold(mob);
            case ADVANCE -> {
                release(mob);
                if (distance <= TELL_RANGE) {
                    if (state.tellStart < 0) {
                        state.tellStart = now;
                    }
                    if (now % TELL_PARTICLE_PERIOD == 0) {
                        trail(level, record, state, mob, members, now);
                    }
                }
                if (now % PATH_PERIOD == 0 || mob.getNavigation().isDone()) {
                    mob.getNavigation().moveTo(nearest, 1.0);
                }
            }
            case VANISH -> vanish(level, record, state, mob);
            case NEAR_MISS -> {
                level.playSound(null, nearest.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE,
                        1.0f, 0.7f);
                level.sendParticles(ParticleTypes.SWEEP_ATTACK, nearest.getX(), nearest.getY() + 1.0, nearest.getZ(),
                        1, 0.0, 0.0, 0.0, 0.0);
                retreat(level, record, state, mob);
            }
            case STRIKE -> {
                strike(level, nearest, mob);
                retreat(level, record, state, mob);
            }
        }
    }

    private static void hold(Mob mob) {
        if (!mob.isNoAi()) {
            mob.getNavigation().stop();
            mob.setNoAi(true);
        }
    }

    private static void release(Mob mob) {
        if (mob.isNoAi()) {
            mob.setNoAi(false);
        }
        mob.setTarget(null);
    }

    /** Whether any member has the body in the look cone with a clear line of sight. */
    static boolean seenByAny(List<ServerPlayer> members, Mob mob) {
        Vec3 target = mob.position().add(0.0, mob.getBbHeight() * 0.6, 0.0);
        double cone = PocketDungeonsConfig.hauntLookConeDegrees();
        for (ServerPlayer member : members) {
            Vec3 eye = member.getEyePosition();
            Vec3 look = member.getLookAngle();
            if (HauntRules.inCone(eye.x, eye.y, eye.z, look.x, look.y, look.z, target.x, target.y, target.z, cone)
                    && clearBetween(member.level(), eye, target, member)) {
                return true;
            }
        }
        return false;
    }

    private static boolean clearBetween(net.minecraft.world.level.Level level, Vec3 from, Vec3 to, Entity by) {
        HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, by));
        return hit.getType() == HitResult.Type.MISS;
    }

    // ---- tell, teach, act ---------------------------------------------------------------------------

    /** The trail a moving haunt leaves: particles at its feet, a sound now and then, the rule line once. */
    private static void trail(ServerLevel level, InstanceRecord record, State state, Mob mob,
                              List<ServerPlayer> members, long now) {
        sendParticle(level, state.def, mob.getX(), mob.getY() + 0.2, mob.getZ(), 3);
        if (now % TELL_SOUND_PERIOD < TELL_PARTICLE_PERIOD) {
            soundAt(level, state.def, mob.blockPosition(), 0.8f);
        }
        teach(record, state.def, mob, members);
    }

    /** The tell at the moment it appears: a burst, a sound, and the rule line. */
    private static void tell(ServerLevel level, InstanceRecord record, HauntDefinition def, Mob mob,
                             List<ServerPlayer> members) {
        sendParticle(level, def, mob.getX(), mob.getY() + 0.5, mob.getZ(), 12);
        soundAt(level, def, mob.blockPosition(), 1.0f);
        teach(record, def, mob, members);
    }

    /** Writes the haunt's one rule line to each member who hears the tell and has not read it this trip. */
    private static void teach(InstanceRecord record, HauntDefinition def, Mob mob, List<ServerPlayer> members) {
        Set<UUID> taught = TAUGHT.computeIfAbsent(record.slot, slot -> new HashSet<>());
        for (ServerPlayer member : members) {
            if (member.distanceToSqr(mob) <= HEAR_RANGE * HEAR_RANGE && taught.add(member.getUUID())) {
                Component line = Component.literal(def.ruleLine()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
                member.sendSystemMessage(line);
                member.sendOverlayMessage(line);
            }
        }
    }

    private static void strike(ServerLevel level, ServerPlayer target, Mob mob) {
        float damage = HauntRules.strikeDamage(PocketDungeonsConfig.hauntStrikeDamage(), target.getHealth());
        if (!target.isCreative() && damage > 0.0f) {
            // A plain source: a mob attack would scale with difficulty and could turn a safe figure into a kill.
            target.hurtServer(level, level.damageSources().generic(), damage);
        }
        int slow = PocketDungeonsConfig.hauntSlownessSeconds();
        if (slow > 0 && !target.isCreative()) {
            target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, slow * 20, 1, false, true));
        }
        level.playSound(null, target.blockPosition(), SoundEvents.SKELETON_HURT, SoundSource.HOSTILE, 1.0f, 0.6f);
        level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 1.0, target.getZ(), 10, 0.3, 0.4, 0.3, 0.2);
    }

    // ---- leaving ------------------------------------------------------------------------------------

    /** After an act: the body goes, the retreat clock starts and the pressure begins again from nothing. */
    private static void retreat(ServerLevel level, InstanceRecord record, State state, Mob mob) {
        leave(level, state, mob);
        state.retreatUntil = level.getServer().getTickCount() + PocketDungeonsConfig.hauntRetreatSeconds() * 20L;
    }

    /**
     * A vanish: smoke and the tell left where it stood. In a sighting each one is counted, and the haunt gives up
     * for the floor after {@code hauntSightingVanishes}; after any stage it keeps away for the retreat time.
     */
    private static void vanish(ServerLevel level, InstanceRecord record, State state, Mob mob) {
        leave(level, state, mob);
        state.retreatUntil = level.getServer().getTickCount() + PocketDungeonsConfig.hauntRetreatSeconds() * 20L;
        if (stageOf(record) == HauntRules.Stage.SIGHTING) {
            state.vanishes++;
            if (state.vanishes >= PocketDungeonsConfig.hauntSightingVanishes()) {
                state.spent = true;
            }
        }
    }

    private static void leave(ServerLevel level, State state, Mob mob) {
        level.sendParticles(ParticleTypes.POOF, mob.getX(), mob.getY() + 1.0, mob.getZ(), 8, 0.2, 0.5, 0.2, 0.02);
        sendParticle(level, state.def, mob.getX(), mob.getY() + 0.5, mob.getZ(), 10);
        soundAt(level, state.def, mob.blockPosition(), 0.7f);
        mob.discard();
        state.mob = null;
        state.tellStart = -1;
    }

    /** Takes the body off the floor without a trace; {@code spend} keeps it from coming back. */
    private static void banish(ServerLevel level, State state, boolean spend) {
        if (state.mob != null) {
            Entity body = level.getEntity(state.mob);
            if (body != null) {
                body.discard();
            }
            state.mob = null;
        }
        if (spend) {
            state.spent = true;
        }
    }

    // ---- ending a floor -----------------------------------------------------------------------------

    /** The floor ended: the body goes with it. Safe to call for any floor. */
    static void floorEnded(ServerLevel level, InstanceRecord record) {
        if (record == null) {
            return;
        }
        discard(level, STATES.remove(record.slot));
        if (record.layout != null) {
            sweep(level, record.layout.bounds());
        }
    }

    /** Instance teardown, with only a slot and the layout bounds to hand. */
    static void teardown(ServerLevel level, int slot, InstanceLayout layout) {
        discard(level, STATES.remove(slot));
        TAUGHT.remove(slot);
        if (layout != null) {
            sweep(level, layout.bounds());
        }
    }

    private static void discard(ServerLevel level, State state) {
        if (state != null && state.mob != null && level != null) {
            banish(level, state, false);
        }
    }

    /** Discards any haunt body still standing in {@code bounds}, found by tag. */
    private static void sweep(ServerLevel level, AABB bounds) {
        if (level == null || bounds == null) {
            return;
        }
        for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds, m -> m.entityTags().contains(TAG))) {
            mob.discard();
        }
    }

    /** Test hook: whether the floor of {@code slot} holds a live haunt body. */
    static boolean hasBody(int slot) {
        State state = STATES.get(slot);
        return state != null && state.mob != null;
    }

    /** Test hook: the haunt body of {@code slot}, or {@code null}. */
    static UUID bodyOf(int slot) {
        State state = STATES.get(slot);
        return state == null ? null : state.mob;
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private static List<ServerPlayer> livingMembers(MinecraftServer server, InstanceRecord record) {
        List<ServerPlayer> out = new ArrayList<>();
        for (UUID id : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null && player.isAlive() && !player.isSpectator()
                    && (levelForTesting != null || DungeonWorldRules.inDungeon(player.level()))) {
                out.add(player);
            }
        }
        return out;
    }

    /**
     * A standing spot for the body. A sighting wants one 10 to 16 blocks out that {@code around} can see; the
     * other stages want one 16 to 26 blocks out that no member can see. Never an excluded room.
     */
    private static BlockPos pickSpot(ServerLevel level, InstanceRecord record, List<ServerPlayer> members,
                                     ServerPlayer around, boolean seen, RandomSource random) {
        for (int attempt = 0; attempt < 80; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = seen ? 10.0 + random.nextDouble() * 6.0 : 16.0 + random.nextDouble() * 10.0;
            int x = (int) Math.floor(around.getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(around.getZ() + Math.sin(angle) * radius);
            for (int dy = 3; dy >= -3; dy--) {
                BlockPos at = new BlockPos(x, around.getBlockY() + dy, z);
                if (!level.getBlockState(at).isAir() || !level.getBlockState(at.above()).isAir()
                        || !level.getBlockState(at.below()).isSolid() || excluded(record, at)) {
                    continue;
                }
                Vec3 eyeHeight = Vec3.atBottomCenterOf(at).add(0.0, 1.2, 0.0);
                if (seen) {
                    if (clearBetween(level, around.getEyePosition(), eyeHeight, around)) {
                        return at;
                    }
                    continue;
                }
                boolean visible = false;
                for (ServerPlayer member : members) {
                    if (member.distanceToSqr(eyeHeight) < 14.0 * 14.0
                            || clearBetween(level, member.getEyePosition(), eyeHeight, member)) {
                        visible = true;
                        break;
                    }
                }
                if (!visible) {
                    return at;
                }
            }
        }
        return null;
    }

    private static void sendParticle(ServerLevel level, HauntDefinition def, double x, double y, double z, int count) {
        var type = BuiltInRegistries.PARTICLE_TYPE.getOptional(Identifier.parse(def.tellParticle())).orElse(null);
        if (type instanceof SimpleParticleType simple) {
            level.sendParticles(simple, x, y, z, count, 0.25, 0.4, 0.25, 0.01);
        }
    }

    private static void soundAt(ServerLevel level, HauntDefinition def, BlockPos at, float volume) {
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(Identifier.parse(def.tellSound())).orElse(null);
        if (sound != null) {
            level.playSound(null, at, sound, SoundSource.HOSTILE, volume, 0.8f);
        }
    }
}
