package pocketdungeons;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-owner room bans (Manage Party, 2026-10-09): who may not join this owner's party, visit their room or
 * be invited by them. Kept apart from {@link RoomWhitelist} on the same pattern, because a ban outlives a
 * whitelist entry: banning someone also removes them from it, and unbanning does not give it back.
 */
final class RoomBans extends SavedData {

    private final Map<UUID, Set<UUID>> bans = new HashMap<>();

    RoomBans() {}

    private record OwnerEntry(UUID owner, List<UUID> banned) {}

    private static final Codec<OwnerEntry> OWNER_ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("owner").forGetter(OwnerEntry::owner),
            Codec.STRING.xmap(UUID::fromString, UUID::toString).listOf().fieldOf("banned")
                    .forGetter(OwnerEntry::banned)
    ).apply(instance, OwnerEntry::new));

    static final Codec<RoomBans> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            OWNER_ENTRY_CODEC.listOf().optionalFieldOf("owners", List.of())
                    .forGetter(b -> b.bans.entrySet().stream()
                            .map(e -> new OwnerEntry(e.getKey(), List.copyOf(e.getValue()))).toList())
    ).apply(instance, RoomBans::fromEntries));

    private static RoomBans fromEntries(List<OwnerEntry> owners) {
        RoomBans state = new RoomBans();
        for (OwnerEntry entry : owners) {
            state.bans.put(entry.owner(), new LinkedHashSet<>(entry.banned()));
        }
        return state;
    }

    static final SavedDataType<RoomBans> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "room_bans"),
            RoomBans::new,
            CODEC,
            DataFixTypes.LEVEL);

    /** Always the overworld's storage, matching {@link RoomWhitelist#forServer}. */
    static RoomBans forServer(MinecraftServer server) {
        SavedDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(TYPE);
    }

    boolean isBanned(UUID owner, UUID target) {
        return bans.getOrDefault(owner, Set.of()).contains(target);
    }

    Set<UUID> get(UUID owner) {
        return Set.copyOf(bans.getOrDefault(owner, Set.of()));
    }

    /** @return {@code false} if {@code target} was already banned */
    boolean ban(UUID owner, UUID target) {
        boolean added = bans.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(target);
        if (added) {
            setDirty();
        }
        return added;
    }

    /** @return {@code false} if {@code target} was not banned */
    boolean unban(UUID owner, UUID target) {
        Set<UUID> set = bans.get(owner);
        boolean removed = set != null && set.remove(target);
        if (removed) {
            setDirty();
        }
        return removed;
    }
}
