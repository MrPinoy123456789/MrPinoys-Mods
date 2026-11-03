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

    /** The content id this item was registered for. */
    String contentId() {
        return contentId;
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
        // A Bedrock player's right click on a block reaches the server only as a click on that block: Geyser sends no
        // separate use, so a staff aimed at the ground or a wall did nothing. Java clients send the use as well, after
        // this, so for them the block click stays a plain block click.
        if (furniture == null && context.getPlayer() instanceof ServerPlayer sp && foundry.core.Floodgate.isBedrock(sp.getUUID())) {
            InteractionResult used = bedrockUse(sp, context.getHand());
            if (used != null) {
                USED_ON.put(sp.getUUID(), (long) sp.level().getServer().getTickCount());
                return used;
            }
        }
        return super.useOn(context);
    }

    /** When a Bedrock player's block click last ran this item's use, so a use in the same tick is not run twice. */
    private static final java.util.Map<java.util.UUID, Long> USED_ON = new java.util.HashMap<>();

    /**
     * A use by a Bedrock player: a charged mechanic starts a cast that fires by itself after its charge time (Charging),
     * anything else fires at once. Null when the item has nothing to do on use.
     */
    private InteractionResult bedrockUse(ServerPlayer sp, InteractionHand hand) {
        var list = mechanics();
        if (Charging.casting(sp.getUUID())) return InteractionResult.CONSUME;
        int armed = MechanicHost.armedCharge(contentId, list, sp);
        if (armed >= 0) {
            var m = list.get(armed);
            Charging.cast(sp, new Charging.Cast(contentId, armed, sp.level().getServer().getTickCount() + m.chargeTicks(), hand));
            String what = m.name() == null || m.name().isBlank() ? "spell" : m.name();
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal("Casting " + what + "... keep it in hand"), true);
            return InteractionResult.CONSUME;
        }
        if (MechanicHost.fire(Trigger.ON_USE, contentId, list, sp, null)) return InteractionResult.SUCCESS;
        if (hasCharge()) AbilityBar.poke(sp);
        return null;
    }

    /** Fires the charged mechanic a Bedrock cast armed (Charging.tick). True when it ran. */
    boolean fireCast(ServerPlayer sp, int index) {
        if (!Gate.allowed(sp, def())) return false;
        return MechanicHost.fireCharged(contentId, mechanics(), index, sp);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer sp) {
            // Right click makes the server swing the arm; that swing is not a left click.
            Swings.suppress(sp);
            if (!Gate.check(sp, def())) return InteractionResult.FAIL;
            if (foundry.core.Floodgate.isBedrock(sp.getUUID())) {
                Long at = USED_ON.remove(sp.getUUID());
                if (at != null && at == sp.level().getServer().getTickCount()) return InteractionResult.CONSUME;
                InteractionResult used = bedrockUse(sp, hand);
                return used != null ? used : super.use(level, player, hand);
            }
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
            // Nothing could run (a charged ability recharging): answer with the ability line instead of silence.
            if (hasCharge()) AbilityBar.poke(sp);
        }
        return super.use(level, player, hand);
    }

    /**
     * An item that has moved (replaced_by) turns into its new self in the player's inventory, the same count and
     * components, the first tick it is carried. Containers are not searched; the stack changes when a player picks it up.
     */
    @Override
    public void inventoryTick(ItemStack stack, net.minecraft.server.level.ServerLevel level, net.minecraft.world.entity.Entity entity,
                              net.minecraft.world.entity.EquipmentSlot slot) {
        var def = def();
        if (def != null && def.replacedBy() != null && entity instanceof ServerPlayer player) {
            Item next = FoundryItems.get(def.replacedBy());
            if (next != null && next != this) {
                var inv = player.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    if (inv.getItem(i) == stack) {
                        inv.setItem(i, stack.transmuteCopy(next, stack.getCount()));
                        FoundryMod.LOG.info("Foundry: {}'s {} became {}", player.getGameProfile().name(), contentId, def.replacedBy());
                        return;
                    }
                }
            }
        }
        super.inventoryTick(stack, level, entity, slot);
    }

    /** True for a caster: its left click casts (on_swing) and it has no hit of its own (on_hit_entity). */
    boolean castsOnAttack() {
        var list = mechanics();
        return list.stream().anyMatch(m -> m.trigger() == Trigger.ON_SWING)
                && list.stream().noneMatch(m -> m.trigger() == Trigger.ON_HIT_ENTITY);
    }

    /** True when the charged ability is recharging for the player this packet goes to. */
    private boolean rechargingFor(PacketContext context) {
        if (context == null) return false;
        var profile = context.get(PacketContext.GAME_PROFILE);
        var server = context.get(PacketContext.SERVER_INSTANCE);
        if (profile == null || server == null) return false;
        ServerPlayer viewer = server.getPlayerList().getPlayer(profile.id());
        return viewer != null && MechanicHost.chargeRecharging(contentId, mechanics(), viewer);
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
        var list = mechanics();
        if (hold.index() < 0 || hold.index() >= list.size()) return false;
        var m = list.get(hold.index());
        if (held < m.releaseTicks()) {
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal("Hold longer"), true);
            return false;
        }
        // A mechanic that allows an early release runs at part charge; any other has waited its full charge here.
        return MechanicHost.fireCharged(contentId, list, hold.index(), sp, m.chargeOf(held));
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
        // The client predicts mining from its own copy, so it gets the same tool as the server (a war pick of rank 3 mines
        // as diamond on screen too, not as its stone pickaxe base).
        var tool = stack.get(net.minecraft.core.component.DataComponents.TOOL);
        if (def != null && def.tool() != null && tool != null) out.set(net.minecraft.core.component.DataComponents.TOOL, tool);
        // Held for a "long" time on the client, so it never finishes by itself; the server decides at release. While the
        // charged ability recharges for the player this copy is for, it is left off, so their client draws no pose for a
        // press that cannot do anything (AbilityBar resends the inventory when it starts and when it is back).
        if (hasCharge() && !rechargingFor(context)) out.set(net.minecraft.core.component.DataComponents.CONSUMABLE, CHARGE_LOOK);
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
