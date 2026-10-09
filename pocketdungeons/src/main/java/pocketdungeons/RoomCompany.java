package pocketdungeons;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-owner party history (Manage Party, 2026-10-09): everyone who has joined this owner's party, newest
 * first and capped, so the party screen can list people who are not in it right now. A drive-by invite that
 * was never accepted never lands here.
 */
final class RoomCompany extends SavedData {

    static final int MAX = 30;

    private final Map<UUID, List<UUID>> company = new HashMap<>();

    RoomCompany() {}

    private record OwnerEntry(UUID owner, List<UUID> members) {}

    private static final Codec<OwnerEntry> OWNER_ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("owner").forGetter(OwnerEntry::owner),
            Codec.STRING.xmap(UUID::fromString, UUID::toString).listOf().fieldOf("members")
                    .forGetter(OwnerEntry::members)
    ).apply(instance, OwnerEntry::new));

    static final Codec<RoomCompany> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            OWNER_ENTRY_CODEC.listOf().optionalFieldOf("owners", List.of())
                    .forGetter(c -> c.company.entrySet().stream()
                            .map(e -> new OwnerEntry(e.getKey(), List.copyOf(e.getValue()))).toList())
    ).apply(instance, RoomCompany::fromEntries));

    private static RoomCompany fromEntries(List<OwnerEntry> owners) {
        RoomCompany state = new RoomCompany();
        for (OwnerEntry entry : owners) {
            state.company.put(entry.owner(), new ArrayList<>(entry.members()));
        }
        return state;
    }

    static final SavedDataType<RoomCompany> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "room_company"),
            RoomCompany::new,
            CODEC,
            DataFixTypes.LEVEL);

    static RoomCompany forServer(MinecraftServer server) {
        SavedDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(TYPE);
    }

    /** Notes that {@code member} joined {@code owner}'s party: moved to the front, the oldest dropped past the cap. */
    void note(UUID owner, UUID member) {
        if (owner.equals(member)) {
            return;
        }
        List<UUID> list = company.computeIfAbsent(owner, k -> new ArrayList<>());
        list.remove(member);
        list.add(0, member);
        while (list.size() > MAX) {
            list.remove(list.size() - 1);
        }
        setDirty();
    }

    /** Newest first. */
    List<UUID> get(UUID owner) {
        return List.copyOf(company.getOrDefault(owner, List.of()));
    }

    /** @return whether {@code member} was in the history */
    boolean forget(UUID owner, UUID member) {
        List<UUID> list = company.get(owner);
        boolean removed = list != null && list.remove(member);
        if (removed) {
            setDirty();
        }
        return removed;
    }
}
