package smalltalk.identity;

import net.minecraft.world.entity.npc.villager.Villager;

import java.util.List;
import java.util.UUID;

/**
 * Derive, don't store (SPEC.md section 2). Every villager UUID hashes to the
 * same name, personality, and quirks forever, on any world, with no save
 * data of its own.
 *
 * <p><b>Stability contract:</b> {@link #NAMES}, {@link Personality}'s enum
 * order, and {@link ItemCategory}'s enum order are all frozen the moment
 * this ships. Appending to {@link #NAMES} is safe. Reordering, removing, or
 * inserting anywhere but the end renames every villager in every world.
 * Treat this whole class as API.
 */
public final class IdentityDeriver {

    /** Bumping this is a breaking change -- every resident gets renamed. */
    public static final int IDENTITY_VERSION = 1;

    private static final List<String> NAMES = List.of(
            "Aldric", "Bettina", "Corwin", "Dagny", "Elowen", "Fenwick", "Greta", "Halvard",
            "Ione", "Jorund", "Kessa", "Lior", "Maren", "Nils", "Orla", "Pell",
            "Quenna", "Roswitha", "Sten", "Tova", "Ulric", "Verna", "Wren", "Yorick"
    );

    private IdentityDeriver() {}

    public static Identity derive(UUID villagerId) {
        long seed = mix(villagerId.getMostSignificantBits())
                ^ mix(villagerId.getLeastSignificantBits() * 0x9E3779B97F4A7C15L);

        String name = NAMES.get(index(seed, NAMES.size()));
        Personality personality = Personality.values()[index(seed >>> 11, Personality.values().length)];

        ItemCategory[] categories = ItemCategory.values();
        ItemCategory liked = categories[index(seed >>> 22, categories.length)];
        int dislikedIndex = index(seed >>> 33, categories.length - 1);
        if (dislikedIndex >= liked.ordinal()) {
            dislikedIndex++;
        }
        ItemCategory disliked = categories[dislikedIndex];

        return new Identity(name, personality, liked, disliked);
    }

    /** Name tags win (SPEC.md section 2); the derived name is only a default. */
    public static String displayName(Villager villager) {
        if (villager.hasCustomName() && villager.getCustomName() != null) {
            return villager.getCustomName().getString();
        }
        return derive(villager.getUUID()).derivedName();
    }

    private static int index(long seed, int bound) {
        return (int) Long.remainderUnsigned(mix(seed), bound);
    }

    /** splitmix64's finalizer. Good avalanche, deterministic, frozen. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
