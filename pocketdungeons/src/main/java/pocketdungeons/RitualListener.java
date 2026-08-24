package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The lodestone ritual: right-click a lodestone holding the keystone and the
 * dungeon opens, with no command typed.
 *
 * <h2>U7 inverts the key rule</h2>
 *
 * <p>U4's rule was "consume only a stack with <em>no</em> {@code custom_data} at
 * all", which was right when the key was a plain echo shard and the danger was
 * eating somebody's kamutotems sigil. It is now the exact opposite: consume
 * <strong>only</strong> a stack carrying {@code pocketdungeons.keystone}, and
 * {@code PASS} on everything else -- sigils included, for exactly U4's reasoning,
 * and now for free, because the test is positive rather than a list of things to
 * avoid. Lodestone plus keystone is the font.
 *
 * <h2>U8 Stage 4: one lodestone, three destinations</h2>
 *
 * <p>Right-clicking a lodestone with the compass now branches on server state
 * (T14): a pending door offer routes to the selector room, an owned live
 * instance re-enters it for free, and anything else opens a fresh run. Ominous
 * is no longer requested here at all -- it rides entirely on the keystone's own
 * affix (U8 Stage 6).
 *
 * <p>{@code /dungeon} still works and still costs the same keystone. This is a
 * flavour entry point, not a gate: it is the <em>lodestone</em> that is optional,
 * never the key.
 */
final class RitualListener {

    private RitualListener() {}

    static void register() {
        UseBlockCallback.EVENT.register(RitualListener::onUseBlock);

        // Resolve every configured item once at startup rather than on the first
        // click. A typo is an operator's mistake to hear about at boot, not
        // something a player discovers by right-clicking a lodestone and having
        // nothing happen.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Keystone.warmUp();
            TrialContent.warmUp();
        });
    }

    private static InteractionResult onUseBlock(Player player, Level level,
                                                InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        // Both hands fire this event; without the gate the ritual would try to
        // run twice for a single click.
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        BlockPos pos = hit.getBlockPos();

        // T13: a door in the player's own selector room. Handled mod-side and
        // ahead of everything else -- vanilla's own door open/close must never
        // run for one of these, or a door that swings looks like it did
        // something.
        Integer step = Instances.selectorDoorStep(serverPlayer, pos);
        if (step != null) {
            sendDoorOffer(serverPlayer, step);
            return InteractionResult.SUCCESS_SERVER;
        }

        if (!PocketDungeonsConfig.ritualEnabled()) {
            return InteractionResult.PASS;
        }
        if (!level.getBlockState(pos).is(Blocks.LODESTONE)) {
            return InteractionResult.PASS;
        }
        // Sneaking means "act on what I am holding, not on this block" -- it is
        // how vanilla lets you place against a block you would otherwise use, and
        // it is the escape hatch for an operator who points ritualKeyItem at
        // something placeable. Same guard kamutotems' Station uses.
        if (player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        // The positive test that replaced U4's "no custom_data at all". A
        // kamutotems sigil is not a keystone, so it PASSes here and the event
        // chain continues to kamutotems' own use handler untouched -- the exact
        // outcome U4 wanted, reached by a rule that cannot be wrong about a tagged
        // item nobody has written yet.
        ItemStack held = player.getItemInHand(hand);
        if (!Keystone.isKeystone(held)) {
            return InteractionResult.PASS;
        }

        // T14: branch on server state before touching the "already inside"
        // guards below -- a pending offer or an owned instance both route
        // somewhere other than a fresh dungeon, from any lodestone anywhere.
        DungeonLog.Entry entry = DungeonLog.forServer(serverPlayer.level().getServer())
                .get(serverPlayer.getUUID());
        if (entry.pendingOfferLevel() > 0) {
            level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.BLOCKS, 1.0f, 1.0f);
            Instances.enterSelectorRoom(serverPlayer);
            return InteractionResult.SUCCESS_SERVER;
        }

        // The exit pad is a lodestone too. Without this, standing on it and
        // right-clicking it would eat a key to "enter" a dungeon you are already
        // standing in. The dimension check covers a player who is in the void
        // without a live record -- the orphan-recovery case. Free re-entry (T5)
        // is handled by enterWithKeystone itself, ahead of any keystone spend.
        if (Instances.hasInstance(serverPlayer)
                || serverPlayer.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            return InteractionResult.PASS;
        }

        // Ahead of entry, so the player who is about to be teleported away is
        // still here to hear it.
        level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_CHARGE,
                SoundSource.BLOCKS, 1.0f, 1.0f);

        // Consume only on success. enterWithKeystone() has failure paths --
        // missing dimension, a stamp that could not be placed -- that leave the
        // player exactly where they stood, and eating a keystone somebody spent
        // several runs earning on one of those is a real loss.
        if (!Instances.enterWithKeystone(serverPlayer)) {
            return InteractionResult.PASS;
        }
        serverPlayer.sendSystemMessage(Component.literal("The lodestone pulls you under.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * The offer as chat with a clickable accept (T13). Verified against the
     * 26.2 jar: {@code ClickEvent} is a sealed interface with record subtypes,
     * so {@code new ClickEvent.RunCommand(...)} plus {@code withClickEvent} is
     * the shape -- the old {@code new ClickEvent(Action, String)} constructor
     * form does not compile here.
     */
    private static void sendDoorOffer(ServerPlayer player, int step) {
        DungeonLog.Entry entry = DungeonLog.forServer(player.level().getServer()).get(player.getUUID());
        int pendingLevel = entry.pendingOfferLevel();
        if (pendingLevel <= 0) {
            player.sendSystemMessage(Component.literal(
                    "There is no offer waiting for you here.").withStyle(ChatFormatting.RED));
            return;
        }
        Keystone.Offer[] offers = Keystone.offers(pendingLevel);
        Keystone.Offer offer = offers[Math.min(step - 1, offers.length - 1)];

        String heading = (offer.affix() == Keystone.Affix.NONE ? "Oak" : offer.affix().label)
                + " Door -- Keystone [" + offer.level() + "]"
                + (offer.affix() == Keystone.Affix.NONE ? "" : ", " + offer.affix().label.toLowerCase())
                + ".";
        player.sendSystemMessage(Component.literal(heading).withStyle(offer.affix().colour));
        if (offer.affix() == Keystone.Affix.OMINOUS) {
            player.sendSystemMessage(Component.literal(
                    "Every room runs ominous. The reward room rolls the ominous tables.")
                    .withStyle(ChatFormatting.GRAY));
        } else if (offer.affix() == Keystone.Affix.FRAGILE) {
            player.sendSystemMessage(Component.literal(
                    "Fragile: your next failure costs double.")
                    .withStyle(ChatFormatting.GRAY));
        }

        Component accept = Component.literal("[ Take this key ]")
                .withStyle(s -> s.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent.RunCommand("/dungeon choose " + step)));
        player.sendSystemMessage(accept);
    }
}
