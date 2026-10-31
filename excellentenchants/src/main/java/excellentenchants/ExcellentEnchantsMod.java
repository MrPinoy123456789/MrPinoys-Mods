package excellentenchants;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint. Server-side only. The enchantments themselves are data pack JSON, so
 * vanilla clients see them with nothing installed; this class wires the effects the
 * JSON cannot express.
 */
public final class ExcellentEnchantsMod implements ModInitializer {

    public static final String MOD_ID = "excellentenchants";
    public static final Logger LOG = LoggerFactory.getLogger("ExcellentEnchants");

    @Override
    public void onInitialize() {
        OnHit.register();
        OnKill.register();
        Armor.register();
        BlockDrops.register();
        Projectiles.register();
        LOG.info("Excellent Enchants initialised (server-side only)");
    }
}
