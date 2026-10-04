package pocketdungeons.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.vault.VaultServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Playtest 2026-10-03-2: the items an unlocked vault is about to eject, so a
 * party's one opening can pay one roll per member. Invokers only; nothing is
 * injected.
 */
@Mixin(VaultServerData.class)
public interface VaultServerDataAccessor {

    @Invoker("getItemsToEject")
    List<ItemStack> pocketdungeons$getItemsToEject();

    @Invoker("setItemsToEject")
    void pocketdungeons$setItemsToEject(List<ItemStack> items);
}
