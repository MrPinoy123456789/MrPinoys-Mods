package pocketdungeons;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

final class LemonBridge {
    static final String TAG = "lemon.part";

    static void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND
                    || !(player instanceof ServerPlayer target)
                    || !Keystone.isKeystone(player.getItemInHand(hand))) {
                return InteractionResult.PASS;
            }
            return call(target, "summon", "") > 0 ? InteractionResult.SUCCESS_SERVER : InteractionResult.PASS;
        });
    }

    static boolean isPart(Entity entity) {
        return entity.entityTags().contains(TAG);
    }

    static int tour(ServerPlayer player, String text) {
        if (!available(player)) {
            Component line = Component.literal(text);
            player.connection.send(new ClientboundSetActionBarTextPacket(line));
            player.sendSystemMessage(line);
            return 1;
        }
        return call(player, "tour", text);
    }

    static void say(ServerPlayer player, String text) {
        if (available(player)) {
            call(player, "say", text);
        } else {
            player.sendSystemMessage(Component.literal(text));
        }
    }

    private static boolean available(ServerPlayer player) {
        return player.level().getServer().getCommands().getDispatcher().getRoot().getChild("lemon") != null;
    }

    static int call(ServerPlayer player, String verb, String text) {
        if (!available(player)) {
            return 0;
        }
        var server = player.level().getServer();
        String command = "lemon " + verb + " " + player.getName().getString()
                + (text.isEmpty() ? "" : " " + text);
        try {
            return server.getCommands().getDispatcher().execute(command,
                    server.createCommandSourceStack().withSuppressedOutput());
        } catch (CommandSyntaxException e) {
            PocketDungeonsMod.LOG.warn("Lemon command failed: {}", e.getMessage());
            return 0;
        }
    }

    static void withoutLemon(ServerLevel level, AABB box, Runnable capture) {
        List<Entity> parts = level.getEntitiesOfClass(Entity.class, box, LemonBridge::isPart);
        List<Vec3> homes = new ArrayList<>();
        for (Entity part : parts) {
            homes.add(part.position());
            part.setPos(part.getX(), box.maxY + 64, part.getZ());
        }
        try {
            capture.run();
        } finally {
            for (int i = 0; i < parts.size(); i++) {
                parts.get(i).setPos(homes.get(i));
            }
        }
    }

    static LiteralArgumentBuilder<CommandSourceStack> contextNode() {
        return Commands.literal("context")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer player = EntityArgument.getPlayer(ctx, "target");
                            String json = PlayerContext.toJson(PlayerContext.gather(ctx.getSource().getServer(), player)).toString();
                            ctx.getSource().sendSuccess(() -> Component.literal(json), false);
                            return 1;
                        }));
    }

    static LiteralArgumentBuilder<CommandSourceStack> menuNode() {
        return Commands.literal("lemonmenu").executes(ctx -> {
            RitualListener.openMenu(ctx.getSource().getPlayerOrException());
            return 1;
        });
    }

    static LiteralArgumentBuilder<CommandSourceStack> archiveNode() {
        return Commands.literal("lemonarchive").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            if (!LemonArchive.handOver(player, player.getMainHandItem())) {
                RitualListener.openMenu(player);
            }
            return 1;
        });
    }

    static LiteralArgumentBuilder<CommandSourceStack> reportNode() {
        return Commands.literal("report")
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            String text = StringArgumentType.getString(ctx, "text");
                            PlaytestJournal.report(player, text);
                            PocketDungeonsMod.LOG.info("Report from {}: {}", player.getName().getString(), text);
                            player.sendSystemMessage(Component.literal("Noted. Thank you."));
                            return 1;
                        }));
    }
}
