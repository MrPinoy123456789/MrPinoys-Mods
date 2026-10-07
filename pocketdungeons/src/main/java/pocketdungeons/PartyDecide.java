package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The live wrapper over {@link PartyDecisions} (design D15): may this player
 * preview and commit a door, choose a branch or pull HOME for the run they are in.
 * The leader is the record owner; their whitelist setting lives in
 * {@link DungeonLog.Campaign}. {@code /dungeon quit} is deliberately not routed
 * through here: it carries the owner's keystone penalty and stays owner only.
 */
final class PartyDecide {

    private PartyDecide() {}

    /** Whether {@code player} may decide for {@code record}'s trip. Silent. */
    static boolean may(MinecraftServer server, InstanceRecord record, ServerPlayer player) {
        if (record == null || player == null) {
            return false;
        }
        if (server == null || record.owner == null) {
            return player.getUUID().equals(record.owner);
        }
        DungeonLog.Campaign campaign = DungeonLog.forServer(server).get(record.owner).campaign();
        return PartyDecisions.mayDecide(campaign.decideWhitelist(), record.owner, campaign.decideList(),
                player.getUUID());
    }

    /** {@link #may} that tells the player why when the answer is no. */
    static boolean mayOrRefuse(MinecraftServer server, InstanceRecord record, ServerPlayer player) {
        if (may(server, record, player)) {
            return true;
        }
        player.sendSystemMessage(Component.literal(
                "The party leader has limited who decides here. Ask them to add you to the list.")
                .withStyle(ChatFormatting.RED));
        return false;
    }
}
