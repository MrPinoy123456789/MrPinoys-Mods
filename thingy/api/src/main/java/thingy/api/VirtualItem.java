package thingy.api;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * One virtual item, as seen by a consuming mod handing it out as a reward.
 *
 * <p>Callers never learn whether the underlying implementation registers a real
 * item or stamps a vanilla one. That is deliberate. Minecraft has carriers;
 * Thingy has identities (PLAN.md, "Core design principle").
 */
public interface VirtualItem {

    /** Namespaced id, e.g. {@code "wondrous:flying_boots"}. Safe to store in quest JSON. */
    String id();

    /** Player-facing name, e.g. "Pocket Crafter". */
    Component displayName();

    /** A fresh stack of one, fully tagged and decorated. */
    ItemStack createStack();

    /** A fresh stack of {@code count}. */
    ItemStack createStack(int count);
}
