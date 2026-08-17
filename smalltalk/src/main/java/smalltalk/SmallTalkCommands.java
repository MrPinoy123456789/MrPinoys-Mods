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
                                                        StringArgumentType.getString(ctx, "item"))))))));
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
