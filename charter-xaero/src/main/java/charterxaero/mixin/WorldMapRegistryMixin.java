package charterxaero.mixin;

import charterxaero.xaero.WorldMapBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Xaero's World Map fills its highlighter registry in one place (the map session) with Open Parties and Claims' claims
 * only, then freezes it with {@code end()}. There is no public way in, so the kingdoms' highlighter joins just before
 * the freeze. Applied only when the World Map is loaded (see {@link XaeroMixinPlugin}).
 */
@Pseudo
@Mixin(targets = "xaero.map.highlight.HighlighterRegistry", remap = false)
public abstract class WorldMapRegistryMixin {

    @Inject(method = "end", at = @At("HEAD"), remap = false)
    private void charterxaero$addKingdoms(CallbackInfo ci) {
        WorldMapBridge.register(this);
    }
}
