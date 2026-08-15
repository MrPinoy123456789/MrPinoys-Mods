package kamutotems;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import kamutotems.core.DiscoveryBook;
import kamutotems.core.DiscoveryTier;
import kamutotems.core.Kamu;
import kamutotems.core.KamuCatalog;

/**
 * sgui discovery book. Shows every kamu and how much the player knows about it.
 */
public final class BookMenu {

    private static final int[] BOOK_SLOTS = {
            10, 12, 14,
            19, 21, 23,
            28, 30, 32,
            37, 39, 41,
            46, 48, 50
    };

    private static final Map<UUID, Runnable> PENDING_BACK = new HashMap<>();

    private BookMenu() {}

    public static void open(ServerPlayer player) {
        PENDING_BACK.remove(player.getUUID());
        buildAndOpen(player);
    }

    public static void openFrom(ServerPlayer player, Runnable back) {
        PENDING_BACK.put(player.getUUID(), back);
        buildAndOpen(player);
    }

    private static void buildAndOpen(ServerPlayer player) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, player, false);
        gui.setTitle(Component.literal("Kamu Discovery Book").withStyle(ChatFormatting.DARK_PURPLE));

        DiscoveryBook book = loadBook(player);
        List<Kamu> all = KamuData.catalog().all();

        for (int i = 0; i < BOOK_SLOTS.length && i < all.size(); i++) {
            gui.setSlot(BOOK_SLOTS[i], element(all.get(i), book.tierOf(all.get(i).id())));
        }

        Runnable back = PENDING_BACK.get(player.getUUID());
        if (back != null) {
            gui.setSlot(StationRouting.BACK_SLOT, StationRouting.backButton(back));
        }

        gui.open();
    }

    private static DiscoveryBook loadBook(ServerPlayer player) {
        // Discovery JSON is written by this class alone; shape is ours to define.
        JsonObject data = Persist.load("discovery/" + player.getUUID() + ".json");
        Map<String, DiscoveryTier> initial = new HashMap<>();
        if (data != null) {
            for (Map.Entry<String, JsonElement> e : data.entrySet()) {
                try {
                    initial.put(e.getKey(), DiscoveryTier.valueOf(e.getValue().getAsString().toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return new DiscoveryBook(initial);
    }

    private static GuiElement element(Kamu kamu, DiscoveryTier tier) {
        List<Component> lore = new ArrayList<>();
        ChatFormatting nameColor;
        String title;

        switch (tier) {
            case SECRET -> {
                title = "Hidden";
                nameColor = ChatFormatting.BLACK;
                lore.add(Component.literal("This spirit is hidden by the operator.")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            case UNKNOWN -> {
                title = "???";
                nameColor = ChatFormatting.DARK_GRAY;
                lore.add(Component.literal("You have not met this spirit yet.")
                        .withStyle(ChatFormatting.GRAY));
            }
            case HINTED -> {
                title = kamu.displayName();
                nameColor = ChatFormatting.GRAY;
                lore.add(Component.literal("You have seen this spirit, but do not yet know it.")
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            }
            default -> { // KNOWN
                title = kamu.displayName();
                nameColor = switch (kamu.rarity()) {
                    case RARE -> ChatFormatting.GOLD;
                    case UNCOMMON -> ChatFormatting.AQUA;
                    default -> ChatFormatting.WHITE;
                };
                lore.add(Component.literal(kamu.category().toString()).withStyle(ChatFormatting.BLUE));
                lore.add(Component.literal("Effect: " + kamu.effectId()).withStyle(ChatFormatting.GRAY));
            }
        }

        ItemStack icon = new ItemStack(SlotMenu.iconFor(kamu.id()), 1);
        return new GuiElementBuilder(icon.getItem())
                .setName(Component.literal(title).withStyle(nameColor, ChatFormatting.BOLD))
                .setLore(lore)
                .build();
    }
}
