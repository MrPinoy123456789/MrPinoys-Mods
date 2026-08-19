package kamutotems;

import com.mojang.brigadier.CommandDispatcher;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import kamutotems.core.Kamuy;
import kamutotems.core.QuestSegment;
import kamutotems.core.QuestState;
import kamutotems.core.QuestDefinition;
import kamutotems.core.AssignedQuest;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * The Kamu Station. Any vanilla fletching table opens the hub on right-click.
 *
 * <p>Hub entries: Carving (SlotMenu), Fusion (KamuForge), Discoveries (BookMenu),
 * Naming (NameMenu), Journal and Quests (local panels). Every panel returns to
 * the hub, except the anvil naming screen which is closed without committing.
 */
public final class Station {

    private Station() {}

    public static void register() {
        UseBlockCallback.EVENT.register(Station::onUseBlock);
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // No station-specific commands. The hub is opened by right-clicking a
        // fletching table; the existing /totem name and /kamuy commands still work.
    }

    public static void openHub(ServerPlayer player) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x4, player, false);
        gui.setTitle(Component.literal("Kamu Station").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

        Runnable backToHub = () -> openHub(player);

        gui.setSlot(10, entry(Items.TOTEM_OF_UNDYING,
                "Carving", "Place bound kamu into your totem.",
                ChatFormatting.AQUA,
                () -> { gui.close(); SlotMenu.openFrom(player, backToHub); }));

        gui.setSlot(12, entry(Items.BLAST_FURNACE,
                "Fusion", "Fuse two identical kamu into one of the next tier.",
                ChatFormatting.GOLD,
                () -> { gui.close(); KamuForge.openFrom(player, backToHub); }));

        gui.setSlot(14, entry(Items.BOOK,
                "Discoveries", "See every spirit the totem knows.",
                ChatFormatting.DARK_PURPLE,
                () -> { gui.close(); BookMenu.openFrom(player, backToHub); }));

        gui.setSlot(16, entry(Items.NAME_TAG,
                "Naming", "Give your Kamuy a name.",
                ChatFormatting.GREEN,
                () -> { gui.close(); NameMenu.openFrom(player, backToHub); }));

        gui.setSlot(29, entry(Items.WRITABLE_BOOK,
                "Journal", "Read your Kamuy's story.",
                ChatFormatting.LIGHT_PURPLE,
                () -> { gui.close(); openJournal(player, backToHub); }));

        gui.setSlot(31, entry(Items.CLOCK,
                "Quests", "Today's daily chain and streak.",
                ChatFormatting.YELLOW,
                () -> { gui.close(); openQuests(player, backToHub); }));

        gui.setSlot(33, new GuiElementBuilder(Items.ARROW)
                .setName(Component.literal("Close").withStyle(ChatFormatting.RED))
                .setLore(List.of(StationRouting.dim("Close this menu")))
                .setCallback((i, t, a, g) -> g.close())
                .build());

        gui.open();
    }

    private static GuiElement entry(Item icon, String title, String blurb,
                                     ChatFormatting color, Runnable open) {
        return new GuiElementBuilder(icon)
                .setName(Component.literal(title).withStyle(color, ChatFormatting.BOLD))
                .setLore(List.of(StationRouting.dim(blurb)))
                .setCallback((i, t, a, g) -> open.run())
                .build();
    }

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand,
                                                 BlockHitResult hitResult) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (!level.getBlockState(hitResult.getBlockPos()).is(Blocks.FLETCHING_TABLE)) {
            return InteractionResult.PASS;
        }
        // Vanilla lets a sneaking player with something in hand act on the item
        // instead of the block, so blocks can be placed against a fletching
        // table. Sneaking with both hands empty still opens the hub.
        if (player.isShiftKeyDown()
                && !(player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty())) {
            return InteractionResult.PASS;
        }
        openHub(serverPlayer);
        return InteractionResult.SUCCESS_SERVER;
    }

    /** Journal as a read-only panel with a back button. */
    private static void openJournal(ServerPlayer player, Runnable back) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false);
        gui.setTitle(Component.literal("Journal").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));

        Kamuy kamuy = TotemHost.KamuyStore.getOrCreate(player);
        String name = kamuy.name() != null ? kamuy.name() : "an unnamed spirit";

        List<Component> lore = new ArrayList<>();
        for (String line : kamuy.journal()) {
            lore.add(Component.literal(line).withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
        }
        if (lore.isEmpty()) {
            lore.add(StationRouting.dim("Your Kamuy has not yet written anything."));
        }

        gui.setSlot(13, new GuiElementBuilder(Items.WRITABLE_BOOK)
                .setName(Component.literal(name).withStyle(ChatFormatting.GOLD))
                .setLore(lore)
                .build());

        gui.setSlot(StationRouting.BACK_SLOT_SMALL, StationRouting.backButton(back));
        gui.open();
    }

    /** Quests panel: the daily chain and streak, with a turn-in attempt button. */
    private static void openQuests(ServerPlayer player, Runnable back) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false);
        gui.setTitle(Component.literal("Quests").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));

        // ---- the daily chain, top row ------------------------------------
        // Guarded: the quest host stands down when `dailyquests` is installed,
        // and DailyChain's catalog is null in that case.
        if (QuestHost.dailyChainReady()) {
            DailyChain.PlayerDay day = DailyChain.dayFor(player);
            List<QuestSegment> segments = day.chain().segments();
            List<Integer> counts = day.progress().counts();

            for (int i = 0; i < segments.size() && i < 3; i++) {
                QuestSegment seg = segments.get(i);
                boolean complete = day.progress().segmentComplete(i, day.chain());
                int count = i < counts.size() ? counts.get(i) : 0;
                int total = seg.matcher().requiredCount();

                String text = (i + 1) + ". " + seg.displayText() + "  " + count + "/" + total;
                ChatFormatting color = complete ? ChatFormatting.GREEN : ChatFormatting.WHITE;

                gui.setSlot(10 + i * 2, new GuiElementBuilder(Items.BOOK)
                        .setName(Component.literal(text).withStyle(color))
                        .setLore(List.of(StationRouting.dim(seg.kind().toUpperCase())))
                        .build());
            }

            int streak = DailyChain.streakOf(player.getUUID());
            gui.setSlot(16, new GuiElementBuilder(Items.CLOCK)
                    .setName(Component.literal("Streak: " + streak + " day" + (streak == 1 ? "" : "s"))
                            .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                    .setLore(List.of(StationRouting.dim("Hand in items or finish the chain to grow it.")))
                    .build());

            gui.setSlot(22, new GuiElementBuilder(Items.CHEST)
                    .setName(Component.literal("Hand in").withStyle(ChatFormatting.GREEN))
                    .setLore(List.of(StationRouting.dim("Turn in items for today's chain")))
                    .setCallback((i, t, a, g) -> {
                        Component result = TurnIn.attempt(player);
                        if (result != null) {
                            player.sendSystemMessage(result);
                        } else {
                            // tryAdvance already sent success messages via DailyChain.
                            g.close();
                            openQuests(player, back);
                        }
                    })
                    .build());
        }

        // ---- errands from elsewhere, second row ---------------------------
        // Assigned quests are the outward API's visible half: a wayfarer handed
        // the player a scroll, and this is where it shows up. They are listed
        // even when the daily chain is off, because they do not depend on it.
        List<AssignedQuest> held = AssignedQuestHost.heldBy(player);
        int shown = 0;
        for (AssignedQuest q : held) {
            if (shown >= 3 || q.state() != QuestState.ACTIVE) {
                continue;
            }
            QuestDefinition def = AssignedQuestHost.definitionOf(q);
            if (def == null) {
                continue;
            }
            List<Component> lore = new ArrayList<>();
            if (def.sourceLabel() != null && !def.sourceLabel().isBlank()) {
                lore.add(StationRouting.dim(def.sourceLabel()));
            }
            for (int s = 0; s < def.segments().size(); s++) {
                QuestSegment seg = def.segments().get(s);
                int count = s < q.progress().counts().size() ? q.progress().counts().get(s) : 0;
                lore.add(Component.literal("  " + seg.displayText() + "  "
                                + count + "/" + seg.matcher().requiredCount())
                        .withStyle(ChatFormatting.GRAY));
            }
            gui.setSlot(19 + shown * 2, new GuiElementBuilder(Items.WRITABLE_BOOK)
                    .setName(Component.literal(def.title()).withStyle(ChatFormatting.LIGHT_PURPLE))
                    .setLore(lore)
                    .build());
            shown++;
        }

        if (shown == 0) {
            gui.setSlot(19, new GuiElementBuilder(Items.MAP)
                    .setName(Component.literal("No errands").withStyle(ChatFormatting.DARK_GRAY))
                    .setLore(List.of(StationRouting.dim("Wanderers sometimes ask for help.")))
                    .build());
        }

        gui.setSlot(StationRouting.BACK_SLOT_SMALL, StationRouting.backButton(back));
        gui.open();
    }
}
