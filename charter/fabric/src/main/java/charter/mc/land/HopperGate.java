package charter.mc.land;

import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The hook behind Mixin 8, {@code HopperBlockEntity.addItem(Container, Container,
 * ItemStack, Direction)} (PLAN.md §5.4). Hoppers and droppers both insert through
 * that one static method, and no Fabric API event sees it, so the Mixin offers
 * every insert to the registered interceptors first.
 *
 * <p>M8 builds the gate and registers nothing. The granary and camp store logic
 * (cut the insert to what the site's cart pool covers, debit the pool) is M9's and
 * M10's: they call {@link #register} from their own register call.
 */
public final class HopperGate {

    /** Decides what happens to one insert. */
    public interface Interceptor {
        /**
         * @return null to leave the insert alone, or the stack that comes back to the
         *         caller as what was not inserted (the whole stack to refuse everything)
         */
        ItemStack intercept(Container source, Container destination, ItemStack stack, Direction face);
    }

    private static final List<Interceptor> INTERCEPTORS = new ArrayList<>();

    private HopperGate() {
    }

    public static void register(Interceptor interceptor) {
        INTERCEPTORS.add(interceptor);
    }

    public static void unregister(Interceptor interceptor) {
        INTERCEPTORS.remove(interceptor);
    }

    /** The first interceptor's answer, or null when none object. */
    public static ItemStack offer(Container source, Container destination, ItemStack stack, Direction face) {
        for (Interceptor interceptor : INTERCEPTORS) {
            ItemStack answer = interceptor.intercept(source, destination, stack, face);
            if (answer != null) return answer;
        }
        return null;
    }
}
