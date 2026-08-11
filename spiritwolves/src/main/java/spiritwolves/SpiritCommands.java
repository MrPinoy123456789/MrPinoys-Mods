package spiritwolves;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@code /spiritwolves} -- info, release, verb management (delegated to
 * {@link VerbCommands}), and admin helpers. SPEC.md sections 16.5, 16.8, 18.3.
 */
public final class SpiritCommands {

    private static final long RELEASE_CONFIRM_WINDOW_MILLIS = TimeUnit.SECONDS.toMillis(30);

    /** Player UUID -> millis at which their release confirmation expires. */
    private static final Map<UUID, Long> pendingRelease = new HashMap<>();

    private SpiritCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("spiritwolves")

                        .then(Commands.literal("info")
                                .executes(ctx -> info(ctx.getSource().getPlayerOrException())))

                        .then(VerbCommands.build())

                        .then(Commands.literal("release")
                                .executes(ctx -> releasePrompt(ctx.getSource().getPlayerOrException()))
                                .then(Commands.literal("confirm")
                                        .executes(ctx -> releaseConfirm(ctx.getSource().getPlayerOrException()))))

                        .then(Commands.literal("admin")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.literal("stone")
                                        .then(Commands.argument("targets", EntityArgument.players())
                                                .executes(ctx -> adminStone(
                                                        EntityArgument.getPlayers(ctx, "targets"), 1))
                                                .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64))
                                                        .executes(ctx -> adminStone(
                                                                EntityArgument.getPlayers(ctx, "targets"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(Commands.literal("wipe")
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .executes(ctx -> adminWipe(EntityArgument.getPlayer(ctx, "target")))))
                                .then(Commands.literal("souls")
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .then(Commands.argument("amount", com.mojang.brigadier.arguments.LongArgumentType.longArg(0))
                                                        .executes(ctx -> adminSouls(
                                                                EntityArgument.getPlayer(ctx, "target"),
                                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "amount")))))) )));
    }

    // ---- info ---------------------------------------------------------------

    private static int info(ServerPlayer player) {
        WolfRecord record = PlayerWolfRegistry.get(player.getUUID());
        if (record == null) {
            player.sendSystemMessage(Component.literal("You have no spirit wolf.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }

        String name = record.wolfName != null ? record.wolfName : "Your wolf";
        long level = Souls.levelFor(record.souls);
        player.sendSystemMessage(Component.literal(
                        name + " -- Level " + level + " -- " + record.souls + " souls")
                .withStyle(ChatFormatting.GOLD));

        ItemStack held = player.getMainHandItem();
        if (SpiritStone.is(held) && SpiritStone.isBound(held)) {
            int charges = SpiritStone.chargesRemaining(held);
            player.sendSystemMessage(Component.literal("Charges: " + charges + "/" + SpiritStone.maxCharges())
                    .withStyle(charges > 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
        }

        player.sendSystemMessage(Component.literal(
                        record.summoned ? "The wolf is out." : "The wolf is resting in the stone.")
                .withStyle(ChatFormatting.GRAY));

        int streak = liveStreak(player, record);
        player.sendSystemMessage(Component.literal(String.format(
                        "Streak: %d  (size %.2fx, +%.1f attack, speed %.2fx)",
                        streak, Streak.scaleFor(streak), Streak.damageFor(streak), Streak.speedFor(streak)))
                .withStyle(streak > 0 ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
        player.sendSystemMessage(Component.literal("Best streak: " + record.bestStreak)
                .withStyle(ChatFormatting.GRAY));

        net.minecraft.world.entity.animal.wolf.Wolf liveWolf = liveWolf(player, record);
        if (liveWolf != null) {
            net.minecraft.world.entity.ai.attributes.AttributeInstance attackAttr =
                    liveWolf.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            if (attackAttr != null) {
                player.sendSystemMessage(Component.literal(String.format(
                                "Live attack damage: %.2f  (base %.2f)",
                                attackAttr.getValue(), attackAttr.getBaseValue()))
                        .withStyle(ChatFormatting.RED));
            }
            for (net.minecraft.world.effect.MobEffectInstance effect : liveWolf.getActiveEffects()) {
                player.sendSystemMessage(Component.literal(
                                "Effect: " + effect.getEffect().value().getDisplayName().getString()
                                        + " " + (effect.getAmplifier() + 1) + " (" + (effect.getDuration() / 20) + "s)")
                        .withStyle(ChatFormatting.DARK_PURPLE));
            }
        }

        StringBuilder equipped = new StringBuilder();
        for (Verbs.Verb verb : Verbs.ALL) {
            WolfRecord.VerbRecord verbRecord = record.verbs.get(verb.id());
            if (verbRecord != null && verbRecord.equipped) {
                if (equipped.length() > 0) {
                    equipped.append(", ");
                }
                equipped.append(verb.displayName()).append(' ').append(Verbs.roman(verbRecord.tier));
            }
        }
        player.sendSystemMessage(Component.literal(
                        "Verbs: " + (equipped.length() == 0 ? "none equipped" : equipped))
                .withStyle(ChatFormatting.LIGHT_PURPLE));

        // The journal can outgrow the lore's six-line cap -- info prints all of it.
        for (String entry : record.journalLines()) {
            player.sendSystemMessage(Component.literal(entry)
                    .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
        }
        return 1;
    }

    /** The streak of the wolf this player has out, or 0 if it isn't out. */
    private static int liveStreak(ServerPlayer player, WolfRecord record) {
        net.minecraft.world.entity.animal.wolf.Wolf wolf = liveWolf(player, record);
        return wolf == null ? 0 : Streak.current(wolf);
    }

    /** The wolf's live entity if it's currently summoned, or null. */
    private static net.minecraft.world.entity.animal.wolf.Wolf liveWolf(ServerPlayer player, WolfRecord record) {
        if (!record.summoned || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return null;
        }
        return Summoning.findWolf(level, record.wolfUuid);
    }

    // ---- release --------------------------------------------------------------

    private static int releasePrompt(ServerPlayer player) {
        WolfRecord record = PlayerWolfRegistry.get(player.getUUID());
        if (record == null) {
            player.sendSystemMessage(Component.literal("You have no spirit wolf.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        String name = record.wolfName != null ? record.wolfName : "Your wolf";
        pendingRelease.put(player.getUUID(), System.currentTimeMillis() + RELEASE_CONFIRM_WINDOW_MILLIS);

        player.sendSystemMessage(Component.literal(
                        "Release " + name + "? Its soul -- and everything it has become -- will be lost "
                                + "forever. " + record.souls + " souls. This cannot be undone.")
                .withStyle(ChatFormatting.RED));

        MutableComponent button = Component.literal("[ Release ]").withStyle(style -> style
                .withColor(ChatFormatting.RED)
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/spiritwolves release confirm"))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("Confirm -- this cannot be undone."))));
        player.sendSystemMessage(button);
        return 1;
    }

    private static int releaseConfirm(ServerPlayer player) {
        Long expiry = pendingRelease.remove(player.getUUID());
        if (expiry == null || System.currentTimeMillis() > expiry) {
            player.sendSystemMessage(Component.literal(
                            "Release request expired. Run /spiritwolves release again.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }

        WolfRecord record = PlayerWolfRegistry.get(player.getUUID());
        if (record == null) {
            player.sendSystemMessage(Component.literal("You have no spirit wolf.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        String name = record.wolfName != null ? record.wolfName : "Your wolf";

        if (record.summoned && player.level() instanceof net.minecraft.server.level.ServerLevel level) {
            net.minecraft.world.entity.animal.wolf.Wolf wolf = Summoning.findWolf(level, record.wolfUuid);
            if (wolf != null) {
                UUID wolfUuid = wolf.getUUID();
                Streak.forget(wolfUuid);
                VerbProcs.forget(wolfUuid);
                RecallLock.forget(wolfUuid);
                Senses.forget(wolfUuid);
                wolf.discard();
            }
        }

        PlayerWolfRegistry.remove(player.getUUID());
        Tracker.forEachBoundStone(player, SpiritStone::revertToUnbound);

        player.sendSystemMessage(Component.literal(name + "'s soul slips free. The stone is silent.")
                .withStyle(ChatFormatting.GRAY));
        Chime.recalled(player);
        return 1;
    }

    // ---- admin ----------------------------------------------------------------

    private static int adminStone(java.util.Collection<ServerPlayer> targets, int count) {
        for (ServerPlayer target : targets) {
            ItemStack stack = SpiritStone.createUnbound(count);
            if (!target.getInventory().add(stack)) {
                target.drop(stack, false);
            }
            target.sendSystemMessage(Component.literal("You received " + count + " unbound Spirit Stone(s).")
                    .withStyle(ChatFormatting.AQUA));
        }
        return targets.size();
    }

    private static int adminWipe(ServerPlayer target) {
        WolfRecord record = PlayerWolfRegistry.get(target.getUUID());
        if (record != null && record.summoned && target.level() instanceof net.minecraft.server.level.ServerLevel level) {
            net.minecraft.world.entity.animal.wolf.Wolf wolf = Summoning.findWolf(level, record.wolfUuid);
            if (wolf != null) {
                UUID wolfUuid = wolf.getUUID();
                Streak.forget(wolfUuid);
                VerbProcs.forget(wolfUuid);
                RecallLock.forget(wolfUuid);
                Senses.forget(wolfUuid);
                wolf.discard();
            }
        }
        PlayerWolfRegistry.remove(target.getUUID());
        Tracker.forEachBoundStone(target, SpiritStone::revertToUnbound);
        target.sendSystemMessage(Component.literal("Your spirit wolf record was wiped by an admin.")
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int adminSouls(ServerPlayer target, long amount) {
        WolfRecord record = PlayerWolfRegistry.get(target.getUUID());
        if (record == null) {
            target.sendSystemMessage(Component.literal("That player has no spirit wolf.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        record.souls += amount;
        PlayerWolfRegistry.markDirty(target.getUUID());
        target.sendSystemMessage(Component.literal(
                        "Souls granted. Total: " + record.souls)
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return 1;
    }
}
