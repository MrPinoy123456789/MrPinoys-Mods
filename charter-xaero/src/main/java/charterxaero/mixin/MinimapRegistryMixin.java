package charterxaero.mixin;

import charterxaero.xaero.MinimapBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Xaero's Minimap keeps its own highlighter registry, filled in its session with Open Parties and Claims only (the
 * World Map's {@code registerMinimapHighlighters} is empty in 1.47.2, so the World Map's highlighters never reach the
 * Minimap and adding to both does not tint a chunk twice). The kingdoms' highlighter joins just before {@code end()}
 * freezes it. Applied only when the Minimap is loaded (see {@link XaeroMixinPlugin}).
 */
@Pseudo
@Mixin(targets = "xaero.common.minimap.highlight.HighlighterRegistry", remap = false)
public abstract class MinimapRegistryMixin {

    @Inject(method = "end", at = @At("HEAD"), remap = false)
    private void charterxaero$addKingdoms(CallbackInfo ci) {
        MinimapBridge.register(this);
    }
}
