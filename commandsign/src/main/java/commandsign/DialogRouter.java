package commandsign;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.Optional;
import java.util.UUID;

/**
 * Where a clicked dialog button lands.
 *
 * <p>Always entered on the server main thread; {@link CustomClickMixin} hops
 * with {@code server.execute} before calling in here. Parses the payload,
 * re-reads the live sign block entity (a screen can sit open while the world
 * changes), and applies the save. Every read has a default rather than a
 * throw: a malformed payload is something a modified client can send whenever
 * it likes, and it must produce a sentence in chat, never an exception on a
 * network thread.
 */
public final class DialogRouter {

    private DialogRouter() {}

    public static void handle(ServerPlayer player, Identifier id, Optional<Tag> payload) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        if (!(payload.orElse(null) instanceof CompoundTag tag)) {
            CommandSignMod.LOG.warn("Dialog action {} arrived without a compound payload", id);
            return;
        }

        // The owner in the payload must be the player who clicked. A mismatch
        // is not a stale screen, it is a forged one.
        UUID owner = uuid(tag.getStringOr(DialogScreens.KEY_OWNER, ""));
        if (owner == null || !owner.equals(player.getUUID())) {
            CommandSignMod.LOG.warn("Dialog action {} from {} carried owner {}",
                    id, player.getName().getString(),
                    tag.getStringOr(DialogScreens.KEY_OWNER, ""));
            return;
        }

        if (DialogScreens.ACTION_SAVE.equals(id.getPath())) {
            save(player, server, tag);
        } else {
            CommandSignMod.LOG.warn("Unknown dialog action {}", id);
        }
    }

    private static void save(ServerPlayer player, MinecraftServer server, CompoundTag tag) {
        String dim = tag.getStringOr(DialogScreens.KEY_DIM, "");
        int x = tag.getIntOr(DialogScreens.KEY_X, 0);
        int y = tag.getIntOr(DialogScreens.KEY_Y, 0);
        int z = tag.getIntOr(DialogScreens.KEY_Z, 0);

        ServerLevel level = levelOf(server, dim);
        if (level == null) {
            player.sendSystemMessage(Component.literal("That sign's dimension is not loaded.")
                    .withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }

        if (!(level.getBlockEntity(new BlockPos(x, y, z)) instanceof SignBlockEntity sign)) {
            player.sendSystemMessage(Component.literal("That sign is gone.")
                    .withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }

        // Re-check op on the click, not when the screen opened.
        if (!CommandSignMod.isOp(server, player)) {
            player.sendSystemMessage(Component.literal("You are no longer an op.")
                    .withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }

        String command = tag.getStringOr(DialogScreens.KEY_COMMAND, "").trim();
        // BooleanInput submits a ByteTag (1b/0b), not the onTrue/onFalse strings.
        // Read it with getBooleanOr; getStringOr would always hit the default.
        boolean enabled = tag.getBooleanOr(DialogScreens.KEY_ENABLED, false);

        if (enabled && command.isEmpty()) {
            player.sendSystemMessage(Component.literal("Type a command before enabling the sign.")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
            return;
        }

        SignCommand.apply(sign, level, player, enabled, command);
        player.sendSystemMessage(Component.literal(
                enabled ? "Command sign saved." : "Command sign removed.")
                .withStyle(net.minecraft.ChatFormatting.GREEN));
    }

    static ServerLevel levelOf(MinecraftServer server, String dimension) {
        try {
            return server.getLevel(ResourceKey.create(Registries.DIMENSION,
                    Identifier.parse(dimension)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static UUID uuid(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
