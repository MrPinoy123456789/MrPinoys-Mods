package kamutotems;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import kamutotems.core.Kamuy;
import kamutotems.core.KamuyName;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * sgui AnvilInputGui for naming the Kamuy.
 *
 * <p>Following the suite's proven pattern, the value is taken on an explicit
 * Accept click rather than on close, so backing out cancels cleanly instead of
 * committing whatever was half-typed. Closing the anvil without accepting is a
 * no-op; the player can reopen the hub by right-clicking the fletching table.
 */
public final class NameMenu {

    private NameMenu() {}

    public static void open(ServerPlayer player) {
        openFrom(player, null);
    }

    public static void openFrom(ServerPlayer player, Runnable back) {
        Kamuy kamuy = TotemHost.KamuyStore.getOrCreate(player);
        String current = kamuy.name() != null ? kamuy.name() : "";

        AnvilInputGui input = new AnvilInputGui(player, false);
        input.setTitle(Component.literal("Name your Kamuy").withStyle(ChatFormatting.AQUA));
        input.setDefaultInputValue(current.isBlank() ? " " : current);
        input.setSlot(2, new GuiElementBuilder(Items.WRITABLE_BOOK)
                .setName(Component.literal("Accept").withStyle(ChatFormatting.GREEN))
                .setLore(List.of(StationRouting.dim("Type above, then click here")))
                .setCallback((i, t, a, g) -> {
                    String typed = input.getInput();
                    if (typed == null || typed.isBlank()) {
                        player.sendSystemMessage(Component.literal("No name was entered.")
                                .withStyle(ChatFormatting.RED));
                        return;
                    }
                    applyName(player, typed.trim());
                    g.close();
                    if (back != null) {
                        back.run();
                    }
                })
                .build());
        input.open();
    }

    private static void applyName(ServerPlayer player, String raw) {
        String sanitised = KamuyName.sanitise(raw, 32);
        if (sanitised == null) {
            player.sendSystemMessage(Component.literal(
                            "That name cannot be used. Keep it short and plain.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        Kamuy kamuy = TotemHost.KamuyStore.getOrCreate(player);
        String born = kamuy.bornDateKey() != null && !kamuy.bornDateKey().isBlank()
                ? kamuy.bornDateKey()
                : LocalDate.now(ZoneOffset.UTC).toString();
        Kamuy next = new Kamuy(sanitised, kamuy.construct(), born,
                kamuy.kamuDrunk(), kamuy.saves(), kamuy.bossesSlain(), kamuy.pool());
        TotemHost.KamuyStore.put(player.getUUID(), next);

        ItemStack totem = Totem.find(player);
        if (totem != null) {
            Totem.refreshLore(totem, next);
        }
        Chime.play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.2f, 1.2f);
        player.sendSystemMessage(Component.literal("Your Kamuy is now " + sanitised + ".")
                .withStyle(ChatFormatting.AQUA));
    }
}
