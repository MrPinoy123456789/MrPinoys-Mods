package pocketdungeons;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L2 (D41): the impure half of the content module system: scans
 * {@code data/<namespace>/content_module/*.json} into a
 * {@link ContentModules} manifest the snapshot publishes atomically, and
 * registers the loot hook that merges an enabled module's tables into its
 * target tables.
 *
 * <h2>Loot injection</h2>
 *
 * <p>The manifest's {@code loot} map says {@code "chests/tier_2":
 * "modules/alchemy/chests/tier_2"}: when {@code pocketdungeons:chests/tier_2}
 * is rolled and the module is enabled, the module table is rolled with the
 * same {@link net.minecraft.world.level.storage.loot.LootContext} and its
 * drops merge into the result. The hook consults {@link ContentModules} at
 * roll time, so a toggle lands on the next roll; manifest surfaces that
 * load at reload time (bags in the picker, station availability) settle on
 * the next {@code /reload}.
 */
final class ContentModuleLoader {

    private ContentModuleLoader() {}

    /**
     * Parses every {@code content_module} resource into a manifest without
     * publishing it, the build half of {@link ContentReload}'s atomic reload.
     * A module table each {@code loot} entry names must resolve as a real
     * loot table, the same validation a bag's {@code loot_table} gets.
     */
    static ContentModules parse(MinecraftServer server, ResourceManager rm) {
        Map<String, ContentModules.Module> modules = new LinkedHashMap<>();
        List<String> rejections = new ArrayList<>();
        Map<Identifier, Resource> resources = rm.listResources(
                "content_module", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Identifier, Resource> resource : sorted) {
            String id = JsonPackSupport.resourceId(resource.getKey(), "content_module");
            try (BufferedReader reader = resource.getValue().openAsReader()) {
                ContentModules.Module module = ContentModules.Module.fromJson(
                        JsonParser.parseReader(reader).getAsJsonObject(), id);
                for (String tableId : module.loot().values()) {
                    validateLootRef(server, tableId, id);
                }
                modules.put(id, module);
            } catch (Exception exception) {
                String reason = id + " - " + exception.getMessage();
                rejections.add(reason);
                PocketDungeonsMod.LOG.error("Rejected content module '{}': {}", id, reason, exception);
            }
        }
        for (String overrideId : ContentModules.overrides().keySet()) {
            if (!modules.containsKey(overrideId)) {
                PocketDungeonsMod.LOG.warn("pocketdungeons.json modules entry '{}' names no loaded "
                        + "content module; it has no effect", overrideId);
            }
        }
        return ContentModules.create(modules, rejections);
    }

    private static void validateLootRef(MinecraftServer server, String lootId, String moduleId) {
        Identifier id = Identifier.parse(lootId);
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, id);
        boolean present = server.reloadableRegistries().lookup()
                .lookup(Registries.LOOT_TABLE)
                .map(lookup -> lookup.get(key).isPresent())
                .orElse(false);
        if (!present) {
            throw new IllegalStateException(moduleId + ": module loot table not found: " + lootId);
        }
    }

    /** Wires the module loot hook. Call once from mod init. */
    static void register() {
        LootTableEvents.MODIFY_DROPS.register((table, context, drops) -> {
            String tableId = table.unwrapKey()
                    .map(key -> key.identifier().toString())
                    .orElse("");
            if (tableId.isEmpty()) {
                return;
            }
            List<String> moduleTables = ContentModules.enabledLootTargets().get(tableId);
            if (moduleTables == null) {
                return;
            }
            MinecraftServer server = context.getLevel().getServer();
            LootParams params = new LootParams.Builder(context.getLevel())
                    .withParameter(LootContextParams.ORIGIN, net.minecraft.world.phys.Vec3.ZERO)
                    .withLuck(context.getLuck())
                    .create(LootContextParamSets.CHEST);
            for (String moduleTableId : moduleTables) {
                ResourceKey<LootTable> key = ResourceKey.create(
                        Registries.LOOT_TABLE, Identifier.parse(moduleTableId));
                LootTable moduleTable = server.reloadableRegistries().getLootTable(key);
                for (ItemStack drop : moduleTable.getRandomItems(params, context.getRandom().nextLong())) {
                    drops.add(drop);
                }
            }
        });
    }
}
