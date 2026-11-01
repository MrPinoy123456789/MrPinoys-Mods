package foundry.mc;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import foundry.core.WornPlacement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws worn trinkets for vanilla clients, which cannot render Trinkets models: one item display per trinket,
 * attached to the player with Polymer and turned with the body (or head) every tick. Only Foundry items in a
 * Trinkets slot with a placement ({@link WornPlacement}) are drawn. Hidden while the player is invisible or in a
 * pose where the body is not upright (swimming, gliding, sleeping).
 */
final class WornDisplays {
    private record Shown(ServerPlayer player, String signature, ElementHolder holder, EntityAttachment attachment,
                         List<ItemDisplayElement> elements, List<WornPlacement> places) {}

    private static final Map<UUID, Shown> SHOWN = new HashMap<>();

    private WornDisplays() {}

    static void tick(MinecraftServer server) {
        List<UUID> online = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            update(player);
        }
        SHOWN.entrySet().removeIf(e -> {
            if (online.contains(e.getKey())) return false;
            destroy(e.getValue());
            return true;
        });
    }

    static void clear() {
        SHOWN.values().forEach(WornDisplays::destroy);
        SHOWN.clear();
    }

    private static void destroy(Shown s) {
        s.attachment.destroy();
        s.holder.destroy();
    }

    private static void update(ServerPlayer player) {
        List<ItemStack> stacks = new ArrayList<>();
        List<WornPlacement> places = new ArrayList<>();
        if (WornItems.trinkets() && player.isAlive()) {
            TrinketsHook.worn(player, stack -> {
                if (stack.getItem() instanceof FoundryItem item && item.def() != null && item.def().worn()) {
                    WornPlacement.of(item.def().wornSlots().get(0)).ifPresent(p -> {
                        stacks.add(stack.copyWithCount(1));
                        places.add(p);
                    });
                }
            });
        }
        String signature = stacks.stream().map(s -> s.getItem().toString()).toList().toString();
        Shown cur = SHOWN.get(player.getUUID());
        if (cur != null && (cur.player != player || !cur.signature.equals(signature))) {
            destroy(cur);
            SHOWN.remove(player.getUUID());
            cur = null;
        }
        if (stacks.isEmpty()) return;
        if (cur == null) {
            ElementHolder holder = new ElementHolder();
            List<ItemDisplayElement> elements = new ArrayList<>();
            for (ItemStack stack : stacks) {
                ItemDisplayElement e = new ItemDisplayElement(stack);
                e.setItemDisplayContext(ItemDisplayContext.FIXED);
                e.setInterpolationDuration(3);
                holder.addElement(e);
                elements.add(e);
            }
            EntityAttachment attachment = EntityAttachment.ofTicking(holder, player);
            cur = new Shown(player, signature, holder, attachment, elements, places);
            SHOWN.put(player.getUUID(), cur);
        }
        boolean upright = !player.isInvisible() && player.getPose() != Pose.SWIMMING
                && player.getPose() != Pose.FALL_FLYING && player.getPose() != Pose.SLEEPING;
        for (int i = 0; i < cur.elements.size(); i++) {
            ItemDisplayElement e = cur.elements.get(i);
            WornPlacement p = cur.places.get(i);
            float scale = upright ? p.scale() : 0f;
            float yaw = p.onHead() ? player.getYHeadRot() : player.yBodyRot;
            // Minecraft yaw 0 faces south (+z); "back" is opposite the facing direction.
            double rad = Math.toRadians(yaw);
            float up = p.up() - (player.isCrouching() ? 0.25f : 0f);
            Vector3f at = new Vector3f((float) (Math.sin(rad) * p.back()), up, (float) (-Math.cos(rad) * p.back()));
            e.setLeftRotation(new Quaternionf().rotationY((float) Math.toRadians((p.onHead() ? 0f : 180f) - yaw)));
            e.setScale(new Vector3f(scale, scale, scale));
            e.setTranslation(at);
            e.startInterpolationIfDirty();
        }
    }
}
