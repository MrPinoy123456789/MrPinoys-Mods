package chartercurrency;

import chartercurrency.core.Banks;
import chartercurrency.core.Currency;
import chartercurrency.core.Denomination;
import chartercurrency.core.KingdomSource;
import chartercurrency.core.Mint;
import chartercurrency.core.Treasury;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultConfig;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Bank: a vault block a kingdom's Lord sets on that kingdom's land (owner, 2026-11-03).
 * Right-click opens a chest menu, the kind of screen Bedrock players get through Geyser:
 * the player's money, bank all, take out Sovereigns, the Mint's rules for today, and the
 * shop. Any smithing table in the Bank's chunk is its Mint ({@link MintListener}).
 *
 * <p>The rules are {@link Banks}, under test. This class moves blocks and items:
 * <ul>
 *   <li>Placing: the Bank item is a vault item with a marker. A click with it is checked
 *       before vanilla places anything; a refusal places nothing. An allowed placement is
 *       recorded at the end of the tick, once the vault is really there.</li>
 *   <li>Breaking: a vault drops nothing, so a broken Bank drops its Bank item and frees
 *       the kingdom's slot. A Bank that vanished another way is forgotten when its chunk
 *       is next loaded.</li>
 *   <li>The vanilla vault is tamed: every click on a Bank is taken here, and its config
 *       has no loot and an unobtainable key. It shows a spinning gold nugget.</li>
 *   <li>Looking at a Bank or a Mint puts a line on the action bar, which Bedrock shows too.</li>
 * </ul>
 */
final class BankStation {

    static final String MARKER = "bank";

    private static final ResourceKey<LootTable> NO_LOOT = ResourceKey.create(Registries.LOOT_TABLE,
            Identifier.fromNamespaceAndPath(CharterCurrencyMod.MOD_ID, "bank/no_loot"));
    private static final ResourceKey<LootTable> SHOWN = ResourceKey.create(Registries.LOOT_TABLE,
            Identifier.fromNamespaceAndPath(CharterCurrencyMod.MOD_ID, "bank/display"));

    /** How often stale Banks are looked for, and how often the look-at line is refreshed. */
    private static final int SWEEP_TICKS = 200;
    private static final int LOOK_TICKS = 10;

    private record Pending(ServerLevel level, BlockPos pos, Optional<String> kingdom, UUID player) {}

    private final Services s;
    private final BankSites banks;
    private final MintListener mint;
    private final List<Pending> pending = new ArrayList<>();

    BankStation(Services services, BankSites banks, MintListener mint) {
        this.s = services;
        this.banks = banks;
        this.mint = mint;
    }

    // ---- the item ---------------------------------------------------------------

    /** One Bank item: a vault with the marker, a name and three lines saying what it is for. */
    static ItemStack item() {
        ItemStack stack = new ItemStack(Items.VAULT);
        CompoundTag inner = new CompoundTag();
        inner.putBoolean(MARKER, true);
        CompoundTag root = new CompoundTag();
        root.put(CoinStacks.ROOT, inner);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        stack.set(DataComponents.ITEM_NAME, Component.literal("Bank").withStyle(ChatFormatting.GOLD));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                lore("A Lord sets it on their kingdom's land.", ChatFormatting.GRAY),
                lore("A smithing table in its chunk is the Mint.", ChatFormatting.GRAY),
                lore("One Bank per kingdom.", ChatFormatting.YELLOW))));
        return stack;
    }

    static boolean isItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.VAULT)) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getCompoundOrEmpty(CoinStacks.ROOT).getBooleanOr(MARKER, false);
    }

    private static Component lore(String text, ChatFormatting colour) {
        return Component.literal(text).withStyle(style -> style.withItalic(false).applyFormat(colour));
    }

    // ---- wiring -------------------------------------------------------------------

    void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            if (banks.isBank(level, pos)) {
                if (!level.getBlockState(pos).is(Blocks.VAULT)) {
                    banks.remove(BankSites.key(level, pos));
                    return InteractionResult.PASS;
                }
                // Both hands are taken, so a trial key or anything else never reaches the vault.
                if (hand == InteractionHand.MAIN_HAND) open(sp);
                return InteractionResult.CONSUME;
            }
            ItemStack held = sp.getItemInHand(hand);
            if (!isItem(held)) return InteractionResult.PASS;
            BlockPos target = new BlockPlaceContext(sp, hand, held, hit).getClickedPos().immutable();
            Banks.Placement placement;
            try {
                placement = decide(sp, level, target);
            } catch (RuntimeException e) {
                CharterCurrencyMod.LOG.warn("Kingdom lookup failed while placing a Bank", e);
                sp.sendSystemMessage(Messages.bad("The Bank cannot reach the kingdom records right now. Try again shortly."));
                return InteractionResult.FAIL;
            }
            if (!placement.allowed()) {
                sp.sendSystemMessage(Messages.bad(refusal(placement)));
                return InteractionResult.FAIL;
            }
            if (level.getBlockState(target).is(Blocks.VAULT)) return InteractionResult.PASS;
            pending.add(new Pending((ServerLevel) level, target, placement.kingdom(), sp.getUUID()));
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (level.isClientSide() || !banks.remove(BankSites.key(level, pos))) return;
            Block.popResource(level, pos, item());
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Messages.body("The Bank is lifted. Set it down again on your kingdom's land."));
            }
            s.log.admin(player.getName().getString() + " broke the Bank at " + BankSites.key(level, pos));
        });

        ServerTickEvents.END_SERVER_TICK.register(this::tick);
    }

    private void tick(MinecraftServer server) {
        if (!pending.isEmpty()) {
            for (Pending p : List.copyOf(pending)) settle(server, p);
            pending.clear();
        }
        int now = server.getTickCount();
        if (now % LOOK_TICKS == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) lookAt(p);
        }
        if (now % SWEEP_TICKS == 0) sweep(server);
    }

    /** Records a placement once the vault is really standing, and tames it. */
    private void settle(MinecraftServer server, Pending p) {
        if (!p.level().getBlockState(p.pos()).is(Blocks.VAULT)) return;
        if (!banks.add(p.level(), p.pos(), p.kingdom())) return;
        tame(p.level(), p.pos());
        ServerPlayer placer = server.getPlayerList().getPlayer(p.player());
        String where = p.kingdom().map(k -> s.kingdoms.source().displayName(k) + "'s Bank").orElse("A Bank");
        if (placer != null) {
            placer.sendSystemMessage(Messages.good(where + " stands here. Right-click it to open."));
            placer.sendSystemMessage(Messages.body("Any smithing table in this chunk is now its Mint."));
        }
        s.log.admin((placer == null ? p.player().toString() : placer.getName().getString())
                + " placed " + where + " at " + BankSites.key(p.level(), p.pos()));
    }

    /** No loot, a key nobody can hold, and a gold nugget on show. */
    private static void tame(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof VaultBlockEntity vault) {
            vault.setConfig(new VaultConfig(NO_LOOT, 4.0, 4.5, new ItemStack(Items.BARRIER), Optional.of(SHOWN)));
            vault.setChanged();
        }
    }

    /** Forgets a Bank whose chunk is loaded and whose vault is gone. */
    private void sweep(MinecraftServer server) {
        for (String site : banks.list()) forgetIfGone(server, site);
    }

    private void forgetIfGone(MinecraftServer server, String site) {
        String[] p = site.split(" ");
        if (p.length != 4) return;
        for (ServerLevel level : server.getAllLevels()) {
            if (!BankSites.dimension(level).equals(p[0])) continue;
            BlockPos pos;
            try {
                pos = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            } catch (NumberFormatException e) {
                return;
            }
            if (level.isLoaded(pos) && !level.getBlockState(pos).is(Blocks.VAULT)) {
                banks.remove(site);
                s.log.admin("Forgot the Bank at " + site + ": its vault is gone");
            }
            return;
        }
    }

    // ---- placing ------------------------------------------------------------------

    private Banks.Placement decide(ServerPlayer player, Level level, BlockPos target) {
        boolean op = TreasuryCommands.isOp(player.createCommandSourceStack());
        KingdomSource source = s.kingdoms.source();
        UUID id = player.getUUID();
        Optional<String> kingdom = source.kingdomOf(id);
        boolean lord = kingdom.map(k -> source.lordsOf(k).contains(id)).orElse(false);
        boolean landKnown = source.knowsLand();
        Optional<String> owner = landKnown
                ? source.landOwner(BankSites.dimension(level), target.getX() >> 4, target.getZ() >> 4)
                : Optional.empty();
        // A slot held by a Bank that is gone is freed before the count is read.
        Optional<String> forKingdom = op && landKnown ? owner : kingdom;
        MinecraftServer server = player.level().getServer();
        forKingdom.flatMap(banks::siteOf).ifPresent(site -> forgetIfGone(server, site));
        return banks.check(op, kingdom, lord, landKnown, owner);
    }

    private String refusal(Banks.Placement placement) {
        return switch (placement.refusal()) {
            case NO_KINGDOM -> "Only a kingdom's Lord can set down a Bank, and you are in no kingdom.";
            case NOT_A_LORD -> "Only your kingdom's Lord can set down its Bank.";
            case NO_LAND -> "Only an operator can set down a Bank on this server.";
            case NOT_YOUR_LAND -> "Set the Bank on your own kingdom's land: the keep or an outpost.";
            case ALREADY_HAS_ONE -> {
                String k = placement.kingdom().orElse("");
                String at = banks.siteOf(k).map(BankStation::coordinates).orElse("somewhere");
                yield s.kingdoms.source().displayName(k) + " already has its Bank, at " + at
                        + ". A kingdom has one; break that one to move it.";
            }
            case NONE -> "";
        };
    }

    /** {@code -3 64 40 in the overworld} from a site key. */
    static String coordinates(String site) {
        String[] p = site.split(" ");
        if (p.length != 4) return site;
        String dim = p[0].substring(p[0].indexOf(':') + 1).replace('_', ' ');
        return p[1] + " " + p[2] + " " + p[3] + " in the " + dim;
    }

    // ---- the menu -----------------------------------------------------------------

    void open(ServerPlayer player) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false);
        gui.setTitle(Component.literal("Bank").withStyle(ChatFormatting.GOLD));
        draw(gui, player);
        gui.open();
    }

    /**
     * Three rows, read top to bottom: your money, your kingdom's treasury, the services. Every button runs the
     * command a player could type, so the menu and chat never disagree; the treasury Take buttons run the door of
     * whoever governs spending (Charter's Withdraw when Charter is installed), and only show to a player that
     * door would let through ({@link Banks#showsTreasuryTake}).
     */
    private void draw(SimpleGui gui, ServerPlayer player) {
        Currency coin = s.coin();
        Denomination top = coin.largestFirst().get(0);
        long banked = s.economy.getBalance(player.getUUID(), coin);
        long carried = 0;
        for (Denomination d : coin.denominations()) carried += CoinOps.carried(player, d) * d.value();

        // ---- row one: yours ----
        gui.setSlot(0, new GuiElementBuilder(Items.GOLD_INGOT)
                .setName(Component.literal("Your money").withStyle(ChatFormatting.GOLD))
                .setLore(List.of(
                        line("In the bank: ", ShopDisplay.coins(coin, banked)),
                        line("Carrying: ", ShopDisplay.coins(coin, carried)),
                        dim("The shop spends what is in the bank."),
                        dim("Show me the money!")))
                .build());
        gui.setSlot(2, new GuiElementBuilder(Items.HOPPER)
                .setName(Component.literal("Bank all your coin").withStyle(ChatFormatting.GREEN))
                .setLore(List.of(dim("Every coin you carry goes in your bank.")))
                .setCallback((i, t, a, g) -> run(gui, player, "bank all"))
                .build());
        int[] amounts = {1, 8, 64};
        for (int n = 0; n < amounts.length; n++) {
            int count = amounts[n];
            gui.setSlot(4 + n, new GuiElementBuilder(coinItem(top))
                    .setCount(count)
                    .setName(Component.literal("Take out " + top.describe(count)).withStyle(ChatFormatting.YELLOW))
                    .setLore(List.of(dim(banked >= (long) count * top.value()
                            ? "From your bank into your pockets."
                            : "Not enough in your bank.")))
                    .setCallback((i, t, a, g) -> run(gui, player, "withdraw " + count + " " + top.id()))
                    .build());
        }

        // ---- row two: your kingdom's ----
        drawTreasury(gui, player, coin, top, carried);

        // ---- row three: the services ----
        List<Component> mintLore = new ArrayList<>();
        mintLore.add(dim("Any smithing table in this chunk."));
        mintLore.add(dim("Hold gold ingots and right-click it."));
        mintLore.add(dim("One gold ingot strikes " + Mint.COINS_PER_STRIKE + " Sovereigns."));
        mintLore.add(dim("Sneak and right-click: strike them all."));
        mintLore.add(Messages.body(mint.allowanceFor(player)));
        mintLore.add(dim("One coin in five goes to your kingdom."));
        gui.setSlot(18, new GuiElementBuilder(Items.SMITHING_TABLE)
                .setName(Component.literal("The Mint").withStyle(ChatFormatting.GOLD))
                .setLore(mintLore)
                .build());
        gui.setSlot(22, new GuiElementBuilder(Items.EMERALD)
                .setName(Component.literal("Open the shop").withStyle(ChatFormatting.GREEN))
                .setLore(List.of(dim("Buy with what is in your bank.")))
                .setCallback((i, t, a, g) -> command(player, "shop"))
                .build());
        gui.setSlot(26, new GuiElementBuilder(Items.BARRIER)
                .setName(Component.literal("Close").withStyle(ChatFormatting.RED))
                .setCallback((i, t, a, g) -> g.close())
                .build());
    }

    /** The middle row: the treasury of the player's kingdom, Give buttons for everyone in it, Take buttons for its spenders. */
    private void drawTreasury(SimpleGui gui, ServerPlayer player, Currency coin, Denomination top, long carried) {
        KingdomSource kingdoms = s.kingdoms.source();
        Optional<String> kingdom;
        try {
            kingdom = kingdoms.kingdomOf(player.getUUID());
        } catch (RuntimeException e) {
            CharterCurrencyMod.LOG.warn("Kingdom lookup failed at the Bank", e);
            kingdom = Optional.empty();
        }
        if (kingdom.isEmpty()) {
            gui.setSlot(9, new GuiElementBuilder(Items.CHEST)
                    .setName(Component.literal("Kingdom treasury").withStyle(ChatFormatting.GRAY))
                    .setLore(List.of(dim("You are in no kingdom."), dim("Join one and its treasury shows here.")))
                    .build());
            return;
        }
        String org = kingdom.get();
        String name = kingdoms.displayName(org);
        long balance = s.treasury.balance(org);
        boolean op = TreasuryCommands.isOp(player.createCommandSourceStack());
        boolean spender = Banks.showsTreasuryTake(kingdoms, org, player.getUUID(), false, top.value());

        List<Component> lore = new ArrayList<>();
        lore.add(line("Holds: ", ShopDisplay.coins(coin, balance)));
        lore.add(line("Mint tax, all time: ", ShopDisplay.coins(coin, s.treasury.taxCollected(org))));
        List<Treasury.Entry> recent = s.treasury.ledger(org, "coin");
        if (!recent.isEmpty()) {
            lore.add(dim("Lately:"));
            for (int i = 0; i < Math.min(3, recent.size()); i++) lore.add(dim("  " + TreasuryCommands.line(recent.get(i))));
        }
        lore.add(dim(spender ? "You may take coin out." : "Your Lord, and anyone granted the spend right, take coin out."));
        gui.setSlot(9, new GuiElementBuilder(Items.CHEST)
                .setName(Component.literal(name + "'s treasury").withStyle(ChatFormatting.GOLD))
                .setLore(lore)
                .build());

        gui.setSlot(11, new GuiElementBuilder(Items.HOPPER)
                .setName(Component.literal("Give all your coin to " + name).withStyle(ChatFormatting.GREEN))
                .setLore(List.of(dim("Every coin you carry goes to the treasury."),
                        dim(carried > 0 ? "You carry " + coin.describe(carried) + "." : "You carry no coin.")))
                .setCallback((i, t, a, g) -> run(gui, player, "treasury deposit all"))
                .build());
        for (int n = 0; n < Banks.TREASURY_GIVES.length; n++) {
            int count = Banks.TREASURY_GIVES[n];
            gui.setSlot(12 + n, new GuiElementBuilder(coinItem(top))
                    .setCount(count)
                    .setName(Component.literal("Give " + top.describe(count)).withStyle(ChatFormatting.GREEN))
                    .setLore(List.of(dim(carried >= (long) count * top.value()
                            ? "From your pockets to the treasury."
                            : "You do not carry that much.")))
                    .setCallback((i, t, a, g) -> run(gui, player, "treasury deposit " + count + " " + top.id()))
                    .build());
        }

        for (int n = 0; n < Banks.TREASURY_TAKES.length; n++) {
            int count = Banks.TREASURY_TAKES[n];
            long marks = (long) count * top.value();
            if (!Banks.showsTreasuryTake(kingdoms, org, player.getUUID(), op, marks)) continue;
            // Charter's own door when Charter governs and recognises this spender; otherwise this mod's, which lets ops through.
            String door = kingdoms.governsSpending() && kingdoms.maySpend(org, player.getUUID(), marks)
                    ? "charter vault withdraw " + marks
                    : "treasury withdraw " + count + " " + top.id();
            gui.setSlot(15 + n, new GuiElementBuilder(coinItem(top))
                    .setCount(count)
                    .setName(Component.literal("Take " + top.describe(count) + " from the treasury").withStyle(ChatFormatting.YELLOW))
                    .setLore(List.of(dim(balance >= marks ? "From the treasury into your pockets." : "The treasury does not hold that much."),
                            dim("Written in the ledger under your name.")))
                    .setCallback((i, t, a, g) -> run(gui, player, door))
                    .build());
        }

        gui.setSlot(20, new GuiElementBuilder(Items.WRITABLE_BOOK)
                .setName(Component.literal("The ledger").withStyle(ChatFormatting.GOLD))
                .setLore(List.of(dim("Every coin in and out of the treasury, in chat."), dim("Follow the money.")))
                .setCallback((i, t, a, g) -> {
                    g.close();
                    command(player, "treasury ledger");
                })
                .build());
    }

    private static net.minecraft.world.item.Item coinItem(Denomination d) {
        return ItemBank.resolve(d.itemId()).orElse(Items.GOLD_NUGGET);
    }

    /** Runs the same command a player would type, so the menu and chat can never differ, then redraws. */
    private void run(SimpleGui gui, ServerPlayer player, String commandLine) {
        command(player, commandLine);
        draw(gui, player);
    }

    private static void command(ServerPlayer player, String commandLine) {
        player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), commandLine);
    }

    private static MutableComponent line(String label, MutableComponent value) {
        return Messages.body(label).append(value);
    }

    private static Component dim(String text) {
        return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
    }

    // ---- the look-at line -------------------------------------------------------------

    /** A Bank or a Mint in the crosshair says what it is on the action bar. */
    private void lookAt(ServerPlayer player) {
        if (player.isSpectator()) return;
        HitResult hit = player.pick(player.blockInteractionRange(), 1.0f, false);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = block.getBlockPos();
        Level level = player.level();
        if (banks.isBank(level, pos)) {
            player.sendOverlayMessage(Messages.good("Bank: right-click to open. In the bank: ")
                    .append(ShopDisplay.coins(s.coin(), s.economy.getBalance(player.getUUID(), s.coin()))));
        } else if (level.getBlockState(pos).is(Blocks.SMITHING_TABLE)
                && (s.sites.isBound(level, pos) || banks.inChunkOf(level, pos))) {
            player.sendOverlayMessage(Messages.good("Mint: right-click with gold ingots. ")
                    .append(Messages.body(mint.allowanceFor(player))));
        }
    }
}
