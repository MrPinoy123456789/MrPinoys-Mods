package foundry.mc;

import eu.pb4.polymer.core.api.item.SimplePolymerItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import foundry.core.mechanics.MechanicDef;
import foundry.core.mechanics.Trigger;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;

/**
 * The one item class every data-driven item uses. All behaviour comes from the definition, never from a
 * per-item subclass. Vanilla clients see {@code base} plus an item model reference from the resource pack.
 */
final class FoundryItem extends SimplePolymerItem {
    private final Identifier clientModel;

    private final String contentId;
    private final foundry.core.Ident ident;

    FoundryItem(Item.Properties properties, Item base, Identifier clientModel, foundry.core.Ident ident) {
        super(properties, base);
        this.clientModel = clientModel;
        this.ident = ident;
        this.contentId = ident.toString();
    }

    /** Read from the live content set each time, so a successful /foundry reload changes mechanics without a restart. */
    private List<MechanicDef> mechanics() {
        var def = FoundryMod.loaded().items().get(ident);
        return def == null ? List.of() : def.mechanics();
    }

    /** The live definition, or null while it is missing from the content set. */
    foundry.core.ItemDef def() {
        return FoundryMod.loaded().items().get(ident);
    }

    /** Runs this item's mechanics for a trigger raised outside the item hooks (a left click swing). */
    boolean fire(Trigger trigger, ServerPlayer player, LivingEntity hit) {
        if (!Gate.allowed(player, def())) return false;
        return MechanicHost.fire(trigger, contentId, mechanics(), player, hit);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() instanceof ServerPlayer sp) Swings.suppress(sp);
        if (context.getPlayer() instanceof ServerPlayer locked && !Gate.check(locked, def())) return InteractionResult.FAIL;
        var furniture = Furniture.forItem(ident);
        if (furniture != null && Furniture.place(context, furniture)) return InteractionResult.SUCCESS;
        return super.useOn(context);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer sp) {
            // Right click makes the server swing the arm; that swing is not a left click.
            Swings.suppress(sp);
            if (!Gate.check(sp, def())) return InteractionResult.FAIL;
            var list = mechanics();
            // A charged mechanic starts a hold, and fires when it is released after long enough.
            int armed = MechanicHost.armedCharge(contentId, list, sp);
            if (armed >= 0) {
                Charging.begin(sp, new Charging.Hold(contentId, armed, sp.level().getServer().getTickCount(),
                        list.get(armed).chargeTicks()));
                sp.startUsingItem(hand);
                return InteractionResult.CONSUME;
            }
            if (MechanicHost.fire(Trigger.ON_USE, contentId, list, sp, null)) return InteractionResult.SUCCESS;
        }
        return super.use(level, player, hand);
    }

    private boolean hasCharge() {
        return mechanics().stream().anyMatch(MechanicDef::charged);
    }

    /** A held item has no end: the player decides when to let go. Items with no charged mechanic are untouched. */
    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return hasCharge() ? 72000 : super.getUseDuration(stack, entity);
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remainingTicks) {
        if (!(entity instanceof ServerPlayer sp)) return false;
        var hold = Charging.end(sp);
        if (hold == null || !hold.contentId().equals(contentId)) return false;
        long held = sp.level().getServer().getTickCount() - hold.startTick();
        if (held < hold.needTicks()) {
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal("Hold longer"), true);
            return false;
        }
        return MechanicHost.fireCharged(contentId, mechanics(), hold.index(), sp);
    }

    /** What the client is told to show: a bow draw for a charged item, and the melee numbers the server uses. */
    private static final net.minecraft.world.item.component.Consumable CHARGE_LOOK =
            net.minecraft.world.item.component.Consumable.builder()
                    .consumeSeconds(3600f)
                    .animation(net.minecraft.world.item.ItemUseAnimation.BOW)
                    .sound(net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
                            Identifier.parse("minecraft:intentionally_empty"))))
                    .hasConsumeParticles(false)
                    .build();

    @Override
    public void modifyBasePolymerItemStack(ItemStack out, ItemStack stack, PacketContext context, HolderLookup.Provider lookup) {
        super.modifyBasePolymerItemStack(out, stack, context, lookup);
        var def = FoundryMod.loaded().items().get(ident);
        if (def != null && def.hasMeleeStats()) {
            var attributes = stack.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS);
            if (attributes != null) out.set(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS, attributes);
        }
        // Held for a "long" time on the client, so it never finishes by itself; the server decides at release.
        if (hasCharge()) out.set(net.minecraft.core.component.DataComponents.CONSUMABLE, CHARGE_LOOK);
    }

    @Override
    public void hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        super.hurtEnemy(stack, target, attacker);
        if (attacker instanceof ServerPlayer sp && Gate.allowed(sp, def())) MechanicHost.fire(Trigger.ON_HIT_ENTITY, contentId, mechanics(), sp, target);
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context, HolderLookup.Provider lookup) {
        return clientModel != null ? clientModel : super.getPolymerItemModel(stack, context, lookup);
    }
}
