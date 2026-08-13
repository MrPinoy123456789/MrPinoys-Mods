package wayfarers;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.context.ContextKey;
import net.minecraft.util.context.ContextKeySet;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * The configured {@code drops} table for a hostile encounter (§5.1).
 *
 * <p>Rolled once, on the death of the entity the encounter is tracking, on top
 * of whatever the mob drops by itself. A missing or unusable table is a warning
 * and nothing else — the fight still resolves.
 */
public final class Drops {

    /** Table ids already complained about, so a bad config warns once, not per kill. */
    private static final Set<String> WARNED = new HashSet<>();

    private Drops() {}

    static void roll(Encounters.Active active, LivingEntity entity, DamageSource source) {
        if (!"hostile".equals(active.def.template())) {
            return;
        }
        String id = active.def.drops();
        if (id == null || id.isBlank()) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        Identifier key = Identifier.tryParse(id);
        if (key == null) {
            warnOnce(id, "is not a valid identifier");
            return;
        }

        LootTable table = level.getServer().reloadableRegistries()
                .getLootTable(ResourceKey.create(Registries.LOOT_TABLE, key));
        if (table == LootTable.EMPTY) {
            warnOnce(id, "does not exist");
            return;
        }

        LootParams params = paramsFor(table, level, entity, source);
        if (params == null) {
            return;
        }

        Vec3 pos = entity.position();
        table.getRandomItems(params, entity.getRandom().nextLong(),
                LootTable.createStackSplitter(level, stack -> spawn(level, pos, stack)));
    }

    /**
     * Builds params the table will actually accept. Entity tables want the
     * victim and the damage source; chest tables want only an origin. Offering
     * a parameter a set does not allow makes {@code create} throw, so every key
     * is filtered against {@link ContextKeySet#allowed()} first.
     */
    private static LootParams paramsFor(LootTable table, ServerLevel level,
                                        LivingEntity entity, DamageSource source) {
        ContextKeySet set = table.getParamSet();
        LootParams.Builder builder = new LootParams.Builder(level);
        put(builder, set, LootContextParams.THIS_ENTITY, entity);
        put(builder, set, LootContextParams.ORIGIN, entity.position());
        put(builder, set, LootContextParams.DAMAGE_SOURCE, source);
        put(builder, set, LootContextParams.ATTACKING_ENTITY, source.getEntity());
        put(builder, set, LootContextParams.DIRECT_ATTACKING_ENTITY, source.getDirectEntity());
        if (source.getEntity() instanceof Player player) {
            put(builder, set, LootContextParams.LAST_DAMAGE_PLAYER, player);
        }
        try {
            return builder.create(set);
        } catch (RuntimeException ex) {
            warnOnce(String.valueOf(table.getParamSet()),
                    "needs parameters this encounter cannot supply (" + ex.getMessage() + ")");
            return null;
        }
    }

    private static <T> void put(LootParams.Builder builder, ContextKeySet set,
                                ContextKey<T> keyRef, T value) {
        if (value != null && set.allowed().contains(keyRef)) {
            builder.withParameter(keyRef, value);
        }
    }

    private static void spawn(ServerLevel level, Vec3 pos, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemEntity item = new ItemEntity(level, pos.x, pos.y, pos.z, stack);
        item.setDefaultPickUpDelay();
        level.addFreshEntity(item);
    }

    private static void warnOnce(String id, String why) {
        if (WARNED.add(id)) {
            WayfarersMod.LOG.warn("Encounter drops table '{}' {}; no extra loot", id, why);
        }
    }

    /** Config reload clears the complaint list so a fixed table stops being quiet. */
    static void forgetWarnings() {
        WARNED.clear();
    }
}
