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
 * The Mint: right-click a bound smithing table to strike coin from ingots.
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
            if (!level.getBlockState(pos).is(Blocks.SMITHING_TABLE) || !s.sites.isBound(level, pos)) {
                return InteractionResult.PASS;
            }
            // Only the main hand acts; the off-hand pass is consumed so the table's own
            // screen cannot open from it.
            if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) use(sp);
            return InteractionResult.CONSUME;
        });
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

    private Optional<Denomination> denominationOf(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        for (Denomination d : s.coin().denominations()) {
            Optional<Item> ingot = ingotFor(d);
            if (ingot.isPresent() && stack.is(ingot.get())) return Optional.of(d);
        }
        return Optional.empty();
    }

    private void use(ServerPlayer player) {
        Optional<Denomination> held = denominationOf(player.getMainHandItem());
        if (held.isEmpty()) {
            showAllowance(player);
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

    private String allowanceLine(Mint.State st) {
        long perDay = s.settings.mint.fullBandPerDay();
        if (st.fullRemaining + st.halfRemaining <= 0) {
            return "The Mint is done with you for today. It reopens at midnight.";
        }
        return "Today: " + st.fullRemaining + " of " + Math.max(perDay, st.fullRemaining)
                + " left at full rate, then " + st.halfRemaining + " at half.";
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
            switch (plan.shortfall()) {
                case NEED_MORE_INGOTS -> player.sendSystemMessage(Messages.body(
                        "Half rate now: " + Mint.HALF_BAND_INGOTS + " ingots per coin."));
                case NOTHING_OFFERED -> player.sendSystemMessage(Messages.body(allowanceLine(st)));
                default -> {
                    if (st.fullRemaining + st.halfRemaining <= 0) {
                        player.sendSystemMessage(Messages.body(allowanceLine(st)));
                    } else {
                        player.sendSystemMessage(Messages.body("Not enough allowance left for a "
                                + d.singular() + ". " + allowanceLine(st)));
                    }
                }
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

        if (plan.coinsToPlayer() > 0) {
            long dropped = ItemBank.give(player, CoinStacks.make(d, coin, 1), plan.coinsToPlayer());
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
        if (plan.coinsHalf() > 0) {
            player.sendSystemMessage(Messages.body(
                    "Half rate now: " + Mint.HALF_BAND_INGOTS + " ingots per coin."));
        }
        if (kingdom == null && plan.coinsToKingdom() > 0) {
            player.sendSystemMessage(Messages.body(
                    "You are in no kingdom, so the taxed coin is not kept by anyone."));
        }

        s.log.tx(actor + " struck " + plan.coinsStruck() + " " + d.id() + " (" + plan.marksStruck()
                + " Marks, " + plan.ingotsUsed() + " ingots); to player " + plan.coinsToPlayer()
                + ", tax " + plan.marksToKingdom() + " Marks to "
                + (kingdom == null ? "nobody (destroyed)" : kingdom));
    }
}
