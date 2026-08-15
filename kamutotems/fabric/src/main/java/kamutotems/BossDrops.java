package kamutotems;

import kamutotems.core.Kamu;
import kamutotems.core.KamuCatalog;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Drops exactly one tier-1 kamu that the boss was carrying.
 */
public final class BossDrops {

    private static final Logger LOG = LoggerFactory.getLogger("kamutotems");

    public static void drop(Boss boss, ServerLevel level, ServerPlayer killer,
                            KamuCatalog catalog, long dropSeed) {
        String kamuId = boss.roll().dropKamu(dropSeed);
        Kamu kamu = catalog.get(kamuId);
        if (kamu == null) {
            LOG.warn("Boss dropped unknown kamu id {}", kamuId);
            return;
        }

        // One implementation of the Kamu stack, in Totem.createKamu. A dropped
        // kamu and a kamu removed from a totem must be the same item -- they
        // were not, before integration. Always tier 1: fusion is the only
        // ladder (SPEC section 7.2).
        ItemStack stack = Totem.createKamu(kamuId, 1);

        if (killer != null) {
            if (!killer.getInventory().add(stack)) {
                killer.drop(stack, false);
                LOG.info("Kamu drop for {} would not fit; dropped at feet", killer.getName().getString());
            }
        } else {
            // ItemEntity(ServerLevel, x, y, z, ItemStack) verified in 26.2.
            level.addFreshEntity(new ItemEntity(level,
                    boss.entity().getX(), boss.entity().getY(), boss.entity().getZ(), stack));
        }
    }
}
