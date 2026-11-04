package fortune.mc;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import fortune.core.Annals;
import fortune.core.Calendar;
import fortune.core.Claims;
import fortune.core.Line;
import fortune.core.Paytable;
import fortune.core.PlayerRecord;
import fortune.core.ReelStrip;
import fortune.core.Settings;
import fortune.core.Stake;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The machine: one 9 by 6 chest for Java and Bedrock (Geyser translates it). Row 0 is the week and the streak, rows 2
 * to 4 the three reels with the payline in the middle, row 5 the buttons and the stake. The window is read only:
 * sgui refuses every take, place, shift click and number key, and the player's own inventory is locked while it is
 * open.
 *
 * <p>The window never decides anything. A pull asks {@link FortuneService#pull}, which charges, rolls and saves the
 * spin with its reward pending before this class draws a single frame; the frames then scroll to that result, and the
 * reveal pays it. Closing early reveals and pays at once; leaving mid spin keeps it pending for the next join.
 */
public final class MachineGui extends SimpleGui {

    public static final int[] REEL_COLUMNS = {2, 4, 6};
    public static final int LEVER = 49;
    public static final int ODDS = 45;
    public static final int MUSTER = 46;
    public static final int NO_STAKE = 47;
    public static final int[] STAKE_SLOTS = {48, 50, 51};
    public static final int ANNALS = 52;
    public static final int CLOSE = 53;
    public static final int STREAK = 8;

    private final FortuneService service;
    private final BlockPos at;
    private final Random filler = new Random();

    private FortuneService.Pull.Spun spun;
    private int[][] strips;
    private int[][] frameTicks;
    private int[] frameAt;
    private int tick;
    private boolean spinning;
    private boolean revealed = true;
    private final int[][] resting;
    private boolean restingIsToday;

    MachineGui(FortuneService service, ServerPlayer player, BlockPos at) {
        super(MenuType.GENERIC_9x6, player, false);
        this.service = service;
        this.at = at;
        setTitle(Component.literal("Fantasy's Fortune").withStyle(ChatFormatting.DARK_PURPLE));
        setLockPlayerInventory(true);
        this.resting = restingView();
        drawAll();
    }

    // ---- state, for the commands and the gametests ----

    public boolean spinning() {
        return spinning;
    }

    public boolean revealed() {
        return revealed;
    }

    public FortuneService.Pull.Spun lastSpin() {
        return spun;
    }

    /** The symbol index shown on a reel's payline right now. */
    public int paylineShown(int reel) {
        return window(reel)[1];
    }

    // ---- the lever ----

    /** What a click on the lever does: pull, or skip to the result while the reels run. */
    public void pullLever() {
        if (spinning) {
            skip();
            return;
        }
        FortuneService.Pull pull = service.pull(player, at);
        switch (pull) {
            case FortuneService.Pull.Refused refused -> {
                player.sendSystemMessage(Messages.bad(refused.message()));
                drawButtons();
            }
            case FortuneService.Pull.Spun result -> start(result);
        }
    }

    private void start(FortuneService.Pull.Spun result) {
        this.spun = result;
        int gap = Bedrock.isBedrock(player.getUUID()) ? service.settings().frameTicksBedrock : service.settings().frameTicks;
        int[] payline = result.spin().outcome().payline();
        strips = new int[3][];
        frameTicks = new int[3][];
        frameAt = new int[]{-1, -1, -1};
        for (int reel = 0; reel < 3; reel++) {
            frameTicks[reel] = ReelStrip.frameTicks(ReelStrip.STOP_TICKS[reel], gap);
            strips[reel] = ReelStrip.strip(frameTicks[reel].length, payline[reel], service.machine().roller(), filler);
        }
        tick = 0;
        spinning = true;
        revealed = false;
        drawAll();
    }

    /**
     * One server tick, called by {@link FortuneService#tick} for every open machine. Driven from the server tick
     * rather than sgui's menu tick so the reels keep time whether or not the player's own tick runs.
     */
    void serverTick() {
        if (spinning) advance(1);
    }

    /** Runs the animation forward. Public so the gametests can drive it without waiting on real ticks. */
    public void advance(int ticks) {
        for (int i = 0; i < ticks && spinning; i++) {
            tick++;
            boolean moved = false;
            for (int reel = 0; reel < 3; reel++) {
                int[] times = frameTicks[reel];
                while (frameAt[reel] + 1 < times.length && times[frameAt[reel] + 1] <= tick) {
                    frameAt[reel]++;
                    moved = true;
                    if (frameAt[reel] == times.length - 1) {
                        Reveal.sound(player, SoundEvents.NOTE_BLOCK_BELL, 0.6f, 1.0f + reel * 0.2f);
                    }
                }
            }
            if (moved) {
                drawReels();
                float pitch = Math.min(2.0f, 0.6f + tick / 50f);
                Reveal.sound(player, SoundEvents.NOTE_BLOCK_HAT, 0.35f, pitch);
            }
            if (allStopped()) finish();
        }
    }

    /** A second press of the lever: straight to the result. */
    public void skip() {
        if (!spinning) return;
        for (int reel = 0; reel < 3; reel++) frameAt[reel] = frameTicks[reel].length - 1;
        finish();
    }

    private boolean allStopped() {
        for (int reel = 0; reel < 3; reel++) if (frameAt[reel] < frameTicks[reel].length - 1) return false;
        return true;
    }

    private void finish() {
        spinning = false;
        drawAll();
        revealOnce();
    }

    private void revealOnce() {
        if (revealed || spun == null) return;
        revealed = true;
        Reveal.show(service, player, spun, at);
    }

    @Override
    public void onRemoved() {
        // Closed mid spin: reveal and pay now. Logged out mid spin: the reveal skips a player who has left, so the
        // saved reward stays pending and is paid on the next join.
        if (spinning) {
            spinning = false;
            revealOnce();
        }
        service.closed(player, this);
    }

    // ---- drawing ----

    private void drawAll() {
        GuiElement glass = glass(Items.STAINED_GLASS_PANE.black());
        for (int slot = 0; slot < 54; slot++) setSlot(slot, glass);
        drawWeek();
        drawFrame();
        drawReels();
        drawButtons();
    }

    private void drawFrame() {
        GuiElement arrow = new GuiElementBuilder(Items.STAINED_GLASS_PANE.yellow())
                .setName(Messages.name("Payline", ChatFormatting.YELLOW))
                .setLore(List.of(Messages.lore("Only the middle row pays.", ChatFormatting.GRAY)))
                .build();
        setSlot(27, arrow);
        setSlot(35, arrow);
        for (int reel = 0; reel < 3; reel++) {
            setSlot(9 + REEL_COLUMNS[reel], new GuiElementBuilder(Items.STAINED_GLASS_PANE.yellow())
                    .setName(Messages.name("Reel " + (reel + 1), ChatFormatting.YELLOW))
                    .build());
        }
    }

    private void drawWeek() {
        Settings settings = service.settings();
        PlayerRecord record = service.record(player.getUUID());
        boolean claimedToday = record.claimedOn(service.today());
        int claimed = record.weekPosition == 0 ? (claimedToday ? record.weekSymbols.size() : 0) : record.weekPosition;
        claimed = Math.min(claimed, record.weekSymbols.size());
        for (int day = 1; day <= 7; day++) {
            GuiElementBuilder b;
            if (day <= claimed) {
                int symbol = service.machine().symbolIndex(record.weekSymbols.get(day - 1));
                b = new GuiElementBuilder(symbolItem(Math.max(0, symbol)))
                        .setName(Messages.name("Day " + day + ": claimed", ChatFormatting.GREEN))
                        .glow();
            } else if (day == claimed + 1 && !claimedToday) {
                b = new GuiElementBuilder(day == 7 ? Items.GOLD_BLOCK : Items.SUNFLOWER)
                        .setName(Messages.name("Day " + day + ": Today" + (day == 7 ? ", the Golden Spin" : ""), ChatFormatting.GOLD))
                        .setLore(List.of(Messages.lore(floorText(day), ChatFormatting.GRAY)))
                        .glow();
            } else {
                b = new GuiElementBuilder(day == 7 ? Items.GOLD_BLOCK : Items.STAINED_GLASS_PANE.gray())
                        .setName(Messages.name("Day " + day + (day == 7 ? ": the Golden Spin" : ""), ChatFormatting.GRAY))
                        .setLore(List.of(Messages.lore(floorText(day), ChatFormatting.GRAY),
                                Messages.lore("The week moves one day per spin, not per date.", ChatFormatting.DARK_GRAY)));
            }
            setSlot(day - 1, b.build());
        }
        List<Component> lore = new ArrayList<>();
        lore.add(Messages.lore("Best streak: " + record.bestStreak, ChatFormatting.GRAY));
        if (record.lastClaimDay >= 0) {
            lore.add(Messages.lore("Your streak is safe until " + Calendar.shortDate(Claims.streakSafeUntil(record, settings)) + ".", ChatFormatting.GRAY));
        }
        lore.add(Messages.lore("Miss up to " + settings.streakGraceDays + " days and it holds.", ChatFormatting.DARK_GRAY));
        setSlot(STREAK, new GuiElementBuilder(Items.CANDLE)
                .setCount(Math.max(1, Math.min(64, record.streak)))
                .setName(Messages.name("Streak: " + record.streak, ChatFormatting.GOLD))
                .setLore(lore)
                .build());
    }

    private static String floorText(int day) {
        Line floor = Claims.floorForWeekDay(day);
        return switch (floor) {
            case NO_MATCH -> "Pays at least the no match line.";
            case PAIR -> "Pays at least a pair.";
            default -> "Three of a kind guaranteed.";
        };
    }

    private void drawReels() {
        boolean glowPayline = !spinning && (spun != null || restingIsToday);
        for (int reel = 0; reel < 3; reel++) {
            int[] rows = window(reel);
            for (int row = 0; row < 3; row++) {
                int symbol = rows[row];
                Settings.SymbolDef def = service.settings().symbols.get(symbol);
                GuiElementBuilder b = new GuiElementBuilder(symbolItem(symbol))
                        .setName(Messages.name(def.name, row == 1 ? ChatFormatting.WHITE : ChatFormatting.GRAY));
                if (row == 1 && glowPayline) b.glow();
                setSlot(9 * (2 + row) + REEL_COLUMNS[reel], b.build());
            }
        }
    }

    private int[] window(int reel) {
        if (strips != null) return ReelStrip.window(strips[reel], frameAt[reel]);
        return resting[reel];
    }

    private int[][] restingView() {
        PlayerRecord record = service.record(player.getUUID());
        int[][] view = new int[3][3];
        restingIsToday = record.claimedOn(service.today()) && record.lastPayline.size() == 3;
        for (int reel = 0; reel < 3; reel++) {
            view[reel][0] = service.machine().roller().filler(filler);
            view[reel][2] = service.machine().roller().filler(filler);
            int mid = restingIsToday ? service.machine().symbolIndex(record.lastPayline.get(reel)) : -1;
            view[reel][1] = mid >= 0 ? mid : service.machine().roller().filler(filler);
        }
        return view;
    }

    private void drawButtons() {
        Settings settings = service.settings();
        PlayerRecord record = service.record(player.getUUID());
        boolean canSpin = !record.claimedOn(service.today());

        setSlot(ODDS, new GuiElementBuilder(Items.BOOK)
                .setName(Messages.name("Odds", ChatFormatting.AQUA))
                .setLore(oddsLore())
                .setCallback((i, t, a, g) -> {
                    if (clickable(t)) for (Component line : Odds.lines(service, record)) player.sendSystemMessage(line);
                })
                .build());

        // TODO kingdom muster (spec 7): the banner button. Off until the muster is built.
        setSlot(MUSTER, glass(Items.STAINED_GLASS_PANE.black()));

        if (service.stakeOffered()) {
            long balance = service.bank().balance(player.getUUID());
            Settings.StakeTier chosen = Stake.tier(settings, record.stakeTier);
            boolean noneChosen = chosen == null;
            GuiElementBuilder none = new GuiElementBuilder(Items.PAPER)
                    .setName(Messages.name("No stake", noneChosen ? ChatFormatting.GREEN : ChatFormatting.WHITE))
                    .setLore(List.of(Messages.lore("Spin for the base reward.", ChatFormatting.GRAY),
                            Messages.lore("You have " + Stake.sovereignText(Stake.sovereigns(balance)) + " banked.", ChatFormatting.DARK_GRAY)))
                    .setCallback((i, t, a, g) -> {
                        if (clickable(t) && !spinning) {
                            service.chooseStake(player.getUUID(), "none");
                            drawButtons();
                        }
                    });
            if (noneChosen) none.glow();
            setSlot(NO_STAKE, none.build());
            for (int k = 0; k < STAKE_SLOTS.length; k++) {
                if (k < settings.stakeTiers.size()) {
                    setSlot(STAKE_SLOTS[k], stakeButton(settings.stakeTiers.get(k), chosen, balance));
                } else {
                    setSlot(STAKE_SLOTS[k], glass(Items.STAINED_GLASS_PANE.black()));
                }
            }
        } else {
            setSlot(NO_STAKE, glass(Items.STAINED_GLASS_PANE.black()));
            for (int slot : STAKE_SLOTS) setSlot(slot, glass(Items.STAINED_GLASS_PANE.black()));
        }

        setSlot(LEVER, leverButton(record, canSpin));

        List<Component> annals = new ArrayList<>();
        List<Annals.Win> wins = Annals.add(service.store().annals(), null, service.today());
        if (wins.isEmpty()) annals.add(Messages.lore("No big wins yet this week. Be the first.", ChatFormatting.GRAY));
        for (Annals.Win w : wins) {
            annals.add(Messages.lore(w.name + ": " + w.result + " (" + Calendar.shortDate(w.day) + ")", ChatFormatting.GRAY));
        }
        setSlot(ANNALS, new GuiElementBuilder(Items.CLOCK)
                .setName(Messages.name("Annals: this week's biggest wins", ChatFormatting.AQUA))
                .setLore(annals)
                .build());

        setSlot(CLOSE, new GuiElementBuilder(Items.BARRIER)
                .setName(Messages.name("Close", ChatFormatting.RED))
                .setLore(List.of(Messages.lore("\"I'll be back.\" Closing keeps your reward.", ChatFormatting.GRAY)))
                .setCallback((i, t, a, g) -> {
                    if (clickable(t)) close();
                })
                .build());
    }

    private GuiElement leverButton(PlayerRecord record, boolean canSpin) {
        List<Component> lore = new ArrayList<>();
        String name;
        if (spinning) {
            name = "Skip to the result";
            lore.add(Messages.lore("The result is already decided and saved.", ChatFormatting.GRAY));
        } else if (canSpin) {
            name = "Pull the lever";
            int day = record.weekPosition + 1;
            lore.add(Messages.lore("Day " + day + " of 7. " + floorText(day), ChatFormatting.GRAY));
            Settings.StakeTier chosen = service.stakeOffered() ? Stake.tier(service.settings(), record.stakeTier) : null;
            lore.add(Messages.lore(chosen == null ? "Stake: none"
                    : "Stake: " + Stake.sovereignText(chosen.sovereigns) + " for +" + chosen.bonusPercent + "%", ChatFormatting.YELLOW));
            lore.add(Messages.lore("\"Do you feel lucky?\" Every pull pays something.", ChatFormatting.DARK_GRAY));
        } else {
            name = "Come back tomorrow";
            lore.add(Messages.lore("Next spin in " + service.nextSpinIn() + ".", ChatFormatting.GRAY));
        }
        return new GuiElementBuilder(Items.LEVER)
                .setName(Messages.name(name, canSpin || spinning ? ChatFormatting.GOLD : ChatFormatting.GRAY))
                .setLore(lore)
                .setCallback((i, t, a, g) -> {
                    if (clickable(t)) pullLever();
                })
                .build();
    }

    private GuiElement stakeButton(Settings.StakeTier tier, Settings.StakeTier chosen, long balance) {
        boolean affordable = Stake.affordable(tier, balance);
        String title = tier.name + ": " + Stake.sovereignText(tier.sovereigns);
        if (!affordable) {
            return new GuiElementBuilder(Items.STAINED_GLASS_PANE.gray())
                    .setName(Messages.name(title, ChatFormatting.DARK_GRAY))
                    .setLore(List.of(Messages.lore("+" + tier.bonusPercent + "% Valor and items", ChatFormatting.DARK_GRAY),
                            Messages.lore("You have " + Stake.sovereignText(Stake.sovereigns(balance)) + " banked.", ChatFormatting.RED)))
                    .setCallback((i, t, a, g) -> {
                        if (clickable(t)) {
                            player.sendSystemMessage(Messages.bad("You need " + Stake.sovereignText(tier.sovereigns) + " in your bank for that stake."));
                        }
                    })
                    .build();
        }
        boolean selected = chosen != null && chosen.id.equals(tier.id);
        GuiElementBuilder b = new GuiElementBuilder(ItemIds.item(tier.item, Items.GOLD_NUGGET))
                .setCount(Math.max(1, Math.min(64, tier.sovereigns)))
                .setName(Messages.name(title, selected ? ChatFormatting.GREEN : ChatFormatting.WHITE))
                .setLore(List.of(Messages.lore("+" + tier.bonusPercent + "% Valor and items", ChatFormatting.YELLOW),
                        Messages.lore("Same odds, bigger rewards.", ChatFormatting.GRAY),
                        Messages.lore("Spent whatever the reels show. Charged only when you pull.", ChatFormatting.GRAY),
                        Messages.lore(selected ? "Selected" : "Click to choose", ChatFormatting.DARK_GRAY)))
                .setCallback((i, t, a, g) -> {
                    if (clickable(t) && !spinning) {
                        service.chooseStake(player.getUUID(), tier.id);
                        drawButtons();
                    }
                });
        if (selected) b.glow();
        return b.build();
    }

    private List<Component> oddsLore() {
        List<Component> out = new ArrayList<>();
        PlayerRecord record = service.record(player.getUUID());
        Paytable table = Odds.todaysTable(service, record);
        for (Paytable.Row row : table.rows()) {
            out.add(Messages.lore(Odds.rowText(service, table, row), ChatFormatting.GRAY));
        }
        out.add(Messages.lore("A stake raises rewards, never these odds.", ChatFormatting.DARK_GRAY));
        out.add(Messages.lore("Click for the full table in chat.", ChatFormatting.DARK_GRAY));
        return out;
    }

    private Item symbolItem(int index) {
        Settings settings = service.settings();
        Item fallback = ItemIds.item(settings.crownFallbackItem, Items.NETHER_STAR);
        return ItemIds.item(settings.symbols.get(index).item, fallback);
    }

    private static GuiElement glass(Item item) {
        return new GuiElementBuilder(item).setName(Component.empty()).hideTooltip().build();
    }

    /**
     * A plain left or right click (a tap on Bedrock). Shift clicks, number keys, drags, double clicks, middle clicks
     * and drops do nothing, so no stray key can pull the lever.
     */
    private static boolean clickable(ClickType type) {
        return type == ClickType.MOUSE_LEFT || type == ClickType.MOUSE_RIGHT;
    }
}
