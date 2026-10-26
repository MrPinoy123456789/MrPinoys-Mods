package charter.mc.granary;

import charter.land.Core;
import charter.mc.land.HopperGate;
import charter.siege.Supply;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Optional;

/**
 * The granary's answer at M8's hopper gate (DESIGN.md §10.4): while a keep is
 * besieged, or a camp is fighting, no hopper or dropper may fill its granary or
 * camp store. The mirror reads whatever the chests hold each step, so an insert
 * let through here would become provisions without coming in through an open
 * side. In peace, and for any chest that is not a granary, nothing changes.
 *
 * <p>M10 adds the exception: food unloaded from a cart that came in through an
 * open side, up to that cart's allowance.
 */
final class GranaryHopperGate implements HopperGate.Interceptor {

    @Override
    public ItemStack intercept(Container source, Container destination, ItemStack stack, Direction face) {
        GranaryService svc = Granaries.service();
        if (svc == null || stack.isEmpty()) return null;
        Optional<Core> site = siteOf(svc, destination);
        if (site.isEmpty()) return null;
        boolean underSiege = new Supply(svc.runtime().engine, svc.food()).underSiege(site.get());
        return underSiege ? stack : null;
    }

    /** The keep or camp whose granary or store this container is part of, if any. */
    static Optional<Core> siteOf(GranaryService svc, Container destination) {
        if (destination instanceof BlockEntity be) return siteAt(svc, be.getLevel(), be.getBlockPos());
        if (destination instanceof CompoundContainer compound) {
            // A double chest: either half being a granary chest makes it one.
            for (GranaryBook.Site site : svc.book().sites()) {
                Level level = svc.level(site.dimension);
                if (level == null) continue;
                for (GranaryBook.Pos pos : site.containers) {
                    BlockEntity half = level.getBlockEntity(new BlockPos(pos.x(), pos.y(), pos.z()));
                    if (half instanceof Container c && compound.contains(c)) return svc.coreOf(site);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Core> siteAt(GranaryService svc, Level level, BlockPos at) {
        if (level == null) return Optional.empty();
        GranaryBook.Site site = svc.book().at(level.dimension().identifier().toString(),
                new GranaryBook.Pos(at.getX(), at.getY(), at.getZ()));
        return site == null ? Optional.empty() : svc.coreOf(site);
    }
}
