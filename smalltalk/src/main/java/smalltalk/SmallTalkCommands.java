package smalltalk;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import smalltalk.dialogue.HintSelector;
import smalltalk.identity.Identity;
import smalltalk.identity.IdentityDeriver;
import smalltalk.interaction.DialogHold;
import smalltalk.interaction.DialogScreens;
import smalltalk.interaction.GiftHandler;
import smalltalk.task.FetchTaskLogic;
import smalltalk.task.Task;
import smalltalk.task.TaskRegistry;
import smalltalk.task.TaskState;

import java.util.UUID;

/**
 * The {@code /smalltalk} command tree. {@code whois} is the debug command
 * from SPEC.md section 16 step 1. {@code trade} and {@code gift} are what
 * the dialogue menu's buttons actually run, and {@code gift-confirm} is
 * what the gift dialog's own Yes button runs -- see
 * {@code interaction.DialogScreens} for why commands rather than
 * {@code custom_click_action}.
 */
public final class SmallTalkCommands {

    /** How far a stale dialog button (walked away since opening it) is still honoured. */
    private static final double INTERACT_MAX_DISTANCE_SQ = 8.0 * 8.0;

    private SmallTalkCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("smalltalk")
                        .then(Commands.literal("whois")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(ctx -> whois(ctx.getSource(),
                                                EntityArgument.getEntity(ctx, "target")))))
                        .then(Commands.literal("hint")
                                .then(Commands.argument("villager", StringArgumentType.word())
                                        .executes(ctx -> hint(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "villager")))))
                        .then(Commands.literal("trade")
                                .then(Commands.argument("villager", StringArgumentType.word())
                                        .executes(ctx -> trade(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "villager")))))
                        .then(Commands.literal("gift")
                                .then(Commands.argument("villager", StringArgumentType.word())
                                        .executes(ctx -> gift(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "villager")))))
                        .then(Commands.literal("gift-confirm")
                                .then(Commands.argument("villager", StringArgumentType.word())
                                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                                .executes(ctx -> giftConfirm(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "villager"),
                                                        StringArgumentType.getString(ctx, "item"))))))
                        .then(Commands.literal("request-accept")
                                .then(Commands.argument("villager", StringArgumentType.word())
                                        .then(Commands.argument("taskId", StringArgumentType.word())
                                                .executes(ctx -> requestAccept(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "villager"),
                                                        StringArgumentType.getString(ctx, "taskId"))))))
                        .then(Commands.literal("request-decline")
                                .then(Commands.argument("villager", StringArgumentType.word())
                                        .then(Commands.argument("taskId", StringArgumentType.word())
                                                .executes(ctx -> requestDecline(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "villager"),
                                                        StringArgumentType.getString(ctx, "taskId"))))))
                        .then(Commands.literal("request-complete")
                                .then(Commands.argument("villager", StringArgumentType.word())
                                        .then(Commands.argument("taskId", StringArgumentType.word())
                                                .executes(ctx -> requestComplete(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "villager"),
                                                        StringArgumentType.getString(ctx, "taskId"))))))));
    }

    private static int whois(CommandSourceStack source, Entity target) {
        if (!(target instanceof Villager villager)) {
            source.sendFailure(Component.literal("That's not a villager."));
            return 0;
        }
        Identity identity = IdentityDeriver.derive(villager.getUUID());
        source.sendSuccess(() -> Component.literal(IdentityDeriver.displayName(villager)
                        + "  [" + identity.personality() + "]  likes " + identity.likedCategory()
                        + ", dislikes " + identity.dislikedCategory())
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int hint(CommandSourceStack source, String villagerUuid) {
        Villager villager = nearbyVillager(source, villagerUuid);
        if (villager == null) {
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        Identity identity = IdentityDeriver.derive(villager.getUUID());
        String line = HintSelector.select(identity.personality(), identity.likedCategory(), player.getRandom());
        DialogHold.hold(villager, player);
        player.openDialog(DialogScreens.hint(villager, line));
        return 1;
    }

    private static int trade(CommandSourceStack source, String villagerUuid) {
        Villager villager = nearbyVillager(source, villagerUuid);
        if (villager == null) {
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        DialogHold.release(villager);
        villager.setTradingPlayer(player);
        villager.openTradingScreen(player, villager.getDisplayName(), villager.getVillagerData().level());
        return 1;
    }

    private static int gift(CommandSourceStack source, String villagerUuid) {
        Villager villager = nearbyVillager(source, villagerUuid);
        if (villager == null) {
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        DialogHold.hold(villager, player);
        player.openDialog(DialogScreens.giftPicker(villager, player));
        return 1;
    }

    private static int giftConfirm(CommandSourceStack source, String villagerUuid, String itemId) {
        Villager villager = nearbyVillager(source, villagerUuid);
        if (villager == null) {
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        boolean result = GiftHandler.confirmGift(villager, level, player, itemId);
        DialogHold.release(villager);
        return result ? 1 : 0;
    }

    /** SPEC.md section 12.2's Accept button: OFFERED -&gt; ACCEPTED, re-verified from scratch. */
    private static int requestAccept(CommandSourceStack source, String villagerUuid, String taskIdStr) {
        Villager villager = nearbyVillager(source, villagerUuid);
        ServerPlayer player = source.getPlayer();
        if (villager == null || player == null || !(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        UUID taskId = parseUuid(taskIdStr);
        TaskRegistry registry = TaskRegistry.of(level);
        Task task = taskId == null ? null : registry.find(taskId).orElse(null);
        long tick = level.getGameTime();

        if (!isValidRequest(task, villager, player, tick, TaskState.OFFERED)) {
            DialogHold.release(villager);
            player.sendSystemMessage(Component.literal("That offer isn't there anymore.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }

        registry.update(withState(task, TaskState.ACCEPTED));
        DialogHold.release(villager);
        player.sendSystemMessage(Component.literal(
                        IdentityDeriver.displayName(villager) + " will be glad when you bring that by.")
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    /** SPEC.md section 12.2's "Not now" button: OFFERED -&gt; ABANDONED, re-verified from scratch. */
    private static int requestDecline(CommandSourceStack source, String villagerUuid, String taskIdStr) {
        Villager villager = nearbyVillager(source, villagerUuid);
        ServerPlayer player = source.getPlayer();
        if (villager == null || player == null || !(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        UUID taskId = parseUuid(taskIdStr);
        TaskRegistry registry = TaskRegistry.of(level);
        Task task = taskId == null ? null : registry.find(taskId).orElse(null);
        long tick = level.getGameTime();

        if (!isValidRequest(task, villager, player, tick, TaskState.OFFERED)) {
            DialogHold.release(villager);
            return 0;
        }

        registry.update(withState(task, TaskState.ABANDONED));
        DialogHold.release(villager);
        return 1;
    }

    /**
     * SPEC.md sections 12.2/12.4's completion confirm: re-verifies the task
     * (untrusted payload) and, separately, the player's inventory at confirm
     * time -- not just when the completion dialog opened (same discipline as
     * {@code gift-confirm}, SPEC.md section 6.2).
     */
    private static int requestComplete(CommandSourceStack source, String villagerUuid, String taskIdStr) {
        Villager villager = nearbyVillager(source, villagerUuid);
        ServerPlayer player = source.getPlayer();
        if (villager == null || player == null || !(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        UUID taskId = parseUuid(taskIdStr);
        TaskRegistry registry = TaskRegistry.of(level);
        Task task = taskId == null ? null : registry.find(taskId).orElse(null);
        long tick = level.getGameTime();

        if (!isValidRequest(task, villager, player, tick, TaskState.ACCEPTED)) {
            DialogHold.release(villager);
            player.sendSystemMessage(Component.literal("That request isn't there anymore.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        if (!FetchTaskLogic.hasItems(player, task)) {
            DialogHold.release(villager);
            player.sendSystemMessage(Component.literal("You don't have those to hand in anymore.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }

        FetchTaskLogic.applyReward(villager, level, player, task, tick);
        registry.update(withState(task, TaskState.COMPLETE));
        DialogHold.release(villager);
        return 1;
    }

    /** The payload is untrusted at every step (SPEC.md section 12.2) -- exists, expected state, this player, not expired. */
    private static boolean isValidRequest(Task task, Villager villager, ServerPlayer player, long tick, TaskState expected) {
        return task != null
                && task.state() == expected
                && task.issuer().equals(villager.getUUID())
                && task.assignee().equals(player.getUUID())
                && tick <= task.expiresTick();
    }

    private static Task withState(Task task, TaskState state) {
        return new Task(task.taskId(), task.issuer(), task.assignee(), task.type(), task.payload(),
                task.issuedTick(), task.expiresTick(), state);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Resolves and re-verifies the villager a dialog button named -- the payload is untrusted (SPEC.md section 12.2's discipline). */
    private static Villager nearbyVillager(CommandSourceStack source, String villagerUuid) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return null;
        }
        UUID id;
        try {
            id = UUID.fromString(villagerUuid);
        } catch (IllegalArgumentException e) {
            return null;
        }

        Entity entity = player.level().getEntityInAnyDimension(id);
        if (!(entity instanceof Villager villager) || !villager.isAlive()) {
            source.sendFailure(Component.literal("They've wandered off."));
            return null;
        }
        if (villager.distanceToSqr(player) > INTERACT_MAX_DISTANCE_SQ) {
            source.sendFailure(Component.literal("Too far away now."));
            return null;
        }
        return villager;
    }
}
