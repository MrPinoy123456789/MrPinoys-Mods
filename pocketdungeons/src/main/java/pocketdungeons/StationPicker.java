package pocketdungeons;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The station picker: an SGUI chest where the room's owner takes station
 * blocks to place in their room. Vanilla blocks, one per sink, each
 * unlocked at a keystone-level threshold. Locked stations appear as greyed
 * icons with their unlock requirement in the lore; unlocked stations are
 * clickable and give the player the block item to place wherever they want.
 *
 * <h2>Why SGUI, not the vanilla dialog the mod's other screens use</h2>
 *
 * <p>The mod's existing dialogs are action pickers: choose a door, choose an
 * enchantment, choose a slot. The player is selecting an action to perform
 * right now. This screen is different: it gives the player a physical block
 * to carry and place. A chest where you see the smithing table sitting in a
 * slot, hover it to read what it does, and click to pick it up is the same
 * interaction the player will then have with the block once it is placed.
 * The UI teaches the mechanic by being the mechanic.
 *
 * <p>This is the only screen in the mod that gives items rather than
 * performing actions, so it does not set a precedent that forces SGUI onto
 * the other five pickers. They stay on the vanilla dialog where they belong.
 *
 * <h2>Owner-only</h2>
 *
 * <p>The wall-lodestone menu gates the "Stations" option behind
 * {@code roomOwner}, the same way it gates "Change Shell": a party member
 * visiting another player's room does not see the option. The station
 * blocks are taken from the room's lodestone, which is the owner's
 * terminal.
 *
 * <h2>Player-placeable, not protected fixtures</h2>
 *
 * <p>The stations are vanilla blocks. A player places them wherever they
 * want, breaks and re-places them freely. If they lose one (dropped in
 * lava, left in a torn-down dungeon), they reopen this picker and take
 * another. No capture hygiene: the blocks are not part of the room blob,
 * and nothing about the station's function depends on where the block is.
 */
final class StationPicker {

    /** Middle row of a 9x3 chest, spaced for readability. */
    private static final int SLOT_SALVAGE = 10;
    private static final int SLOT_REROLL = 11;
    private static final int SLOT_LECTERN = 12;
    private static final int SLOT_STORAGE = 14;
    private static final int SLOT_CUBE = 16;

    private StationPicker() {}

    /**
     * Opens the station picker chest for {@code player}. Reads the player's
     * live keystone level from {@link DungeonLog} so the unlock state is
     * current, not a snapshot from when the menu was opened.
     */
    static void open(ServerPlayer player) {
        int level = DungeonLog.forServer(player.level().getServer())
                .get(player.getUUID()).keystoneLevel();
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false);
        gui.setTitle(Component.literal("Stations"));

        gui.setSlot(SLOT_SALVAGE, stationElement(
                "Salvage Bench",
                resolveItem(PocketDungeonsConfig.salvageBlock()),
                level >= PocketDungeonsConfig.salvageUnlockLevel(),
                PocketDungeonsConfig.salvageUnlockLevel(),
                List.of(
                        "Scraps surplus gear and spare vault keys.",
                        "Dungeon gear and keys pay emeralds; mob gear pays XP.",
                        "Right-click the block with gear or a key in hand.",
                        "An empty hand uses it as a normal grindstone."),
                player));

        gui.setSlot(SLOT_REROLL, stationElement(
                "Smithing Table",
                resolveItem(PocketDungeonsConfig.rerollBlock()),
                level >= PocketDungeonsConfig.rerollUnlockLevel(),
                PocketDungeonsConfig.rerollUnlockLevel(),
                List.of(
                        "Rerolls one enchantment on a piece of gear.",
                        "Costs lapis, scaled by the gear's tier.",
                        "Right-click the block with gear in hand.",
                        "A blacksmith NPC spawns near it to sell gear for emeralds."),
                player));

        gui.setSlot(SLOT_STORAGE, stationElement(
                "Run Storage",
                resolveItem(PocketDungeonsConfig.storageBlock()),
                true,
                0,
                List.of(
                        "Twenty-seven slots of your own, for this run only.",
                        "Place it in the dungeon, right-click to open it. It is not your real ender chest.",
                        "Everything inside comes home with you when the run closes."),
                player));

        gui.setSlot(SLOT_LECTERN, stationElement(
                "Lectern",
                resolveItem("minecraft:lectern"),
                level >= PocketDungeonsConfig.lockInUnlockLevel(),
                PocketDungeonsConfig.lockInUnlockLevel(),
                List.of(
                        "A librarian spawns beside it.",
                        "Locks in a piece of gear: adds Mending and keeps it safe.",
                        "Costs "+"emeralds. Locked gear cannot be salvaged or rerolled.",
                        "Right-click the librarian holding the gear."),
                player));

        gui.setSlot(SLOT_CUBE, stationElement(
                "Herobrine Cube",
                resolveItem(PocketDungeonsConfig.cubeBlock()),
                level >= PocketDungeonsConfig.cubeUnlockLevel(),
                PocketDungeonsConfig.cubeUnlockLevel(),
                List.of(
                        "Extract powers from rare items. Imbue them onto gear.",
                        "Extract consumes the item permanently. Imbue costs iron.",
                        "Right-click with a rare item to extract, or gear to imbue."),
                player));

        gui.open();
    }

    /**
     * One station's chest element: the block as the icon, lore describing
     * what it does, and either a click-to-take callback (unlocked) or a
     * locked state with the unlock requirement (locked).
     */
    private static GuiElementBuilder stationElement(String name, Item icon,
                                                     boolean unlocked, int unlockLevel,
                                                     List<String> description,
                                                     ServerPlayer player) {
        if (icon == null) {
            // A misconfigured station block id: show a barrier so the slot is
            // not empty, the same way ballot's state item uses a barrier for
            // an unready poll. The error was already logged by ConfiguredItem.
            return new GuiElementBuilder(net.minecraft.world.item.Items.BARRIER)
                    .setName(Component.literal(name + " (misconfigured)")
                            .withStyle(ChatFormatting.DARK_RED))
                    .setLore(List.of(Component.literal("Check pocketdungeons.json")
                            .withStyle(ChatFormatting.DARK_RED)));
        }

        List<Component> lore = new java.util.ArrayList<>();
        for (String line : description) {
            lore.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }

        GuiElementBuilder builder = new GuiElementBuilder(icon)
                .setName(Component.literal(name)
                        .withStyle(unlocked ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY));

        if (unlocked) {
            lore.add(Component.literal("Click to take.")
                    .withStyle(ChatFormatting.GREEN));
            builder.setLore(lore);
            builder.setCallback((index, clickType, actionType, gui) -> {
                if (clickType.isLeft) {
                    player.getInventory().placeItemBackInInventory(new ItemStack(icon));
                    player.sendSystemMessage(Component.literal("Taken: " + name + ". Place it in your room.")
                            .withStyle(ChatFormatting.AQUA));
                    gui.close();
                }
            });
        } else {
            lore.add(Component.literal("Unlocks at keystone level " + unlockLevel + ".")
                    .withStyle(ChatFormatting.YELLOW));
            builder.setLore(lore);
        }

        return builder;
    }

    /**
     * Resolves a config item id string to an {@link Item}, the same way
     * {@link ConfiguredItem} does but without caching: this is called once
     * per picker open, not per tick, so the cost is negligible and a config
     * reload between opens is picked up for free.
     */
    private static Item resolveItem(String id) {
        Identifier parsed = Identifier.tryParse(id);
        return parsed == null ? null : BuiltInRegistries.ITEM.getOptional(parsed).orElse(null);
    }
}
