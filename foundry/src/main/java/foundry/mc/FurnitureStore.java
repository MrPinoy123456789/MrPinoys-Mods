package foundry.mc;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The single source of truth for placed furniture in one level: block position, definition id, facing. Display
 * entities are virtual and rebuilt from these records; nothing about them is saved. See docs/FURNITURE_DECISION.md.
 */
public final class FurnitureStore extends SavedData {
    public record Entry(BlockPos pos, String definition, Direction facing) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
                Codec.STRING.fieldOf("definition").forGetter(Entry::definition),
                Direction.CODEC.fieldOf("facing").forGetter(Entry::facing)).apply(i, Entry::new));
    }

    private static final Codec<FurnitureStore> CODEC = Entry.CODEC.listOf().fieldOf("furniture").xmap(
            list -> {
                FurnitureStore store = new FurnitureStore();
                list.forEach(e -> store.entries.put(e.pos().asLong(), e));
                return store;
            },
            store -> new ArrayList<>(store.entries.values())).codec();

    public static final SavedDataType<FurnitureStore> TYPE = new SavedDataType<>(
            Identifier.parse("foundry:furniture"), FurnitureStore::new, CODEC, DataFixTypes.LEVEL);

    private final Map<Long, Entry> entries = new LinkedHashMap<>();

    public static FurnitureStore of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public Entry get(BlockPos pos) {
        return entries.get(pos.asLong());
    }

    void put(Entry entry) {
        entries.put(entry.pos().asLong(), entry);
        setDirty();
    }

    public void remove(BlockPos pos) {
        if (entries.remove(pos.asLong()) != null) setDirty();
    }

    /** Records inside one chunk; a copy, so callers may remove while iterating. */
    List<Entry> inChunk(int chunkX, int chunkZ) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if ((e.pos().getX() >> 4) == chunkX && (e.pos().getZ() >> 4) == chunkZ) out.add(e);
        }
        return out;
    }

    public int size() {
        return entries.size();
    }
}
