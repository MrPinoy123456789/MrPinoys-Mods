package charter.mc.granary;

import charter.engine.Actor;
import charter.engine.Outcome;
import charter.id.PlayerId;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.granary.GranaryBook.Site;
import charter.mc.granary.GranaryService.Access;
import charter.siege.Supply;
import charter.siege.actions.DepositFood;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The granary and camp store screen (PLAN.md §6.1): an sgui chest window whose slots
 * are the real chest slots, so what is shown is what is stored. A vanilla client
 * sees an ordinary chest.
 *
 * <p>Slots only accept food the table knows, and only what core would accept
 * (under siege, what the carry-in allowance covers); taking out needs the withdraw
 * power. Every tick, and on close, the chest is compared with core's mirror: more
 * food than the mirror holds is a deposit and goes through {@link DepositFood}, and
 * whatever core does not accept is handed back.
 *
 * <p>The chests of a big granary do not fit one window, so the content area pages.
 */
public final class GranaryScreen extends SimpleGui {

    private static final int PAGE = 45;
    private static final int PREV = 45;
    private static final int INFO = 49;
    private static final int NEXT = 53;

    private final GranaryService service;
    private final Site site;
    private final Core core;
    private final Access access;
    private final ServerPlayer viewer;
    private int page;

    public GranaryScreen(ServerPlayer player, GranaryService service, Site site, Core core, Access access) {
        super(MenuType.GENERIC_9x6, player, false);
        this.service = service;
        this.site = site;
        this.core = core;
        this.access = access;
        this.viewer = player;
        setTitle(Component.literal(Text.get(core.isKeep() ? "granary.title" : "granary.camp_title")));
        draw();
    }

    private List<Container> boxes() {
        return service.containers(site, null);
    }

    private static int totalSlots(List<Container> boxes) {
        int n = 0;
        for (Container b : boxes) n += b.getContainerSize();
        return n;
    }

    private static int pages(List<Container> boxes) {
        return Math.max(1, (totalSlots(boxes) + PAGE - 1) / PAGE);
    }

    private void draw() {
        List<Container> boxes = boxes();
        page = Math.max(0, Math.min(page, pages(boxes) - 1));
        int total = totalSlots(boxes);
        for (int i = 0; i < PAGE; i++) {
            int at = page * PAGE + i;
            if (at >= total) {
                clearSlot(i);
                continue;
            }
            int offset = at;
            for (Container box : boxes) {
                if (offset < box.getContainerSize()) {
                    setSlot(i, new Cell(box, offset));
                    break;
                }
                offset -= box.getContainerSize();
            }
        }
        for (int i = PAGE; i < PAGE + 9; i++) clearSlot(i);
        setSlot(INFO, info());
        if (pages(boxes) > 1) {
            setSlot(PREV, new GuiElementBuilder(Items.ARROW)
                    .setName(Component.literal(Text.get("granary.prev")).withStyle(ChatFormatting.YELLOW))
                    .setCallback((i, t, a, g) -> {
                        page--;
                        draw();
                    }).build());
            setSlot(NEXT, new GuiElementBuilder(Items.ARROW)
                    .setName(Component.literal(Text.get("granary.next")).withStyle(ChatFormatting.YELLOW))
                    .setCallback((i, t, a, g) -> {
                        page++;
                        draw();
                    }).build());
        }
    }

    private GuiElement info() {
        CharterRuntime rt = service.runtime();
        GuiElementBuilder b = new GuiElementBuilder(Items.CLOCK)
                .setName(Component.literal(GranaryText.timeLine(rt, core)).withStyle(ChatFormatting.GOLD));
        b.addLoreLine(Component.literal(Text.get(access == Access.FULL ? "granary.may_take" : "granary.may_not_take")).withStyle(ChatFormatting.GRAY));
        return b.build();
    }

    /** What the player may put in, as core would take it right now. */
    private boolean accepts(ItemStack stack) {
        if (access == Access.NONE || stack.isEmpty()) return false;
        Supply supply = service.runtime().sim.supply();
        return !supply.preview(carrier(), core.id, Map.of(GranaryService.itemId(stack), stack.getCount())).isEmpty();
    }

    private String carrier() {
        return "player:" + viewer.getUUID();
    }

    /** A real chest slot, with the screen's rules on what goes in and out. */
    private final class Cell extends Slot {
        Cell(Container container, int index) {
            super(container, index, 0, 0);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return accepts(stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            return access == Access.FULL;
        }
    }

    @Override
    public void onTick() {
        super.onTick();
        if (service.book().site(site.core) != site || service.coreOf(site).isEmpty()) {
            close();
            return;
        }
        settle();
    }

    @Override
    public void onRemoved() {
        super.onRemoved();
        if (service.book().site(site.core) == site) settle();
    }

    /**
     * Compares the chest with core's mirror. Extra food is a deposit: core accepts
     * what the allowance covers and the rest goes back to the player. Anything else
     * (a withdrawal) just refreshes the mirror.
     */
    public void settle() {
        CharterRuntime rt = service.runtime();
        List<Container> boxes = boxes();
        var now = service.read(boxes);
        var mirror = rt.state.siege.store(core.id).provisions;
        Map<String, Integer> offered = new TreeMap<>();
        now.counts().forEach((id, n) -> {
            int extra = n - mirror.count(id);
            if (extra > 0) offered.put(id, extra);
        });
        if (offered.isEmpty()) {
            if (!now.counts().equals(mirror.counts())) service.mirror(site, null);
            return;
        }
        PlayerId id = new PlayerId(viewer.getUUID());
        Supply supply = rt.sim.supply();
        Map<String, Integer> accepted = supply.preview(carrier(), core.id, offered);
        if (!accepted.isEmpty()) {
            Outcome outcome = rt.engine.run(new Actor.Player(id), new DepositFood(core.owner, core.id, carrier(), offered));
            if (!outcome.done()) accepted = Map.of();
        }
        Map<String, Integer> excess = new TreeMap<>();
        for (var e : offered.entrySet()) {
            int over = e.getValue() - accepted.getOrDefault(e.getKey(), 0);
            if (over > 0) excess.put(e.getKey(), over);
        }
        if (!excess.isEmpty()) {
            GranaryService.take(boxes, excess);
            giveBack(excess);
            viewer.sendSystemMessage(Component.literal(Text.get("granary.returned")).withStyle(ChatFormatting.YELLOW));
        }
        service.mirror(site, null);
    }

    private void giveBack(Map<String, Integer> items) {
        for (var e : items.entrySet()) {
            Item item = CharterServer.item(e.getKey());
            int left = e.getValue();
            while (left > 0) {
                int n = Math.min(left, item.getDefaultMaxStackSize());
                ItemStack stack = new ItemStack(item, n);
                viewer.getInventory().add(stack);
                if (!stack.isEmpty()) viewer.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
                left -= n;
            }
        }
    }
}
