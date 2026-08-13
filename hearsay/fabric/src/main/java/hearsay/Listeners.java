package hearsay;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Proximity scan from players outward.
 *
 * <p>Never scans the whole world; for each player, it asks the level for
 * villagers inside an AABB inflated by the configured hearing range.
 */
public final class Listeners {

    private final HearsayConfig config;
    private Map<UUID, List<Candidate>> byPlayer = new LinkedHashMap<>();

    public Listeners(HearsayConfig config) {
        this.config = config;
    }

    public Map<UUID, List<Candidate>> byPlayer() {
        return Collections.unmodifiableMap(byPlayer);
    }

    public void tick(MinecraftServer server) {
        Map<UUID, List<Candidate>> next = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            double range = config.settings().hearingRange();
            for (ServerPlayer player : level.players()) {
                AABB box = player.getBoundingBox().inflate(range);
                List<Villager> nearby = level.getEntitiesOfClass(Villager.class, box);
                List<Candidate> list = new ArrayList<>(nearby.size());
                for (Villager v : nearby) {
                    String professionId = professionId(v);
                    list.add(new Candidate(v, professionId));
                }
                next.put(player.getUUID(), list);
            }
        }
        this.byPlayer = next;
    }

    public static String professionId(Villager v) {
        VillagerData data = v.getVillagerData();
        Holder<VillagerProfession> profession = data.profession();
        Identifier id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession.value());
        return id != null ? id.toString() : "minecraft:none";
    }

    public record Candidate(Villager villager, String professionId) {}
}
