package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A dungeon\u0027s finale (design pass 2026-10-09, Q4; PD-184): the last floor of an ordinary dungeon ends in a
 * fight, not a walk to the pad. When the final floor\u0027s spawners are cleared the title says the floor stirs,
 * a few seconds later a wave of the dungeon\u0027s own mobs (sized to the party) stands up around the members,
 * and from Act 2 a named elite leads it with a boss bar. The terminal pad stays shut until every one of them
 * is dead, the same gate the brood chamber uses; the win pays an extra reward chest.
 *
 * <p>The wave is data ({@link DungeonDef.Finale}); capstones keep their own fights ({@link CapstoneFights}).
 * The sizing rules are {@link FinaleRules}. Nothing here seals a door: the gate is the pad.
 *
 * <p>A dungeon may add behaviour through a {@link FinaleScript} (roadmap T01, {@link FinaleScripts}); one
 * without a script runs exactly the data wave.
 */
final class FinaleWave {

    /** Tag on every mob of a finale. */
    static final String TAG = PocketDungeonsMod.MOD_ID + ".finale";

    /** One slot's finale in progress; a {@link FinaleContext} is a view over it. */
    static final class State {
        final long seed;
        final InstanceRecord record;
        final FinaleScript script;
        DungeonDef.Finale finale;
        boolean triggered;
        long spawnTick;
        boolean spawned;
        boolean done;
        boolean halfFired;
        final List<UUID> mobs = new ArrayList<>();
        final Set<UUID> dead = new HashSet<>();
        UUID elite;
        ServerBossEvent bar;
        /** The script's own per run state, dropped with this object. */
        final Map<Class<?>, Object> scriptState = new HashMap<>();

        State(InstanceRecord record) {
            this.seed = record.layout.seed();
            this.record = record;
            this.script = FinaleScripts.forRecord(record);
        }
    }

    private static final Map<Integer, State> STATES = SessionState.clearedAtStop(new HashMap<>());

    private FinaleWave() {}

    // ---- when a floor has one ---------------------------------------------------------------------

    /** The finale the floor in progress ends in, or {@code null}: an ordinary dungeon\u0027s final floor with one authored. */
    static DungeonDef.Finale finaleOf(InstanceRecord record) {
        if (!PocketDungeonsConfig.finaleEnabled() || record == null || record.layout == null) {
            return null;
        }
        DungeonDef def = TripView.def(record);
        if (def == null || def.finale() == null || def.kind() == DungeonDef.Kind.CAPSTONE || !TripView.onFinal(record)) {
            return null;
        }
        return def.finale();
    }

    private static State state(InstanceRecord record) {
        State state = STATES.get(record.slot);
        if (state == null || state.seed != record.layout.seed()) {
            discard(null, state);
            state = new State(record);
            STATES.put(record.slot, state);
        }
        return state;
    }

    /** Whether the final floor\u0027s spawners are cleared, which is what brings the finale on. */
    private static boolean due(ServerLevel level, InstanceRecord record) {
        Set<BlockPos> spawners = TrialContent.activeSpawners(record.layout, level);
        if (spawners.isEmpty()) {
            return false;
        }
        return DifficultyProfile.spawnersCleared(TrialContent.countCleared(level, spawners), spawners.size(),
                PocketDungeonsConfig.spawnerClearThreshold());
    }

    // ---- the watch tick ----------------------------------------------------------------------------

    /** Whether the floor finale has begun and is not yet won, which a haunt must stay out of. */
    static boolean stirring(InstanceRecord record) {
        if (record == null || record.layout == null || finaleOf(record) == null) {
            return false;
        }
        State state = STATES.get(record.slot);
        return state != null && state.seed == record.layout.seed() && state.triggered && !state.done;
    }

    /** One watch tick of the finale, if the floor holds one. */
    static void tick(MinecraftServer server, InstanceRecord record) {
        DungeonDef.Finale finale = finaleOf(record);
        if (finale == null || record.phase != RunSession.Phase.ACTIVE || !record.floor.completed.isEmpty()) {
            return;
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return;
        }
        State state = state(record);
        if (state.done) {
            return;
        }
        List<ServerPlayer> members = CapstoneFights.onlineMembers(server, record);
        boolean due = !state.triggered && !members.isEmpty() && due(level, record);
        String name = "";
        if (!state.triggered && !members.isEmpty()) {
            DungeonDef.Node node = TripView.def(record).node(record.interval.nodeId);
            name = node == null ? TripView.dungeonName(record) : node.name();
        }
        step(server, level, record, finale, members, due, name);
    }

    /**
     * The finale\u0027s state machine for one tick, split from {@link #tick} so a gametest (which has no dungeon
     * level) can drive it. {@code due} says the final floor\u0027s spawners are cleared; it is only read until the
     * finale triggers. {@code name} is the room\u0027s name for the title.
     */
    static void step(MinecraftServer server, ServerLevel level, InstanceRecord record, DungeonDef.Finale finale,
                     List<ServerPlayer> members, boolean due, String name) {
        State state = state(record);
        if (state.done) {
            return;
        }
        state.finale = finale;
        FinaleContext ctx = new FinaleContext(server, level, record, finale, state, members);
        FinaleScript script = state.script;
        if (!state.triggered) {
            if (members.isEmpty()) {
                return;
            }
            boolean go = due;
            if (!go && script != FinaleScript.NONE) {
                boolean[] asked = {false};
                hook(state, "shouldTrigger", () -> asked[0] = script.shouldTrigger(ctx));
                go = asked[0];
            }
            if (!go) {
                return;
            }
            state.triggered = true;
            state.spawnTick = server.getTickCount() + PocketDungeonsConfig.finaleCountdownSeconds() * 20L;
            String line = finale.elite() == null ? "Hold the room." : finale.elite().name() + " comes.";
            String title = name + " stirs";
            List<String> lines = List.of(line);
            if (script != FinaleScript.NONE) {
                List<List<String>> custom = new ArrayList<>();
                hook(state, "triggerLines", () -> custom.add(script.triggerLines(ctx)));
                if (!custom.isEmpty() && custom.get(0) != null && !custom.get(0).isEmpty()) {
                    title = custom.get(0).get(0);
                    lines = custom.get(0).subList(1, custom.get(0).size());
                }
            }
            for (ServerPlayer member : members) {
                StaggeredTitle.show(server, member.getUUID(),
                        Component.literal(title).withStyle(ChatFormatting.GOLD), lines, ChatFormatting.GRAY);
            }
            level.playSound(null, members.get(0).blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE,
                    1.0f, 0.6f);
            hook(state, "onTriggered", () -> script.onTriggered(ctx));
            return;
        }
        if (!state.spawned) {
            if (server.getTickCount() < state.spawnTick || members.isEmpty()) {
                return;
            }
            boolean[] taken = {false};
            hook(state, "spawnWave", () -> taken[0] = script.spawnWave(ctx));
            if (!taken[0]) {
                spawnWave(level, record, finale, state, members);
            }
            state.spawned = true;
            hook(state, "onWaveSpawned", () -> script.onWaveSpawned(ctx));
            return;
        }
        if (state.bar != null) {
            Entity elite = state.elite == null ? null : level.getEntity(state.elite);
            if (elite instanceof Mob mob && mob.isAlive()) {
                state.bar.setProgress(Math.max(0.0f, Math.min(1.0f, mob.getHealth() / mob.getMaxHealth())));
                for (ServerPlayer member : members) {
                    state.bar.addPlayer(member);
                }
            } else {
                state.bar.removeAllPlayers();
            }
        }
        if (script != FinaleScript.NONE) {
            // A copy: an onMobDied hook may add mobs (a script's split children), which grows the list.
            for (UUID id : new ArrayList<>(state.mobs)) {
                if (!isAlive(level, id) && state.dead.add(id)) {
                    hook(state, "onMobDied", () -> script.onMobDied(ctx, id));
                }
            }
            if (!state.halfFired && FinaleRules.halfDead(alive(level, state), state.mobs.size())) {
                state.halfFired = true;
                hook(state, "onHalfDead", () -> script.onHalfDead(ctx));
            }
            if (alive(level, state) > 0) {
                hook(state, "onTick", () -> script.onTick(ctx));
            }
        }
        if (alive(level, state) == 0) {
            state.done = true;
            record.floor.finaleWon = true;
            if (state.bar != null) {
                state.bar.removeAllPlayers();
            }
            CapstoneFights.tell(members, "The last stand is over. The pad is open.", ChatFormatting.GREEN);
            hook(state, "onWon", () -> script.onWon(ctx));
        }
    }

    /** Runs a script hook; one that throws is logged and skipped so a bad script cannot keep the pad shut. */
    private static void hook(State state, String name, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Finale script {} threw in {} (slot {})", state.script.getClass().getName(),
                    name, state.record.slot, e);
        }
    }

    private static boolean isAlive(ServerLevel level, UUID id) {
        Entity entity = level.getEntity(id);
        return entity != null && entity.isAlive();
    }

    /** The finale of this record\u0027s slot, or {@code null} before it has started (gametests read it). */
    static State stateOf(InstanceRecord record) {
        return STATES.get(record.slot);
    }

    /** How many of the finale\u0027s mobs are still alive. */
    static int alive(ServerLevel level, State state) {
        int alive = 0;
        for (UUID id : state.mobs) {
            if (isAlive(level, id)) {
                alive++;
            }
        }
        return alive;
    }

    // ---- spawning ----------------------------------------------------------------------------------

    private static void spawnWave(ServerLevel level, InstanceRecord record, DungeonDef.Finale finale, State state,
                                  List<ServerPlayer> members) {
        RandomSource random = level.getRandom();
        BlockPos around = members.get(0).blockPosition();
        int party = Math.max(1, members.size());
        int[] bases = new int[finale.mobs().size()];
        for (int i = 0; i < bases.length; i++) {
            bases[i] = finale.mobs().get(i).count();
        }
        int[] counts = FinaleRules.counts(bases, party, finale.perMemberPercent());
        for (int i = 0; i < counts.length; i++) {
            for (int n = 0; n < counts[i]; n++) {
                Mob mob = spawn(level, finale.mobs().get(i).type(), around, random);
                if (mob != null) {
                    provoke(mob, members.get(0));
                    state.mobs.add(mob.getUUID());
                }
            }
        }
        if (finale.elite() != null) {
            spawnElite(level, state, finale.elite(), around, party, members);
        }
        PocketDungeonsMod.LOG.info("Finale of slot {}: {} mobs for a party of {}", record.slot, state.mobs.size(), party);
    }

    /**
     * The finale\u0027s elite: spawned near {@code around}, named, scaled to the party, glowing, leading the
     * boss bar every member sees. Counts toward the finale; returns {@code null} when it could not spawn.
     */
    static Mob spawnElite(ServerLevel level, State state, DungeonDef.Elite elite, BlockPos around, int party,
                          List<ServerPlayer> members) {
        RandomSource random = level.getRandom();
        Mob mob = spawn(level, elite.type(), around, random);
        if (mob == null) {
            return null;
        }
        dressElite(level, state, mob, elite, party, members);
        return mob;
    }

    /**
     * Makes {@code mob} the finale's elite: named, its health set from the elite's base scaled to the party,
     * holding its item, glowing, leading the boss bar every member sees, and counted toward the finale.
     */
    static void dressElite(ServerLevel level, State state, Mob mob, DungeonDef.Elite elite, int party,
                           List<ServerPlayer> members) {
        provoke(mob, members.get(0));
        int health = FinaleRules.eliteHealth(elite.health(), party,
                PocketDungeonsConfig.finaleEliteHealthPercentPerMember());
        mob.setCustomName(Component.literal(elite.name()).withStyle(ChatFormatting.GOLD));
        mob.setCustomNameVisible(true);
        if (mob.getAttribute(Attributes.MAX_HEALTH) != null) {
            mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        }
        mob.setHealth(mob.getMaxHealth());
        if (!elite.mainhand().isEmpty()) {
            Item held = BuiltInRegistries.ITEM.getOptional(Identifier.parse(elite.mainhand())).orElse(null);
            if (held != null) {
                mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(held));
                mob.setDropChance(EquipmentSlot.MAINHAND, 0f);
            }
        }
        mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100, 0, false, false));
        state.elite = mob.getUUID();
        mob.setPersistenceRequired();
        mob.addTag(TAG);
        if (!state.mobs.contains(mob.getUUID())) {
            state.mobs.add(mob.getUUID());
        }
        if (state.bar != null) {
            state.bar.removeAllPlayers();
        }
        state.bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(elite.name()),
                BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
        for (ServerPlayer member : members) {
            state.bar.addPlayer(member);
        }
    }

    static Mob spawn(ServerLevel level, String typeId, BlockPos around, RandomSource random) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(typeId)).orElse(null);
        if (type == null) {
            PocketDungeonsMod.LOG.warn("A finale names an unknown mob type: {}", typeId);
            return null;
        }
        BlockPos at = freeSpotNear(level, around, random);
        Entity entity = type.spawn(level, at == null ? around : at, EntitySpawnReason.TRIGGERED);
        if (!(entity instanceof Mob mob)) {
            return null;
        }
        mob.setPersistenceRequired();
        mob.addTag(TAG);
        return mob;
    }

    /**
     * A finale\u0027s wolves are the pack\u0027s last stand, not strays: a neutral mob (a wolf) is angered at the party so
     * it fights, and any mob is pointed at a member.
     */
    static void provoke(Mob mob, ServerPlayer target) {
        if (mob instanceof net.minecraft.world.entity.animal.wolf.Wolf wolf) {
            HostileWolves.mark(wolf);
        }
        if (mob instanceof net.minecraft.world.entity.NeutralMob neutral) {
            neutral.startPersistentAngerTimer();
        }
        mob.setTarget(target);
    }

    /** A standing spot three to eight blocks from {@code around}: air at feet and head, solid below. */
    static BlockPos freeSpotNear(ServerLevel level, BlockPos around, RandomSource random) {
        for (int attempt = 0; attempt < 60; attempt++) {
            int dx = random.nextInt(17) - 8;
            int dz = random.nextInt(17) - 8;
            if (Math.abs(dx) < 3 && Math.abs(dz) < 3) {
                continue;
            }
            for (int dy = 2; dy >= -2; dy--) {
                BlockPos at = around.offset(dx, dy, dz);
                if (level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir()
                        && level.getBlockState(at.below()).isSolid()) {
                    return at;
                }
            }
        }
        return null;
    }

    // ---- the pad gate ---------------------------------------------------------------------------------

    /** Why the pad stays shut while the last stand stands in the way, or {@code null}. */
    static String padRefusal(MinecraftServer server, InstanceRecord record) {
        if (finaleOf(record) == null) {
            return null;
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return null;
        }
        State state = state(record);
        boolean due = state.triggered || due(level, record);
        return FinaleRules.padRefusal(due, state.done, state.spawned ? alive(level, state) : 0);
    }

    /** The extra reward chests a won finale pays, read when the floor is counted. */
    static int rewardChests(InstanceRecord record) {
        return record.floor.finaleWon ? PocketDungeonsConfig.finaleRewardChests() : 0;
    }

    // ---- cleanup --------------------------------------------------------------------------------------

    /** The floor ended: whatever of the finale is left goes with it. */
    static void floorEnded(ServerLevel level, InstanceRecord record) {
        if (record != null) {
            discard(level, STATES.remove(record.slot));
        }
    }

    /** Instance teardown, with only a slot to hand. */
    static void teardown(ServerLevel level, int slot) {
        discard(level, STATES.remove(slot));
    }

    private static void discard(ServerLevel level, State state) {
        if (state == null) {
            return;
        }
        if (level != null && state.triggered && state.script != FinaleScript.NONE && state.finale != null) {
            FinaleContext ctx = new FinaleContext(level.getServer(), level, state.record, state.finale, state,
                    CapstoneFights.onlineMembers(level.getServer(), state.record));
            hook(state, "onFloorEnded", () -> state.script.onFloorEnded(ctx));
        }
        if (state.bar != null) {
            state.bar.removeAllPlayers();
        }
        if (level != null) {
            for (UUID id : state.mobs) {
                Entity entity = level.getEntity(id);
                if (entity != null) {
                    entity.discard();
                }
            }
        }
    }
}
