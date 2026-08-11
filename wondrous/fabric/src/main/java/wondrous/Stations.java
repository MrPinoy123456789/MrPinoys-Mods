package wondrous;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import wondrous.api.WondrousTag;

import java.util.Optional;

/**
 * Right-click handling for the station items (workbench, anvil, grindstone,
 * stonecutter, loom, ender chest).
 *
 * <p>Registered on both use callbacks: {@link UseBlockCallback} for clicking a
 * block, {@link UseItemCallback} for clicking air. The first of those is
 * load-bearing -- returning a consuming result is what stops a pocket workbench
 * from placing itself as a crafting table.
 */
public final class Stations {

    private Stations() {}

    public static void register(ItemRegistry registry) {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            return tryOpen(registry, serverPlayer, hand);
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            return tryOpen(registry, serverPlayer, hand);
        });
    }

    private static InteractionResult tryOpen(ItemRegistry registry,
                                             ServerPlayer player,
                                             InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);

        Optional<String> id = WondrousTag.read(held);
        if (id.isEmpty()) {
            return InteractionResult.PASS;
        }

        Optional<Definitions.Def> def = registry.defOf(id.get());
        if (def.isEmpty() || def.get().isPassive()) {
            // Either an id from a newer version of the mod, or a passive item
            // (boots, area tools) -- worn or swung, not clicked.
            return InteractionResult.PASS;
        }

        player.openMenu(new SimpleMenuProvider(def.get().menu(), def.get().name()));

        // SUCCESS_SERVER rather than SUCCESS: only the server knows this item is
        // anything special, so there is nothing for the client to have predicted.
        return InteractionResult.SUCCESS_SERVER;
    }
}
