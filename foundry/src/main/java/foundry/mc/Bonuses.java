package foundry.mc;

import foundry.core.BonusDef;
import foundry.core.BonusMath;
import foundry.core.BonusParser;
import foundry.core.Ident;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.context.ContextKey;
import net.minecraft.util.context.ContextKeySet;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * Gathering bonuses: a free level of Fortune on broken blocks or Looting on killed mobs for players who hold a
 * permission node (a race). The data is {@link BonusDef}, the arithmetic is {@link BonusMath}; this class reads the
 * world. A bonus is an extra roll of the same drops, so it never changes what the first roll gave.
 *
 * Not compiled or run when written (the build host could not reach the Fabric Maven repository). The loot parameter
 * handling follows wayfarers.Drops, which is built. Needs a gametest before it ships.
 */
final class Bonuses {
    private static volatile Map<Ident, BonusDef> defs = Map.of();

    private Bonuses() {}

    /** Reads {@code <content>/<namespace>/bonuses/*.json}. Problems are logged; valid files still load. */
    static void reload() {
        BonusParser.Loaded loaded = BonusParser.load(FoundryMod.contentRoot());
        loaded.problems().forEach(p -> FoundryMod.LOG.warn("{}", p));
        defs = loaded.bonuses();
        FoundryMod.LOG.info("Foundry: {} gathering bonuses loaded from {}", defs.size(), FoundryMod.contentRoot());
    }

    static void register() {
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) blockBroken(sl, sp, pos, state, blockEntity);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity.level() instanceof ServerLevel sl && source.getEntity() instanceof ServerPlayer sp) mobKilled(sl, sp, entity, source);
        });
    }

    private static void blockBroken(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (defs.isEmpty() || creative(player)) return;
        ItemStack tool = player.getMainHandItem();
        if (enchantLevel(level, tool, Enchantments.SILK_TOUCH) > 0) return;
        int toolLevel = enchantLevel(level, tool, Enchantments.FORTUNE);
        for (BonusDef def : defs.values()) {
            if (def.kind() != BonusDef.Kind.FORTUNE || !holds(player, def) || !matchesBlock(def, state)) continue;
            int gain = BonusMath.gain(toolLevel, def.level(), def.cap());
            if (gain <= 0) continue;
            for (ItemStack drop : Block.getDrops(state, level, pos, blockEntity, player, tool)) {
                if (!allowed(def, drop)) continue;
                int extra = BonusMath.extra(def.kind(), gain, drop.getCount(), player.getRandom().nextDouble());
                if (extra > 0) Block.popResource(level, pos, drop.copyWithCount(extra));
            }
        }
    }

    private static void mobKilled(ServerLevel level, ServerPlayer player, LivingEntity mob, DamageSource source) {
        if (defs.isEmpty() || creative(player)) return;
        int weaponLevel = enchantLevel(level, player.getMainHandItem(), Enchantments.LOOTING);
        for (BonusDef def : defs.values()) {
            if (def.kind() != BonusDef.Kind.LOOTING || !holds(player, def) || !matchesEntity(def, mob.getType())) continue;
            int gain = BonusMath.gain(weaponLevel, def.level(), def.cap());
            if (gain <= 0) continue;
            var key = mob.getType().getDefaultLootTable();
            if (key.isEmpty()) continue;
            LootTable table = level.getServer().reloadableRegistries().getLootTable(key.get());
            if (table == LootTable.EMPTY) continue;
            LootParams params = paramsFor(table, level, mob, source);
            if (params == null) continue;
            Vec3 pos = mob.position();
            table.getRandomItems(params, mob.getRandom().nextLong(), LootTable.createStackSplitter(level, stack -> {
                if (stack.isEmpty() || !allowed(def, stack)) return;
                int extra = BonusMath.extra(def.kind(), gain, stack.getCount(), player.getRandom().nextDouble());
                if (extra > 0) spawn(level, pos, stack.copyWithCount(extra));
            }));
        }
    }

    /** The player's game mode itself: the gametest mock player answers isCreative() true whatever its mode. */
    private static boolean creative(ServerPlayer player) {
        return player.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.CREATIVE;
    }

    private static boolean holds(ServerPlayer player, BonusDef def) {
        return MechanicHost.permissionCheck.test(player, Identifier.parse(def.permission()));
    }

    private static boolean allowed(BonusDef def, ItemStack stack) {
        if (def.onlyItems().isEmpty()) return true;
        return def.onlyItems().contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    private static boolean matchesBlock(BonusDef def, BlockState state) {
        return matches(def.targets(), BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                tag -> state.is(TagKey.create(Registries.BLOCK, Identifier.parse(tag))));
    }

    private static boolean matchesEntity(BonusDef def, EntityType<?> type) {
        return matches(def.targets(), BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(),
                tag -> type.builtInRegistryHolder().is(TagKey.create(Registries.ENTITY_TYPE, Identifier.parse(tag))));
    }

    private static boolean matches(List<String> targets, String id, java.util.function.Predicate<String> inTag) {
        for (String t : targets) {
            if (t.startsWith("#") ? inTag.test(t.substring(1)) : t.equals(id)) return true;
        }
        return false;
    }

    private static int enchantLevel(ServerLevel level, ItemStack stack, ResourceKey<Enchantment> key) {
        var holder = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
        return EnchantmentHelper.getItemEnchantmentLevel(holder, stack);
    }

    /** Offers each loot parameter only when the table's set allows it, as wayfarers.Drops does. */
    private static LootParams paramsFor(LootTable table, ServerLevel level, LivingEntity mob, DamageSource source) {
        ContextKeySet set = table.getParamSet();
        LootParams.Builder builder = new LootParams.Builder(level);
        put(builder, set, LootContextParams.THIS_ENTITY, mob);
        put(builder, set, LootContextParams.ORIGIN, mob.position());
        put(builder, set, LootContextParams.DAMAGE_SOURCE, source);
        put(builder, set, LootContextParams.ATTACKING_ENTITY, source.getEntity());
        put(builder, set, LootContextParams.DIRECT_ATTACKING_ENTITY, source.getDirectEntity());
        if (source.getEntity() instanceof ServerPlayer player) put(builder, set, LootContextParams.LAST_DAMAGE_PLAYER, player);
        try {
            return builder.create(set);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <T> void put(LootParams.Builder builder, ContextKeySet set, ContextKey<T> key, T value) {
        if (value != null && set.allowed().contains(key)) builder.withParameter(key, value);
    }

    private static void spawn(ServerLevel level, Vec3 pos, ItemStack stack) {
        ItemEntity item = new ItemEntity(level, pos.x, pos.y, pos.z, stack);
        item.setDefaultPickUpDelay();
        level.addFreshEntity(item);
    }
}
