package pocketdungeons;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
 * never reaches the vanilla chat log the server tool reads. The kind word
 * after {@code Lemon} is what the server tool sorts on: {@link #EVENT_KINDS}
 * wake a waiting agent, {@link #ECHO_KINDS} only echo the agent's own action.
 * Keep both lists and {@code tools/server/pdserver.mjs} in step.
 */
final class Lemon {

    private Lemon() {}

    /** Carried by the body and the bubble, so every entity sweep can recognise them. */
    static final String TAG = "pocketdungeons_lemon";

    /** The team that colours Lemon's glowing outline yellow. */
    private static final String TEAM = "pd_lemon";

    static final String HONEST_LINE = "I do not know that one yet.";

    /** What {@code dungeon lemon think} says when the agent gives no text. */
    static final String THINK_LINE = "Let me check. Back in a moment.";

    /**
     * Log kinds that need the agent's attention: the player spoke, answered,
     * was left unanswered, a held line landed or was dropped, the player set
     * quiet, the agent's llm mode lapsed, or the player summoned Lemon with
     * their keystone.
     */
    static final List<String> EVENT_KINDS = List.of("ask", "answer", "unanswered", "delivered", "dropped",
            "quiet", "lapsed", "summoned");
    /** Log kinds that only echo what the agent (or the guide) just did; the command reply already said so. */
    static final List<String> ECHO_KINDS = List.of("says", "asks", "replies", "thinks", "held", "mode", "hushed");

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
        /** The player's own {@code /lemon quiet}: lasts until {@code /lemon on}. */
        boolean quiet;
        /** The agent's {@code dungeon lemon quiet}: lasts until the player next speaks to Lemon. */
        boolean agentQuiet;
        /** Set by {@code think}: the guide fallback waits until then instead of {@code lemonFallbackSeconds}. */
        long thinkingUntil;
        /** Set by {@code think}: Lemon stays away, not listening lit, until it next speaks or is spoken to. */
        boolean hidden;
        long lastAddressed = -CONVERSATION_TICKS;
        /** Side Lemon hovers on: -1 left, 1 right, 0 above. */
        int side;
        /** Where Lemon stays put (before its bob); null until placed. It only turns in place. */
        Vec3 anchor;
        /** Whether {@link #anchor} was chosen at fight distance. */
        boolean anchorCombat;

        State(UUID player) {
            this.player = player;
        }

        boolean idle() {
            return body == null && bubble == null && pending.isEmpty() && deferred.isEmpty()
                    && !waiting && !quiet && !agentQuiet;
        }
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final Map<UUID, Long> LAST_COMBAT = new HashMap<>();
    /**
     * The agent's llm mode per player, as a server tick deadline. Kept apart
     * from {@link State} so it survives a relog: a player who logs out and in
     * mid-session is still connected to the agent.
     */
    private static final Map<UUID, Long> LLM_UNTIL = new HashMap<>();

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
            LLM_UNTIL.clear();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            server.execute(() -> forget(server, id));
        });
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) ->
                despawnBody(player.level().getServer(), player.getUUID()));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                despawnBody(newPlayer.level().getServer(), newPlayer.getUUID()));

        // The keystone summons Lemon: a right-click with it in the main hand.
        // A recovery compass has no use of its own, so claiming the click
        // takes nothing away. Main hand only, or a keystone carried in the
        // off hand would summon Lemon on every right-click with a sword.
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND
                    || !(player instanceof ServerPlayer serverPlayer)
                    || !Keystone.isKeystone(player.getItemInHand(hand))) {
                return InteractionResult.PASS;
            }
            summon(serverPlayer);
            return InteractionResult.SUCCESS_SERVER;
        });

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
        // Speaking to Lemon lifts the agent's quiet; the player's own /lemon quiet stays until /lemon on.
        state.agentQuiet = false;
        String name = player.getName().getString();
        if (state.waiting) {
            PocketDungeonsMod.LOG.info("Lemon answer <{}> {}", name, text);
            PlaytestJournal.lemonAnswer(player, state.question, text);
            state.waiting = false;
            state.question = null;
            // Answered: Lemon heads off after a short linger instead of hovering.
            state.idleUntil = now + lingerTicks();
            state.hidden = false;
            echo(player, text);
            return;
        }
        PocketDungeonsMod.LOG.info("Lemon ask <{}> {}", name, text);
        echo(player, text);
        if (text.isEmpty()) {
            show(player, state, "Yes?", false, "says");
            PlaytestJournal.lemonAsk(player, text, "guide", 0, 0);
            return;
        }
        if (llmActive(player.getUUID(), now)) {
            state.pending.add(new Question(text, now));
            // Lemon turns up and listens, lit, while the agent thinks. The
            // pending question keeps it lit and present; nothing else holds
            // it, so once the reply is said it leaves (PD-77).
            state.hidden = false;
            ensureBody(player, state);
            reanchorIfStale(player, state);
            return;
        }
        show(player, state, HONEST_LINE, false, "says");
        PlaytestJournal.lemonAsk(player, text, "none", 0, 0);
    }

    /**
     * The player right-clicked their keystone: Lemon comes to them, or, if
     * already here, moves to a fresh spot in front of them. It stays for
     * {@code lemonIdleSeconds} and then leaves as usual. A summon counts as
     * speaking to Lemon: it lifts the agent's quiet, and lines said in the
     * next half minute are shown even under the player's own quiet. Logged as
     * {@code Lemon summoned} so a waiting agent wakes and can greet them.
     */
    static void summon(ServerPlayer player) {
        long now = player.level().getServer().getTickCount();
        State state = stateFor(player.getUUID());
        state.lastAddressed = now;
        state.agentQuiet = false;
        state.hidden = false;
        state.leaving = 0;
        boolean present = state.body != null && !state.body.isRemoved() && state.body.level() == player.level();
        ensureBody(player, state);
        if (state.body == null) {
            return;
        }
        if (present) {
            reanchor(player, state, now, inFight(player, now));
            chirp(player, SoundEvents.ALLAY_AMBIENT_WITH_ITEM, 0.6f, 1.2f);
        }
        state.idleUntil = Math.max(state.idleUntil, now + 20L * PocketDungeonsConfig.lemonIdleSeconds());
        PocketDungeonsMod.LOG.info("Lemon summoned <{}>", player.getName().getString());
    }

    /**
     * The player reached for the journal Lemon carries (a right-click on the
     * body). Lemon keeps it: the player gets the menu the room's lodestone
     * opens instead, and Lemon stays a while, since they are paying attention.
     */
    static void journalReached(ServerPlayer player) {
        long now = player.level().getServer().getTickCount();
        State state = STATES.get(player.getUUID());
        if (state != null) {
            state.lastAddressed = now;
            state.idleUntil = Math.max(state.idleUntil, now + 20L * PocketDungeonsConfig.lemonIdleSeconds());
        }
        RitualListener.openMenu(player);
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
        // An event for the agent: the player changed what Lemon may do unasked.
        PocketDungeonsMod.LOG.info("Lemon quiet <{}> {}", player.getName().getString(), quiet ? "on" : "off");
    }

    static boolean isQuiet(UUID player) {
        State state = STATES.get(player);
        return state != null && (state.quiet || state.agentQuiet);
    }

    // ---- what the agent says ----------------------------------------------------------

    /**
     * {@code dungeon lemon say}: an unprompted line. Appear, speak, leave a
     * few seconds after the last bubble. It waits out a fight (two minutes at
     * most) and is dropped while the player has Lemon quiet, unless the player
     * is in a conversation (a question is pending, or they spoke to Lemon in
     * the last half minute). Answers go through {@link #reply}.
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
        if (!prompted) {
            if (state.quiet || state.agentQuiet) {
                PocketDungeonsMod.LOG.info("Lemon held <{}> (quiet) {}", player.getName().getString(), text);
                return Delivery.HELD_QUIET;
            }
            if (inFight(player, now)) {
                state.deferred.add(new Deferred(text, ask, now));
                PocketDungeonsMod.LOG.info("Lemon held <{}> (fight) {}", player.getName().getString(), text);
                return Delivery.DEFERRED_FIGHT;
            }
        }
        resolvePending(player, state, now, "llm");
        show(player, state, text, ask, ask ? "asks" : "says");
        return Delivery.SHOWN;
    }

    /**
     * {@code dungeon lemon reply}: the agent's answer to the player. Always
     * shown now, however long the agent took, never held for a fight or quiet.
     * It answers every pending question (journaled as {@code answered_by: llm}
     * with the wait), and with none pending it is simply said. Lemon leaves a
     * few seconds after the last bubble unless it is waiting on an ask.
     */
    static Delivery reply(ServerPlayer player, String text) {
        long now = player.level().getServer().getTickCount();
        State state = stateFor(player.getUUID());
        resolvePending(player, state, now, "llm");
        show(player, state, text, false, "replies");
        return Delivery.SHOWN;
    }

    /**
     * {@code dungeon lemon think}: acknowledge a question while the agent
     * looks something up. Lemon says a short line ({@link #THINK_LINE} when
     * {@code text} is blank), then leaves once it is read and stays away until
     * the reply (or until the player speaks again). The pending question is
     * held for {@code lemonThinkSeconds} before the guide's fallback answers.
     */
    static Delivery think(ServerPlayer player, String text) {
        long now = player.level().getServer().getTickCount();
        State state = stateFor(player.getUUID());
        show(player, state, text == null || text.isBlank() ? THINK_LINE : text, false, "thinks");
        if (!state.pending.isEmpty()) {
            state.thinkingUntil = now + 20L * PocketDungeonsConfig.lemonThinkSeconds();
        }
        state.hidden = true;
        return Delivery.SHOWN;
    }

    /**
     * {@code dungeon lemon quiet}: honour the player saying "quiet". Lemon
     * vanishes and holds its unprompted lines until the player next speaks to
     * it; replies still come. The player's own {@code /lemon quiet} is the
     * sticky version.
     */
    static void quietUntilSpoken(ServerPlayer player) {
        State state = stateFor(player.getUUID());
        state.agentQuiet = true;
        PocketDungeonsMod.LOG.info("Lemon hushed <{}> until they speak to Lemon", player.getName().getString());
        hush(player);
    }

    /** {@code dungeon lemon dismiss}: vanish now, dropping anything still to say. */
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
        if (llm) {
            LLM_UNTIL.put(player.getUUID(), now + 20L * PocketDungeonsConfig.lemonLlmLapseSeconds());
        } else {
            LLM_UNTIL.remove(player.getUUID());
        }
        PocketDungeonsMod.LOG.info("Lemon mode <{}> {}", player.getName().getString(), llm ? "llm" : "guide");
    }

    /**
     * A small read-out for the context snapshot. {@code quiet} is the
     * player's own {@code /lemon quiet}; {@code quietUntilSpoken} the agent's;
     * {@code thinking} means a {@code think} is holding the pending questions.
     */
    record View(boolean present, boolean waiting, String mode, boolean quiet, boolean quietUntilSpoken,
                int pendingQuestions, boolean thinking, String question) {}

    static View view(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        long now = player.level().getServer().getTickCount();
        String mode = llmActive(player.getUUID(), now) ? "llm" : "guide";
        if (state == null) {
            return new View(false, false, mode, false, false, 0, false, "");
        }
        return new View(state.body != null, state.waiting, mode, state.quiet, state.agentQuiet,
                state.pending.size(), !state.pending.isEmpty() && now < state.thinkingUntil,
                state.question == null ? "" : state.question);
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
        Long until = LLM_UNTIL.get(state.player);
        if (until != null && now >= until) {
            LLM_UNTIL.remove(state.player);
            PocketDungeonsMod.LOG.info("Lemon mode <{}> guide (lapsed)", player.getName().getString());
        }
        // Guide mode answers whatever the agent left hanging, so the player
        // is never ignored.
        if (!state.pending.isEmpty() && fallbackDue(llmActive(state, now),
                state.pending.get(0).askedAt(), state.thinkingUntil, now)) {
            PocketDungeonsMod.LOG.info("Lemon unanswered <{}> {}", player.getName().getString(),
                    state.pending.get(state.pending.size() - 1).text());
            resolvePending(player, state, now, "none");
            state.hidden = false;
            show(player, state, HONEST_LINE, false, "says");
        }
        if (!state.deferred.isEmpty() && now % 10 == 0) {
            state.deferred.removeIf(d -> now - d.queuedAt() > DEFERRED_TTL_TICKS);
            if (!state.deferred.isEmpty() && !inFight(player, now)) {
                for (Deferred d : new ArrayList<>(state.deferred)) {
                    show(player, state, d.text(), d.ask(), d.ask() ? "asks" : "says");
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
        boolean lit = !state.hidden && (talking || state.waiting || !state.pending.isEmpty());
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
        state.thinkingUntil = 0;
    }

    /**
     * Whether the guide's fallback should answer the oldest pending question
     * now. Out of llm mode it answers at once. In llm mode it waits
     * {@code lemonFallbackSeconds} from the question, or until a {@code think}
     * runs out, whichever is later. PD-79: {@code think} used to set its hold
     * and nothing read it, so an agent that said "let me check" still lost the
     * question at 45 seconds.
     */
    static boolean fallbackDue(boolean llm, long askedAt, long thinkingUntil, long now) {
        if (!llm) {
            return true;
        }
        long fallbackAt = askedAt + 20L * PocketDungeonsConfig.lemonFallbackSeconds();
        return now >= Math.max(fallbackAt, thinkingUntil);
    }

    private static boolean llmActive(UUID player, long now) {
        Long until = LLM_UNTIL.get(player);
        return until != null && now < until;
    }

    private static boolean llmActive(State state, long now) {
        return llmActive(state.player, now);
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

    private static long lingerTicks() {
        return 20L * PocketDungeonsConfig.lemonLingerSeconds();
    }

    private static void show(ServerPlayer player, State state, String text, boolean ask, String kind) {
        long now = player.level().getServer().getTickCount();
        state.leaving = 0;
        ensureBody(player, state);
        reanchorIfStale(player, state);
        state.bubbles.addAll(LemonSpeech.bubbles(text));
        if (state.bubbleTicksLeft == 0) {
            nextBubble(player, state);
        }
        player.sendSystemMessage(Component.literal("Lemon: ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
        PocketDungeonsMod.LOG.info("Lemon {} <{}> {}", kind, player.getName().getString(), text);
        if (ask) {
            state.waiting = true;
            state.question = text;
            state.waitUntil = now + 20L * PocketDungeonsConfig.lemonAskIdleSeconds();
            state.idleUntil = Math.max(state.idleUntil, state.waitUntil);
        } else {
            state.idleUntil = now + lingerTicks();
        }
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
            Vec3 at = bubbleTarget(state.body.position());
            bubble.snapTo(at.x, at.y, at.z, 0.0f, 0.0f);
            state.bubble = bubble;
            level.addFreshEntity(bubble);
        }
        state.bubble.show(Component.literal(next).withStyle(ChatFormatting.WHITE));
        state.bubbleTicksLeft = LemonSpeech.bubbleTicks(next);
    }

    // ---- the body ------------------------------------------------------------------------

    private static void ensureBody(ServerPlayer player, State state) {
        if (state.hidden) {
            return;
        }
        if (state.body != null && !state.body.isRemoved() && state.body.level() == player.level()) {
            return;
        }
        despawn(player.level().getServer(), state, false);
        ServerLevel level = player.level();
        long now = level.getServer().getTickCount();
        state.side = pickSide(player, state.side, now);
        Vec3 at = hoverTarget(player, state.side, now, false);
        state.anchor = at.subtract(0, bob(now), 0);
        state.anchorCombat = false;
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

    /** Past this many blocks away an anchor counts as stale when Lemon next speaks. */
    private static final double ANCHOR_MAX_DIST = 9.0;
    /** An anchor this far off the player's facing counts as stale when Lemon next speaks. */
    private static final double ANCHOR_MAX_ANGLE = Math.toRadians(100);

    /**
     * Lemon stays at its anchor and only turns to face the player, so they can
     * walk past it or away from it and it stays where it is (player request,
     * 2026-09-29). It moves only for a new point of interest: when it next
     * speaks or is spoken to and the old spot is stale, or when a fight
     * starts or ends and it needs the other distance.
     */
    private static void hover(ServerPlayer player, State state, long now) {
        boolean combat = inFight(player, now);
        if (state.anchor == null || combat != state.anchorCombat) {
            reanchor(player, state, now, combat);
        }
        Vec3 target = state.anchor.add(0, bob(now), 0);
        Vec3 current = state.body.position();
        Vec3 next = current.distanceToSqr(target) > 64 ? target : current.add(target.subtract(current).scale(0.3));
        float yaw = facing(next, player);
        state.body.setPos(next);
        state.body.setYRot(yaw);
        state.body.setYHeadRot(yaw);
        state.body.setYBodyRot(yaw);
        if (state.bubble != null) {
            state.bubble.setPos(bubbleTarget(state.body.position()));
        }
    }

    private static void reanchor(ServerPlayer player, State state, long now, boolean combat) {
        state.side = pickSide(player, state.side, now);
        state.anchor = hoverTarget(player, state.side, now, combat).subtract(0, bob(now), 0);
        state.anchorCombat = combat;
    }

    /** A new spot in view, but only if the old anchor is far away, out of view or blocked. */
    private static void reanchorIfStale(ServerPlayer player, State state) {
        if (state.body == null || state.anchor == null || !stale(player, state.anchor)) {
            return;
        }
        long now = player.level().getServer().getTickCount();
        reanchor(player, state, now, inFight(player, now));
    }

    private static boolean stale(ServerPlayer player, Vec3 anchor) {
        Vec3 to = anchor.subtract(player.position());
        double flat = Math.sqrt(to.x * to.x + to.z * to.z);
        if (flat > ANCHOR_MAX_DIST) {
            return true;
        }
        double yaw = Math.toRadians(player.getYRot());
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        if (flat > 1.0 && (to.x * forward.x + to.z * forward.z) / flat < Math.cos(ANCHOR_MAX_ANGLE)) {
            return true;
        }
        return !clear(player, anchor);
    }

    private static double bob(long now) {
        return Math.sin(now * 0.12) * 0.05;
    }

    /**
     * Where Lemon hovers: off to one side so the bubble can sit near the centre
     * of the player's view. Calm combat keeps Lemon close and readable; during
     * a fight it drifts farther out of the way.
     */
    private static Vec3 hoverTarget(ServerPlayer player, int side, long now, boolean combat) {
        double yaw = Math.toRadians(player.getYRot());
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        double bob = bob(now);
        double ahead = combat ? 4.2 : 3.0;
        double off = combat ? 1.6 : 0.7;
        if (side == 0) {
            // Above the player's head, only when both sides are blocked.
            return player.position().add(forward.scale(ahead * 0.6)).add(0, 2.3 + bob, 0);
        }
        return player.position()
                .add(right.scale(off * side))
                .add(forward.scale(ahead))
                .add(0, 1.35 + bob, 0);
    }

    /**
     * The speech bubble sits just above Lemon's head and shares its anchor, so
     * the text stays where Lemon is when the player looks or walks away.
     */
    private static Vec3 bubbleTarget(Vec3 body) {
        return body.add(0, 0.85, 0);
    }

    /** The first spot of right, left, above that is open air in sight of the player's eyes. */
    private static int pickSide(ServerPlayer player, int current, long now) {
        int[] order = current == 0 ? new int[] {1, -1, 0} : new int[] {current, -current, 0};
        for (int side : order) {
            if (clear(player, hoverTarget(player, side, now, false))) {
                return side;
            }
        }
        return current;
    }

    private static boolean clear(ServerPlayer player, Vec3 at) {
        AABB box = new AABB(at.x - 0.25, at.y, at.z - 0.25, at.x + 0.25, at.y + 0.7, at.z + 0.25);
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
