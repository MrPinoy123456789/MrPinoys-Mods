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
 */
final class FinaleWave {

    /** Tag on every mob of a finale. */
    static final String TAG = PocketDungeonsMod.MOD_ID + ".finale";

    private static final class State {
        final long seed;
        boolean triggered;
        long spawnTick;
        boolean spawned;
        boolean done;
        final List<UUID> mobs = new ArrayList<>();
        UUID elite;
        ServerBossEvent bar;

        State(long seed) {
            this.seed = seed;
        }
    }

    private static final Map<Integer, State> STATES = new HashMap<>();

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
            state = new State(record.layout.seed());
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
        if (!state.triggered) {
            if (members.isEmpty() || !due(level, record)) {
                return;
            }
            state.triggered = true;
            state.spawnTick = server.getTickCount() + PocketDungeonsConfig.finaleCountdownSeconds() * 20L;
            DungeonDef.Node node = TripView.def(record).node(record.interval.nodeId);
            String name = node == null ? TripView.dungeonName(record) : node.name();
            String line = finale.elite() == null ? "Hold the room." : finale.elite().name() + " comes.";
            for (ServerPlayer member : members) {
                StaggeredTitle.show(server, member.getUUID(),
                        Component.literal(name + " stirs").withStyle(ChatFormatting.GOLD), List.of(line),
                        ChatFormatting.GRAY);
            }
            level.playSound(null, members.get(0).blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE,
                    1.0f, 0.6f);
            return;
        }
        if (!state.spawned) {
            if (server.getTickCount() < state.spawnTick || members.isEmpty()) {
                return;
            }
            spawnWave(level, record, finale, state, members);
            state.spawned = true;
            return;
        }
        int alive = alive(level, state);
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
        if (alive == 0) {
            state.done = true;
            record.floor.finaleWon = true;
            if (state.bar != null) {
                state.bar.removeAllPlayers();
            }
            CapstoneFights.tell(members, "The last stand is over. The pad is open.", ChatFormatting.GREEN);
        }
    }

    /** How many of the finale\u0027s mobs are still alive. */
    private static int alive(ServerLevel level, State state) {
        int alive = 0;
        for (UUID id : state.mobs) {
            Entity entity = level.getEntity(id);
            if (entity != null && entity.isAlive()) {
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
            DungeonDef.Elite elite = finale.elite();
            Mob mob = spawn(level, elite.type(), around, random);
            if (mob != null) {
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
                state.mobs.add(mob.getUUID());
                state.bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(elite.name()),
                        BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
                for (ServerPlayer member : members) {
                    state.bar.addPlayer(member);
                }
            }
        }
        PocketDungeonsMod.LOG.info("Finale of slot {}: {} mobs for a party of {}", record.slot, state.mobs.size(), party);
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
    private static void provoke(Mob mob, ServerPlayer target) {
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
