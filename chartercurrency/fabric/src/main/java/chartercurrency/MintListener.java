package chartercurrency;

import chartercurrency.core.Currency;
import chartercurrency.core.Denomination;
import chartercurrency.core.Mint;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

/**
 * The Mint: right-click a bound smithing table, or any smithing table in a Bank's chunk, to strike Gold Sovereigns from gold ingots. Copper and iron
 * ingots are refused ({@link Mint#strikes}): farms make them without limit.
 *
 * <p>Coin enters the world only here. The order of a strike is: plan, take the ingots,
 * commit the state and the tax, give the coin. A strike that cannot take its ingots
 * changes nothing; nothing is ever half done. The arithmetic is {@link Mint}, under
 * test; this class only moves items.
 *
 * <p>The table never opens its UI: both hands are consumed on a bound table.
 */
final class MintListener {

    private final Services s;

    MintListener(Services services) {
        this.s = services;
    }

    void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hit.getBlockPos();
            if (!level.getBlockState(pos).is(Blocks.SMITHING_TABLE) || !isMint(level, pos)) {
                return InteractionResult.PASS;
            }
            // Only the main hand acts; the off-hand pass is consumed so the table's own
            // screen cannot open from it.
            if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) use(sp);
            return InteractionResult.CONSUME;
        });
    }

    /**
     * A table an operator bound, or any smithing table in the chunk of a Bank
     * ({@link BankStation}). Every Mint works the same; the tax follows the striker.
     */
    boolean isMint(net.minecraft.world.level.Level level, BlockPos pos) {
        return s.sites.isBound(level, pos) || (s.banks != null && s.banks.inChunkOf(level, pos));
    }

    /** The ingot item a denomination is struck from, by denomination id. */
    static Optional<Item> ingotFor(Denomination d) {
        return switch (d.id()) {
            case "copper" -> Optional.of(Items.COPPER_INGOT);
            case "iron" -> Optional.of(Items.IRON_INGOT);
            case "gold" -> Optional.of(Items.GOLD_INGOT);
            default -> Optional.empty();
        };
    }

    /** The denomination whose ingot this is, struck at the Mint or not. */
    private Optional<Denomination> denominationOf(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        for (Denomination d : s.coin().denominations()) {
            Optional<Item> ingot = ingotFor(d);
            if (ingot.isPresent() && stack.is(ingot.get())) return Optional.of(d);
        }
        return Optional.empty();
    }

    private void use(ServerPlayer player) {
        if (CoinStacks.markerOf(player.getMainHandItem()).isPresent()) {
            // Coin is never melted (CoinCrafting); the shop sells the ingot at the Mint's rate.
            player.sendSystemMessage(Messages.body("The Mint does not melt coin. A gold ingot is "
                    + Mint.COINS_PER_STRIKE + " Sovereigns in the shop: /shop"));
            return;
        }
        Optional<Denomination> held = denominationOf(player.getMainHandItem());
        if (held.isEmpty()) {
            showAllowance(player);
            return;
        }
        if (!Mint.strikes(held.get().id())) {
            player.sendSystemMessage(Messages.body("The Mint strikes gold only. Copper and iron are not money here."));
            return;
        }
        strike(player, held.get(), player.isShiftKeyDown());
    }

    /** The remaining allowance, as the {@code /mint} command and an empty-handed click show it. */
    void showAllowance(ServerPlayer player) {
        Mint.State st = s.mint.state(player.getUUID(), s.settings.today());
        player.sendSystemMessage(Messages.body(allowanceLine(st)));
        player.sendSystemMessage(Messages.body("One coin in five goes to your kingdom's treasury."));
    }

    /** Today's allowance in one line, for the Bank menu and the look-at line. */
    String allowanceFor(ServerPlayer player) {
        return allowanceLine(s.mint.state(player.getUUID(), s.settings.today()));
    }

    private String allowanceLine(Mint.State st) {
        Mint.Rules rules = s.settings.mint;
        int cost = Mint.ingotsPerStrike(st.ingotsToday, rules);
        long until = Mint.ingotsUntilFalloff(st.ingotsToday, rules);
        int per = Mint.COINS_PER_STRIKE;
        if (cost == 1) {
            return "Today: " + until + " of " + rules.softCapIngots()
                    + " gold ingots left at full rate, " + per + " Sovereigns each.";
        }
        if (until < 0) {
            return "Past today's full rate: " + cost + " ingots per " + per + " Sovereigns until midnight.";
        }
        return "Past today's full rate: " + cost + " ingots per " + per + " Sovereigns, " + (cost * 2)
                + " after " + until + " more. Full rate again at midnight.";
    }

    private void strike(ServerPlayer player, Denomination d, boolean everyStack) {
        Item ingot = ingotFor(d).orElseThrow();
        Currency coin = s.coin();

        long offered = everyStack
                ? ItemBank.count(player, ingot)
                : Math.min(ItemBank.count(player, ingot), player.getMainHandItem().getCount());
        int ingots = (int) Math.min(Integer.MAX_VALUE, offered);

        // The kingdom is asked every time; if it cannot be answered nothing is struck
        // and nothing is destroyed.
        String kingdom;
        try {
            kingdom = s.kingdoms.source().kingdomOf(player.getUUID()).orElse(null);
        } catch (RuntimeException e) {
            CharterCurrencyMod.LOG.warn("Kingdom lookup failed during a strike", e);
            player.sendSystemMessage(Messages.bad(
                    "The Mint cannot reach the kingdom records right now. Try again shortly."));
            return;
        }

        long today = s.settings.today();
        Mint.Plan plan = s.mint.preview(player.getUUID(), d.id(), d.value(), ingots, today);
        if (!plan.struck()) {
            Mint.State st = s.mint.state(player.getUUID(), today);
            if (plan.shortfall() == Mint.Shortfall.NEED_MORE_INGOTS) {
                player.sendSystemMessage(Messages.body(Mint.COINS_PER_STRIKE + " " + d.plural() + " cost "
                        + Mint.ingotsPerStrike(st.ingotsToday, s.settings.mint) + " ingots now. "
                        + allowanceLine(st)));
            } else {
                player.sendSystemMessage(Messages.body(allowanceLine(st)));
            }
            return;
        }

        // Ingots first. Matching is by item only, across the main 36 slots.
        if (!ItemBank.removeExactly(player, stack -> stack.is(ingot), plan.ingotsUsed())) {
            player.sendSystemMessage(Messages.bad("Could not take the ingots. Nothing was struck."));
            return;
        }

        String actor = player.getName().getString();
        long paid = s.mint.commit(player.getUUID(), plan, kingdom, actor, today);

        // Ten coins an ingot outgrows a pocket fast: what fits is handed over, the rest
        // goes straight into the bank rather than onto the floor.
        long banked = 0;
        if (plan.coinsToPlayer() > 0) {
            ItemStack sample = CoinStacks.make(d, coin, 1);
            long fits = Math.min(plan.coinsToPlayer(), ItemBank.freeCapacity(player, sample));
            long rest = plan.coinsToPlayer() - fits;
            if (rest > 0 && s.economy.deposit(player.getUUID(), coin, rest * d.value()).ok()) {
                banked = rest;
            } else {
                fits = plan.coinsToPlayer();
            }
            long dropped = fits > 0 ? ItemBank.give(player, sample, fits) : 0;
            if (dropped > 0) {
                CharterCurrencyMod.LOG.warn("Mint coins for {} overflowed capacity by {}", actor, dropped);
            }
        }
        Chime.purchased(player);

        String name = plan.coinsStruck() == 1 ? d.singular() : d.plural();
        StringBuilder line = new StringBuilder("Struck " + plan.coinsStruck() + " " + name + ": "
                + plan.coinsToPlayer() + " to you");
        if (plan.coinsToKingdom() > 0) {
            if (paid > 0 && kingdom != null) {
                line.append(", ").append(plan.coinsToKingdom()).append(" to ")
                        .append(s.kingdoms.source().displayName(kingdom));
            } else {
                line.append(", ").append(plan.coinsToKingdom()).append(" lost to the Mint");
            }
        }
        line.append(". ").append(allowanceLine(s.mint.state(player.getUUID(), today)));
        player.sendSystemMessage(Messages.good(line.toString()));
        if (banked > 0) {
            player.sendSystemMessage(Messages.body("Your pockets were full, so " + banked + " "
                    + (banked == 1 ? d.singular() : d.plural()) + " went straight into your bank."));
        }
        if (kingdom == null && plan.coinsToKingdom() > 0) {
            player.sendSystemMessage(Messages.body(
                    "You are in no kingdom, so the taxed coin is not kept by anyone."));
        }

        s.log.tx(actor + " struck " + plan.coinsStruck() + " " + d.id() + " (" + plan.marksStruck()
                + " Marks, " + plan.ingotsUsed() + " ingots); to player " + plan.coinsToPlayer()
                + (banked > 0 ? " (" + banked + " banked, pockets full)" : "")
                + ", tax " + plan.marksToKingdom() + " Marks to "
                + (kingdom == null ? "nobody (destroyed)" : kingdom));
    }
}
