package ballot.mc;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * The four items an organiser ever handles.
 *
 * <p>All identified by a custom data component, never by display name — an anvil
 * renames anything, and a name check would make every player an organiser.
 *
 * <p>Each carries the poll's key rather than its name, because names change and
 * identity does not. Placing one binds it: see {@link Placement}.
 */
public final class BallotItems {

    private BallotItems() {}

    private static final String KEY = "ballot";
    private static final String POLL = "poll";

    private static final String WAND = "wand";
    private static final String LECTERN = "lectern";
    private static final String BALLOT = "ballot_box";
    private static final String PLOT_BOX = "plot_box";
    private static final String PLOT_SIGN = "plot_sign";

    // ---- the wand -----------------------------------------------------------

    public static ItemStack wand() {
        ItemStack stack = new ItemStack(Items.STICK);
        stamp(stack, WAND, null);
        name(stack, "Ballot Wand", ChatFormatting.GOLD);
        lore(stack,
                "Right-click a poll block to manage it",
                "Sneak + right-click to unbind",
                "Hold anything else to take part normally");
        return stack;
    }

    public static boolean isWand(ItemStack stack) {
        return pollKeyOf(stack, WAND) != null || WAND.equals(kindOf(stack));
    }

    // ---- placeable pieces ---------------------------------------------------

    /** The poll's front door: settings for organisers, the page for everyone else. */
    public static ItemStack lectern(String pollKey, String pollName) {
        ItemStack stack = new ItemStack(Items.LECTERN);
        stamp(stack, LECTERN, pollKey);
        name(stack, pollName.isBlank() ? "Vote Noticeboard" : pollName + " — noticeboard",
                ChatFormatting.GOLD);
        lore(stack,
                "Place it anywhere — that binds it",
                "Right-click: see the vote",
                "Wand + right-click: settings");
        return stack;
    }

    /** A poll's single voting box. Every option is listed inside it. */
    public static ItemStack ballotBox(String pollKey) {
        ItemStack stack = new ItemStack(Items.JUKEBOX);
        stamp(stack, BALLOT, pollKey);
        name(stack, "Ballot Box", ChatFormatting.GOLD);
        lore(stack,
                "Place it where people gather",
                "Right-click: see the options and vote",
                "One per vote — all options are inside");
        return stack;
    }

    /** A build competition's per-plot voting box. */
    public static ItemStack plotBox(String pollKey) {
        ItemStack stack = new ItemStack(Items.JUKEBOX);
        stamp(stack, PLOT_BOX, pollKey);
        name(stack, "Plot Ballot Box", ChatFormatting.GOLD);
        lore(stack,
                "Place it at a plot — that creates the plot",
                "Players vote for this build here",
                "Put the plot sign beside it");
        return stack;
    }

    /** The sign players claim a plot at. Binds to the nearest plot box when placed. */
    public static ItemStack plotSign(String pollKey) {
        ItemStack stack = new ItemStack(Items.OAK_SIGN);
        stamp(stack, PLOT_SIGN, pollKey);
        name(stack, "Plot Sign", ChatFormatting.GOLD);
        lore(stack,
                "Place it beside a plot ballot box",
                "Players claim and name the plot here",
                "It writes itself — don't edit it");
        return stack;
    }

    // ---- reading ------------------------------------------------------------

    public static String lecternPollKey(ItemStack stack) {
        return pollKeyOf(stack, LECTERN);
    }

    public static String ballotBoxPollKey(ItemStack stack) {
        return pollKeyOf(stack, BALLOT);
    }

    public static String plotBoxPollKey(ItemStack stack) {
        return pollKeyOf(stack, PLOT_BOX);
    }

    public static String plotSignPollKey(ItemStack stack) {
        return pollKeyOf(stack, PLOT_SIGN);
    }

    /** Which of ours this is, or null. */
    public static String kindOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        String kind = data.copyTag().getStringOr(KEY, "");
        return kind.isBlank() ? null : kind;
    }

    private static String pollKeyOf(ItemStack stack, String kind) {
        if (!kind.equals(kindOf(stack))) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        String key = data.copyTag().getStringOr(POLL, "");
        return key.isBlank() ? null : key;
    }

    // ---- building -----------------------------------------------------------

    private static void stamp(ItemStack stack, String kind, String pollKey) {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY, kind);
        if (pollKey != null) {
            tag.putString(POLL, pollKey);
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void name(ItemStack stack, String text, ChatFormatting colour) {
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(text).withStyle(colour));
    }

    private static void lore(ItemStack stack, String... lines) {
        stack.set(DataComponents.LORE, new ItemLore(java.util.Arrays.stream(lines)
                .map(line -> (Component) Component.literal(line)
                        .withStyle(ChatFormatting.GRAY))
                .toList()));
    }
}
