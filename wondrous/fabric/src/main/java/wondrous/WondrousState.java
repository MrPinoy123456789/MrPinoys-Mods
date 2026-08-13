package wondrous;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;

/**
 * Per-world saved data for persistent wondrous blocks and links.
 */
public final class WondrousState extends SavedData {

    public record PosKey(ResourceKey<Level> dimension, BlockPos pos) {}
    public record Link(BlockPos dest, UUID owner) {}

    private static final int SWEEP_INTERVAL = 1200; // once per minute

    private final Map<PosKey, List<ItemStack>> craftingStations = new HashMap<>();
    private final Map<PosKey, Link> links = new HashMap<>();

    public WondrousState() {}

    public static final Codec<PosKey> POS_KEY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(PosKey::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(PosKey::pos)
    ).apply(instance, PosKey::new));

    public static final Codec<Link> LINK_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("dest").forGetter(Link::dest),
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("owner").forGetter(Link::owner)
    ).apply(instance, Link::new));

    // Both maps are keyed by a compound, so they are stored as lists of entries.
    // Codec.unboundedMap cannot be used here: NbtOps builds maps through
    // RecordBuilder.AbstractStringBuilder, which errors with "key is not a string"
    // on anything that does not encode to a bare string. That failure is silent
    // at write time and loses the whole file.
    private record StationEntry(PosKey key, List<ItemStack> grid) {}
    private record LinkEntry(PosKey key, Link link) {}

    private static final Codec<StationEntry> STATION_ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            POS_KEY_CODEC.fieldOf("at").forGetter(StationEntry::key),
            ItemStack.OPTIONAL_CODEC.listOf().fieldOf("grid").forGetter(StationEntry::grid)
    ).apply(instance, StationEntry::new));

    private static final Codec<LinkEntry> LINK_ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            POS_KEY_CODEC.fieldOf("at").forGetter(LinkEntry::key),
            LINK_CODEC.fieldOf("link").forGetter(LinkEntry::link)
    ).apply(instance, LinkEntry::new));

    public static final Codec<WondrousState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            STATION_ENTRY_CODEC.listOf().fieldOf("crafting_stations")
                    .forGetter(s -> s.craftingStations.entrySet().stream()
                            .map(e -> new StationEntry(e.getKey(), e.getValue())).toList()),
            LINK_ENTRY_CODEC.listOf().fieldOf("links")
                    .forGetter(s -> s.links.entrySet().stream()
                            .map(e -> new LinkEntry(e.getKey(), e.getValue())).toList())
    ).apply(instance, WondrousState::fromEntries));

    private static WondrousState fromEntries(List<StationEntry> stations, List<LinkEntry> links) {
        WondrousState state = new WondrousState();
        for (StationEntry e : stations) {
            state.craftingStations.put(e.key(), new ArrayList<>(e.grid()));
        }
        for (LinkEntry e : links) {
            state.links.put(e.key(), e.link());
        }
        return state;
    }

    public static final SavedDataType<WondrousState> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("wondrous", "wondrous_state"),
            WondrousState::new,
            CODEC,
            DataFixTypes.LEVEL);

    /**
     * Always the overworld's storage. {@code ServerLevel.getDataStorage()} is
     * per-dimension, but every key here already carries its dimension -- writing
     * per-dimension would hide nether and end entries from the sweep, the transfer
     * tick and {@code /wondrous links}, all of which read one store.
     */
    public static WondrousState forServer(MinecraftServer server) {
        SavedDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(TYPE);
    }

    public static WondrousState forLevel(ServerLevel level) {
        return forServer(level.getServer());
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % SWEEP_INTERVAL != 0) {
                return;
            }
            WondrousState state = forServer(server);
            state.sweep(server);
        });
    }

    /**
     * Whether a station is registered here. A registered station with nothing in it
     * is normal -- it is what a freshly placed one looks like -- so emptiness of the
     * grid must never stand in for "not a station".
     */
    public boolean hasStation(ServerLevel level, BlockPos pos) {
        return craftingStations.containsKey(new PosKey(level.dimension(), pos));
    }

    public List<ItemStack> getStation(ServerLevel level, BlockPos pos) {
        return craftingStations.getOrDefault(new PosKey(level.dimension(), pos), Collections.emptyList());
    }

    public void setStation(ServerLevel level, BlockPos pos, List<ItemStack> grid) {
        craftingStations.put(new PosKey(level.dimension(), pos), new ArrayList<>(grid));
        setDirty();
    }

    public void removeStation(ServerLevel level, BlockPos pos) {
        if (craftingStations.remove(new PosKey(level.dimension(), pos)) != null) {
            setDirty();
        }
    }

    public Link getLink(ServerLevel level, BlockPos pos) {
        return links.get(new PosKey(level.dimension(), pos));
    }

    public void setLink(ServerLevel level, BlockPos pos, BlockPos dest, UUID owner) {
        links.put(new PosKey(level.dimension(), pos), new Link(dest, owner));
        setDirty();
    }

    public void removeLink(ServerLevel level, BlockPos pos) {
        if (links.remove(new PosKey(level.dimension(), pos)) != null) {
            setDirty();
        }
    }

    /** Remove every link whose source or destination is the given position. */
    public void removeLinksAt(ServerLevel level, BlockPos pos) {
        boolean changed = false;
        Iterator<Map.Entry<PosKey, Link>> it = links.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PosKey, Link> e = it.next();
            if (!e.getKey().dimension().equals(level.dimension())) {
                continue;
            }
            if (e.getKey().pos().equals(pos) || e.getValue().dest().equals(pos)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
    }

    public long linkCount(UUID owner) {
        return links.values().stream().filter(l -> l.owner().equals(owner)).count();
    }

    public Map<PosKey, List<ItemStack>> allStations() {
        return Collections.unmodifiableMap(craftingStations);
    }

    public Map<PosKey, Link> allLinks() {
        return Collections.unmodifiableMap(links);
    }

    private void sweep(MinecraftServer server) {
        boolean changed = false;

        Iterator<Map.Entry<PosKey, List<ItemStack>>> sit = craftingStations.entrySet().iterator();
        while (sit.hasNext()) {
            Map.Entry<PosKey, List<ItemStack>> e = sit.next();
            ServerLevel level = server.getLevel(e.getKey().dimension());
            if (level == null) {
                sit.remove();
                changed = true;
                continue;
            }
            if (!level.hasChunkAt(e.getKey().pos())) {
                continue;
            }
            if (level.getBlockState(e.getKey().pos()).getBlock() != Blocks.CRAFTING_TABLE) {
                sit.remove();
                changed = true;
            }
        }

        Iterator<Map.Entry<PosKey, Link>> lit = links.entrySet().iterator();
        while (lit.hasNext()) {
            Map.Entry<PosKey, Link> e = lit.next();
            ServerLevel level = server.getLevel(e.getKey().dimension());
            if (level == null) {
                lit.remove();
                changed = true;
                continue;
            }
            if (!level.hasChunkAt(e.getKey().pos()) || !level.hasChunkAt(e.getValue().dest())) {
                continue;
            }
            if (!(level.getBlockEntity(e.getKey().pos()) instanceof Container)
                    || !(level.getBlockEntity(e.getValue().dest()) instanceof Container)) {
                lit.remove();
                changed = true;
            }
        }

        if (changed) {
            setDirty();
        }
    }
}
