package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A pack cap (design pass 2026-10-09, Q8): a player keeps at most {@code petCap} tamed wolves standing with
 * them in a dungeon; the rest are told to sit where they are. Wolves come from the Kennels, the Hound Crypt
 * and the Lost Dog, and a pack built on purpose should help in a fight without becoming a stampede. Nothing a
 * wolf does is taken away: a sitting wolf is a wolf you can come back for.
 */
final class PetCap {

    private static final int PERIOD = 40;

    private PetCap() {}

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PERIOD != 0) {
                return;
            }
            ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (level == null) {
                return;
            }
            int cap = PocketDungeonsConfig.petCap();
            for (ServerPlayer player : level.players()) {
                AABB around = player.getBoundingBox().inflate(48.0);
                List<Wolf> pack = new ArrayList<>(level.getEntitiesOfClass(Wolf.class, around,
                        wolf -> wolf.isTame() && wolf.isOwnedBy(player) && !wolf.isOrderedToSit()));
                int excess = PetCapRules.excess(pack.size(), cap);
                if (excess == 0) {
                    continue;
                }
                pack.sort(Comparator.comparingInt(Wolf::getId));
                for (int i = 0; i < excess; i++) {
                    pack.get(pack.size() - 1 - i).setOrderedToSit(true);
                }
                player.sendOverlayMessage(Component.literal("Your pack is full (" + cap + "). The rest wait here.")
                        .withStyle(ChatFormatting.YELLOW));
            }
        });
    }
}
