package kamutotems;

import kamutotems.core.BossRoll;
import kamutotems.core.Kamu;
import kamutotems.core.KamuCatalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import thingy.api.VirtualEntities;

/**
 * Host for bosses: daily free tier-I, sigil-summoned tiers II-IV, and cleanup.
 */
public final class BossHost {

    private static final Logger LOG = LoggerFactory.getLogger("kamutotems");
    private static final String TAG = "kamutotems_boss";

    private static final Map<UUID, Boss> BY_ENTITY = new HashMap<>();
    private static final Map<UUID, UUID> BY_PLAYER = new HashMap<>();
    private static final Map<UUID, String> FREE_CLAIMS = new HashMap<>();
    private static final Map<UUID, Integer> SIGIL_COUNTERS = new HashMap<>();

    private static KamuCatalog CATALOG;
    private static long WORLD_SEED;
    private static int ROLLOVER_HOUR;

    private BossHost() {}

    public static void register() {
        CATALOG = KamuData.catalog();

        // Exposes the already-tracked BY_ENTITY map to other Thingy-aware mods
        // (PLAN.md Phase 7). Nothing about tracking, persistence, or
        // reattachment changes: this only publishes what byEntity already
        // answers, the same way AuraHost already reads it from inside kamutotems.
        VirtualEntities.register("kamutotems", entityId ->
                Optional.ofNullable(byEntity(entityId)));

        ServerLifecycleEvents.SERVER_STARTED.register(BossHost::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(BossHost::onShutdown);
        ServerTickEvents.END_SERVER_TICK.register(BossHost::onTick);
        ServerLivingEntityEvents.AFTER_DEATH.register(BossHost::onDeath);
        // ServerEntityEvents.ENTITY_LOAD is in the lifecycle package, not entity.event.
        ServerEntityEvents.ENTITY_LOAD.register(BossHost::onEntityLoad);
        ServerPlayConnectionEvents.JOIN.register(BossHost::onJoin);
        ServerPlayConnectionEvents.DISCONNECT.register(BossHost::onDisconnect);
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (Sigil.isRandomSigil(stack)) {
                return Sigil.onUseRandom(player, level, hand);
            }
            return Sigil.onUseItem(player, level, hand);
        });
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (Sigil.isRandomSigil(stack)) {
                return Sigil.onUseRandomBlock(player, level, hand, hitResult);
            }
            if (!Sigil.isSigil(stack)) {
                return InteractionResult.PASS;
            }
            if (level.isClientSide()) {
                return InteractionResult.SUCCESS;
            }
            // For sigils, let usable blocks (chests, crafting tables, dispensers, etc.)
            // open their menus. Dispensers/crafting tables use useWithoutItem, so try
            // that when useItemOn passes. Only fall back to the ritual when the block
            // doesn't handle the interaction at all.
            BlockPos pos = hitResult.getBlockPos();
            var state = level.getBlockState(pos);
            InteractionResult blockResult = state.useItemOn(stack, level, player, hand, hitResult);
            if (!blockResult.consumesAction()) {
                blockResult = state.useWithoutItem(level, player, hitResult);
            }
            if (blockResult.consumesAction()) {
                return blockResult;
            }
            return Sigil.onUseItem(player, level, hand, hitResult);
        });
        registerDispenserBehaviors();
    }

    private static void registerDispenserBehaviors() {
        if (CATALOG == null) {
            return;
        }
        for (Item item : BuiltInRegistries.ITEM) {
            if (item instanceof SpawnEggItem) {
                final DispenseItemBehavior original;
                DispenseItemBehavior found = DispenserBlock.DISPENSER_REGISTRY.get(item);
                if (found == null) {
                    original = DispenseItemBehavior.NOOP;
                } else {
                    original = found;
                }
                DispenserBlock.registerBehavior(item, (source, stack) -> {
                    if (!Sigil.isRolled(stack)) {
                        return original.dispense(source, stack);
                    }
                    ServerLevel level = source.level();
                    Direction dir = source.state().getValue(DispenserBlock.FACING);
                    BlockPos target = source.pos().relative(dir, 1);
                    Vec3 pos = Vec3.atCenterOf(target);
                    float yRot = dir.toYRot();
                    int tier = Sigil.tier(stack);
                    BossRoll roll = Sigil.rollFrom(stack, CATALOG);
                    if (roll == null) {
                        return original.dispense(source, stack);
                    }
                    EntityType<?> type = Sigil.typeOf(stack);
                    Boss boss = Boss.spawn(level, pos, yRot, null, tier, roll, true,
                            Sigil.counter(stack), Sigil.seed(stack), type, CATALOG);
                    if (boss == null) {
                        return original.dispense(source, stack);
                    }
                    track(boss);
                    stack.shrink(1);
                    return stack.isEmpty() ? ItemStack.EMPTY : stack;
                });
            }
        }
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("boss")
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("summon")
                        .then(Commands.argument("tier", IntegerArgumentType.integer(1, 4))
                                .executes(ctx -> summon(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "tier")))))
                .then(Commands.literal("cleanup")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> cleanup(ctx.getSource()))));
    }

    public static boolean hasActive(UUID player) {
        return BY_PLAYER.containsKey(player);
    }

    public static void track(Boss boss) {
        BY_ENTITY.put(boss.entity().getUUID(), boss);
        BY_PLAYER.put(boss.owner(), boss.entity().getUUID());
        // Entity.addTag verified in 26.2.
        boss.entity().addTag(TAG);
        writeRecord(boss);
    }

    public static java.util.Collection<Boss> activeBosses() {
        return new java.util.ArrayList<>(BY_ENTITY.values());
    }

    /** The active boss for this entity UUID, or {@code null}. Used by
     * {@link AuraHost} to source a non-player bearer's {@code AuraSpec}
     * (PLAN_COMBAT §2.1 -- one aura pipeline for players and bosses alike). */
    public static Boss byEntity(UUID entityId) {
        return BY_ENTITY.get(entityId);
    }

    // ---- lifecycle --------------------------------------------------------

    /** Initializes deterministic boss state and restores persisted claim counters at server start. */
    private static void onServerStarted(MinecraftServer server) {
        // Seed comes from ServerLevel.getSeed(); getWorldData().worldGenOptions() does not exist in 26.2.
        WORLD_SEED = server.overworld().getSeed();
        ROLLOVER_HOUR = KamuTotemsConfig.i("quest", "rollover_hour_utc", 0);
        Sigil.init(CATALOG, WORLD_SEED);

        JsonObject data = Persist.load("boss_state.json");
        if (data != null) {
            readState(data);
        }
        LOG.info("Boss host ready: today is {}", todayKey());
    }

    /** Removes boss bars and persists claim state during orderly shutdown. */
    private static void onShutdown(MinecraftServer server) {
        despawnAndRefundAll(server);
        save();
    }

    /** Updates active boss bars, sigil rolls, and stale tracking once per server tick. */
    private static void onTick(MinecraftServer server) {
        boolean rolled = Sigil.sweep(server, SIGIL_COUNTERS);
        if (rolled) {
            save();
        }

        var it = BY_ENTITY.values().iterator();
        while (it.hasNext()) {
            Boss boss = it.next();
            if (boss.entity().isRemoved() || !boss.entity().isAlive()) {
                boss.removeBarAll();
                it.remove();
                BY_PLAYER.remove(boss.owner());
                forgetRecord(boss.entity());
                continue;
            }
            boss.updateBar();
        }
    }

    /** Pays the carried-kamu drop and releases tracking when a registered boss dies. */
    private static void onDeath(LivingEntity entity, DamageSource source) {
        Boss boss = BY_ENTITY.remove(entity.getUUID());
        if (boss == null) {
            return;
        }
        BY_PLAYER.remove(boss.owner());
        boss.removeBarAll();
        forgetRecord(entity);

        ServerPlayer killer = source.getEntity() instanceof ServerPlayer p ? p : null;
        // ServerLevel.getRandom().nextLong() verified in 26.2.
        long dropSeed = ((ServerLevel) entity.level()).getRandom().nextLong();
        BossDrops.drop(boss, (ServerLevel) entity.level(), killer, CATALOG, dropSeed);
        dropCustomLoot(boss, entity, source);
    }

    private static void dropCustomLoot(Boss boss, LivingEntity entity, DamageSource source) {
        String key = KamuTotemsConfig.s("boss", "tier_" + boss.tier() + "_loot_table", "");
        if (key.isEmpty()) {
            return;
        }
        try {
            ResourceKey<LootTable> lootTable = ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(key));
            entity.dropFromLootTable((ServerLevel) entity.level(), source, true, lootTable);
        } catch (Exception e) {
            LOG.warn("Failed to apply boss loot table {}", key, e);
        }
    }

    /** Reattaches a tagged boss to its persisted identity after it loads. */
    private static void onEntityLoad(Entity entity, ServerLevel level) {
        // Entity.entityTags() verified in 26.2 (renamed from getTags()).
        if (!entity.entityTags().contains(TAG) || BY_ENTITY.containsKey(entity.getUUID())) {
            return;
        }

        BossRecords.BossRecord record = BossRecords.forLevel(level).get(entity.getUUID());
        if (record == null) {
            // Tagged with nothing behind it: either an orphan from before this
            // store existed, or a record lost with a corrupt region file. The
            // tag stays. Stripping it is exactly the silent demotion this fix
            // removes, and a tagged mob with no record costs only a log line.
            LOG.warn("Boss entity {} loaded with the {} tag but no saved record; left untracked",
                    entity.getUUID(), TAG);
            return;
        }

        Boss boss = reattach(entity, level, record);
        BY_ENTITY.put(entity.getUUID(), boss);
        BY_PLAYER.put(boss.owner(), entity.getUUID());
        boss.updateBar();
        LOG.info("Reattached tier-{} boss {} after restart", record.tier(), entity.getUUID());
    }

    /**
     * Rebuilds a {@link Boss} around an already-loaded entity.
     *
     * <p>The bar is not serializable and is not stored: it is rebuilt from the
     * entity's own custom name (set at spawn from {@link BossNames}) and falls
     * back to a fresh build when that name is gone. An offline owner gets an
     * empty bar, and {@link #onJoin} adds them when they next connect.
     */
    private static Boss reattach(Entity entity, ServerLevel level, BossRecords.BossRecord record) {
        List<Kamu> carried = record.kamuIds().stream()
                .map(CATALOG::get)
                .filter(Objects::nonNull)
                .toList();

        Component barName = entity.getCustomName();
        if (barName == null) {
            barName = BossNames.build(record.tier(), entity.getType(), record.aura(), carried);
        }

        // 26.2 ServerBossEvent takes a UUID first. Verified against the merged jar.
        ServerBossEvent bar = new ServerBossEvent(
                record.owner(),
                barName,
                BossEvent.BossBarColor.PURPLE,
                BossEvent.BossBarOverlay.PROGRESS);
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(record.owner());
        if (owner != null) {
            bar.addPlayer(owner);
        }

        return new Boss(record.owner(), record.tier(), record.roll(), carried,
                record.aura(), entity, bar, record.fromSigil(),
                record.purchaseCounter(), record.seed());
    }

    /** Shows a reattached boss's bar to its owner when they reconnect. */
    private static void onJoin(net.minecraft.server.network.ServerGamePacketListenerImpl handler,
                               net.fabricmc.fabric.api.networking.v1.PacketSender sender,
                               MinecraftServer server) {
        ServerPlayer player = handler.getPlayer();
        if (player == null) {
            return;
        }
        Boss boss = getActive(player.getUUID());
        if (boss != null) {
            boss.bar().addPlayer(player);
        }
    }

    /** Despawns and refunds the departing player's active boss encounter. */
    private static void onDisconnect(net.minecraft.server.network.ServerGamePacketListenerImpl handler, MinecraftServer server) {
        ServerPlayer player = handler.getPlayer();
        if (player != null) {
            despawnFor(player);
        }
    }

    // ---- commands ---------------------------------------------------------

    private static int status(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.literal("Boss host is running.").withStyle(ChatFormatting.GRAY), false);
            return 1;
        }

        String today = todayKey();
        String claimed = FREE_CLAIMS.get(player.getUUID());
        boolean free = claimed == null || !today.equals(claimed);

        source.sendSuccess(() -> Component.literal("Daily boss (" + today + ")")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        source.sendSuccess(() -> Component.literal(free ? "  Free tier-I is available." : "  Free tier-I already claimed today.")
                .withStyle(free ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);

        Boss boss = getActive(player.getUUID());
        if (boss != null) {
            source.sendSuccess(() -> Component.literal("  Active: " + boss.roll().kamuIds().size()
                            + " kamu bearer")
                    .withStyle(ChatFormatting.DARK_PURPLE), false);
        } else {
            source.sendSuccess(() -> Component.literal("  /boss summon <tier> (1-4)")
                    .withStyle(ChatFormatting.WHITE), false);
        }
        return 1;
    }

    private static int summon(CommandSourceStack source, int tier) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only players can summon a boss.").withStyle(ChatFormatting.RED));
            return 0;
        }

        if (hasActive(player.getUUID())) {
            source.sendFailure(Component.literal("You already have an active boss.").withStyle(ChatFormatting.RED));
            return 0;
        }

        int radius = KamuTotemsConfig.i("boss", "no_summon_radius", 24);
        BlockPos spawn = player.level().getRespawnData().pos();
        double dist = player.position().distanceTo(Vec3.atCenterOf(spawn));
        if (dist < radius) {
            source.sendFailure(Component.literal("You are too close to spawn.").withStyle(ChatFormatting.RED));
            return 0;
        }

        if (tier == 1) {
            String today = todayKey();
            String claimed = FREE_CLAIMS.get(player.getUUID());
            if (claimed != null && today.equals(claimed)) {
                source.sendFailure(Component.literal("You have already claimed today's free boss.").withStyle(ChatFormatting.RED));
                return 0;
            }
            // BossRoll.forDate is core; covered by the core suite.
            long seed = 31L * today.hashCode() + WORLD_SEED;
            BossRoll roll = BossRoll.forDate(today, WORLD_SEED, CATALOG);
            EntityType<?> type = Sigil.resolveEntityType(seed);
            Boss boss = Boss.spawn(player.level(),
                    player.position().add(player.getLookAngle().scale(2.0)),
                    player.getYRot(), player, 1, roll, false, 0, seed, type, CATALOG);
            if (boss == null) {
                source.sendFailure(Component.literal("The daily boss could not be called.").withStyle(ChatFormatting.RED));
                return 0;
            }
            FREE_CLAIMS.put(player.getUUID(), today);
            track(boss);
            source.sendSuccess(() -> Component.literal("A free bearer of kamu answers your challenge.")
                    .withStyle(ChatFormatting.DARK_PURPLE), false);
            save();
            return 1;
        }

        // Tiers II-IV require a rolled sigil of that tier.
        int sigilSlot = findRolledSigil(player, tier);
        if (sigilSlot < 0) {
            source.sendFailure(Component.literal("You need a rolled tier-" + tier + " sigil.").withStyle(ChatFormatting.RED));
            return 0;
        }

        ItemStack stack = player.getInventory().getItem(sigilSlot);
        if (stack.getItem() == Items.ECHO_SHARD) {
            stack = Sigil.migrate(stack, CATALOG);
            player.getInventory().setItem(sigilSlot, stack);
        }

        BossRoll roll = Sigil.rollFrom(stack, CATALOG);
        if (roll == null) {
            source.sendFailure(Component.literal("That sigil is unreadable; it will be re-rolled.").withStyle(ChatFormatting.RED));
            return 0;
        }

        EntityType<?> type = Sigil.typeOf(stack);
        Boss boss = Boss.spawn(player.level(),
                player.position().add(player.getLookAngle().scale(2.0)),
                player.getYRot(), player, tier, roll, true,
                Sigil.counter(stack), Sigil.seed(stack), type, CATALOG);
        if (boss == null) {
            source.sendFailure(Component.literal("The sigil failed to call a boss.").withStyle(ChatFormatting.RED));
            return 0;
        }

        stack.shrink(1);
        if (stack.isEmpty()) {
            player.getInventory().setItem(sigilSlot, ItemStack.EMPTY);
        }
        track(boss);
        source.sendSuccess(() -> Component.literal("A tier-" + tier + " bearer of "
                        + roll.kamuIds().size() + " kamu answers the sigil.")
                .withStyle(ChatFormatting.DARK_PURPLE), false);
        return 1;
    }

    private static int cleanup(CommandSourceStack source) {
        int count = BY_ENTITY.size();
        for (Boss boss : new java.util.ArrayList<>(BY_ENTITY.values())) {
            if (boss.fromSigil()) {
                refund(boss, null);
            }
            boss.removeBarAll();
            forgetRecord(boss.entity());
            boss.entity().discard();
        }
        BY_ENTITY.clear();
        BY_PLAYER.clear();
        source.sendSuccess(() -> Component.literal("Despawned " + count + " boss(es).").withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    // ---- helpers ----------------------------------------------------------

    private static Boss getActive(UUID player) {
        UUID entityId = BY_PLAYER.get(player);
        return entityId == null ? null : BY_ENTITY.get(entityId);
    }

    private static int findRolledSigil(ServerPlayer player, int tier) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (Sigil.isRolled(stack) && Sigil.tier(stack) == tier) {
                return i;
            }
        }
        return -1;
    }

    private static void despawnAndRefundAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            despawnFor(player);
        }
    }

    private static void despawnFor(ServerPlayer player) {
        Boss boss = getActive(player.getUUID());
        if (boss == null) {
            return;
        }
        if (boss.fromSigil()) {
            refund(boss, player);
        }
        boss.removeBarAll();
        forgetRecord(boss.entity());
        boss.entity().discard();
        BY_ENTITY.remove(boss.entity().getUUID());
        BY_PLAYER.remove(player.getUUID());
    }

    private static void refund(Boss boss, ServerPlayer player) {
        if (player == null) {
            return;
        }
        ItemStack sigil = Sigil.makeRolledSigil(boss.tier(), boss.roll(),
                boss.seed(), boss.purchaseCounter(), CATALOG);
        if (!player.getInventory().add(sigil)) {
            player.drop(sigil, false);
            LOG.info("Refunded sigil for {} dropped at feet", player.getName().getString());
        }
    }

    /** Persists a boss's identity so a crash restart can reattach it. */
    private static void writeRecord(Boss boss) {
        if (!(boss.entity().level() instanceof ServerLevel level)) {
            return;
        }
        List<String> kamuIds = boss.kamu().stream().map(Kamu::id).toList();
        BossRecords.forLevel(level).put(new BossRecords.BossRecord(
                boss.entity().getUUID(),
                boss.owner(),
                boss.tier(),
                boss.roll(),
                kamuIds,
                boss.aura(),
                boss.fromSigil(),
                boss.purchaseCounter(),
                boss.seed()));
    }

    /** Drops the persisted identity of a boss that is dead, refunded, or gone. */
    private static void forgetRecord(Entity entity) {
        if (entity.level() instanceof ServerLevel level) {
            BossRecords.forLevel(level).remove(entity.getUUID());
        }
    }

    private static void save() {
        JsonObject data = new JsonObject();
        JsonObject free = new JsonObject();
        for (Map.Entry<UUID, String> e : FREE_CLAIMS.entrySet()) {
            free.addProperty(e.getKey().toString(), e.getValue());
        }
        data.add("free", free);

        JsonObject counters = new JsonObject();
        for (Map.Entry<UUID, Integer> e : SIGIL_COUNTERS.entrySet()) {
            counters.addProperty(e.getKey().toString(), e.getValue());
        }
        data.add("counters", counters);

        Persist.save("boss_state.json", data);
    }

    private static void readState(JsonObject data) {
        if (data.has("free") && data.get("free").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : data.getAsJsonObject("free").entrySet()) {
                try {
                    FREE_CLAIMS.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
                } catch (RuntimeException ignored) {}
            }
        }
        if (data.has("counters") && data.get("counters").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : data.getAsJsonObject("counters").entrySet()) {
                try {
                    SIGIL_COUNTERS.put(UUID.fromString(e.getKey()), e.getValue().getAsInt());
                } catch (RuntimeException ignored) {}
            }
        }
    }

    /** Exposed for Sigil.makeFirstTrial, which mints the daily reward sigil. */
    static long worldSeed() {
        return WORLD_SEED;
    }

    static String todayKey() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC).minusHours(ROLLOVER_HOUR);
        return now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
