package fortune.mc;

import fortune.core.Calendar;
import fortune.core.PlayerRecord;
import fortune.core.Settings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hands a saved spin over. Valor and Renown are paid only by running {@code payoutCommand} as the server console
 * (Charter Currency's {@code earn}, whose run id is per player per day); items go into the inventory, any that do not
 * fit drop at the player's feet. Fortune never creates Coin.
 */
final class Payout {

    private Payout() {
    }

    static void pay(FortuneService service, ServerPlayer player, PlayerRecord record) {
        Settings settings = service.settings();
        runCommand(service.server(), settings.payoutCommand, player, record, null);
        for (Settings.Extra extra : record.pendingExtras) give(player, ItemIds.stack(extra.item, extra.count));
        if (record.pendingKeepsake) {
            give(player, ItemIds.keepsake(settings.jackpotKeepsake.item, settings.jackpotKeepsake.name,
                    List.of("Three Crowns at Fantasy's Fortune", "Pulled by " + record.name + " on " + Calendar.iso(record.lastClaimDay))));
        }
        if (record.pendingMilestone > 0) {
            for (Settings.Milestone m : settings.milestones) {
                if (m.claims != record.pendingMilestone) continue;
                give(player, ItemIds.keepsake(m.keepsakeItem, m.keepsakeName,
                        List.of(record.name + " " + m.title, m.claims + " days at Fantasy's Fortune")));
                for (String command : settings.milestoneCommands) runCommand(service.server(), command, player, record, m.title);
            }
        }
        record.payPending = false;
        record.pendingExtras = new ArrayList<>();
        record.pendingKeepsake = false;
        record.pendingMilestone = 0;
    }

    /**
     * Runs one configured command as the console. A command for Charter Currency is skipped when that mod is absent,
     * so the machine still pays its items.
     */
    static void runCommand(MinecraftServer server, String template, ServerPlayer player, PlayerRecord record, String title) {
        if (template == null || template.isBlank()) return;
        String command = template.trim();
        if (command.startsWith("/")) command = command.substring(1);
        String first = command.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (first.equals("chartercurrency") && !FortuneService.currencyLoaded()) return;
        command = command
                .replace("%player%", player.getPlainTextName())
                .replace("%uuid%", player.getUUID().toString())
                .replace("%level%", Integer.toString(Math.max(1, record.lastLevel)))
                .replace("%date%", Calendar.iso(record.lastClaimDay))
                .replace("%title%", title == null ? "" : title);
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        } catch (RuntimeException e) {
            FortuneMod.LOG.error("Fortune payout command failed: {}", command, e);
        }
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        int max = Math.max(1, stack.getMaxStackSize());
        while (!stack.isEmpty()) {
            ItemStack part = stack.split(max);
            if (!player.getInventory().add(part) && !part.isEmpty()) {
                player.getInventory().placeItemBackInInventory(part, Prediction.SERVER_ONLY);
            }
        }
    }
}
