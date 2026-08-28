package cobbleeconomy;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import thingy.api.Give;
import thingy.api.VirtualItem;
import thingy.api.VirtualItems;

import java.util.Optional;

/**
 * The only file in this mod that imports {@code thingy.api}.
 *
 * <p>Wondrous items are not registry entries, so a shop listing cannot name one the
 * way it names {@code minecraft:ice}: there is no id in the item registry to look
 * up. Instead a listing names one by prefix, {@code "wondrous:<id>"}, and every call
 * site that would otherwise go through {@link ItemBank#resolve} checks
 * {@link #isWondrousItemId} first and comes here instead.
 *
 * <p>The class is still named {@code WondrousShop} and the id prefix is still
 * {@code "wondrous:"} deliberately (Thingy PLAN.md Phase 2, the "explicit decision
 * on the wondrous: config prefix" the phase calls for): the prefix names the item's
 * own namespace, not the mod that currently resolves it, so a shop.json written
 * against the old wondrous jar keeps working unchanged against Thingy. This class
 * now resolves through {@link VirtualItems} instead of the retired
 * {@code wondrous.api.WondrousItems}; nothing about the on-disk config format moved.
 *
 * <p>Removing the thingy dependency is deleting this file and the branches in
 * {@link ShopConfig} and {@link ShopCommands} that call into it; {@link ShopEntry}
 * itself stores nothing but the prefixed id string.
 */
final class WondrousShop {

    private static final String PREFIX = "wondrous:";

    private WondrousShop() {}

    /** True if {@code itemId} names a wondrous item rather than a registry item. */
    static boolean isWondrousItemId(String itemId) {
        return itemId != null && itemId.startsWith(PREFIX);
    }

    /** Strips the prefix. Only meaningful when {@link #isWondrousItemId} is true. */
    static String idFrom(String itemId) {
        return itemId.substring(PREFIX.length());
    }

    /**
     * Whether Thingy is present, asked of the loader rather than of the api.
     *
     * <p><b>This check must not touch a {@code thingy.api} class.</b> The api is a
     * {@code compileOnly} dependency and is not bundled, so when the mod is absent those
     * classes are absent too, and the first reference to one throws
     * {@link NoClassDefFoundError}. That is an {@link Error}, not an {@link Exception},
     * so {@link ShopConfig#load}'s {@code catch (Exception)} does not stop it; it
     * propagates out of shop load and kills server startup, which is the precise
     * opposite of that method's stated intent that a bad shop file must never stop the
     * server from starting.
     *
     * <p>{@code isModLoaded} answers the same question using only loader classes, and
     * short-circuits every path below it. It is checked again in {@link #lookup} so that
     * no method here can be entered unguarded.
     */
    static boolean available() {
        return LOADED && VirtualItems.get().isPresent();
    }

    /** Resolved once. Asking the loader is cheap, but not free, and this never changes. */
    private static final boolean LOADED =
            net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("thingy");

    /**
     * {@code VirtualItems.byId} takes the namespaced id ({@code "wondrous:flying_boots"}),
     * so the prefix stripped by every call site's {@link #idFrom} is put back on here
     * rather than at every caller: the shop-config id shape and the Thingy registry id
     * shape happen to be the same string, but that is this class's fact to know, not
     * {@link ShopConfig}'s.
     */
    private static Optional<VirtualItem> lookup(String bareId) {
        if (!LOADED) return Optional.empty();
        return VirtualItems.get().flatMap(v -> v.byId(PREFIX + bareId));
    }

    /**
     * The vanilla item a wondrous item is stamped onto, e.g. {@code minecraft:crafting_table}
     * for the Pocket Crafter. Used only for capacity checks and shop-load validation;
     * never for delivery, since it carries none of the tag or decoration.
     */
    static Optional<Item> baseItem(String id) {
        return lookup(id).map(item -> item.createStack().getItem());
    }

    /** Player-facing name, for the {@code /shop} listing and the purchase message. */
    static Optional<Component> displayName(String id) {
        return lookup(id).map(VirtualItem::displayName);
    }

    /**
     * A single fully-tagged stack, for display only: the shop GUI's icon for this
     * entry. Unlike {@link #baseItem}, this carries the item's real name, lore and
     * glow, because a GUI slot has room to show them and {@link #deliver} already
     * proves this exact stack is what a purchase produces.
     */
    static Optional<ItemStack> iconStack(String id) {
        return lookup(id).map(item -> item.createStack(1));
    }

    /**
     * Builds a fully tagged stack of {@code count} and hands it to {@code player},
     * dropping at their feet if it will not fit. Returns false only if the id no
     * longer resolves, e.g. the mod was removed after shop-load validation passed,
     * in which case the caller must treat it as a failed delivery and refund.
     */
    static boolean deliver(ServerPlayer player, String id, int count) {
        Optional<VirtualItem> item = lookup(id);
        if (item.isEmpty()) {
            return false;
        }
        Give.giveOrDrop(player, item.get().createStack(count));
        return true;
    }
}
