package kamutotems;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import kamutotems.core.AssignedQuest;
import kamutotems.core.AssignedQuests;
import kamutotems.core.BossRoll;
import kamutotems.core.KamuCatalog;
import kamutotems.core.QuestDefinition;
import kamutotems.core.QuestProgress;
import kamutotems.core.QuestReward;
import kamutotems.core.QuestSegment;
import kamutotems.core.QuestState;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Host for per-player assigned quests, given by items from other mods.
 *
 * <p>Exposes {@link #register()} and {@link #registerCommands(CommandDispatcher)}
 * for the master to wire. The only place this host reaches into another host is
 * the scan tap: {@link QuestHost#onScan} dispatches new scans here so the block
 * dedupe is shared with the daily chain.
 */
public final class AssignedQuestHost {

    private static final Logger LOG = LoggerFactory.getLogger("kamutotems");
    private static final String FILE = "assigned_quests.json";

    private static final Map<UUID, PlayerState> STATES = new HashMap<>();
    private static boolean loaded = false;

    private record QuestEntry(AssignedQuest quest, List<Boolean> paid) {}

    private record PlayerState(List<QuestEntry> quests) {}

    private AssignedQuestHost() {}

    public static void register() {
        QuestApiConfig.load(FabricLoader.getInstance().getConfigDir().resolve(KamuTotemsMod.MOD_ID));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> load());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> save());
        ServerTickEvents.END_SERVER_TICK.register(AssignedQuestHost::tickAll);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                retryPayments(handler.getPlayer()));
        ServerLivingEntityEvents.AFTER_DEATH.register(AssignedQuestHost::onKill);

        UseItemCallback.EVENT.register(QuestScroll::onUseItem);
        UseItemCallback.EVENT.register(BossStone::onUseItem);
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("quests")
                .executes(ctx -> list(ctx.getSource().getPlayerOrException()))
                .then(Commands.literal("turnin")
                        .executes(ctx -> turnIn(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("progress")
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .executes(ctx -> progress(ctx.getSource().getPlayerOrException(),
                                        IntegerArgumentType.getInteger(ctx, "index")))))
                .then(Commands.literal("abandon")
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .executes(ctx -> abandon(ctx.getSource().getPlayerOrException(),
                                        IntegerArgumentType.getInteger(ctx, "index")))))
                .then(Commands.literal("help")
                        .executes(ctx -> help(ctx.getSource()))));
    }

    /**
     * Called by {@link QuestHost#onScan} for every block position that has not
     * already been scanned today, so assigned quests share the same dedupe as
     * the daily chain.
     */
    public static void onScan(ServerPlayer player, Identifier blockId, BlockPos pos) {
        Map<String, String> event = Map.of(
                "eventId", "block_scanned",
                "block", blockId.toString());
        tryAdvance(player, "scan", event);
    }

    /** Called by {@link QuestScroll} when a player reads a quest scroll. */
    public static AssignedQuests.GrantResult attemptGrant(ServerPlayer player, String definitionId) {
        ensureLoaded();
        QuestDefinition def = QuestApiConfig.catalog().get(definitionId);
        if (def == null) {
            return new AssignedQuests.GrantResult(false, null,
                    "This scroll means nothing to you.");
        }

        PlayerState state = stateFor(player.getUUID());
        List<AssignedQuest> held = unwrap(state.quests());
        AssignedQuests.GrantResult result = AssignedQuests.grant(held, def, todayKey(),
                AssignedQuests.DEFAULT_MAX_ACTIVE);

        if (result.ok() && result.quest() != null) {
            state.quests().add(new QuestEntry(result.quest(), newPaidList(def.rewards().size())));
            save();
        }
        return result;
    }

    /** Advances the killer's first matching assigned kill quest after a living-entity death. */
    private static void onKill(LivingEntity entity, DamageSource source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        Map<String, String> event = Map.of(
                "eventId", "entity_killed",
                "entity", entityId);
        tryAdvance(player, "kill", event);
    }

    private static boolean tryAdvance(ServerPlayer player, String kind, Map<String, String> event) {
        ensureLoaded();
        PlayerState state = stateFor(player.getUUID());
        List<AssignedQuest> held = unwrap(state.quests());
        int questIndex = AssignedQuests.findMatching(held, QuestApiConfig.catalog(), kind, event);
        if (questIndex < 0) {
            return false;
        }

        QuestEntry entry = state.quests().get(questIndex);
        QuestDefinition def = QuestApiConfig.catalog().get(entry.quest().definitionId());
        if (def == null) {
            return false;
        }
        int segIndex = findMatchingSegment(entry.quest(), def, kind, event);
        if (segIndex < 0) {
            return false;
        }

        return advanceSegment(player, state, questIndex, segIndex, 1);
    }

    private static int findMatchingSegment(AssignedQuest quest, QuestDefinition def,
                                           String kind, Map<String, String> event) {
        for (int i = 0; i < def.segments().size(); i++) {
            QuestSegment seg = def.segments().get(i);
            if (!seg.kind().equals(kind) || !seg.matcher().matches(event)) {
                continue;
            }
            int count = i < quest.progress().counts().size()
                    ? quest.progress().counts().get(i)
                    : 0;
            if (count < seg.matcher().requiredCount()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean advanceSegment(ServerPlayer player, PlayerState state,
                                          int questIndex, int segIndex, int by) {
        QuestEntry entry = state.quests().get(questIndex);
        QuestDefinition def = QuestApiConfig.catalog().get(entry.quest().definitionId());
        if (def == null) {
            return false;
        }
        if (segIndex < 0 || segIndex >= def.segments().size()) {
            return false;
        }

        AssignedQuest before = entry.quest();
        AssignedQuest next = before.advance(segIndex, by, def);
        boolean justCompleted = !before.complete(def) && next.complete(def);

        state.quests().set(questIndex, new QuestEntry(next, entry.paid()));

        if (justCompleted) {
            player.sendSystemMessage(Component.literal("Quest complete: " + def.title())
                    .withStyle(ChatFormatting.GOLD));
            Chime.play(player, SoundEvents.NOTE_BLOCK_BELL, 0.35f, 1.0f);
            tryPayRewards(player, state.quests().get(questIndex), def, true);
            save();
        } else {
            QuestSegment seg = def.segments().get(segIndex);
            int count = segIndex < next.progress().counts().size()
                    ? next.progress().counts().get(segIndex)
                    : 0;
            player.sendSystemMessage(Component.literal(seg.displayText() + "  " + count + "/"
                            + seg.matcher().requiredCount())
                    .withStyle(ChatFormatting.YELLOW));
            Chime.play(player, SoundEvents.NOTE_BLOCK_HAT, 0.12f, 1.6f);
        }
        return true;
    }

    // ---- commands -----------------------------------------------------------

    private static int list(ServerPlayer player) {
        ensureLoaded();
        PlayerState state = stateFor(player.getUUID());
        player.sendSystemMessage(Component.literal("Your errands")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        int shown = 0;
        for (int i = 0; i < state.quests().size(); i++) {
            QuestEntry entry = state.quests().get(i);
            QuestState s = entry.quest().state();
            if (s != QuestState.ACTIVE && s != QuestState.COMPLETE) {
                continue;
            }
            shown++;
            QuestDefinition def = QuestApiConfig.catalog().get(entry.quest().definitionId());
            String title = def != null ? def.title() : entry.quest().definitionId();
            ChatFormatting color = s == QuestState.COMPLETE ? ChatFormatting.GREEN : ChatFormatting.WHITE;
            boolean allPaid = entry.paid().stream().allMatch(Boolean::booleanValue);
            String suffix = s == QuestState.COMPLETE
                    ? (allPaid ? " [paid]" : " [reward pending]")
                    : "";
            player.sendSystemMessage(Component.literal(shown + ". " + title + suffix)
                    .withStyle(color));
        }

        if (shown == 0) {
            player.sendSystemMessage(Component.literal("None.").withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }

    private static int progress(ServerPlayer player, int displayIndex) {
        ensureLoaded();
        PlayerState state = stateFor(player.getUUID());
        int actualIndex = actualIndex(state, displayIndex);
        if (actualIndex < 0) {
            player.sendSystemMessage(Component.literal("There is no errand at that number.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        QuestEntry entry = state.quests().get(actualIndex);
        QuestDefinition def = QuestApiConfig.catalog().get(entry.quest().definitionId());
        if (def == null) {
            player.sendSystemMessage(Component.literal("That errand is no longer defined.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        player.sendSystemMessage(Component.literal(def.title())
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        if (!def.sourceLabel().isBlank()) {
            player.sendSystemMessage(Component.literal(def.sourceLabel())
                    .withStyle(ChatFormatting.GRAY));
        }

        for (int i = 0; i < def.segments().size(); i++) {
            QuestSegment seg = def.segments().get(i);
            int count = i < entry.quest().progress().counts().size()
                    ? entry.quest().progress().counts().get(i)
                    : 0;
            int required = seg.matcher().requiredCount();
            boolean complete = count >= required;
            String line = (i + 1) + ". " + seg.displayText() + "  " + count + "/" + required;
            player.sendSystemMessage(Component.literal(line)
                    .withStyle(complete ? ChatFormatting.GREEN : ChatFormatting.WHITE));
        }

        if (!def.rewards().isEmpty()) {
            player.sendSystemMessage(Component.literal("Rewards:").withStyle(ChatFormatting.YELLOW));
            for (int i = 0; i < def.rewards().size(); i++) {
                QuestReward reward = def.rewards().get(i);
                boolean paid = i < entry.paid().size() && entry.paid().get(i);
                String line = "  " + (i + 1) + ". " + rewardDesc(reward)
                        + (paid ? " [paid]" : " [pending]");
                player.sendSystemMessage(Component.literal(line)
                        .withStyle(paid ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            }
        }

        if (entry.quest().state() == QuestState.COMPLETE) {
            retryPayments(player);
        }
        return 1;
    }

    private static int turnIn(ServerPlayer player) {
        ensureLoaded();
        PlayerState state = stateFor(player.getUUID());

        for (int qi = 0; qi < state.quests().size(); qi++) {
            QuestEntry entry = state.quests().get(qi);
            if (entry.quest().state() != QuestState.ACTIVE) {
                continue;
            }
            QuestDefinition def = QuestApiConfig.catalog().get(entry.quest().definitionId());
            if (def == null) {
                continue;
            }

            for (int si = 0; si < def.segments().size(); si++) {
                QuestSegment seg = def.segments().get(si);
                if (!"turn_in".equals(seg.kind())) {
                    continue;
                }
                int count = si < entry.quest().progress().counts().size()
                        ? entry.quest().progress().counts().get(si)
                        : 0;
                int required = seg.matcher().requiredCount();
                if (count >= required) {
                    continue;
                }
                String itemId = seg.matcher().predicate().get("item");
                if (itemId == null) {
                    continue;
                }
                Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
                if (item == null || item == Items.AIR) {
                    continue;
                }

                int needed = required - count;
                if (countInventory(player, item) < needed) {
                    continue;
                }

                removeItems(player, item, needed);
                if (advanceSegment(player, state, qi, si, needed)) {
                    save();
                    return 1;
                }

                // Advance failed despite having the items: refund so nothing is lost.
                ItemStack refund = new ItemStack(item, needed);
                if (!player.getInventory().add(refund)) {
                    player.drop(refund, false);
                }
            }
        }

        player.sendSystemMessage(Component.literal("You are not carrying anything your errands want.")
                .withStyle(ChatFormatting.RED));
        return 0;
    }

    private static int abandon(ServerPlayer player, int displayIndex) {
        ensureLoaded();
        PlayerState state = stateFor(player.getUUID());
        int actualIndex = actualIndex(state, displayIndex);
        if (actualIndex < 0) {
            player.sendSystemMessage(Component.literal("There is no errand at that number.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        QuestEntry entry = state.quests().get(actualIndex);
        if (entry.quest().state() != QuestState.ACTIVE) {
            player.sendSystemMessage(Component.literal("Only active errands can be abandoned.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        AssignedQuest next = new AssignedQuest(entry.quest().definitionId(),
                entry.quest().grantedDateKey(), entry.quest().progress(), QuestState.ABANDONED);
        state.quests().set(actualIndex, new QuestEntry(next, entry.paid()));
        save();
        player.sendSystemMessage(Component.literal("Errand abandoned.")
                .withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Assigned errands")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        source.sendSuccess(() -> Component.literal("  /quests              List active errands")
                .withStyle(ChatFormatting.WHITE), false);
        source.sendSuccess(() -> Component.literal("  /quests progress <#> Show details and reward status")
                .withStyle(ChatFormatting.WHITE), false);
        source.sendSuccess(() -> Component.literal("  /quests turnin       Hand in items an errand wants")
                .withStyle(ChatFormatting.WHITE), false);
        source.sendSuccess(() -> Component.literal("  /quests abandon <#>  Abandon an errand")
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ---- rewards ------------------------------------------------------------

    private static void tryPayRewards(ServerPlayer player, QuestEntry entry, QuestDefinition def, boolean notify) {
        List<Boolean> paid = entry.paid();
        int rewardCount = def.rewards().size();
        while (paid.size() < rewardCount) {
            paid.add(false);
        }

        boolean anyNewlyPaid = false;
        boolean anyFailed = false;
        for (int i = 0; i < rewardCount; i++) {
            if (paid.get(i)) {
                continue;
            }
            QuestReward reward = def.rewards().get(i);
            if (payReward(player, reward)) {
                paid.set(i, true);
                anyNewlyPaid = true;
            } else {
                anyFailed = true;
            }
        }

        if (anyNewlyPaid) {
            save();
        }
        if (anyFailed && notify) {
            player.sendSystemMessage(Component.literal("A reward could not be given and will be retried.")
                    .withStyle(ChatFormatting.RED));
        }
    }

    private static boolean payReward(ServerPlayer player, QuestReward reward) {
        ItemStack stack;
        switch (reward.kind()) {
            case "diamond" -> stack = new ItemStack(Items.DIAMOND, reward.amount());
            case "cobblestone" -> stack = new ItemStack(Items.COBBLESTONE, reward.amount());
            case "sigil" -> {
                int tier = Math.max(1, Math.min(4, reward.amount()));
                KamuCatalog catalog = KamuData.catalog();
                if (catalog == null) {
                    return false;
                }
                long seed = ((ServerLevel) player.level()).getRandom().nextLong();
                BossRoll roll = BossRoll.forSeed(seed, tier, catalog);
                if (roll == null) {
                    return false;
                }
                stack = Sigil.makeRolledSigil(tier, roll, seed, 0, catalog);
            }
            case "item" -> {
                if (reward.itemId() == null) {
                    return false;
                }
                Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(reward.itemId()));
                if (item == null || item == Items.AIR) {
                    return false;
                }
                stack = new ItemStack(item, reward.amount());
            }
            default -> {
                return false;
            }
        }

        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
            LOG.info("Quest reward for {} would not fit; dropped at feet",
                    player.getName().getString());
        }
        return true;
    }

    private static String rewardDesc(QuestReward reward) {
        String note = reward.note();
        if (note != null && !note.isBlank()) {
            return note;
        }
        return switch (reward.kind()) {
            case "diamond" -> reward.amount() + " diamond" + (reward.amount() == 1 ? "" : "s");
            case "cobblestone" -> reward.amount() + " cobblestone";
            case "sigil" -> "Sigil of Trial " + roman(reward.amount());
            case "item" -> reward.amount() + " " + (reward.itemId() == null ? "item" : reward.itemId());
            default -> reward.kind();
        };
    }

    // ---- persistence --------------------------------------------------------

    private static void load() {
        STATES.clear();
        loaded = true;
        JsonObject data = Persist.load(FILE);
        if (data == null || !data.has("players") || !data.get("players").isJsonObject()) {
            return;
        }
        JsonObject players = data.getAsJsonObject("players");
        for (Map.Entry<String, JsonElement> e : players.entrySet()) {
            try {
                UUID id = UUID.fromString(e.getKey());
                PlayerState state = readPlayerState(e.getValue().getAsJsonObject());
                STATES.put(id, state);
            } catch (RuntimeException ex) {
                LOG.error("Failed to load assigned quest record for {}; skipped", e.getKey(), ex);
            }
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        JsonObject players = new JsonObject();
        for (Map.Entry<UUID, PlayerState> e : STATES.entrySet()) {
            players.add(e.getKey().toString(), writePlayerState(e.getValue()));
        }
        root.add("players", players);
        Persist.save(FILE, root);
    }

    private static PlayerState readPlayerState(JsonObject o) {
        List<QuestEntry> quests = new ArrayList<>();
        if (o.has("quests") && o.get("quests").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("quests")) {
                try {
                    quests.add(readQuestEntry(el.getAsJsonObject()));
                } catch (RuntimeException ex) {
                    LOG.error("Failed to read a quest entry; skipped", ex);
                }
            }
        }
        return new PlayerState(quests);
    }

    private static QuestEntry readQuestEntry(JsonObject o) {
        String definitionId = o.get("definitionId").getAsString();
        String grantedDateKey = o.get("grantedDateKey").getAsString();

        JsonObject progressObj = o.getAsJsonObject("progress");
        String dateKey = progressObj.get("dateKey").getAsString();
        List<Integer> counts = new ArrayList<>();
        if (progressObj.has("counts") && progressObj.get("counts").isJsonArray()) {
            for (JsonElement e : progressObj.getAsJsonArray("counts")) {
                counts.add(e.getAsInt());
            }
        }
        QuestProgress progress = new QuestProgress(dateKey, counts);
        QuestState state = QuestState.valueOf(o.get("state").getAsString());
        AssignedQuest quest = new AssignedQuest(definitionId, grantedDateKey, progress, state);

        List<Boolean> paid = new ArrayList<>();
        if (o.has("paid") && o.get("paid").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("paid")) {
                paid.add(e.getAsBoolean());
            }
        }
        QuestDefinition def = QuestApiConfig.catalog().get(definitionId);
        int expected = def != null ? def.rewards().size() : paid.size();
        while (paid.size() < expected) {
            paid.add(false);
        }
        if (paid.size() > expected && expected >= 0) {
            paid = paid.subList(0, expected);
        }
        return new QuestEntry(quest, new ArrayList<>(paid));
    }

    private static JsonObject writePlayerState(PlayerState state) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (QuestEntry entry : state.quests()) {
            JsonObject qo = new JsonObject();
            qo.addProperty("definitionId", entry.quest().definitionId());
            qo.addProperty("grantedDateKey", entry.quest().grantedDateKey());

            JsonObject po = new JsonObject();
            po.addProperty("dateKey", entry.quest().progress().dateKey());
            JsonArray counts = new JsonArray();
            for (int c : entry.quest().progress().counts()) {
                counts.add(c);
            }
            po.add("counts", counts);
            qo.add("progress", po);

            qo.addProperty("state", entry.quest().state().name());

            JsonArray paid = new JsonArray();
            for (boolean p : entry.paid()) {
                paid.add(p);
            }
            qo.add("paid", paid);
            arr.add(qo);
        }
        o.add("quests", arr);
        return o;
    }

    // ---- helpers ------------------------------------------------------------

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static PlayerState stateFor(UUID id) {
        return STATES.computeIfAbsent(id, k -> new PlayerState(new ArrayList<>()));
    }

    /**
     * The quests a player is currently carrying, in the order they were granted.
     * Read-only view for the station's Quests panel.
     */
    public static List<AssignedQuest> heldBy(ServerPlayer player) {
        return List.copyOf(unwrap(stateFor(player.getUUID()).quests()));
    }

    /** The loaded definition for an assigned quest, or {@code null}. */
    public static QuestDefinition definitionOf(AssignedQuest quest) {
        return quest == null ? null : QuestApiConfig.catalog().get(quest.definitionId());
    }

    private static List<AssignedQuest> unwrap(List<QuestEntry> entries) {
        List<AssignedQuest> out = new ArrayList<>(entries.size());
        for (QuestEntry e : entries) {
            out.add(e.quest());
        }
        return out;
    }

    private static List<Boolean> newPaidList(int size) {
        List<Boolean> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(false);
        }
        return list;
    }

    private static int actualIndex(PlayerState state, int displayIndex) {
        if (displayIndex < 1) {
            return -1;
        }
        int seen = 0;
        for (int i = 0; i < state.quests().size(); i++) {
            QuestState s = state.quests().get(i).quest().state();
            if (s == QuestState.ACTIVE || s == QuestState.COMPLETE) {
                seen++;
                if (seen == displayIndex) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int countInventory(ServerPlayer player, Item item) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void removeItems(ServerPlayer player, Item item, int wanted) {
        int remaining = wanted;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.is(item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            player.getInventory().removeItem(i, take);
            remaining -= take;
        }
    }

    private static void retryPayments(ServerPlayer player) {
        PlayerState state = STATES.get(player.getUUID());
        if (state == null) {
            return;
        }
        for (QuestEntry entry : state.quests()) {
            if (entry.quest().state() == QuestState.COMPLETE) {
                QuestDefinition def = QuestApiConfig.catalog().get(entry.quest().definitionId());
                if (def != null) {
                    tryPayRewards(player, entry, def, false);
                }
            }
        }
    }

    private static void tickAll(MinecraftServer server) {
        ensureLoaded();
        String today = todayKey();
        boolean changed = false;

        for (PlayerState state : STATES.values()) {
            List<AssignedQuest> held = unwrap(state.quests());
            List<AssignedQuest> updated = AssignedQuests.tick(held, QuestApiConfig.catalog(), today);
            boolean stateChanged = updated.size() != held.size();
            for (int i = 0; !stateChanged && i < held.size(); i++) {
                if (!held.get(i).equals(updated.get(i))) {
                    stateChanged = true;
                }
            }
            if (stateChanged) {
                List<QuestEntry> next = new ArrayList<>(updated.size());
                for (int i = 0; i < updated.size(); i++) {
                    AssignedQuest uq = updated.get(i);
                    List<Boolean> paid = i < state.quests().size()
                            ? state.quests().get(i).paid()
                            : newPaidList(QuestApiConfig.catalog().get(uq.definitionId()) != null
                                    ? QuestApiConfig.catalog().get(uq.definitionId()).rewards().size()
                                    : 0);
                    next.add(new QuestEntry(uq, paid));
                }
                state.quests().clear();
                state.quests().addAll(next);
                changed = true;
            }
        }

        if (changed) {
            save();
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            retryPayments(player);
        }
    }

    private static String todayKey() {
        int rolloverHour = KamuTotemsConfig.i("quest", "rollover_hour_utc", 0);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC).minusHours(rolloverHour);
        return now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> String.valueOf(n);
        };
    }
}
