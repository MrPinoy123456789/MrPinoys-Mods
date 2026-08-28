package thingy.api;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and writes the {@code minecraft:custom_data} identity marker for every
 * namespace Thingy hosts.
 *
 * <p>The framework's name is never the identity namespace of content it hosts
 * (PLAN.md, "Namespace rule"). Content stamps with its own namespace
 * ({@code wondrous:}, {@code kamutotems:}, ...); {@code thingy:} is reserved
 * for framework metadata only. A namespace registers its own reader strategy
 * here, because different mods that predate Thingy shaped their tag
 * differently, and Thingy adapts to them rather than the reverse.
 *
 * <p>Phase 1 registers exactly one namespace, {@code wondrous}, with the
 * bare-string reader that reproduces {@code WondrousTag}'s exact shape: key
 * {@code "wondrous"}, value a bare string id. A live stack is byte-identical
 * before and after the swap (PLAN.md, "Shape preservation constraint"). Other
 * reader shapes ("value is a compound with an id field", "value is a compound,
 * presence alone means this single item") are deferred to Phase 5, against the
 * genuinely awkward shapes kamutotems and spiritwolves actually use.
 *
 * <p>{@link #read} runs on every right-click and every block break in the
 * game. It must be cheap and it must never throw.
 */
public final class VirtualTag {

    /** How one namespace's custom_data value is written and read back. */
    public interface Reader {

        /** Stamps {@code id} onto the stack under this namespace's key. */
        void stamp(ItemStack stack, String key, String id);

        /** The id stored under this namespace's key, if present. */
        Optional<String> read(CustomData data, String key);
    }

    /** {@code {<namespace>: "<id>"}}: the shape WondrousTag uses. */
    public static final Reader BARE_STRING = new Reader() {
        @Override
        public void stamp(ItemStack stack, String key, String id) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(key, id));
        }

        @Override
        public Optional<String> read(CustomData data, String key) {
            return data.copyTag().getString(key);
        }
    };

    private static final Map<String, Reader> READERS = new HashMap<>();

    private VirtualTag() {}

    /**
     * Registers the reader strategy for a namespace. Call once, at that
     * namespace's registration time. A second registration for the same
     * namespace fails fast and loud, naming both callers (PLAN.md, "Namespace
     * rule": duplicate registration is never silent last-write-wins).
     */
    public static synchronized void register(String namespace, Reader reader) {
        Reader existing = READERS.putIfAbsent(namespace, reader);
        if (existing != null) {
            throw new IllegalStateException(
                    "Namespace '" + namespace + "' is already registered with a VirtualTag reader");
        }
    }

    /** Stamps {@code id} onto the stack under {@code namespace}, using that namespace's reader. */
    public static ItemStack stamp(ItemStack stack, String namespace, String id) {
        Reader reader = READERS.get(namespace);
        if (reader == null) {
            throw new IllegalStateException("No VirtualTag reader registered for namespace '" + namespace + "'");
        }
        reader.stamp(stack, namespace, id);
        return stack;
    }

    /** The id stored under {@code namespace}, or empty if this stack does not carry one. */
    public static Optional<String> read(ItemStack stack, String namespace) {
        Reader reader = READERS.get(namespace);
        if (stack == null || stack.isEmpty() || reader == null) {
            return Optional.empty();
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return Optional.empty();
        }
        return reader.read(data, namespace);
    }

    /** True if this stack is exactly {@code id} within {@code namespace}. */
    public static boolean is(ItemStack stack, String namespace, String id) {
        return read(stack, namespace).filter(id::equals).isPresent();
    }

    /**
     * The namespaced id ({@code "<namespace>:<id>"}) of this stack, checked
     * against every registered namespace, or empty if none match.
     */
    public static Optional<String> readAny(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return Optional.empty();
        }
        for (Map.Entry<String, Reader> e : READERS.entrySet()) {
            Optional<String> id = e.getValue().read(data, e.getKey());
            if (id.isPresent()) {
                return Optional.of(e.getKey() + ":" + id.get());
            }
        }
        return Optional.empty();
    }
}
