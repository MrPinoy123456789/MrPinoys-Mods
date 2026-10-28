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
 * founded together and the keep is paid for in diamonds from their inventory.
 */
public final class FoundingPlacement {

    private FoundingPlacement() {
    }

    /**
     * Null on success, else the sentence that says why not. Diamonds are taken only
     * after the action succeeded; the engine call is on the server thread, so
     * nothing can take them in between.
     */
    public static String found(ServerPlayer player, Level level, BlockPos pos, String name, String banner) {
        CharterRuntime rt = CharterServer.runtime();
        int price = rt.knobs.keepPriceDiamonds;
        int have = NewcomerPages.diamondsCarried(player);
        if (have < price) return Text.get("book.found.need_diamonds", "price", price, "have", have);
        ChunkKey chunk = Placement.chunkOf(level, pos);
        PlayerId id = new PlayerId(player.getUUID());
        rt.state.touch(id, player.getPlainTextName(), rt.clock.now());
        Outcome outcome = rt.engine.run(new Actor.Player(id), new FoundKeep(id, name, banner, chunk, price));
        if (outcome instanceof Outcome.Refused(Reason reason)) return BookRouter.refusal(reason);

        int left = price;
        for (int i = 0; i < player.getInventory().getContainerSize() && left > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(Items.DIAMOND)) continue;
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            left -= take;
        }
        Placement.recordNexus(rt, level, pos, chunk);
        player.sendSystemMessage(Component.literal(Text.get("book.found.done", "name", name)).withStyle(ChatFormatting.GREEN));
        return null;
    }
}
