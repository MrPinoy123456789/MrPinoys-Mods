package wondrous.api;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * One wondrous item, as seen by another mod handing it out as a reward.
 *
 * <p>Callers never learn whether the underlying implementation registers a real
 * item or stamps a vanilla one. That is deliberate: it is currently the latter,
 * and moving to the former should not touch a line of dailyquests or quizengine.
 */
public interface WondrousItem {

    /** Stable id, e.g. {@code "flying_boots"}. Safe to store in quest JSON. */
    String id();

    /** Player-facing name, e.g. "Pocket Crafter". */
    Component displayName();

    /** A fresh stack of one, fully tagged and decorated. */
    ItemStack createStack();

    /** A fresh stack of {@code count}. */
    ItemStack createStack(int count);
}
