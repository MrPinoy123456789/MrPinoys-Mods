package kamutotems;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import kamutotems.core.KamuCatalog;
import kamutotems.core.QuestSegment;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.Map;

/**
 * Host for the daily three-segment quest chain and its streak.
 */
public final class QuestHost {

    private static KamuCatalog CATALOG;
    private static long WORLD_SEED;
    private static int ROLLOVER_HOUR;

    /**
     * True once the daily chain has actually been initialised.
     *
     * <p>The quest host stands down entirely when the older `dailyquests` mod is
     * present (KamuTotemsMod), so DailyChain.init() never runs and its catalog
     * stays null. The scan tap, however, is still called from TotemHost --
     * because the per-day block dedupe lives here and assigned quests share it.
     * Without this flag that path NPEs on the first scan.
     */
    private static boolean dailyChainReady = false;

    private QuestHost() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(QuestHost::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> DailyChain.save());
        ServerLivingEntityEvents.AFTER_DEATH.register(QuestHost::onKill);
        ServerPlayConnectionEvents.JOIN.register(QuestHost::onJoin);
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("daily")
                .executes(ctx -> {
                    showDaily(ctx.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("turnin")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            Component problem = TurnIn.attempt(player);
                            if (problem != null) {
                                player.sendSystemMessage(problem);
                                return 0;
                            }
                            return 1;
                        }))
                .then(Commands.literal("top")
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> top(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "page"))))
                        .executes(ctx -> top(ctx.getSource(), 1)))
                .then(Commands.literal("help")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            src.sendSuccess(() -> Component.literal("Daily Quests")
                                    .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
                            src.sendSuccess(() -> Component.literal("  /daily  Today's chain").withStyle(ChatFormatting.WHITE), false);
                            src.sendSuccess(() -> Component.literal("  /daily turnin  Hand in an item").withStyle(ChatFormatting.WHITE), false);
                            src.sendSuccess(() -> Component.literal("  /daily top [page]  Streak leaderboard").withStyle(ChatFormatting.WHITE), false);
                            return 1;
                        })));
    }

    /**
     * Scanned block positions, per player, for the current day. Cleared when
     * the date key rolls over.
     *
     * <p><b>Why this exists.</b> Scanning is the one quest verb with no natural
     * cost -- a kill consumes a mob and a turn-in consumes items, but a block
     * can be right-clicked forever. Without a dedupe, "Scan 3 iron ore" is
     * satisfied by scanning the <em>same</em> ore three times, which is not a
     * quest, it is a button. Found in play, 2026-08-13.
     */
    private static final Map<UUID, Set<Long>> SCANNED = new HashMap<>();
    private static String scannedDay = "";

    /**
     * Public handler for the totem's scan verb, wired from {@code TotemHost}.
     *
     * <p>Each block position counts once per day. The position is the identity:
     * scanning a second, different iron ore is progress; scanning the same one
     * again is not.
     */
    /** True once DailyChain has been initialised. See the field's note. */
    public static boolean dailyChainReady() {
        return dailyChainReady;
    }

    public static boolean onScan(ServerPlayer player, Identifier blockId, BlockPos pos) {
        String today = DailyChain.todayKey();
        if (!today.equals(scannedDay)) {
            // Rollover. Derived from the date like everything else here, so a
            // restart cannot hand back a fresh set of scans.
            SCANNED.clear();
            scannedDay = today;
        }

        Set<Long> seen = SCANNED.computeIfAbsent(player.getUUID(), k -> new HashSet<>());
        if (!seen.add(pos.asLong())) {
            player.sendSystemMessage(Component.literal("This place is already known to you.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return false;
        }

        if (dailyChainReady) {
            DailyChain.tryAdvance(player, "scan",
                    Map.of("eventId", "block_scanned", "block", blockId.toString()));
        }
        // Assigned quests share the scan dedupe -- one dedupe, two consumers.
        // They are deliberately NOT gated on the daily chain: the outward API
        // other mods depend on must not die because `dailyquests` is installed.
        AssignedQuestHost.onScan(player, blockId, pos);
        return true;
    }

    private static void onServerStarted(MinecraftServer server) {
        CATALOG = KamuData.catalog();
        // Seed comes from ServerLevel.getSeed(); worldGenOptions() does not exist in 26.2.
        WORLD_SEED = server.overworld().getSeed();
        ROLLOVER_HOUR = KamuTotemsConfig.i("quest", "rollover_hour_utc", 0);
        DailyChain.init(CATALOG, WORLD_SEED, ROLLOVER_HOUR);
        DailyChain.load();
        dailyChainReady = true;
    }

    /** Converts a player-caused entity death into daily kill-quest progress. */
    private static void onKill(LivingEntity entity, DamageSource source) {
        if (!dailyChainReady || !(source.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        DailyChain.tryAdvance(player, "kill",
                Map.of("eventId", "entity_killed", "entity", entityId));
    }

    /** Refreshes the player's leaderboard identity and optionally announces today's chain. */
    private static void onJoin(net.minecraft.server.network.ServerGamePacketListenerImpl handler,
                               net.fabricmc.fabric.api.networking.v1.PacketSender sender,
                               MinecraftServer server) {
        ServerPlayer player = handler.getPlayer();
        DailyChain.seen(player);
        if (KamuTotemsConfig.b("quest", "announce_on_join", true)) {
            showDaily(player);
        }
    }

    private static void showDaily(ServerPlayer player) {
        DailyChain.PlayerDay day = DailyChain.dayFor(player);
        DailyChain.PlayerEntry entry = DailyChain.entryFor(player.getUUID());

        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(Component.literal("Daily chain (" + DailyChain.todayKey() + ")")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        List<QuestSegment> segments = day.chain().segments();
        List<Integer> counts = day.progress().counts();
        for (int i = 0; i < segments.size(); i++) {
            QuestSegment seg = segments.get(i);
            boolean complete = day.progress().segmentComplete(i, day.chain());
            int count = i < counts.size() ? counts.get(i) : 0;
            int total = seg.matcher().requiredCount();
            String text = (i + 1) + ". " + seg.displayText() + "  " + count + "/" + total;
            player.sendSystemMessage(Component.literal(text)
                    .withStyle(complete ? ChatFormatting.GREEN : ChatFormatting.WHITE));
        }

        if (day.completed()) {
            player.sendSystemMessage(Component.literal("Completed today. Come back tomorrow.")
                    .withStyle(ChatFormatting.GREEN));
        }

        player.sendSystemMessage(Component.literal("Streak: " + entry.streak().streak()
                        + " day" + (entry.streak().streak() == 1 ? "" : "s"))
                .withStyle(ChatFormatting.YELLOW));
    }

    private static int top(CommandSourceStack source, int page) {
        int perPage = 10;
        List<DailyChain.PlayerEntry> all = DailyChain.top(Integer.MAX_VALUE);
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        int clamped = Math.max(1, Math.min(page, pages));
        int offset = (clamped - 1) * perPage;
        boolean hasMore = all.size() > clamped * perPage;
        List<DailyChain.PlayerEntry> rows = all.subList(offset,
                Math.min(offset + perPage, all.size()));

        source.sendSuccess(() -> Component.literal("────────────────")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("Daily Streaks")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        source.sendSuccess(() -> Component.literal(
                        String.format("  %-18s %5s %5s", "Player", "Streak", "Total"))
                .withStyle(ChatFormatting.GRAY), false);

        int rank = offset;
        ServerPlayer viewer = source.getPlayer();
        String viewerName = viewer != null ? viewer.getName().getString() : "";
        for (DailyChain.PlayerEntry row : rows) {
            rank++;
            boolean isViewer = row.name() != null && row.name().equals(viewerName);
            String name = row.name() == null ? "?" : row.name();
            String line = String.format("%2d. %-17s %5d %5d",
                    rank, name, row.streak().streak(), row.totalDone());
            Component c = isViewer
                    ? Component.literal(line).withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal("  <- You").withStyle(ChatFormatting.YELLOW))
                    : Component.literal(line).withStyle(ChatFormatting.WHITE);
            source.sendSuccess(() -> c, false);
        }
        if (rows.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  No streaks yet.").withStyle(ChatFormatting.GRAY), false);
        }

        source.sendSuccess(() -> Component.literal(
                        "Page " + clamped + " of " + pages
                                + (hasMore ? " (next: /daily top " + (clamped + 1) + ")" : ""))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("────────────────")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }
}
