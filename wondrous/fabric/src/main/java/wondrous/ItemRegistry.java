package wondrous;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import wondrous.api.WondrousItem;
import wondrous.api.WondrousItems;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Turns {@link Definitions#ALL} into the public API surface. */
public final class ItemRegistry implements WondrousItems {

    private final Map<String, WondrousItem> byId = new LinkedHashMap<>();

    public ItemRegistry() {
        for (Definitions.Def def : Definitions.ALL) {
            byId.put(def.id(), new Impl(def));
        }
    }

    @Override
    public Optional<WondrousItem> byId(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    @Override
    public Collection<WondrousItem> all() {
        return List.copyOf(byId.values());
    }

    /** Ids in declaration order, for command suggestions. */
    public Collection<String> ids() {
        return List.copyOf(byId.keySet());
    }

    /** The definition behind an id, for the internal dispatch paths. */
    public Optional<Definitions.Def> defOf(String id) {
        WondrousItem item = id == null ? null : byId.get(id);
        return item instanceof Impl impl ? Optional.of(impl.def) : Optional.empty();
    }

    private record Impl(Definitions.Def def) implements WondrousItem {

        @Override
        public String id() {
            return def.id();
        }

        @Override
        public Component displayName() {
            return def.name();
        }

        @Override
        public ItemStack createStack() {
            return createStack(1);
        }

        @Override
        public ItemStack createStack(int count) {
            return Definitions.createStack(def, count);
        }
    }
}
