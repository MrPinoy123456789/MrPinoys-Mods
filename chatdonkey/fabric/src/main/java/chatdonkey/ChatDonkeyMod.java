package chatdonkey;

import chatdonkey.core.EndReason;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Entrypoint for Chat Donkey.
 *
 * <p>A RuneScape-style random event: a named, immortal, extremely annoying
 * donkey spawns near an active player, obstructs and lectures them for up to a
 * minute, leaves a small sarcastic gift, and vanishes.
 *
 * <p>The load-bearing rule (SPEC.md section 1, revised in v1.1): the donkey
 * never <em>attacks</em> -- no damage, no aggro -- but it is allowed to be
 * costly. It takes the player's attention at the worst possible moment, and if
 * that gets them killed, that is the feature rather than a tolerated side
 * effect.
 *
 * <p>M1 scope: timer, spawn, the Lecture behavior, config lines, gift, despawn,
 * cooldown. No bribe, no hit reactions, no server-wide cap.
 */
public final class ChatDonkeyMod implements ModInitializer {

    public static final String MOD_ID = "chatdonkey";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static DonkeyConfig config;
    private static Triggers triggers;
    private static Events events;
    private static Voice voice;
    private static OrphanSweep orphans;

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);

        config = new DonkeyConfig(configDir);
        triggers = new Triggers();
        voice = new Voice();
        events = new Events(config, triggers, voice);
        orphans = OrphanSweep.register(events);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> config.reload());

        // Never leave an immortal donkey standing at shutdown.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            events.endAll();
            voice.clear();
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            events.tickAll();
            // After the events, so a line said this tick starts blipping this tick.
            voice.tick();
            triggers.tick(server.getPlayerList().getPlayers(), config.settings(), events);
            // Last: donkeys that loaded this tick have had every chance to be
            // claimed by an event before being judged an orphan.
            orphans.tick(events);
        });

        // Hitting the event donkey is a dialogue trigger, never a solution
        // (SPEC.md section 2). The damage is cancelled outright, so the donkey
        // never actually takes any -- which also means it can never be a
        // mob-aggro shield or a source of drops.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            ActiveEvent event = events.eventForDonkey(entity.getUUID());
            if (event == null) {
                return true;
            }
            if (source.getEntity() instanceof ServerPlayer attacker
                    && attacker.getUUID().equals(event.playerId())) {
                event.registerHit();
            }
            return false;
        });

        // Belt and braces behind ALLOW_DAMAGE: if anything ever reaches the
        // donkey by a route that skips the damage hook, it still cannot die
        // mid-event. An orphaned donkey from a crashed session is not in
        // `events`, so it is an ordinary, killable, harmless vanilla donkey.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!events.isEventDonkey(entity.getUUID())) {
                return true;
            }
            entity.setHealth(entity.getMaxHealth());
            entity.invulnerableTime = 20;
            return false;
        });

        // Damage recency, for the donkeyCanKill=false gate (SPEC.md section 7).
        // Tracked always rather than only when the setting is off, so toggling
        // it via /donkey reload takes effect immediately.
        ServerLivingEntityEvents.AFTER_DAMAGE.register(
                (entity, source, dealt, taken, blocked) -> {
                    if (entity instanceof ServerPlayer player) {
                        triggers.state(player).markDamaged(System.currentTimeMillis());
                    }
                });

        // Death mid-event ends it immediately: no gift, no cooldown penalty. The
        // donkey looting-dancing on a corpse is a v2 idea (SPEC.md section 6).
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                events.endFor(player.getUUID(), EndReason.ABORTED);
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            events.endFor(player.getUUID(), EndReason.ABORTED);
            triggers.forget(player.getUUID());
        });

        orphans = OrphanSweep.register(events);
        Interactions.register(events);
        DonkeyCommands.register(config, triggers, events);

        LOG.info("Chat Donkey initialised (server-side only)");
    }
}
