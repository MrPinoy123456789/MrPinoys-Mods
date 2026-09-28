package pocketdungeons;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.TeamColor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lemon, the player's guide ({@code docs/LEMON_SPEC.md}): one private
 * companion per player that appears when it has something to say and
 * vanishes when it does not. This class owns every player's Lemon: the body
 * ({@link LemonBody}) and speech bubble ({@link LemonBubble}), what is being
 * said, what Lemon is waiting on, the chat routing, and the agent's LLM mode.
 *
 * <p>Guide mode (tutorials, hints, the FAQ) is not built yet: a question with
 * no agent to answer it gets {@link #HONEST_LINE} and is journaled as
 * {@code answered_by: none}.
 *
 * <p>Everything a player says to Lemon is also logged at INFO as
 * {@code Lemon ask <name> text} (and {@code Lemon answer}, {@code Lemon says},
 * {@code Lemon asks}, ...), since routed chat is no longer broadcast and so
 * never reaches the vanilla chat log the server tool reads.
 */
final class Lemon {

    private Lemon() {}

    /** Carried by the body and the bubble, so every entity sweep can recognise them. */
    static final String TAG = "pocketdungeons_lemon";

    /** The team that colours Lemon's glowing outline yellow. */
    private static final String TEAM = "pd_lemon";

    static final String HONEST_LINE = "I do not know that one yet.";

    /** A player who spoke to Lemon this recently is in a conversation: replies count as answers. */
    private static final int CONVERSATION_TICKS = 20 * 30;
    /** Damage taken or dealt this recently means a fight. */
    private static final int FIGHT_TICKS = 20 * 5;
    /** An unprompted line held back by a fight is dropped after this long. */
    private static final int DEFERRED_TTL_TICKS = 20 * 120;
    /** Ticks Lemon spends drifting off before the poof. */
    private static final int LEAVING_TICKS = 10;

    /** What happened to a line an agent asked Lemon to say. */
    enum Delivery { SHOWN, DEFERRED_FIGHT, HELD_QUIET }

    private record Question(String text, long askedAt) {}

    private record Deferred(String text, boolean ask, long queuedAt) {}

    private static final class State {
        final UUID player;
        LemonBody body;
        LemonBubble bubble;
        final ArrayDeque<String> bubbles = new ArrayDeque<>();
        int bubbleTicksLeft;
        long idleUntil;
        int leaving;
        boolean waiting;
        String question;
        long waitUntil;
        final List<Question> pending = new ArrayList<>();
        final List<Deferred> deferred = new ArrayList<>();
        long llmUntil;
        boolean quiet;
        long lastAddressed = -CONVERSATION_TICKS;
        int side = 1;
        long nextSideCheck;

        State(UUID player) {
            this.player = player;
        }

        boolean idle() {
            return body == null && bubble == null && pending.isEmpty() && deferred.isEmpty()
                    && !waiting && llmUntil == 0 && !quiet;
        }
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final Map<UUID, Long> LAST_COMBAT = new HashMap<>();

    // ---- wiring -------------------------------------------------------------------

    static void register() {
        LemonCommands.register();
        ServerTickEvents.END_SERVER_TICK.register(Lemon::tick);

        ServerLifecycleEvents.SERVER_STARTED.register(Lemon::resetTeam);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (State state : new ArrayList<>(STATES.values())) {
                despawn(server, state, false);
            }
            STATES.clear();
            LAST_COMBAT.clear();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            server.execute(() -> forget(server, id));
        });
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) ->
                despawnBody(player.level().getServer(), player.getUUID()));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                despawnBody(newPlayer.level().getServer(), newPlayer.getUUID()));

        // A fight is damage taken or dealt; Lemon never interrupts one unasked.
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (entity instanceof ServerPlayer hurt) {
                LAST_COMBAT.put(hurt.getUUID(), (long) hurt.level().getServer().getTickCount());
            }
            if (source.getEntity() instanceof ServerPlayer attacker) {
                LAST_COMBAT.put(attacker.getUUID(), (long) attacker.level().getServer().getTickCount());
            }
        });

        // Chat to Lemon is taken out of the broadcast (verified in
        // fabric-message-api-v1 7.0.7: ALLOW_CHAT_MESSAGE returning false
        // cancels PlayerList.broadcastChatMessage, vanilla's log line included).
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String text = LemonSpeech.addressed(message.signedContent(), soloForChat(sender));
            if (text == null) {
                return true;
            }
            MinecraftServer server = sender.level().getServer();
            if (server.isSameThread()) {
                heard(sender, text);
            } else {
                server.execute(() -> heard(sender, text));
            }
            return false;
        });
    }

    /**
     * Solo for chat routing: nobody else in the player's party or instance,
     * and either standing in a dungeon instance or alone on the server, so a
     * solo player in the overworld can still talk to other people online.
     */
    static boolean soloForChat(ServerPlayer player) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        int party = record != null ? record.members.size()
                : 1 + PartyService.partyCompanions(player.getUUID()).size();
        if (party > 1) {
            return false;
        }
        return record != null || player.level().getServer().getPlayerList().getPlayerCount() <= 1;
    }

    /** Whether {@code entity} is a Lemon body or bubble. Every entity sweep skips these. */
    static boolean isPart(Entity entity) {
        return entity instanceof LemonBody || entity instanceof LemonBubble
                || entity.entityTags().contains(TAG);
    }

    /** Whether the manager still owns this body or bubble; one it lost removes itself. */
    static boolean owns(Entity entity) {
        UUID owner = entity instanceof LemonBody body ? body.owner
                : entity instanceof LemonBubble bubble ? bubble.owner : null;
        State state = owner == null ? null : STATES.get(owner);
        return state != null && (state.body == entity || state.bubble == entity);
    }

    /**
     * Runs {@code capture} with every Lemon part inside {@code box} lifted out
     * of it and put back after, so a structure capture never copies one into
     * a room blob. Nothing ticks in between, so no client sees the move.
     */
    static void withoutLemon(ServerLevel level, AABB box, Runnable capture) {
        List<Entity> parts = level.getEntitiesOfClass(Entity.class, box, Lemon::isPart);
        List<Vec3> homes = new ArrayList<>();
        for (Entity part : parts) {
            homes.add(part.position());
            part.setPos(part.getX(), box.maxY + 64, part.getZ());
        }
        try {
            capture.run();
        } finally {
            for (int i = 0; i < parts.size(); i++) {
                parts.get(i).setPos(homes.get(i));
            }
        }
    }

    // ---- what the player says -------------------------------------------------------

    /**
     * The player said {@code text} to Lemon (chat routed here, or {@code /lemon}).
     * The answer to a pending {@code ask} is journaled as {@code lemon_answer};
     * anything else is a question: held for the agent in LLM mode, otherwise
     * answered with {@link #HONEST_LINE}.
     */
    static void heard(ServerPlayer player, String text) {
        MinecraftServer server = player.level().getServer();
        long now = server.getTickCount();
        State state = stateFor(player.getUUID());
        state.lastAddressed = now;
        String name = player.getName().getString();
        if (state.waiting) {
            PocketDungeonsMod.LOG.info("Lemon answer <{}> {}", name, text);
            PlaytestJournal.lemonAnswer(player, state.question, text);
            state.waiting = false;
            state.question = null;
            echo(player, text);
            return;
        }
        PocketDungeonsMod.LOG.info("Lemon ask <{}> {}", name, text);
        echo(player, text);
        if (text.isEmpty()) {
            show(player, state, "Yes?", false);
            PlaytestJournal.lemonAsk(player, text, "guide", 0, 0);
            return;
        }
        if (llmActive(state, now)) {
            state.pending.add(new Question(text, now));
            // Lemon turns up and listens, lit, while the agent thinks.
            ensureBody(player, state);
            state.idleUntil = Math.max(state.idleUntil,
                    now + 20L * (PocketDungeonsConfig.lemonFallbackSeconds() + PocketDungeonsConfig.lemonIdleSeconds()));
            return;
        }
        show(player, state, HONEST_LINE, false);
        PlaytestJournal.lemonAsk(player, text, "none", 0, 0);
    }

    /** The player's own line, echoed back to them alone so their chat still reads as a conversation. */
    private static void echo(ServerPlayer player, String text) {
        if (!text.isEmpty()) {
            player.sendSystemMessage(Component.literal("You to Lemon: ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
        }
    }

    /** {@code /lemon quiet} and {@code /lemon on}: unprompted appearances off or on. */
    static void setQuiet(ServerPlayer player, boolean quiet) {
        State state = stateFor(player.getUUID());
        state.quiet = quiet;
        if (quiet) {
            state.deferred.clear();
        }
    }

    static boolean isQuiet(UUID player) {
        State state = STATES.get(player);
        return state != null && state.quiet;
    }

    // ---- what the agent says ----------------------------------------------------------

    /**
     * {@code dungeon lemon say}: appear, speak, idle out. A line that answers
     * the player (a question is pending, or they spoke to Lemon in the last
     * half minute) always shows; an unprompted one waits out a fight and is
     * dropped while the player has Lemon quiet.
     */
    static Delivery say(ServerPlayer player, String text) {
        return deliver(player, text, false);
    }

    /** {@code dungeon lemon ask}: like {@link #say}, then highlighted and waiting for the reply. */
    static Delivery ask(ServerPlayer player, String text) {
        return deliver(player, text, true);
    }

    private static Delivery deliver(ServerPlayer player, String text, boolean ask) {
        long now = player.level().getServer().getTickCount();
        State state = stateFor(player.getUUID());
        boolean prompted = !state.pending.isEmpty() || now - state.lastAddressed < CONVERSATION_TICKS;
        resolvePending(player, state, now, "llm");
        if (!prompted) {
            if (state.quiet) {
                PocketDungeonsMod.LOG.info("Lemon held <{}> (quiet) {}", player.getName().getString(), text);
                return Delivery.HELD_QUIET;
            }
            if (inFight(player, now)) {
                state.deferred.add(new Deferred(text, ask, now));
                PocketDungeonsMod.LOG.info("Lemon held <{}> (fight) {}", player.getName().getString(), text);
                return Delivery.DEFERRED_FIGHT;
            }
        }
        show(player, state, text, ask);
        return Delivery.SHOWN;
    }

    /** {@code dungeon lemon quiet}: vanish now, dropping anything still to say. */
    static void hush(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        if (state == null) {
            return;
        }
        state.bubbles.clear();
        state.deferred.clear();
        state.bubbleTicksLeft = 0;
        state.waiting = false;
        state.question = null;
        despawn(player.level().getServer(), state, true);
    }

    /**
     * {@code dungeon lemon mode}: {@code llm} holds player questions for the
     * agent for {@code lemonLlmLapseSeconds}, after which Lemon lapses back
     * to guide mode unless the agent has set it again.
     */
    static void setMode(ServerPlayer player, boolean llm) {
        long now = player.level().getServer().getTickCount();
        State state = stateFor(player.getUUID());
        state.llmUntil = llm ? now + 20L * PocketDungeonsConfig.lemonLlmLapseSeconds() : 0;
        PocketDungeonsMod.LOG.info("Lemon mode <{}> {}", player.getName().getString(), llm ? "llm" : "guide");
    }

    /** A small read-out for the context snapshot. */
    record View(boolean present, boolean waiting, String mode, boolean quiet, int pendingQuestions,
                String question) {}

    static View view(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        long now = player.level().getServer().getTickCount();
        if (state == null) {
            return new View(false, false, "guide", false, 0, "");
        }
        return new View(state.body != null, state.waiting, llmActive(state, now) ? "llm" : "guide",
                state.quiet, state.pending.size(), state.question == null ? "" : state.question);
    }

    // ---- the tick ----------------------------------------------------------------------

    private static void tick(MinecraftServer server) {
        if (STATES.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        for (State state : new ArrayList<>(STATES.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(state.player);
            if (player == null) {
                forget(server, state.player);
                continue;
            }
            try {
                tickOne(server, player, state, now);
            } catch (RuntimeException e) {
                PocketDungeonsMod.LOG.warn("Lemon tick failed for {}; Lemon vanishes ({})",
                        player.getName().getString(), e.toString());
                despawn(server, state, false);
            }
            if (state.idle()) {
                STATES.remove(state.player);
            }
        }
    }

    private static void tickOne(MinecraftServer server, ServerPlayer player, State state, long now) {
        if (state.llmUntil != 0 && now >= state.llmUntil) {
            state.llmUntil = 0;
            PocketDungeonsMod.LOG.info("Lemon mode <{}> guide (lapsed)", player.getName().getString());
        }
        // Guide mode answers whatever the agent left hanging, so the player
        // is never ignored.
        if (!state.pending.isEmpty() && (!llmActive(state, now)
                || now - state.pending.get(0).askedAt() >= 20L * PocketDungeonsConfig.lemonFallbackSeconds())) {
            PocketDungeonsMod.LOG.info("Lemon unanswered <{}> {}", player.getName().getString(),
                    state.pending.get(state.pending.size() - 1).text());
            resolvePending(player, state, now, "none");
            show(player, state, HONEST_LINE, false);
        }
        if (!state.deferred.isEmpty() && now % 10 == 0) {
            state.deferred.removeIf(d -> now - d.queuedAt() > DEFERRED_TTL_TICKS);
            if (!state.deferred.isEmpty() && !inFight(player, now)) {
                for (Deferred d : new ArrayList<>(state.deferred)) {
                    show(player, state, d.text(), d.ask());
                }
                state.deferred.clear();
            }
        }
        if (state.body == null) {
            return;
        }
        if (state.body.isRemoved() || state.body.level() != player.level()) {
            despawn(server, state, false);
            return;
        }
        if (state.leaving > 0) {
            Vec3 at = state.body.position().add(0, 0.12, 0);
            state.body.setPos(at);
            if (--state.leaving == 0) {
                despawn(server, state, true);
            }
            return;
        }
        if (state.bubbleTicksLeft > 0 && --state.bubbleTicksLeft == 0) {
            nextBubble(player, state);
        }
        boolean talking = state.bubbleTicksLeft > 0;
        if (talking) {
            state.idleUntil = Math.max(state.idleUntil, now + 20L * PocketDungeonsConfig.lemonIdleSeconds());
        }
        if (state.waiting && now >= state.waitUntil) {
            PocketDungeonsMod.LOG.info("Lemon unanswered <{}> (no reply) {}", player.getName().getString(),
                    state.question);
            state.waiting = false;
            state.question = null;
        }
        boolean lit = talking || state.waiting || !state.pending.isEmpty();
        state.body.setGlowingTag(lit);
        long deadline = state.waiting ? Math.max(state.idleUntil, state.waitUntil) : state.idleUntil;
        if (!lit && now >= deadline) {
            state.leaving = LEAVING_TICKS;
            return;
        }
        hover(player, state, now);
    }

    private static void resolvePending(ServerPlayer player, State state, long now, String answeredBy) {
        for (Question q : state.pending) {
            PlaytestJournal.lemonAsk(player, q.text(), answeredBy, 0, (now - q.askedAt()) / 20);
        }
        state.pending.clear();
    }

    private static boolean llmActive(State state, long now) {
        return state.llmUntil != 0 && now < state.llmUntil;
    }

    /** Damage in the last few seconds, or a hostile mob with this player as its target. */
    private static boolean inFight(ServerPlayer player, long now) {
        Long last = LAST_COMBAT.get(player.getUUID());
        if (last != null && now - last < FIGHT_TICKS) {
            return true;
        }
        return !player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16),
                mob -> mob instanceof Enemy && mob.getTarget() == player).isEmpty();
    }

    // ---- speech -------------------------------------------------------------------------

    private static void show(ServerPlayer player, State state, String text, boolean ask) {
        long now = player.level().getServer().getTickCount();
        state.leaving = 0;
        ensureBody(player, state);
        state.bubbles.addAll(LemonSpeech.bubbles(text));
        if (state.bubbleTicksLeft == 0) {
            nextBubble(player, state);
        }
        player.sendSystemMessage(Component.literal("Lemon: ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
        PocketDungeonsMod.LOG.info("Lemon {} <{}> {}", ask ? "asks" : "says", player.getName().getString(), text);
        if (ask) {
            state.waiting = true;
            state.question = text;
            state.waitUntil = now + 20L * PocketDungeonsConfig.lemonAskIdleSeconds();
        }
        state.idleUntil = Math.max(state.idleUntil, now + 20L * PocketDungeonsConfig.lemonIdleSeconds());
    }

    private static void nextBubble(ServerPlayer player, State state) {
        String next = state.body == null ? null : state.bubbles.poll();
        if (next == null) {
            state.bubbles.clear();
            state.bubbleTicksLeft = 0;
            if (state.bubble != null) {
                state.bubble.discard();
                state.bubble = null;
            }
            return;
        }
        if (state.bubble == null || state.bubble.isRemoved()) {
            ServerLevel level = player.level();
            LemonBubble bubble = new LemonBubble(level, player.getUUID());
            Vec3 at = state.body.position().add(0, 0.85, 0);
            bubble.snapTo(at.x, at.y, at.z, 0.0f, 0.0f);
            state.bubble = bubble;
            level.addFreshEntity(bubble);
        }
        state.bubble.show(Component.literal(next).withStyle(ChatFormatting.WHITE));
        state.bubbleTicksLeft = LemonSpeech.bubbleTicks(next);
    }

    // ---- the body ------------------------------------------------------------------------

    private static void ensureBody(ServerPlayer player, State state) {
        if (state.body != null && !state.body.isRemoved() && state.body.level() == player.level()) {
            return;
        }
        despawn(player.level().getServer(), state, false);
        ServerLevel level = player.level();
        long now = level.getServer().getTickCount();
        state.side = pickSide(player, state.side);
        Vec3 at = hoverTarget(player, state.side, now);
        LemonBody body = new LemonBody(level, player.getUUID());
        body.snapTo(at.x, at.y, at.z, facing(at, player), 0.0f);
        state.body = body;
        if (!level.addFreshEntity(body)) {
            state.body = null;
            return;
        }
        joinTeam(level.getServer(), body);
        level.sendParticles(player, ParticleTypes.POOF, true, false, at.x, at.y + 0.3, at.z, 8, 0.2, 0.2, 0.2, 0.01);
        chirp(player, SoundEvents.ALLAY_AMBIENT_WITHOUT_ITEM, 0.6f, 1.2f);
    }

    private static void hover(ServerPlayer player, State state, long now) {
        if (now >= state.nextSideCheck) {
            state.side = pickSide(player, state.side);
            state.nextSideCheck = now + 10;
        }
        Vec3 target = hoverTarget(player, state.side, now);
        Vec3 current = state.body.position();
        Vec3 next = current.distanceToSqr(target) > 64 ? target : current.add(target.subtract(current).scale(0.3));
        float yaw = facing(next, player);
        state.body.setPos(next);
        state.body.setYRot(yaw);
        state.body.setYHeadRot(yaw);
        state.body.setYBodyRot(yaw);
        if (state.bubble != null) {
            state.bubble.setPos(next.add(0, 0.85, 0));
        }
    }

    /**
     * Where Lemon hovers: beside the player's shoulder and a little ahead
     * ({@code side} 1 right, -1 left), at the edge of their view rather than
     * in it, with a gentle bob; {@code side} 0 is above their head, for a
     * cramped corridor.
     */
    private static Vec3 hoverTarget(ServerPlayer player, int side, long now) {
        double yaw = Math.toRadians(player.getYRot());
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        double bob = Math.sin(now * 0.15) * 0.06;
        if (side == 0) {
            return player.position().add(forward.scale(-0.2)).add(0, 2.35 + bob, 0);
        }
        return player.position().add(right.scale(0.9 * side)).add(forward.scale(0.8)).add(0, 1.3 + bob, 0);
    }

    /** The first spot of right, left, above that is open air in sight of the player's eyes. */
    private static int pickSide(ServerPlayer player, int current) {
        int[] order = current == -1 ? new int[] {-1, 1, 0} : new int[] {1, -1, 0};
        long now = player.level().getServer().getTickCount();
        for (int side : order) {
            if (clear(player, hoverTarget(player, side, now))) {
                return side;
            }
        }
        return current;
    }

    private static boolean clear(ServerPlayer player, Vec3 at) {
        AABB box = new AABB(at.x - 0.2, at.y, at.z - 0.2, at.x + 0.2, at.y + 0.6, at.z + 0.2);
        if (!player.level().noCollision(box)) {
            return false;
        }
        HitResult hit = player.level().clip(new ClipContext(player.getEyePosition(), at.add(0, 0.3, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static float facing(Vec3 from, ServerPlayer player) {
        double dx = player.getX() - from.x;
        double dz = player.getZ() - from.z;
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    private static void despawnBody(MinecraftServer server, UUID player) {
        State state = STATES.get(player);
        if (state != null) {
            state.bubbles.clear();
            state.bubbleTicksLeft = 0;
            despawn(server, state, false);
        }
    }

    /** Removes the body and bubble, with a poof and a soft chirp for the player when {@code poof}. */
    private static void despawn(MinecraftServer server, State state, boolean poof) {
        state.leaving = 0;
        if (state.bubble != null) {
            state.bubble.discard();
            state.bubble = null;
        }
        LemonBody body = state.body;
        state.body = null;
        if (body == null) {
            return;
        }
        if (poof && server != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(state.player);
            if (player != null && player.level() == body.level()) {
                player.level().sendParticles(player, ParticleTypes.POOF, true, false,
                        body.getX(), body.getY() + 0.3, body.getZ(), 6, 0.15, 0.15, 0.15, 0.01);
                chirp(player, SoundEvents.ALLAY_ITEM_TAKEN, 0.3f, 1.4f);
            }
        }
        leaveTeam(server, body);
        body.discard();
    }

    /** Logout: Lemon goes, and everything it was holding for this player with it. */
    static void forget(MinecraftServer server, UUID player) {
        State state = STATES.remove(player);
        LAST_COMBAT.remove(player);
        if (state != null) {
            despawn(server, state, false);
        }
    }

    private static State stateFor(UUID player) {
        return STATES.computeIfAbsent(player, State::new);
    }

    private static void chirp(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
                SoundSource.NEUTRAL, player.getX(), player.getY(), player.getZ(), volume, pitch,
                player.getRandom().nextLong()));
    }

    // ---- the glow team ---------------------------------------------------------------------

    /** Drops the team left over from the last run (entries for bodies long gone); it is recreated on use. */
    private static void resetTeam(MinecraftServer server) {
        ServerScoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
        if (team != null) {
            scoreboard.removePlayerTeam(team);
        }
    }

    private static void joinTeam(MinecraftServer server, LemonBody body) {
        ServerScoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
        if (team == null) {
            team = scoreboard.addPlayerTeam(TEAM);
            team.setColor(Optional.of(TeamColor.YELLOW));
            team.setCollisionRule(Team.CollisionRule.NEVER);
            team.setNameTagVisibility(Team.Visibility.NEVER);
        }
        scoreboard.addPlayerToTeam(body.getScoreboardName(), team);
    }

    private static void leaveTeam(MinecraftServer server, LemonBody body) {
        if (server == null) {
            return;
        }
        ServerScoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
        if (team != null && team.getPlayers().contains(body.getScoreboardName())) {
            scoreboard.removePlayerFromTeam(body.getScoreboardName(), team);
        }
    }
}
