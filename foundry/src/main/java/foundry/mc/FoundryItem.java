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
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;

/**
 * The one item class every data-driven item uses. All behaviour comes from the definition, never from a
 * per-item subclass. Vanilla clients see {@code base} plus an item model reference from the resource pack.
 */
final class FoundryItem extends SimplePolymerItem {
    private final Identifier clientModel;

    private final String contentId;
    private final List<MechanicDef> mechanics;

    FoundryItem(Item.Properties properties, Item base, Identifier clientModel, String contentId,
            List<MechanicDef> mechanics) {
        super(properties, base);
        this.clientModel = clientModel;
        this.contentId = contentId;
        this.mechanics = mechanics;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer sp && MechanicHost.fire(Trigger.ON_USE, contentId, mechanics, sp)) {
            return InteractionResult.SUCCESS;
        }
        return super.use(level, player, hand);
    }

    @Override
    public void hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        super.hurtEnemy(stack, target, attacker);
        if (attacker instanceof ServerPlayer sp) MechanicHost.fire(Trigger.ON_HIT_ENTITY, contentId, mechanics, sp);
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context, HolderLookup.Provider lookup) {
        return clientModel != null ? clientModel : super.getPolymerItemModel(stack, context, lookup);
    }
}
