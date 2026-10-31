package excellentenchants;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/** The enchantment keys and a level lookup that survives a missing registry entry. */
public final class Enchants {

    public static final ResourceKey<Enchantment> DRAGON_HEART = key("dragon_heart");
    public static final ResourceKey<Enchantment> FLAME_WALKER = key("flame_walker");
    public static final ResourceKey<Enchantment> ENDER_BOW = key("ender_bow");
    public static final ResourceKey<Enchantment> SILK_CHEST = key("silk_chest");
    public static final ResourceKey<Enchantment> SILK_SPAWNER = key("silk_spawner");
    public static final ResourceKey<Enchantment> DECAPITATOR = key("decapitator");
    public static final ResourceKey<Enchantment> ICE_ASPECT = key("ice_aspect");
    public static final ResourceKey<Enchantment> THRIFTY = key("thrifty");
    public static final ResourceKey<Enchantment> THUNDER = key("thunder");
    public static final ResourceKey<Enchantment> VENOM = key("venom");
    public static final ResourceKey<Enchantment> WISDOM = key("wisdom");
    public static final ResourceKey<Enchantment> WITHER = key("wither");
    public static final ResourceKey<Enchantment> GHAST = key("ghast");
    public static final ResourceKey<Enchantment> TUNNEL = key("tunnel");
    public static final ResourceKey<Enchantment> TELEKINESIS = key("telekinesis");

    private Enchants() {
    }

    private static ResourceKey<Enchantment> key(String id) {
        return ResourceKey.create(Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(ExcellentEnchantsMod.MOD_ID, id));
    }

    /** The level of an enchantment on a stack, or 0 when it is absent or the registry lacks it. */
    public static int level(ServerLevel level, ItemStack stack, ResourceKey<Enchantment> key) {
        if (stack == null || stack.isEmpty()) return 0;
        Holder<Enchantment> holder = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(key).orElse(null);
        return holder == null ? 0 : EnchantmentHelper.getItemEnchantmentLevel(holder, stack);
    }
}
