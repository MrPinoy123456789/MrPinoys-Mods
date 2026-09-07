package pocketdungeons;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Inspector: right-click a placed gameplay object with the inspector tool
 * to open a properties GUI. Supports trial spawners, vaults, and chests.
 *
 * <p>See {@code docs/reference/ROOM_AUTHORING_SPEC.md} section 4.4.
 */
final class RoomEditorInspector {

    private RoomEditorInspector() {}

    /**
     * Opens the inspector GUI for the block at the given position, if it is
     * a supported gameplay object. Returns true if handled, false otherwise.
     */
    static boolean open(ServerPlayer player, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockEntity be = level.getBlockEntity(pos);

        if (be instanceof TrialSpawnerBlockEntity) {
            openSpawnerGui(player, level, pos);
            return true;
        }
        if (be instanceof VaultBlockEntity) {
            openVaultGui(player, level, pos);
            return true;
        }
        if (state.is(Blocks.CHEST)) {
            openChestGui(player, level, pos);
            return true;
        }
        return false;
    }

    // ---- trial spawner ------------------------------------------------------

    private static void openSpawnerGui(ServerPlayer player, ServerLevel level, BlockPos pos) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false);
        gui.setTitle(Component.literal("Trial Spawner Properties"));

        CompoundTag tag = level.getBlockEntity(pos).saveWithoutMetadata(level.registryAccess());
        String normalConfig = tag.getStringOr("normal_config", "pocketdungeons:tier_1/normal");
        int cooldown = tag.getIntOr("target_cooldown_length", 360);
        int range = tag.getIntOr("required_player_range", 14);
        boolean ominous = level.getBlockState(pos).getValue(TrialSpawnerBlock.OMINOUS);

        gui.setSlot(0, property("Config Prefix", stripConfigId(normalConfig),
                "Click to cycle available prefixes."));
        gui.setSlot(1, toggle("Ominous", ominous, "Sunflower = yes, poppy = no."));
        gui.setSlot(2, property("Cooldown", cooldown + " ticks",
                "Click to cycle 30/60/90/120 seconds."));
        gui.setSlot(3, property("Player Range", String.valueOf(range),
                "Click to cycle 4/8/14/32."));
        gui.setSlot(4, toggle("Gated", false, "Iron door = gated, wooden door = open."));

        fillRow(gui, 18, 26);
        gui.setSlot(22, closeElement());
        gui.open();
    }

    // ---- vault --------------------------------------------------------------

    private static void openVaultGui(ServerPlayer player, ServerLevel level, BlockPos pos) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false);
        gui.setTitle(Component.literal("Vault Properties"));

        BlockState state = level.getBlockState(pos);
        Direction facing = state.getValue(VaultBlock.FACING);
        boolean ominous = state.getValue(VaultBlock.OMINOUS);

        gui.setSlot(0, facingProperty(facing, "Vault Facing"));
        gui.setSlot(1, property("Loot Tier", "inherit from room",
                "Click to cycle tier 1/2/3 or inherit."));
        gui.setSlot(2, toggle("Ominous", ominous, "Sunflower = yes, poppy = no."));
        gui.setSlot(3, property("Loot Table", "inherit from run",
                "Type a loot table id in chat to override."));
        gui.setSlot(4, property("Key Item", "default trial key",
                "Place an item to set the key."));

        fillRow(gui, 18, 26);
        gui.setSlot(22, closeElement());
        gui.open();
    }

    // ---- chest --------------------------------------------------------------

    private static void openChestGui(ServerPlayer player, ServerLevel level, BlockPos pos) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false);
        gui.setTitle(Component.literal("Chest Properties"));

        BlockState state = level.getBlockState(pos);
        Direction facing = state.getValue(ChestBlock.FACING);

        gui.setSlot(0, facingProperty(facing, "Chest Facing"));
        gui.setSlot(1, property("Role", "supply",
                "Click to cycle supply / anomaly / store / inherit."));
        gui.setSlot(2, property("Loot Table", "inherit from role",
                "Type a loot table id in chat to override."));

        fillRow(gui, 18, 26);
        gui.setSlot(22, closeElement());
        gui.open();
    }

    // ---- GUI helpers --------------------------------------------------------

    private static GuiElementBuilder property(String name, String value, String lore) {
        return new GuiElementBuilder(Items.PAPER)
                .setName(Component.literal(name + ": " + value)
                        .withStyle(ChatFormatting.WHITE)
                        .withStyle(s -> s.withItalic(false)))
                .addLoreLine(Component.literal(lore).withStyle(ChatFormatting.GRAY)
                        .withStyle(s -> s.withItalic(false)));
    }

    private static GuiElementBuilder toggle(String name, boolean on, String lore) {
        return new GuiElementBuilder(on ? Items.SUNFLOWER : Items.POPPY)
                .setName(Component.literal(name + ": " + (on ? "yes" : "no"))
                        .withStyle(on ? ChatFormatting.GOLD : ChatFormatting.WHITE)
                        .withStyle(s -> s.withItalic(false)))
                .addLoreLine(Component.literal(lore).withStyle(ChatFormatting.GRAY)
                        .withStyle(s -> s.withItalic(false)));
    }

    private static GuiElementBuilder facingProperty(Direction facing, String label) {
        return new GuiElementBuilder(Items.PAPER)
                .setName(Component.literal(label + ": " + facing.name().toLowerCase())
                        .withStyle(ChatFormatting.WHITE)
                        .withStyle(s -> s.withItalic(false)))
                .addLoreLine(Component.literal("Click to cycle north / south / east / west.")
                        .withStyle(ChatFormatting.GRAY)
                        .withStyle(s -> s.withItalic(false)));
    }

    private static GuiElementBuilder closeElement() {
        return new GuiElementBuilder(Items.BARRIER)
                .setName(Component.literal("Close").withStyle(ChatFormatting.RED))
                .setCallback((index, clickType, actionType, g) -> g.close());
    }

    private static void fillRow(SimpleGui gui, int start, int end) {
        for (int i = start; i <= end; i++) {
            gui.setSlot(i, new GuiElementBuilder(Items.GLASS_PANE)
                    .setName(Component.literal("")));
        }
    }

    /**
     * Strips the config id down to a prefix for display. E.g.
     * "pocketdungeons:tier_1/normal" becomes "tier_1".
     */
    private static String stripConfigId(String id) {
        if (id == null || id.isBlank()) {
            return "<DEFAULT>";
        }
        int colon = id.indexOf(':');
        if (colon >= 0) {
            id = id.substring(colon + 1);
        }
        int slash = id.indexOf('/');
        if (slash >= 0) {
            id = id.substring(0, slash);
        }
        return id;
    }
}
