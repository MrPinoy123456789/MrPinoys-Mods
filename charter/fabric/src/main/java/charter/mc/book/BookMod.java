package charter.mc.book;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** The Charter book and the founding Nexus: one register call from {@code CharterMod}. */
public final class BookMod {

    private BookMod() {
    }

    public static void register() {
        Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, Identifier.fromNamespaceAndPath("charter", "nexus"),
                NexusRecipe.SERIALIZER);
        CharterBook.register();
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> BookDialogs.LAST.remove(handler.player.getUUID()));
    }
}
