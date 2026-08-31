package commandsign;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side-only mod that turns vanilla signs into command buttons.
 *
 * <p>An op shift-right-clicks a sign to open a vanilla dialog with a command
 * string and a "Command Sign?" toggle. On save, the mod writes "Right-Click"
 * on line 1 (purple) and the command on line 2, and stamps a hidden NBT flag
 * on the sign's block entity so a non-op cannot fake one by typing matching
 * text. A player right-clicks a marked sign and the server runs the stored
 * command with full permissions on their behalf, the same pattern
 * {@code tpasserver} uses for /tp.
 *
 * <p>The dialog submit round-trip goes through {@link CustomClickMixin} into
 * {@link DialogRouter}, both shaped after {@code pocketdungeons}' dialog
 * stack. Sign interaction uses Fabric API's {@link UseBlockCallback}, the
 * same approach {@code ballot} takes for its poll signs.
 */
public final class CommandSignMod implements ModInitializer {

    public static final String MOD_ID = "commandsign";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** The NBT key stamped on a {@code SignBlockEntity} to mark it as a command sign. */
    public static final String NBT_KEY = "commandsign_is_command_sign";

    @Override
    public void onInitialize() {
        SignInteractions.register();
        LOG.info("Command Signs ready; op shift-right-click a sign to attach a command");
    }

    /** The op check for a world interaction, matching ballot's isOp shape. */
    public static boolean isOp(MinecraftServer server, ServerPlayer player) {
        return player == null || server.getPlayerList().isOp(player.nameAndId());
    }
}
