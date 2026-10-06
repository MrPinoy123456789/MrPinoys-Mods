package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.UUID;

/**
 * (M48) Tools crafted inside the dungeon dimension carry a capped
 * {@code max_damage} so a fresh stone pickaxe is a starter tool with a dozen
 * uses, not a permanent 131-durability mining operation. The cap is tier-scaled
 * so upgrading material still buys lifespan, just not vanilla lifespan:
 * <table>
 * <tr><th>Tier</th><th>Cap</th></tr>
 * <tr><td>Wooden, Golden</td><td>8</td></tr>
 * <tr><td>Stone, Copper</td><td>12</td></tr>
 * <tr><td>Iron</td><td>16</td></tr>
 * <tr><td>Diamond</td><td>32</td></tr>
 * <tr><td>Netherite</td><td>48</td></tr>
 * <tr><td>Unknown (modded)</td><td>12</td></tr>
 * </table>
 *
 * <p>Applies to pickaxes, axes, shovels and hoes: the four mining tool
 * families. Weapons and armour take a gentler cap of their own (see
 * {@link #durabilityCap}). Shears and flint-and-steel are untouched: they are
 * utility items.
 *
 * <p>PD-69: the cap is applied on craft ({@link ResultSlotMixin}) and, through
 * {@link DungeonDrops}, to every item that appears in the dungeon dimension:
 * chest and vault loot, mob drops (equipment included) and spawner rewards.
 * Before that, a mob's axe kept its full vanilla durability.
 *
 * <p>Identification is by registry name suffix rather than by class, because
 * 26.2 has no {@code PickaxeItem} class: pickaxes are plain {@code Item}
 * instances built with {@code ToolMaterial.applyToolProperties}. The suffix
 * check ({@code _pickaxe}, {@code _axe}, {@code _shovel}, {@code _hoe}) is
 * stable across vanilla and the common convention for modded tools.
 *
 * <p>The cap is applied by {@link ResultSlotMixin} on craft, not by a loot
 * table function, so it catches every path: crafting table, inventory
 * crafting, and the recipe book. The bag loot tables set their own
 * {@code max_damage} directly ({@code mason.json} sets 12 on its stone
 * pickaxe), so a bag tool and a crafted tool of the same tier match.
 *
 * <h2>Right tool for the job</h2>
 *
 * <p>The dungeon dimension enforces stricter tool requirements than vanilla:
 * a block in a {@code mineable/*} tag is unbreakable unless the held item's
 * {@link Tool} component marks it as correct for drops. Tier gating follows
 * vanilla ({@code NEEDS_IRON_TOOL} etc.), so a wooden pickaxe cannot mine iron
 * ore. TNT bypasses the tool check but not shell protection. Blocks placed by
 * a player are exempt: that player can always break their own builds by hand.
 *
 * <p><strong>Known limitation:</strong> piston movement and falling-block
 * movement are not tracked. A block placed by a player and then pushed by a
 * piston (or a sand block that falls) leaves its original position tracked
 * while the new position is untracked. Pistons are not craftable inside the
 * dungeon and falling blocks are rare in dungeon cells, so this is accepted
 * rather than adding a {@code PistonEvent} listener for an edge case that does
 * not arise in normal play.
 */
public final class DungeonTools {

    private DungeonTools() {}

    /**
     * If {@code stack} is a mining tool, returns a copy with its
     * {@code max_damage} capped to the tier-scaled value. Otherwise returns
     * {@code stack} unchanged. Called from {@link ResultSlotMixin} on every
     * craft result taken inside the dungeon dimension.
     */
    public static ItemStack limitDurability(ItemStack stack) {
        if (stack.isEmpty()) {
            return stack;
        }
        int cap = durabilityCap(stack.getItem());
        if (cap <= 0) {
            return stack;
        }
        Integer max = stack.get(DataComponents.MAX_DAMAGE);
        if (max != null && max <= cap) {
            return stack; // already capped (or a kit item authored lower): never raise it
        }
        ItemStack copy = stack.copy();
        capInPlace(copy);
        return copy;
    }

    /**
     * {@link #limitDurability} on {@code stack} itself rather than on a copy.
     * PD-89: shift-clicking a crafting result ({@code CraftingMenu.quickMoveStack})
     * moves the result stack straight into the inventory and never goes
     * through {@code ResultSlot.remove}, so {@link ResultSlotMixin} missed it
     * and a crafted diamond sword kept its full 1561. The one call that path
     * makes on the stack before moving it is {@code Item.onCraftedBy}, and
     * {@code CraftedDurabilityMixin} caps it there, in place.
     */
    public static void capInPlace(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        int cap = durabilityCap(stack.getItem());
        if (cap <= 0) {
            return;
        }
        Integer max = stack.get(DataComponents.MAX_DAMAGE);
        if (max != null && max <= cap) {
            return;
        }
        int damage = stack.getDamageValue();
        stack.set(DataComponents.MAX_DAMAGE, cap);
        if (damage > 0 && max != null) {
            stack.setDamageValue(scaledDamage(damage, max, cap));
        }
    }

    /**
     * The damage a stack keeps when its maximum drops from {@code oldMax} to
     * {@code cap}: proportional, so a sword found half worn stays half worn,
     * and never enough to break it on the spot.
     */
    static int scaledDamage(int damage, int oldMax, int cap) {
        if (damage <= 0 || oldMax <= 0) {
            return 0;
        }
        return Math.min(cap - 1, (int) Math.round((double) damage * cap / oldMax));
    }

    /**
     * The durability cap for {@code item}, or {@code -1} if this class does not
     * limit it. Public so the mixin can short-circuit without building a copy.
     *
     * <p>Mining tools take the strict cap (the table in the class javadoc).
     * Weapons and armour (playtest 2026-09-27: "last too long") take a gentler
     * one, about a quarter to a third of vanilla, so a found sword is a real
     * upgrade for a stretch of floors rather than for the whole game:
     * <table>
     * <tr><th>Material</th><th>Weapon</th><th>Armour piece</th></tr>
     * <tr><td>Wooden, Golden, Leather</td><td>24</td><td>32</td></tr>
     * <tr><td>Stone, Copper, Chainmail</td><td>40</td><td>48</td></tr>
     * <tr><td>Iron, Turtle</td><td>64</td><td>64</td></tr>
     * <tr><td>Diamond</td><td>96</td><td>96</td></tr>
     * <tr><td>Netherite</td><td>128</td><td>128</td></tr>
     * </table>
     * Bow, crossbow and shield 64; trident and mace 96. Shears, flint and
     * steel and other utility items stay vanilla.
     */
    public static int durabilityCap(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        String path = id.getPath();
        String tier = tierPrefix(path);
        if (path.endsWith("_pickaxe") || path.endsWith("_axe")
                || path.endsWith("_shovel") || path.endsWith("_hoe")) {
            return switch (tier) {
                case "wooden", "golden" -> 8;
                case "stone", "copper" -> 12;
                case "iron" -> 16;
                case "diamond" -> 32;
                case "netherite" -> 48;
                default -> 12;
            };
        }
        if (path.endsWith("_sword") || path.endsWith("_spear")) {
            return switch (tier) {
                case "wooden", "golden" -> 24;
                case "stone", "copper" -> 40;
                case "iron" -> 64;
                case "diamond" -> 96;
                case "netherite" -> 128;
                default -> 48;
            };
        }
        if (path.endsWith("_helmet") || path.endsWith("_chestplate")
                || path.endsWith("_leggings") || path.endsWith("_boots")) {
            return switch (tier) {
                case "leather", "golden" -> 32;
                case "chainmail", "copper" -> 48;
                case "iron", "turtle" -> 64;
                case "diamond" -> 96;
                case "netherite" -> 128;
                default -> 48;
            };
        }
        return switch (path) {
            case "bow", "crossbow", "shield" -> 64;
            case "trident", "mace" -> 96;
            default -> -1;
        };
    }

    /**
     * Extracts the material prefix from a tool path like
     * {@code "stone_pickaxe"}: everything before the first underscore. A path
     * with no underscore returns the whole string, which falls through to the
     * default cap.
     */
    private static String tierPrefix(String path) {
        int underscore = path.indexOf('_');
        return underscore <= 0 ? path : path.substring(0, underscore);
    }

    // ---- right tool for the job --------------------------------------------

    /**
     * Whether {@code held} is the correct tool to break {@code state} under
     * the dungeon's stricter tool rules. The dungeon dimension enforces
     * "right tool for the job": a block in a {@code mineable/*} tag is
     * unbreakable unless the held item's {@link Tool} component marks it as
     * correct for drops. This is stricter than vanilla, which lets any item
     * break blocks that do not {@code requireCorrectToolForDrops} (like planks
     * with a pickaxe).
     *
     * <p>Tier gating follows vanilla: a wooden pickaxe's {@code Tool} rules
     * mark stone as correct but iron ore as incorrect, so
     * {@link Tool#isCorrectForDrops} returns false for iron ore with a wooden
     * pickaxe. This is the same check {@code ItemStack.isCorrectToolForDrops}
     * uses internally, but called directly on the {@code Tool} component to
     * bypass the {@code !requiresCorrectToolForDrops()} short-circuit that
     * would let any tool break planks.
     *
     * <p>Blocks not in any {@code mineable/*} tag (torches, redstone, carpets,
     * etc.) are always breakable: they have no tool requirement, and the
     * player-placed exemption is not needed for them.
     *
     * @param held  the item in the player's main hand, possibly empty
     * @param state the block being broken
     * @return {@code true} if the block can be broken with this tool
     */
    public static boolean isCorrectTool(ItemStack held, BlockState state) {
        if (!isMineable(state)) {
            return true;
        }
        Tool tool = held.get(DataComponents.TOOL);
        return tool != null && tool.isCorrectForDrops(state);
    }

    /**
     * A human-readable requirement string for {@code state}, or {@code null}
     * if the block has no tool requirement. Used for the action-bar feedback
     * when a player starts mining with the wrong tool. Tier-gated blocks
     * include the tier: "Requires a stone pickaxe" rather than just
     * "Requires a pickaxe".
     *
     * @return e.g. {@code "Requires an axe"}, {@code "Requires an iron pickaxe"},
     *         or {@code null} if the block is not mineable
     */
    public static String requiredToolMessage(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
                return "Requires a diamond pickaxe";
            }
            if (state.is(BlockTags.NEEDS_IRON_TOOL)) {
                return "Requires an iron pickaxe";
            }
            if (state.is(BlockTags.NEEDS_STONE_TOOL)) {
                return "Requires a stone pickaxe";
            }
            return "Requires a pickaxe";
        }
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            return "Requires an axe";
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return "Requires a shovel";
        }
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) {
            return "Requires a hoe";
        }
        return null;
    }

    /**
     * Whether {@code state} is in any of the four {@code mineable/*} block
     * tags. Blocks in these tags require the corresponding tool type under the
     * dungeon's stricter rules; blocks outside them are free to break by hand.
     */
    static boolean isMineable(BlockState state) {
        return state.is(BlockTags.MINEABLE_WITH_PICKAXE)
                || state.is(BlockTags.MINEABLE_WITH_AXE)
                || state.is(BlockTags.MINEABLE_WITH_SHOVEL)
                || state.is(BlockTags.MINEABLE_WITH_HOE);
    }

    // ---- mixin-facing accessors --------------------------------------------

    /**
     * Records a block placement by a player in the dungeon dimension, for the
     * "right tool for the job" player-placed exemption. Called from
     * {@code BlockItemPlaceMixin}. No-op if the player is not in an instance.
     * The placer's UUID is stored so only that player can break the block by
     * hand; other party members still need the correct tool.
     */
    public static void recordPlayerPlacement(UUID playerUuid, BlockPos pos) {
        InstanceRecord record = InstanceRegistry.byMember.get(playerUuid);
        if (record != null) {
            record.playerPlaced.put(pos.immutable(), playerUuid);
        }
        PlaytestJournal.countPlacement(playerUuid);
    }

    /**
     * Removes a position from the player-placed tracking, called when the
     * block at that position is broken or destroyed by an explosion. Prevents
     * stale entries from exempting a natural block that later appears at the
     * same position.
     */
    public static void forgetPlayerPlacement(BlockPos pos) {
        BlockPos immutable = pos.immutable();
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            record.playerPlaced.remove(immutable);
        }
    }

    /**
     * Whether {@code pos} was placed by {@code playerUuid} and is therefore
     * exempt from the tool requirement. Called from
     * {@code RoomProtection.beforeBlockBreak}.
     */
    public static boolean isPlayerPlaced(BlockPos pos, UUID playerUuid) {
        InstanceRecord record = Instances.dungeonRecordAt(pos);
        if (record == null) {
            return false;
        }
        UUID placer = record.playerPlaced.get(pos.immutable());
        return placer != null && placer.equals(playerUuid);
    }

    /**
     * Whether {@code pos} is a shell or furniture block protected from
     * explosions. Called from {@code ServerExplosionMixin} to filter TNT blast
     * positions. Reuses the same coordinate tests as
     * {@code RoomProtection.beforeBlockBreak} so the two protection layers
     * agree exactly.
     */
    public static boolean isShellProtected(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        // M55: check whichever cell (safe room or staging room) the position
        // is in, using roomOriginAt and roomDungeonDoorAt for the correct
        // cell-specific origin and direction.
        BlockPos roomOrigin = Instances.roomOriginAt(pos);
        if (roomOrigin != null) {
            if (RoomProtection.isShell(pos, roomOrigin)) {
                return true;
            }
            DoorMask.Direction dungeonDoor = Instances.roomDungeonDoorAt(pos);
            if (dungeonDoor != null && RoomProtection.isFurniture(pos, roomOrigin, dungeonDoor)) {
                return true;
            }
        }
        BlockPos dungeonCellOrigin = Instances.dungeonCellOriginAt(pos);
        return dungeonCellOrigin != null
                && (RoomProtection.isShell(pos, dungeonCellOrigin) || Ordeals.isFixture(pos));
    }

    /**
     * Whether {@code pos} is inside a dungeon cell (not just the shell). Called
     * from {@code ServerExplosionMixin} to block all block destruction from any
     * explosion source inside a dungeon cell, and from other handlers that need
     * the same distinction.
     */
    public static boolean isInsideDungeonCell(BlockPos pos) {
        return Instances.dungeonCellOriginAt(pos) != null;
    }
}
