package wayfarers;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import wayfarers.core.EncounterDefinition;
import wayfarers.core.EncounterPool;
import wayfarers.core.Listing;
import wayfarers.core.TraderRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Active encounter registry: spawn, tick, and expire.
 *
 * <p>Holds no persistent state. A restart forgets every running encounter and
 * the orphan sweep cleans the leftover body.
 */
public final class Encounters {

    private final Map<UUID, Active> active = new HashMap<>();
    private final WayfarersConfig config;
    private final Bubbles bubbles;
    private final Random random = new Random();

    public Encounters(WayfarersConfig config, Bubbles bubbles) {
        this.config = config;
        this.bubbles = bubbles;
    }

    public boolean isActive(UUID playerId) {
        return active.containsKey(playerId);
    }

    public boolean isEventEntity(UUID entityId) {
        if (active.isEmpty()) {
            return false;
        }
        for (Active a : active.values()) {
            if (a.entity.getUUID().equals(entityId)) {
                return true;
            }
        }
        return false;
    }

    public int activeCount() {
        return active.size();
    }

    Collection<Active> actives() {
        return active.values();
    }

    Active activeFor(UUID playerId) {
        return active.get(playerId);
    }

    Active activeForEntity(UUID entityId) {
        for (Active a : active.values()) {
            if (a.entity.getUUID().equals(entityId)) {
                return a;
            }
        }
        return null;
    }

    public boolean start(ServerPlayer player) {
        if (active.containsKey(player.getUUID())) {
            return false;
        }
        TraderRecord record = TraderRegistry.forPlayer(player.getUUID());
        EncounterDefinition chosen = config.encounters().select(
                record, "", false, "", random);
        if (chosen == null) {
            return false;
        }
        return start(player, chosen);
    }

    public boolean start(ServerPlayer player, String encounterId) {
        if (active.containsKey(player.getUUID())) {
            return false;
        }
        EncounterDefinition chosen = null;
        for (EncounterDefinition d : config.encounters().definitions()) {
            if (d.id().equals(encounterId)) {
                chosen = d;
                break;
            }
        }
        if (chosen == null) {
            return false;
        }
        TraderRecord record = TraderRegistry.forPlayer(player.getUUID());
        if (chosen.once() && record.marvelsSeen().contains(chosen.id())) {
            return false;
        }
        return start(player, chosen);
    }

    private boolean start(ServerPlayer player, EncounterDefinition def) {
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return false;
        }

        if ("hostile".equals(def.template())) {
            if (level.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) {
                WayfarersMod.LOG.debug("Hostile encounter blocked in peaceful");
                return false;
            }
            int playMinutes = player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM, net.minecraft.stats.Stats.PLAY_TIME) / 1200;
            if (playMinutes < config.settings().hostileMinPlayMinutes()) {
                WayfarersMod.LOG.debug("Hostile encounter blocked for new player");
                return false;
            }
            long spawnRadius = config.settings().hostileWorldSpawnBlocks();
            if (player.blockPosition().distSqr(level.getLevelData().getRespawnData().pos()) < spawnRadius * spawnRadius) {
                WayfarersMod.LOG.debug("Hostile encounter blocked too close to world spawn");
                return false;
            }
        }

        Entity entity = Spawns.spawnNear(player, def.body(), random);
        if (entity == null) {
            WayfarersMod.LOG.debug("No room to spawn near {}", player.getName().getString());
            return false;
        }

        long now = System.currentTimeMillis();
        long expiresAt = now + (long) def.expireMinutes() * 60_000L;
        active.put(player.getUUID(), new Active(player, entity, def, now, expiresAt));

        if (!"hostile".equals(def.template())) {
            String pool = def.dialogue().isBlank() ? def.id() : def.dialogue();
            String idle = config.lines().pickFor(pool, "idle", random);
            bubbles.lure(level, entity, idle);
        }

        if (def.once()) {
            TraderRegistry.sawMarvel(player.getUUID(), def.id());
        } else if (def.recurring()) {
            TraderRegistry.recordMeeting(player.getUUID(), def.id(), now);
        }

        WayfarersMod.LOG.info("{} started a {} encounter", player.getName().getString(), def.id());
        return true;
    }

    public void tick() {
        if (active.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Active> finished = new ArrayList<>();

        for (Active a : active.values()) {
            if (a.player.isRemoved() || a.entity.isRemoved() || a.player.level() != a.entity.level()) {
                finished.add(a);
                continue;
            }
            if (now >= a.expiresAt) {
                finished.add(a);
                continue;
            }
            if (tooFar(a)) {
                if (a.farSince == 0) {
                    a.farSince = now;
                } else if (now - a.farSince > (long) config.settings().despawnFarMinutes() * 60_000L) {
                    finished.add(a);
                }
            } else {
                a.farSince = 0;
            }
        }

        for (Active a : finished) {
            end(a);
        }
    }

    private boolean tooFar(Active a) {
        double dx = a.player.getX() - a.entity.getX();
        double dy = a.player.getY() - a.entity.getY();
        double dz = a.player.getZ() - a.entity.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) > config.settings().despawnDistanceBlocks();
    }

    public void endFor(UUID playerId) {
        Active a = active.remove(playerId);
        if (a != null) {
            end(a);
        }
    }

    public void endAll() {
        for (Active a : new ArrayList<>(active.values())) {
            end(a);
        }
    }

    private void end(Active a) {
        active.remove(a.player.getUUID());
        bubbles.clear(a.entity.getUUID());
        boolean strayDonkey = a.def.body().type().toLowerCase().contains("donkey");
        if (!strayDonkey && !a.entity.isRemoved()) {
            a.entity.discard();
        }
        WayfarersMod.LOG.debug("Ended encounter {} for {}", a.def.id(), a.player.getName().getString());
    }

    static final class Active {
        final ServerPlayer player;
        final Entity entity;
        final EncounterDefinition def;
        final long startedAt;
        final long expiresAt;
        final List<Listing> sells;
        final List<Listing> buys;
        int coin;
        long farSince;
        int lastLineTick;
        /** Set once the patrol has been thawed, so every later hit is not a fresh war cry. */
        boolean released;

        Active(ServerPlayer player, Entity entity, EncounterDefinition def, long startedAt, long expiresAt) {
            this.player = player;
            this.entity = entity;
            this.def = def;
            this.startedAt = startedAt;
            this.expiresAt = expiresAt;
            this.sells = new ArrayList<>(def.sells());
            this.buys = new ArrayList<>(def.buys());
            this.coin = def.coin();
        }

        void updateSell(int index, Listing listing) {
            sells.set(index, listing);
        }

        void updateBuy(int index, Listing listing) {
            buys.set(index, listing);
        }

        void consumeCoin(int amount) {
            this.coin = Math.max(0, this.coin - amount);
        }
    }
}
