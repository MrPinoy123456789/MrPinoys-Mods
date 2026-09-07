package pocketdungeons;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * The Room Metadata GUI: a 6-row chest GUI exposing the room's metadata
 * fields for in-world editing. The operator toggles roles, sets tier,
 * weight, depth, access, window, spanY, pressure, provides, requires,
 * and content.
 *
 * <p>See {@code docs/reference/ROOM_AUTHORING_SPEC.md} section 4.5.
 */
final class RoomEditorMetadata {

    /** Mutable metadata state for the player's current build room. */
    private static final Map<UUID, EditorMeta> META_STATE = new ConcurrentHashMap<>();

    private RoomEditorMetadata() {}

    /**
     * Holds the editable metadata for one player's build room session.
     * Defaults match a new room: loot role, 1x1 footprint, tier 1, etc.
     */
    static final class EditorMeta {
        Set<String> roles = new HashSet<>(Set.of("loot"));
        int footprintX = 1;
        int footprintZ = 1;
        int tier = 1;
        int weight = 1;
        int minDepth = 0;
        int maxPerDungeon = -1;
        String access = "open";
        String window = "bars";
        int spanY = 1;
        String pressure = null;
        String content = null;
        List<String> provides = new ArrayList<>();
        List<String> requires = new ArrayList<>();
        List<String> theme = new ArrayList<>();
    }

    /**
     * Returns the player's current metadata state, creating defaults if none.
     */
    static EditorMeta state(UUID uuid) {
        return META_STATE.computeIfAbsent(uuid, k -> new EditorMeta());
    }

    /**
     * Clears the metadata state for a player (on save or exit).
     */
    static void clear(UUID uuid) {
        META_STATE.remove(uuid);
    }

    /**
     * Opens the metadata GUI for the player.
     */
    static void open(ServerPlayer player) {
        EditorMeta meta = state(player.getUUID());
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, player, false);
        gui.setTitle(Component.literal("Room Metadata"));

        // Row 0 (slots 0-8): Roles
        gui.setSlot(0, roleToggle(meta, "encounter"));
        gui.setSlot(1, roleToggle(meta, "loot"));
        gui.setSlot(2, roleToggle(meta, "corridor"));
        gui.setSlot(3, roleToggle(meta, "entrance"));
        gui.setSlot(4, roleToggle(meta, "exit"));
        for (int i = 5; i < 9; i++) {
            gui.setSlot(i, filler());
        }

        // Row 1 (slots 9-17): Footprint, tier, weight
        gui.setSlot(9, textDisplay("Footprint X", String.valueOf(meta.footprintX),
                "1x1 only in the initial phases."));
        gui.setSlot(10, textDisplay("Footprint Z", String.valueOf(meta.footprintZ),
                "1x1 only in the initial phases."));
        gui.setSlot(11, textDisplay("Tier", String.valueOf(meta.tier),
                "Click to cycle 1/2/3."));
        gui.setSlot(12, textDisplay("Weight", String.valueOf(meta.weight),
                "Click to increase."));
        gui.setSlot(13, textDisplay("Min Depth", String.valueOf(meta.minDepth),
                "Click to increase."));
        gui.setSlot(14, textDisplay("Max/Dungeon", meta.maxPerDungeon < 0 ? "unlimited" : String.valueOf(meta.maxPerDungeon),
                "Click to cycle."));
        gui.setSlot(15, textDisplay("spanY", String.valueOf(meta.spanY),
                "Click to cycle 1/2."));
        for (int i = 16; i < 18; i++) {
            gui.setSlot(i, filler());
        }
        gui.setSlot(17, textDisplay("Provides", String.join(", ", meta.provides),
                "Type /dungeon roombuilder provides <tag> to add."));

        // Row 2 (slots 18-26): Access, window, pressure, content, requires
        gui.setSlot(18, textDisplay("Requires", String.join(", ", meta.requires),
                "Type /dungeon roombuilder requires <tag> to add."));
        gui.setSlot(19, textDisplay("Access", meta.access, "Click to toggle open/gated."));
        gui.setSlot(20, textDisplay("Window", meta.window, "Click to cycle bars/glass/tinted/none."));
        gui.setSlot(21, textDisplay("Pressure", meta.pressure == null ? "none" : meta.pressure,
                "Click to cycle none/omen/local."));
        gui.setSlot(22, textDisplay("Content", meta.content == null ? "none" : meta.content,
                "Type /dungeon roombuilder content <id> to set."));
        gui.setSlot(23, textDisplay("Theme", String.join(", ", meta.theme),
                "Type /dungeon roombuilder theme <id> to add."));
        for (int i = 24; i < 27; i++) {
            gui.setSlot(i, filler());
        }

        // Rows 3-4 (slots 27-44): empty for now
        for (int i = 27; i < 45; i++) {
            gui.setSlot(i, filler());
        }

        // Row 5 (slots 45-53): Save and Cancel
        for (int i = 45; i < 53; i++) {
            gui.setSlot(i, filler());
        }
        gui.setSlot(49, new GuiElementBuilder(Items.EMERALD)
                .setName(Component.literal("Save").withStyle(ChatFormatting.GREEN))
                .setCallback((index, clickType, actionType, g) -> {
                    g.close();
                    player.sendSystemMessage(Component.literal(
                            "Metadata saved. Use /dungeon roombuilder save <name> to save the room.")
                            .withStyle(ChatFormatting.AQUA));
                }));
        gui.setSlot(53, new GuiElementBuilder(Items.BARRIER)
                .setName(Component.literal("Cancel").withStyle(ChatFormatting.RED))
                .setCallback((index, clickType, actionType, g) -> g.close()));

        gui.open();
    }

    private static GuiElementBuilder roleToggle(EditorMeta meta, String role) {
        boolean on = meta.roles.contains(role);
        return new GuiElementBuilder(on ? Items.EMERALD : Items.COAL)
                .setName(Component.literal("Role: " + role)
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY)
                        .withStyle(s -> s.withItalic(false)))
                .addLoreLine(Component.literal(on ? "Enabled. Click to disable." : "Disabled. Click to enable.")
                        .withStyle(ChatFormatting.GRAY)
                        .withStyle(s -> s.withItalic(false)))
                .setCallback((index, clickType, actionType, g) -> {
                    if (on) {
                        meta.roles.remove(role);
                    } else {
                        meta.roles.add(role);
                    }
                    g.close();
                    open(g.getPlayer());
                });
    }

    private static GuiElementBuilder textDisplay(String name, String value, String lore) {
        return new GuiElementBuilder(Items.PAPER)
                .setName(Component.literal(name + ": " + value)
                        .withStyle(ChatFormatting.WHITE)
                        .withStyle(s -> s.withItalic(false)))
                .addLoreLine(Component.literal(lore).withStyle(ChatFormatting.GRAY)
                        .withStyle(s -> s.withItalic(false)));
    }

    private static GuiElementBuilder filler() {
        return new GuiElementBuilder(Items.GLASS_PANE)
                .setName(Component.literal(""));
    }
}
