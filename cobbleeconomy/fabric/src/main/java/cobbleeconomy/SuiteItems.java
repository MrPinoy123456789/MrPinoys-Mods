package cobbleeconomy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The suite item index: every {@code data/<namespace>/suite_items/*.json} published by
 * every loaded mod, merged by the vanilla resource manager.
 *
 * <p>Specified by {@code SUITE_ITEMS.md}. The short version: a suite item is a vanilla
 * item plus a component patch, published read-only inside the defining mod's own jar.
 * This mod reads whatever is present and never names a publisher, so nothing here is a
 * dependency in either direction -- an install with no suite items at all sees an empty
 * index and behaves exactly as this mod did before the feature existed.
 *
 * <p><b>The first seven methods deliberately mirror {@link WondrousShop}</b>, which
 * solves the same problem for one specific mod through a compile-time api dependency.
 * The mirroring is the point: every call site that branches on
 * {@link WondrousShop#isWondrousItemId} gains an identical branch on
 * {@link #isSuiteItemId} right beside it, and when the wondrous integration is
 * eventually retired (SUITE_ITEMS.md §10.1) the deletion is mechanical rather than a
 * rewrite.
 *
 * <p>A suite listing is stored in {@link cobbleeconomy.core.ShopEntry#itemId} as
 * {@code "suite:<namespace>:<path>"}, exactly as a wondrous listing is stored as
 * {@code "wondrous:<id>"}. {@link ShopConfig} translates to and from the
 * {@code "suite_item"} key at the file boundary, so {@code shop.json} never sees the
 * prefix and the {@code core} module never learns this feature exists.
 */
public final class SuiteItems {

    /** Marks a {@code ShopEntry.itemId} as naming a suite item rather than a registry item. */
    public static final String PREFIX = "suite:";

    private static volatile Map<String, SuiteItemDefinition> byId = Map.of();
    private static volatile Map<String, List<SuiteItemDefinition>> byNamespace = Map.of();
    private static volatile Map<String, SuiteSource> sources = Map.of();
    private static volatile Map<String, List<String>> tagToIds = Map.of();
    private static volatile Map<String, Set<String>> namespaceTags = Map.of();

    private SuiteItems() {}

    // ---- id handling --------------------------------------------------------
    //
    // Fully implemented, not stubbed: both halves of the build depend on these two
    // agreeing exactly, and they are too small to be worth anyone's disagreement.

    /** True if {@code itemId} names a suite item rather than a registry item. */
    public static boolean isSuiteItemId(String itemId) {
        return itemId != null && itemId.startsWith(PREFIX);
    }

    /** Strips the prefix. Only meaningful when {@link #isSuiteItemId} is true. */
    public static String idFrom(String itemId) {
        return itemId.substring(PREFIX.length());
    }

    // ---- resolution, mirroring WondrousShop ---------------------------------

    /** True if any suite items are loaded at all. */
    public static boolean available() {
        return size() > 0;
    }

    /**
     * The vanilla item a suite item is stamped onto. Used for capacity checks and for
     * shop-load validation -- never for delivery, since it carries none of the marker
     * or decoration.
     */
    public static Optional<Item> baseItem(String id) {
        return byId(id).flatMap(def -> ItemBank.resolve(def.item()));
    }

    /** Player-facing name, for the {@code /shop} listing and the purchase message. */
    public static Optional<Component> displayName(String id) {
        return byId(id).map(def -> Component.literal(def.name()));
    }

    /**
     * A single fully-stamped stack, for display only -- a GUI slot's icon. Carries the
     * item's real name, lore and glow, because this is what makes the admin browser
     * worth having: you see the Spirit Stone, not the words {@code minecraft:echo_shard}.
     */
    public static Optional<ItemStack> iconStack(String id, HolderLookup.Provider registries) {
        return byId(id).flatMap(def -> ItemBank.resolve(def.item()).map(item -> {
            ItemStack stack = new ItemStack(item);
            ItemComponents.apply(stack, def.components(), registries);
            return stack;
        }));
    }

    /**
     * Builds a fully stamped stack of {@code count} and hands it to {@code player},
     * dropping at their feet if it will not fit. Returns false only if the id no longer
     * resolves -- in which case the caller must treat it as a failed delivery and refund.
     */
    public static boolean deliver(ServerPlayer player, String id, int count,
                                  HolderLookup.Provider registries) {
        Optional<SuiteItemDefinition> def = byId(id);
        if (def.isEmpty()) return false;
        Optional<Item> item = ItemBank.resolve(def.get().item());
        if (item.isEmpty()) return false;
        Optional<DataComponentPatch> patch =
                ItemComponents.parse(def.get().components(), registries);
        if (patch.isEmpty()) return false;

        // The return of ItemBank.give is how many items had to be DROPPED rather than
        // placed -- and a dropped item is still delivered, at the player's feet. Testing
        // it for zero would report a successful purchase by a player with a full
        // inventory as a failed delivery, and the caller's response to a failed delivery
        // is a refund: the player would keep the items and get their money back.
        // WondrousShop.deliver returns true unconditionally after giveOrDrop for exactly
        // this reason. False here means only what the javadoc says it means -- the id did
        // not resolve, so nothing was handed over at all.
        ItemBank.give(player, item.get(), count, patch.get());
        return true;
    }

    /**
     * Which suite item, if any, a stack already carries -- the reverse of
     * {@link #iconStack}. Used by the shop editor's "sell what you're holding" flow so
     * a Big Hole Pick in hand becomes {@code suite_item: "wondrous:big_hole_pick"}, not
     * a plain Diamond Pickaxe with no marker at all.
     *
     * <p>Resolution walks the stack's {@code custom_data} namespaces against the loaded
     * index. A namespace with exactly one published item is unambiguous regardless of
     * what its marker value looks like -- this is how spiritwolves, whose marker value
     * carries mutable state ({@code bound}) rather than an id, is still recognised. A
     * namespace with more than one candidate is resolved only if the marker value --
     * or a nested {@code "id"} field inside it -- names a definition's path exactly;
     * that is the shape wondrous already uses and the shape SUITE_ITEMS.md §6
     * recommends for any future mod with more than one item. If neither applies, the
     * stack cannot be told apart from another item in the same namespace and this
     * returns empty rather than guessing -- the caller falls back to the plain
     * registry item, same as an unrecognised item always has.
     */
    public static Optional<String> identify(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) return Optional.empty();

        var root = data.copyTag();
        for (String namespace : root.keySet()) {
            List<SuiteItemDefinition> candidates = inNamespace(namespace);
            if (candidates.isEmpty()) continue;
            if (candidates.size() == 1) {
                return Optional.of(candidates.get(0).id());
            }

            Optional<String> markerId = root.getString(namespace)
                    .or(() -> root.getCompoundOrEmpty(namespace).getString("id"));
            if (markerId.isEmpty()) continue;

            for (SuiteItemDefinition def : candidates) {
                if (def.path().equals(markerId.get())) {
                    return Optional.of(def.id());
                }
            }
        }
        return Optional.empty();
    }

    // ---- the index, for the admin browser -----------------------------------

    /** One definition by its {@code namespace:path} id. */
    public static Optional<SuiteItemDefinition> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Every namespace that published at least one item, sorted. */
    public static List<String> namespaces() {
        return byNamespace.keySet().stream().sorted().toList();
    }

    /**
     * The {@code _source.json} for a namespace. Never null -- a namespace that shipped
     * none gets {@link SuiteSource#fallback}, so no caller needs a null check.
     */
    public static SuiteSource source(String namespace) {
        SuiteSource s = sources.get(namespace);
        return s != null ? s : SuiteSource.fallback(namespace);
    }

    /** Every definition published by one namespace, sorted by path. */
    public static List<SuiteItemDefinition> inNamespace(String namespace) {
        return byNamespace.getOrDefault(namespace, List.of());
    }

    /** Every suite tag used by one namespace's items, sorted and deduped. */
    public static List<String> tagsIn(String namespace) {
        Set<String> tags = namespaceTags.get(namespace);
        return tags == null ? List.of() : tags.stream().sorted().toList();
    }

    /** Every definition carrying a suite tag, across all namespaces. */
    public static List<SuiteItemDefinition> byTag(String tag) {
        List<String> ids = tagToIds.get(tag);
        if (ids == null) return List.of();
        List<SuiteItemDefinition> out = new ArrayList<>();
        for (String id : ids) {
            byId(id).ifPresent(out::add);
        }
        return out;
    }

    /** How many definitions are loaded. */
    public static int size() {
        return byId.size();
    }

    // ---- loader -------------------------------------------------------------

    /**
     * A {@link SimpleJsonResourceReloadListener} over {@code data/<namespace>/suite_items/}.
     * Registered through {@link ResourceManagerHelper#get(PackType)} so the merged datapack
     * view arrives for free and {@code /reload} rebuilds the index.
     */
    static final class Loader extends SimpleJsonResourceReloadListener<JsonElement>
            implements IdentifiableResourceReloadListener {

        private final HolderLookup.Provider registries;

        Loader(HolderLookup.Provider registries) {
            super(ExtraCodecs.JSON, new FileToIdConverter("suite_items", ".json"));
            this.registries = registries;
        }

        @Override
        public Identifier getFabricId() {
            return Identifier.fromNamespaceAndPath(CobbleEconomyMod.MOD_ID, "suite_items");
        }

        @Override
        protected void apply(Map<Identifier, JsonElement> data, ResourceManager manager,
                             ProfilerFiller profiler) {
            Map<String, SuiteItemDefinition> byId = new HashMap<>();
            Map<String, List<SuiteItemDefinition>> byNamespace = new HashMap<>();
            Map<String, SuiteSource> sources = new HashMap<>();
            Map<String, Set<String>> namespaceTags = new HashMap<>();
            Map<String, Set<String>> tagToIds = new HashMap<>();

            int accepted = 0;
            int rejected = 0;

            for (Map.Entry<Identifier, JsonElement> e : data.entrySet()) {
                Identifier loc = e.getKey();
                String namespace = loc.getNamespace();
                String path = loc.getPath();

                if ("_source".equals(path)) {
                    try {
                        JsonObject obj = e.getValue().getAsJsonObject();
                        String name = obj.has("name") ? obj.get("name").getAsString() : null;
                        String icon = obj.has("icon") ? obj.get("icon").getAsString() : null;
                        String description = obj.has("description") ? obj.get("description").getAsString() : null;
                        sources.put(namespace, new SuiteSource(namespace, name, icon, description));
                    } catch (RuntimeException ex) {
                        CobbleEconomyMod.LOG.warn("Suite source '{}' could not be parsed", loc, ex);
                        rejected++;
                    }
                    continue;
                }

                String id = namespace + ":" + path;
                try {
                    JsonObject obj = e.getValue().getAsJsonObject();
                    if (!obj.has("item") || !obj.has("name") || !obj.has("components")) {
                        CobbleEconomyMod.LOG.warn(
                                "Suite item '{}' is missing required fields (item, name, components)", id);
                        rejected++;
                        continue;
                    }

                    String itemId = obj.get("item").getAsString();
                    String name = obj.get("name").getAsString();
                    JsonObject componentsObj = obj.get("components").getAsJsonObject();
                    String components = componentsObj.toString();

                    if (ItemBank.resolve(itemId).isEmpty()) {
                        CobbleEconomyMod.LOG.warn("Suite item '{}' names unknown item '{}'", id, itemId);
                        rejected++;
                        continue;
                    }

                    if (!hasMarker(namespace, componentsObj)) {
                        CobbleEconomyMod.LOG.warn(
                                "Suite item '{}' has no custom_data marker for namespace '{}'", id, namespace);
                        rejected++;
                        continue;
                    }

                    if (ItemComponents.parse(components, registries).isEmpty()) {
                        CobbleEconomyMod.LOG.warn("Suite item '{}' components could not be parsed", id);
                        rejected++;
                        continue;
                    }

                    List<String> tags = new ArrayList<>();
                    if (obj.has("tags")) {
                        JsonArray arr = obj.get("tags").getAsJsonArray();
                        for (JsonElement t : arr) tags.add(t.getAsString());
                    }

                    String rarity = null;
                    if (obj.has("rarity") && obj.get("rarity").isJsonPrimitive()) {
                        rarity = obj.get("rarity").getAsString();
                    }

                    SuiteItemDefinition def = new SuiteItemDefinition(
                            id, namespace, path, itemId, name, tags, rarity, components);
                    byId.put(id, def);
                    byNamespace.computeIfAbsent(namespace, k -> new ArrayList<>()).add(def);
                    Set<String> nsTags = namespaceTags.computeIfAbsent(namespace, k -> new HashSet<>());
                    for (String tag : tags) {
                        nsTags.add(tag);
                        tagToIds.computeIfAbsent(tag, k -> new HashSet<>()).add(id);
                    }
                    accepted++;
                } catch (RuntimeException ex) {
                    CobbleEconomyMod.LOG.warn("Suite item '{}' could not be parsed", id, ex);
                    rejected++;
                }
            }

            for (List<SuiteItemDefinition> list : byNamespace.values()) {
                list.sort(Comparator.comparing(SuiteItemDefinition::path));
            }

            Map<String, List<SuiteItemDefinition>> byNamespaceCopy = new HashMap<>();
            for (Map.Entry<String, List<SuiteItemDefinition>> e : byNamespace.entrySet()) {
                byNamespaceCopy.put(e.getKey(), List.copyOf(e.getValue()));
            }

            Map<String, Set<String>> namespaceTagsCopy = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : namespaceTags.entrySet()) {
                namespaceTagsCopy.put(e.getKey(), Set.copyOf(e.getValue()));
            }

            Map<String, List<String>> tagToIdsCopy = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : tagToIds.entrySet()) {
                tagToIdsCopy.put(e.getKey(), List.copyOf(e.getValue()));
            }

            SuiteItems.byId = Map.copyOf(byId);
            SuiteItems.byNamespace = Map.copyOf(byNamespaceCopy);
            SuiteItems.sources = Map.copyOf(sources);
            SuiteItems.namespaceTags = Map.copyOf(namespaceTagsCopy);
            SuiteItems.tagToIds = Map.copyOf(tagToIdsCopy);

            CobbleEconomyMod.LOG.info("Loaded {} suite items ({} rejected)", accepted, rejected);
        }

        /**
         * True if {@code components} carries a {@code custom_data} marker keyed by the
         * publishing namespace. SUITE_ITEMS.md §6.
         *
         * <p><b>The value's shape is deliberately not constrained.</b> The marker has to
         * be whatever the publishing mod already stamps and already reads, because §6's
         * whole point is that identity survives cosmetic change -- and demanding a
         * particular shape here would mean asking mods to restamp, which breaks every
         * such item already sitting in a player's inventory. The two real publishers
         * disagree about the shape and both are correct:
         *
         * <pre>
         *   wondrous       "minecraft:custom_data": { "wondrous": "pocket_workbench" }
         *   spiritwolves   "minecraft:custom_data": { "spiritwolves": { "bound": false } }
         * </pre>
         *
         * <p>So the requirement is only that the namespace key is present. A publisher
         * with more than one item needs something inside the value to tell them apart --
         * wondrous's string does it, and a nested {@code "id"} is the recommended way for
         * new mods -- but that is the publisher's problem to have, not this loader's to
         * enforce.
         */
        private static boolean hasMarker(String namespace, JsonObject components) {
            JsonElement customData = components.get("minecraft:custom_data");
            if (customData == null || !customData.isJsonObject()) return false;
            return customData.getAsJsonObject().has(namespace);
        }
    }
}
