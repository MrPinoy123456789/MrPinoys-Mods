package cobbleeconomy;

import java.util.List;

/**
 * One entry from {@code data/<namespace>/suite_items/<path>.json}.
 *
 * <p>See {@code SUITE_ITEMS.md} §4.2 for the file format. This is the parsed form of
 * it, and the shape every consumer in this mod sees.
 *
 * <p>{@code components} is held as a raw JSON string rather than a
 * {@code DataComponentPatch}, matching the convention {@link cobbleeconomy.core.ShopEntry}
 * already uses: the string is what came off disk, and {@link ItemComponents} turns it
 * into a patch at the point of use, with caching. Keeping it as a string also means a
 * definition can be indexed before the registries are available.
 *
 * @param id         {@code "spiritwolves:spirit_stone"} -- namespace and path joined
 * @param namespace  the mod that published it
 * @param path       the file name without extension
 * @param item       the vanilla registry item it is stamped onto; never a modded id (§4.4)
 * @param name       plain-text display name, for logs, chat listings and menu titles
 * @param tags       suite tags, lowercase and unnamespaced (§5); never null, may be empty
 * @param rarity     {@code common|uncommon|rare|legendary}, or null. A hint, never a price (§8.3)
 * @param components raw JSON in the shape vanilla {@code /give} accepts; must contain the
 *                   {@code custom_data} marker (§6)
 */
public record SuiteItemDefinition(
        String id,
        String namespace,
        String path,
        String item,
        String name,
        List<String> tags,
        String rarity,
        String components) {

    public SuiteItemDefinition {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** True if this definition carries the given suite tag. */
    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }
}
