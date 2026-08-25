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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-owner room whitelists (M2 T2.2): who besides the owner may break blocks,
 * place blocks, and open containers inside their room.
 *
 * <p>Kept separate from {@link RoomStore}, which holds the room's own block
 * data. That is a new, deliberately-guarded data-loss risk (T2.1); a lost
 * whitelist is not -- a player just re-adds whoever they trusted -- so it
 * carries none of {@code RoomStore}'s backup machinery and lives in ordinary
 * {@code SavedData}, following {@link DungeonLog}'s pattern.
 */
final class RoomWhitelist extends SavedData {

    private final Map<UUID, Set<UUID>> whitelists = new HashMap<>();

    RoomWhitelist() {}

    // Same UUID-keyed-map-as-a-list trap DungeonLog documents: Codec.unboundedMap
    // fails silently at write time on anything that is not a bare string key.
    private record OwnerEntry(UUID owner, List<UUID> whitelist) {}

    private static final Codec<OwnerEntry> OWNER_ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("owner")
                    .forGetter(OwnerEntry::owner),
            Codec.STRING.xmap(UUID::fromString, UUID::toString).listOf().fieldOf("whitelist")
                    .forGetter(OwnerEntry::whitelist)
    ).apply(instance, OwnerEntry::new));

    static final Codec<RoomWhitelist> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            OWNER_ENTRY_CODEC.listOf().optionalFieldOf("owners", List.of())
                    .forGetter(w -> w.whitelists.entrySet().stream()
                            .map(e -> new OwnerEntry(e.getKey(), List.copyOf(e.getValue()))).toList())
    ).apply(instance, RoomWhitelist::fromEntries));

    private static RoomWhitelist fromEntries(List<OwnerEntry> owners) {
        RoomWhitelist state = new RoomWhitelist();
        for (OwnerEntry entry : owners) {
            state.whitelists.put(entry.owner(), new HashSet<>(entry.whitelist()));
        }
        return state;
    }

    static final SavedDataType<RoomWhitelist> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "room_whitelist"),
            RoomWhitelist::new,
            CODEC,
            DataFixTypes.LEVEL);

    /** Always the overworld's storage, matching {@link DungeonLog#forServer}. */
    static RoomWhitelist forServer(MinecraftServer server) {
        SavedDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(TYPE);
    }

    /** Whether {@code actor} may act as this room's owner would -- the owner themselves, or whitelisted. */
    boolean isPermitted(UUID owner, UUID actor) {
        return owner.equals(actor) || whitelists.getOrDefault(owner, Set.of()).contains(actor);
    }

    Set<UUID> get(UUID owner) {
        return Set.copyOf(whitelists.getOrDefault(owner, Set.of()));
    }

    /** @return {@code false} if {@code target} was already whitelisted */
    boolean add(UUID owner, UUID target) {
        boolean added = whitelists.computeIfAbsent(owner, k -> new HashSet<>()).add(target);
        if (added) {
            setDirty();
        }
        return added;
    }

    /** @return {@code false} if {@code target} was not whitelisted */
    boolean remove(UUID owner, UUID target) {
        Set<UUID> set = whitelists.get(owner);
        boolean removed = set != null && set.remove(target);
        if (removed) {
            setDirty();
        }
        return removed;
    }
}
