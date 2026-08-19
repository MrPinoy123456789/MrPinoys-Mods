package smalltalk.social;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SPEC.md section 14: villagers can mention things they've seen other tracked
 * players do. This class selects one such "third-party" memory and wraps the
 * facts needed for dialogue substitution.
 */
public final class SharedKnowledge {

    /** Memories older than this are not "fresh" enough to gossip about. */
    private static final long RECENT_MEMORY_WINDOW_TICKS = 7L * 24_000L;

    private SharedKnowledge() {}

    public record Mention(
            UUID otherPlayer,
            String otherPlayerName,
            MemoryEntry memory,
            String contextId
    ) {}

    /**
     * Tries to find a memory of another tracked player that this villager is
     * familiar enough with to talk about. Returns empty if no suitable candidate
     * exists (e.g. only strangers are known, or the memories are stale).
     */
    public static Optional<Mention> pick(Villager villager, UUID currentPlayer,
                                         long currentTick, RandomSource random) {
        int minimumScore = FamiliarityTier.ACQUAINTANCE.floor();
        Map<UUID, FamiliarityEntry> all = FamiliarityAttachment.allOf(villager);
        if (all.isEmpty()) {
            return Optional.empty();
        }

        MinecraftServer server = villager.level().getServer();
        List<Mention> candidates = new ArrayList<>();
        for (Map.Entry<UUID, FamiliarityEntry> e : all.entrySet()) {
            UUID playerId = e.getKey();
            if (playerId.equals(currentPlayer)) {
                continue;
            }
            FamiliarityEntry entry = e.getValue();
            if (entry.score() < minimumScore) {
                continue;
            }

            String name = resolveName(server, playerId);
            if (name == null || name.isEmpty()) {
                continue;
            }

            for (MemoryEntry memory : entry.memories()) {
                if (currentTick - memory.tick() > RECENT_MEMORY_WINDOW_TICKS) {
                    continue;
                }
                String contextId = contextFor(memory);
                if (contextId != null) {
                    candidates.add(new Mention(playerId, name, memory, contextId));
                }
            }
        }

        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(random.nextInt(candidates.size())));
    }

    /**
     * Maps a memory tag to the dialogue context it can trigger. We deliberately
     * avoid surfacing gift specifics as gossip -- only the fact that another
     * player gave a gift, or that the other player has been away.
     */
    public static String contextFor(MemoryEntry memory) {
        String tag = memory.tag();
        if (tag.startsWith("gave_gift:")) {
            return "mentions_other_player_gift";
        }
        if (tag.startsWith("you_were_away:")) {
            return "mentions_other_player_absence";
        }
        return null;
    }

    private static String resolveName(MinecraftServer server, UUID playerId) {
        if (server == null) {
            return null;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            return online.getName().getString();
        }
        Optional<GameProfile> cached = server.services().profileResolver().fetchById(playerId);
        return cached.map(GameProfile::name).orElse(null);
    }
}
