package foundry.mc;

import foundry.core.BedrockSkin;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionSkinApplyEvent;
import org.geysermc.geyser.api.skin.Skin;
import org.geysermc.geyser.api.skin.SkinData;
import org.geysermc.geyser.api.skin.SkinGeometry;

/**
 * The only class that touches Geyser's API, loaded only when Geyser is installed (see {@link BedrockLooks}).
 *
 * <p>Whenever Geyser sends a player's skin to a Bedrock player (SessionSkinApplyEvent, Geyser 2.4 and later), a player
 * of a race gets their look added (BedrockSkin). Java players always do; a Bedrock player only when their own skin is
 * the plain classic or slim shape, so a persona skin with its own parts is left as it is. When a player's race changes
 * after their skin was sent, the new skin is pushed to every Bedrock player at once (GeyserConnection.sendSkin).
 */
final class GeyserSkins {
    /** Each player's skin as Geyser first had it, so a look can be added or taken off again later. */
    private static final Map<UUID, Original> ORIGINAL = new ConcurrentHashMap<>();

    private record Original(SkinData skin, boolean slim, boolean bedrock) {}

    private GeyserSkins() {}

    static void register() {
        GeyserApi api = GeyserApi.api();
        if (api == null || api.eventBus() == null) throw new IllegalStateException("Geyser is not up yet");
        api.eventBus().subscribe(EventRegistrar.of(new GeyserSkins.Owner()), SessionSkinApplyEvent.class,
                GeyserSkins::onSkin);
        FoundryMod.LOG.info("Foundry: race looks will be added to skins for Bedrock players ({} looks)",
                BedrockLooks.pieces().size());
    }

    /** The registrar Geyser keeps the subscription under. */
    private static final class Owner {}

    private static void onSkin(SessionSkinApplyEvent event) {
        try {
            ORIGINAL.put(event.uuid(), new Original(event.originalSkin(), event.slim(), event.bedrock()));
            SkinData dressed = dress(event.uuid(), event.originalSkin(), event.slim(), event.bedrock());
            if (dressed == null) return;
            event.skin(dressed.skin());
            event.geometry(dressed.geometry());
        } catch (RuntimeException e) {
            FoundryMod.LOG.warn("Foundry: could not add the race look to {}'s Bedrock skin", event.username(), e);
        }
    }

    /** The skin with the player's looks added, or null to leave it as it is. */
    private static SkinData dress(UUID player, SkinData original, boolean slim, boolean bedrock) {
        List<String> worn = BedrockLooks.worn(player);
        if (worn.isEmpty() || original == null || original.skin() == null) return null;
        if (bedrock && !BedrockSkin.plain(original.geometry() == null ? null : original.geometry().geometryName())) return null;
        var made = BedrockSkin.make(original.skin().skinData(), original.skin().textureUrl(), slim, BedrockLooks.humanoid(),
                BedrockLooks.piecesFor(worn));
        if (made == null) return null;
        return new SkinData(new Skin(made.id(), made.rgba()), original.cape(),
                new SkinGeometry(made.geometryName(), made.geometryData()));
    }

    /** Sends this player's skin again to every Bedrock player: with the looks they now wear, or the original. */
    static void refresh(UUID player) {
        Original o = ORIGINAL.get(player);
        if (o == null) return;
        try {
            SkinData dressed = dress(player, o.skin(), o.slim(), o.bedrock());
            SkinData send = dressed != null ? dressed : o.skin();
            for (var connection : GeyserApi.api().onlineConnections()) connection.sendSkin(player, send);
        } catch (RuntimeException e) {
            FoundryMod.LOG.warn("Foundry: could not send a new Bedrock skin for {}", player, e);
        }
    }

    static void forget(UUID player) {
        ORIGINAL.remove(player);
    }
}
