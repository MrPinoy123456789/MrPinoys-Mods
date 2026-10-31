package charter.mc.book;

import charter.engine.Actor;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.id.ChunkKey;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.land.Placement;
import charter.org.actions.FoundKeep;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * A player with no faction places a named Nexus: the faction and its keep are
 * founded together. The founding keep costs nothing: the new faction has no treasury to pay from.
 */
public final class FoundingPlacement {

    private FoundingPlacement() {
    }

    /**
     * Null on success, else the sentence that says why not.
     */
    public static String found(ServerPlayer player, Level level, BlockPos pos, String name, String banner) {
        CharterRuntime rt = CharterServer.runtime();
        ChunkKey chunk = Placement.chunkOf(level, pos);
        PlayerId id = new PlayerId(player.getUUID());
        rt.state.touch(id, player.getPlainTextName(), rt.clock.now());
        Outcome outcome = rt.engine.run(new Actor.Player(id), new FoundKeep(id, name, banner, chunk));
        if (outcome instanceof Outcome.Refused(Reason reason)) return BookRouter.refusal(reason);

        Placement.recordNexus(rt, level, pos, chunk);
        player.sendSystemMessage(Component.literal(Text.get("book.found.done", "name", name)).withStyle(ChatFormatting.GREEN));
        return null;
    }
}
