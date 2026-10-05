package pocketdungeons;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dungeon structure W7b: the live half of the Act 5 capstone, Herobrine (design D9). The rules are in
 * {@link HerobrineRules}; this class reads the world and acts on them. The fight only exists on the
 * final floor of the {@code herobrine} dungeon, in the {@code fracture_hall} boss room.
 *
 * <h2>Steve</h2>
 * A zombie named "Steve" (the name tag stays hidden; the boss bar reads "Herobrine" until the
 * rescue), in full netherite, with scaled health and damage ({@link HerobrineRules#maxHealth},
 * {@link HerobrineRules#attackDamage}), no despawn and no reinforcements, exempt from the keystone
 * mob scaling ({@link CapstoneFights#UNSCALED_TAG}). Three phases by his health:
 * <ol>
 *   <li>melee;</li>
 *   <li>he calls adds, endermen and wither skeletons, in waves;</li>
 *   <li>he blinks around the room like an enderman, and keeps calling.</li>
 * </ol>
 * Each phase shows a title and a chat line.
 *
 * <h2>The rescue</h2>
 * Steve cannot be killed and no member can die to him. The rescue fires on the first of: a member
 * at a quarter of their health; a member's killing blow (cancelled); Steve at a tenth of his health;
 * Steve's killing blow (cancelled). It is a scene run on the server tick ({@link #SCENE}): Steve
 * freezes, the adds go, an {@code Alex} appears (a vanilla mannequin holding a recovery compass,
 * invulnerable, the {@link StoreNPC} approach of a tagged vanilla entity), they speak, Steve is
 * gone in a burst of portal particles, Alex gives the party a word and vanishes, and the pad opens.
 * Stepping on the pad then completes the floor, which finishes the dungeon and, through
 * {@link DungeonProgress#onCapstoneCleared}, sets {@code campaignComplete} for every member present.
 * The pad is shut until the scene is over ({@link #padRefusal}).
 */
final class HerobrineFight {

    static final String STEVE_TAG = PocketDungeonsMod.MOD_ID + ".steve";
    static final String ADD_TAG = PocketDungeonsMod.MOD_ID + ".steve_add";
    static final String ALEX_TAG = PocketDungeonsMod.MOD_ID + ".alex";

    /**
     * The fixed uuid of Alex's mannequin profile. No account stands behind it (the profile is resolved,
     * so the server never looks anything up) and its hash picks the vanilla default skin named Alex
     * (index 0 of the client's 18 defaults, the slim Alex).
     */
    private static final UUID ALEX_PROFILE = UUID.fromString("a1e70000-0000-4000-8000-000000000002");

    /** Where Steve stands when the fight begins, in cell coordinates. */
    private static final double SPAWN_X = 12.5;
    private static final double SPAWN_Z = 8.5;

    /** The phase of the tick handler that runs before the mod's own death handler. */
    private static final Identifier EARLY = Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "herobrine_rescue");

    /** One beat of the rescue scene: the tick (since the rescue fired) it plays on. */
    private enum SceneBeat {
        HUSH(0), ALEX_APPEARS(30), ALEX_CALLS(80), STEVE_ANSWERS(150), ALEX_REPLIES(220), STEVE_GOES(290),
        ALEX_SENDS(340), ALEX_GOES(410);

        final int at;

        SceneBeat(int at) {
            this.at = at;
        }
    }

    /** Ticks the whole scene lasts. */
    static final int SCENE = SceneBeat.ALEX_GOES.at + 20;

    private static final class State {
        final long seed;
        final BlockPos terminal;
        boolean started;
        boolean startFailed;
        boolean rescueFired;
        boolean rescueDone;
        UUID steve;
        UUID alex;
        long rescueStart;
        int beat = -1;
        HerobrineRules.Phase phase = HerobrineRules.Phase.MELEE;
        long nextBlink;
        long nextSummon;
        double maxHealth = 1.0;
        ServerBossEvent bar;

        State(long seed, BlockPos terminal) {
            this.seed = seed;
            this.terminal = terminal;
        }
    }

    private static final Map<Integer, State> STATES = new HashMap<>();

    private HerobrineFight() {}

    /** Wires the fight's tick and its cancels. Called once from {@link CapstoneFights#register()}. */
    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (STATES.isEmpty() || server.getTickCount() % 2 != 0) {
                return;
            }
            ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (level == null) {
                return;
            }
            for (Map.Entry<Integer, State> entry : new ArrayList<>(STATES.entrySet())) {
                InstanceRecord record = InstanceRegistry.bySlot.get(entry.getKey());
                if (record == null || record.layout == null
                        || record.layout.seed() != entry.getValue().seed) {
                    discardAll(level, entry.getValue());
                    STATES.remove(entry.getKey());
                    continue;
                }
                onTick(server, level, record, entry.getValue());
            }
        });

        // No one dies to Steve before the rescue has played out, and Steve cannot be killed. Ahead
        // of the mod's own handler, which would eject the player instead.
        ServerLivingEntityEvents.ALLOW_DEATH.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
        ServerLivingEntityEvents.ALLOW_DEATH.register(EARLY, (entity, source, amount) -> {
            if (STATES.isEmpty() || !(entity.level() instanceof ServerLevel level)
                    || !level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                return true;
            }
            InstanceRecord record = Instances.dungeonRecordAt(entity.blockPosition());
            State state = record == null ? null : STATES.get(record.slot);
            if (state == null || !state.started) {
                return true;
            }
            MinecraftServer server = level.getServer();
            if (entity.entityTags().contains(STEVE_TAG) && entity instanceof Zombie steve) {
                if (!state.rescueFired) {
                    steve.setHealth(Math.max(1.0f, (float) (state.maxHealth * 0.05)));
                    fireRescue(server, level, record, state, "steve would have died");
                }
                return false;
            }
            if (entity instanceof ServerPlayer player && CellGeometry.cellBounds(state.terminal).contains(player.position())
                    && !state.rescueDone) {
                player.setHealth(Math.max(2.0f, player.getMaxHealth() * 0.15f));
                if (!state.rescueFired) {
                    fireRescue(server, level, record, state, "a member would have died");
                }
                return false;
            }
            return true;
        });

        // While the scene plays, nothing in the room hurts a member.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (STATES.isEmpty() || !(entity instanceof ServerPlayer player)
                    || !player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                return true;
            }
            InstanceRecord record = Instances.dungeonRecordAt(player.blockPosition());
            State state = record == null ? null : STATES.get(record.slot);
            return state == null || !state.rescueFired || state.rescueDone;
        });
    }

    private static State state(InstanceRecord record) {
        State state = STATES.get(record.slot);
        if (state == null || state.seed != record.layout.seed() || !state.terminal.equals(record.layout.terminal())) {
            state = new State(record.layout.seed(), record.layout.terminal());
            STATES.put(record.slot, state);
        }
        return state;
    }

    // ---- the watch tick: start ------------------------------------------------------------------

    /** One watch tick: starts the fight when a member stands in the room. */
    static void tick(MinecraftServer server, ServerLevel level, InstanceRecord record) {
        State state = state(record);
        List<ServerPlayer> members = CapstoneFights.onlineMembers(server, record);
        AABB cell = CellGeometry.cellBounds(state.terminal);
        int inRoom = 0;
        for (ServerPlayer player : members) {
            if (cell.contains(player.position()) && !player.isSpectator()) {
                inRoom++;
            }
        }
        if (HerobrineRules.shouldStart(true, state.started, inRoom)) {
            start(server, level, record, state, members);
        }
    }

    private static void start(MinecraftServer server, ServerLevel level, InstanceRecord record, State state,
                              List<ServerPlayer> members) {
        state.started = true;
        int party = Math.max(1, members.size());
        Zombie steve = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.TRIGGERED);
        if (steve == null) {
            state.startFailed = true;
            PocketDungeonsMod.LOG.warn("Steve failed to spawn at {}", state.terminal.toShortString());
            return;
        }
        BlockPos o = state.terminal;
        steve.snapTo(o.getX() + SPAWN_X, o.getY() + 1.0, o.getZ() + SPAWN_Z, 90.0f, 0.0f);
        steve.addTag(STEVE_TAG);
        steve.addTag(CapstoneFights.UNSCALED_TAG);
        steve.setPersistenceRequired();
        steve.setBaby(false);
        steve.setCanPickUpLoot(false);
        steve.setCustomName(Component.literal("Steve"));
        steve.setCustomNameVisible(false);

        state.maxHealth = HerobrineRules.maxHealth(party);
        setBase(steve, Attributes.MAX_HEALTH, state.maxHealth);
        setBase(steve, Attributes.ATTACK_DAMAGE, HerobrineRules.attackDamage(party));
        setBase(steve, Attributes.MOVEMENT_SPEED, 0.27);
        setBase(steve, Attributes.FOLLOW_RANGE, 48.0);
        setBase(steve, Attributes.KNOCKBACK_RESISTANCE, 0.5);
        setBase(steve, Attributes.SPAWN_REINFORCEMENTS_CHANCE, 0.0);
        steve.setHealth(steve.getMaxHealth());

        steve.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
        steve.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
        steve.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
        steve.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
        steve.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.MAINHAND}) {
            steve.setDropChance(slot, 0.0f);
        }
        level.addFreshEntity(steve);
        // The mod's drop floors run on load; Steve's gear is never loot.
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.MAINHAND}) {
            steve.setDropChance(slot, 0.0f);
        }
        state.steve = steve.getUUID();
        ServerPlayer first = members.isEmpty() ? null : members.get(0);
        if (first != null) {
            steve.setTarget(first);
        }

        state.bar = new ServerBossEvent(UUID.randomUUID(),
                Component.literal("Herobrine").withStyle(ChatFormatting.DARK_PURPLE),
                BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_10);
        state.bar.setProgress(1.0f);
        state.nextBlink = server.getTickCount() + HerobrineRules.BLINK_PERIOD_TICKS;
        state.nextSummon = server.getTickCount() + 100;

        level.playSound(null, BlockPos.containing(steve.position()), SoundEvents.ENDERMAN_STARE,
                SoundSource.HOSTILE, 1.0f, 0.5f);
        titleAll(server, members, Component.literal("Herobrine").withStyle(ChatFormatting.DARK_PURPLE),
                List.of("It wears a man's walk."));
        CapstoneFights.tell(members, "The Fracture. The last room he could hold together.", ChatFormatting.DARK_PURPLE);
        say(members, "Steve", "That compass is not yours, and it is not even hers. It points at where I fell.");
        PocketDungeonsMod.LOG.info("Herobrine fight started in slot {} with {} max health for {} member(s)",
                record.slot, state.maxHealth, party);
    }

    private static void setBase(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                double value) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    // ---- the fight tick (every other tick) -----------------------------------------------------

    private static void onTick(MinecraftServer server, ServerLevel level, InstanceRecord record, State state) {
        if (!state.started) {
            return;
        }
        List<ServerPlayer> members = CapstoneFights.onlineMembers(server, record);
        AABB cell = CellGeometry.cellBounds(state.terminal);
        List<ServerPlayer> inRoom = new ArrayList<>();
        for (ServerPlayer player : members) {
            if (cell.contains(player.position()) && !player.isSpectator()) {
                inRoom.add(player);
            }
        }
        long now = server.getTickCount();

        if (state.rescueFired) {
            if (!state.rescueDone) {
                playScene(server, level, state, members, inRoom, now);
            }
            return;
        }

        Entity found = state.steve == null ? null : level.getEntity(state.steve);
        if (!(found instanceof Zombie steve) || !steve.isAlive()) {
            // Steve is gone without a rescue (removed from outside): do not strand the floor.
            if (!inRoom.isEmpty()) {
                fireRescue(server, level, record, state, "steve is missing");
            }
            return;
        }

        if (state.bar != null) {
            state.bar.setProgress(Math.max(0.0f, Math.min(1.0f, steve.getHealth() / steve.getMaxHealth())));
            for (ServerPlayer player : members) {
                if (cell.contains(player.position())) {
                    state.bar.addPlayer(player);
                } else {
                    state.bar.removePlayer(player);
                }
            }
        }

        boolean anyLow = false;
        for (ServerPlayer player : inRoom) {
            anyLow |= HerobrineRules.playerLow(player.getHealth(), player.getMaxHealth());
        }
        boolean bossLow = HerobrineRules.bossLow(steve.getHealth(), steve.getMaxHealth());
        if (HerobrineRules.shouldRescue(false, anyLow, bossLow)) {
            fireRescue(server, level, record, state, anyLow ? "a member is low" : "steve is low");
            return;
        }

        HerobrineRules.Phase phase = HerobrineRules.phase(steve.getHealth(), steve.getMaxHealth());
        if (phase != state.phase) {
            state.phase = phase;
            announcePhase(server, level, members, steve, phase);
            state.nextSummon = now + 20;
            state.nextBlink = now + HerobrineRules.BLINK_PERIOD_TICKS;
        }
        if (inRoom.isEmpty()) {
            return;
        }
        if (phase != HerobrineRules.Phase.MELEE && now >= state.nextSummon) {
            state.nextSummon = now + HerobrineRules.SUMMON_PERIOD_TICKS;
            summonAdds(level, state, steve, inRoom, phase);
        }
        if (phase == HerobrineRules.Phase.BLINK && now >= state.nextBlink) {
            state.nextBlink = now + HerobrineRules.BLINK_PERIOD_TICKS;
            blink(level, state, steve, inRoom);
        }
    }

    private static void announcePhase(MinecraftServer server, ServerLevel level, List<ServerPlayer> members,
                                      Zombie steve, HerobrineRules.Phase phase) {
        level.playSound(null, BlockPos.containing(steve.position()), SoundEvents.ENDER_DRAGON_GROWL,
                SoundSource.HOSTILE, 0.8f, 0.7f);
        if (phase == HerobrineRules.Phase.SUMMONS) {
            titleAll(server, members, Component.literal("Phase II").withStyle(ChatFormatting.DARK_PURPLE),
                    List.of("The things he remembers answer him."));
            say(members, "Steve", "The mess was always optional. Let me show you how little of it I need.");
        } else if (phase == HerobrineRules.Phase.BLINK) {
            titleAll(server, members, Component.literal("Phase III").withStyle(ChatFormatting.DARK_PURPLE),
                    List.of("He is no longer standing still."));
            say(members, "Steve", "I am in every wall I ever remembered. You cannot get behind me.");
        }
    }

    // ---- adds and blinking -----------------------------------------------------------------

    private static int addsAlive(ServerLevel level, State state) {
        int alive = 0;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, CellGeometry.cellBounds(state.terminal))) {
            if (mob.isAlive() && mob.entityTags().contains(ADD_TAG)) {
                alive++;
            }
        }
        return alive;
    }

    private static void summonAdds(ServerLevel level, State state, Zombie steve, List<ServerPlayer> inRoom,
                                   HerobrineRules.Phase phase) {
        int count = HerobrineRules.addsToSpawn(phase, inRoom.size(), addsAlive(level, state));
        RandomSource random = level.getRandom();
        for (int i = 0; i < count; i++) {
            EntityType<? extends Mob> type = i % 2 == 0 ? EntityTypes.ENDERMAN : EntityTypes.WITHER_SKELETON;
            BlockPos at = CapstoneFights.freeSpot(level, state.terminal, random);
            if (at == null) {
                continue;
            }
            Mob mob = type.spawn(level, at, EntitySpawnReason.TRIGGERED);
            if (mob == null) {
                continue;
            }
            mob.setPersistenceRequired();
            mob.addTag(ADD_TAG);
            mob.setTarget(inRoom.get(random.nextInt(inRoom.size())));
            level.sendParticles(ParticleTypes.PORTAL, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5,
                    20, 0.3, 0.6, 0.3, 0.4);
        }
        if (count > 0) {
            level.playSound(null, BlockPos.containing(steve.position()), SoundEvents.ENDERMAN_TELEPORT,
                    SoundSource.HOSTILE, 1.0f, 0.6f);
        }
    }

    private static void blink(ServerLevel level, State state, Zombie steve, List<ServerPlayer> inRoom) {
        RandomSource random = level.getRandom();
        ServerPlayer target = inRoom.get(random.nextInt(inRoom.size()));
        BlockPos spot = null;
        for (int attempt = 0; attempt < 30 && spot == null; attempt++) {
            BlockPos at = CapstoneFights.freeSpot(level, state.terminal, random);
            if (at != null && Vec3.atCenterOf(at).distanceTo(target.position()) <= 7.0
                    && Vec3.atCenterOf(at).distanceTo(target.position()) >= 2.5) {
                spot = at;
            }
        }
        if (spot == null) {
            return;
        }
        level.sendParticles(ParticleTypes.PORTAL, steve.getX(), steve.getY() + 1.0, steve.getZ(),
                30, 0.3, 0.8, 0.3, 0.5);
        level.playSound(null, BlockPos.containing(steve.position()), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.HOSTILE, 1.0f, 0.8f);
        steve.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        steve.setTarget(target);
        level.sendParticles(ParticleTypes.PORTAL, steve.getX(), steve.getY() + 1.0, steve.getZ(),
                30, 0.3, 0.8, 0.3, 0.5);
        level.playSound(null, spot, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0f, 1.0f);
    }

    // ---- the rescue --------------------------------------------------------------------------

    private static void fireRescue(MinecraftServer server, ServerLevel level, InstanceRecord record, State state,
                                   String why) {
        if (state.rescueFired) {
            return;
        }
        state.rescueFired = true;
        state.rescueStart = server.getTickCount();
        Entity found = state.steve == null ? null : level.getEntity(state.steve);
        if (found instanceof Zombie steve) {
            steve.setInvulnerable(true);
            steve.setNoAi(true);
            steve.setTarget(null);
        }
        for (Mob mob : level.getEntitiesOfClass(Mob.class, CellGeometry.cellBounds(state.terminal))) {
            if (mob.entityTags().contains(ADD_TAG)) {
                mob.discard();
            }
        }
        PocketDungeonsMod.LOG.info("Herobrine rescue fired in slot {}: {}", record.slot, why);
    }

    private static void playScene(MinecraftServer server, ServerLevel level, State state, List<ServerPlayer> members,
                                  List<ServerPlayer> inRoom, long now) {
        long elapsed = now - state.rescueStart;
        SceneBeat[] beats = SceneBeat.values();
        // Play every beat that is due and has not played, in order.
        while (state.beat + 1 < beats.length && elapsed >= beats[state.beat + 1].at) {
            state.beat++;
            playBeat(server, level, state, members, inRoom, beats[state.beat]);
        }
        if (elapsed >= SCENE && !state.rescueDone) {
            state.rescueDone = true;
            CapstoneFights.tell(members, "The pad is open.", ChatFormatting.GREEN);
        }
    }

    private static void playBeat(MinecraftServer server, ServerLevel level, State state, List<ServerPlayer> members,
                                 List<ServerPlayer> inRoom, SceneBeat beat) {
        BlockPos o = state.terminal;
        Entity steveEntity = state.steve == null ? null : level.getEntity(state.steve);
        switch (beat) {
            case HUSH -> {
                CapstoneFights.tell(members, "The room goes quiet. Even the air stops moving.", ChatFormatting.DARK_GRAY);
                level.playSound(null, o.offset(8, 2, 8), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.0f, 0.5f);
            }
            case ALEX_APPEARS -> {
                spawnAlex(level, state);
                titleAll(server, members, Component.literal("Alex").withStyle(ChatFormatting.AQUA),
                        List.of("She holds the compass."));
            }
            case ALEX_CALLS -> say(members, "Alex", "Steve. Stop. It is only me.");
            case STEVE_ANSWERS -> {
                if (state.bar != null) {
                    state.bar.setName(Component.literal("Steve").withStyle(ChatFormatting.GRAY));
                }
                say(members, "Steve", "You kept the compass. I gave it to you so that I could not turn back.");
            }
            case ALEX_REPLIES -> say(members, "Alex",
                    "It stopped on the twelfth day. I have been walking toward you ever since.");
            case STEVE_GOES -> {
                say(members, "Steve", "Not yet. Not like this. I am not finished, Alex.");
                if (steveEntity != null) {
                    vanish(level, steveEntity);
                }
                if (state.bar != null) {
                    state.bar.removeAllPlayers();
                }
            }
            case ALEX_SENDS -> {
                say(members, "Alex", "Go. The pad is open. He will not follow you out, and I am not done looking for him.");
                for (ServerPlayer player : inRoom) {
                    player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 1));
                }
            }
            case ALEX_GOES -> {
                Entity alex = state.alex == null ? null : level.getEntity(state.alex);
                if (alex != null) {
                    vanish(level, alex);
                }
                CapstoneFights.tell(members, "She is gone. The compass in your pack still points down. The search"
                        + " continues.", ChatFormatting.GRAY);
            }
        }
    }

    /** A puff of portal and end rod light, a teleport sound, and the entity is gone. */
    private static void vanish(ServerLevel level, Entity entity) {
        level.sendParticles(ParticleTypes.PORTAL, entity.getX(), entity.getY() + 1.0, entity.getZ(),
                60, 0.4, 0.9, 0.4, 0.6);
        level.sendParticles(ParticleTypes.END_ROD, entity.getX(), entity.getY() + 1.0, entity.getZ(),
                25, 0.3, 0.8, 0.3, 0.05);
        level.playSound(null, entity.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 1.0f, 0.9f);
        entity.discard();
    }

    private static void spawnAlex(ServerLevel level, State state) {
        Mannequin alex = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.TRIGGERED);
        if (alex == null) {
            PocketDungeonsMod.LOG.warn("Alex failed to spawn at {}", state.terminal.toShortString());
            return;
        }
        BlockPos o = state.terminal;
        // Beside the pad, on the near side, facing Steve at the far end.
        BlockPos at = alexSpot(level, o);
        alex.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, -90.0f, 0.0f);
        alex.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(new GameProfile(ALEX_PROFILE, "Alex")));
        alex.setCustomName(Component.literal("Alex").withStyle(ChatFormatting.AQUA));
        alex.setCustomNameVisible(true);
        alex.setInvulnerable(true);
        alex.setSilent(true);
        alex.addTag(ALEX_TAG);
        alex.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.RECOVERY_COMPASS));
        level.addFreshEntity(alex);
        state.alex = alex.getUUID();
        level.sendParticles(ParticleTypes.END_ROD, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5,
                40, 0.4, 0.9, 0.4, 0.05);
        level.playSound(null, at, SoundEvents.BEACON_ACTIVATE, SoundSource.NEUTRAL, 1.0f, 1.2f);
    }

    /** A clear standing spot near (5, 1, 8) of the boss cell: the nearest of a few tries either side. */
    private static BlockPos alexSpot(ServerLevel level, BlockPos o) {
        for (int dz = 0; dz <= 3; dz++) {
            for (int sign : new int[]{1, -1}) {
                BlockPos candidate = o.offset(5, 1, 8 + sign * dz);
                if (level.getBlockState(candidate).isAir() && level.getBlockState(candidate.above()).isAir()
                        && level.getBlockState(candidate.below()).isSolid()) {
                    return candidate;
                }
            }
        }
        return o.offset(5, 1, 8);
    }

    // ---- chat helpers -----------------------------------------------------------------------

    private static void say(List<ServerPlayer> members, String speaker, String line) {
        ChatFormatting colour = "Alex".equals(speaker) ? ChatFormatting.AQUA : ChatFormatting.LIGHT_PURPLE;
        for (ServerPlayer player : members) {
            player.sendSystemMessage(Component.literal(speaker + ": ").withStyle(colour, ChatFormatting.BOLD)
                    .append(Component.literal(line).withStyle(colour)));
        }
    }

    private static void titleAll(MinecraftServer server, List<ServerPlayer> members, Component title, List<String> lines) {
        for (ServerPlayer player : members) {
            StaggeredTitle.show(server, player.getUUID(), title, lines, ChatFormatting.GRAY);
        }
    }

    // ---- the pad gate -------------------------------------------------------------------------

    /** Why the terminal pad may not complete the floor yet, or {@code null} when it may. */
    static String padRefusal(MinecraftServer server, InstanceRecord record) {
        if (record.layout == null) {
            return null;
        }
        State state = state(record);
        if (state.startFailed) {
            return null;
        }
        return HerobrineRules.padRefusal(state.rescueDone);
    }

    // ---- ending a floor -----------------------------------------------------------------------

    private static void discardAll(ServerLevel level, State state) {
        if (state.bar != null) {
            state.bar.removeAllPlayers();
        }
        AABB around = CellGeometry.cellBounds(state.terminal).inflate(2.0);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, around)) {
            if (mob.entityTags().contains(STEVE_TAG) || mob.entityTags().contains(ADD_TAG)) {
                mob.discard();
            }
        }
        for (Mannequin alex : level.getEntitiesOfClass(Mannequin.class, around)) {
            if (alex.entityTags().contains(ALEX_TAG)) {
                alex.discard();
            }
        }
    }

    /** The floor ended: Steve, his adds and Alex go with it. */
    static void floorEnded(ServerLevel level, InstanceRecord record) {
        if (record == null) {
            return;
        }
        State state = STATES.remove(record.slot);
        if (state != null) {
            discardAll(level, state);
        }
    }

    /** Instance teardown: the same with only a slot and layout to hand. */
    static void teardown(ServerLevel level, int slot, InstanceLayout layout) {
        State state = STATES.remove(slot);
        if (state != null && level != null) {
            discardAll(level, state);
        }
    }
}
