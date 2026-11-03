package fortune.mc;

import net.fabricmc.loader.api.FabricLoader;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * The only class that touches Floodgate or Cumulus, the same pattern as Charter's book adapter. It is loaded only
 * when the floodgate mod is present ({@link #installIfPresent}), so Fortune runs without it. Both jars are
 * {@code compileOnly}: the server's own Floodgate provides them at run time. {@link Bedrock#modal} moves each answer
 * onto the server thread.
 */
final class FloodgateAdapter implements Bedrock.Sender {

    private FloodgateAdapter() {
    }

    /** Never throws: a Floodgate that cannot be used leaves Bedrock players with the chat prompt. */
    static void installIfPresent() {
        if (!FabricLoader.getInstance().isModLoaded("floodgate")) return;
        try {
            FloodgateApi.getInstance();
            Bedrock.install(new FloodgateAdapter());
            FortuneMod.LOG.info("Floodgate found: Bedrock players get the join prompt as a form.");
        } catch (Throwable t) {
            FortuneMod.LOG.warn("Floodgate is present but its API could not be used: {}", t.toString());
        }
    }

    @Override
    public boolean isBedrock(UUID player) {
        FloodgateApi api = FloodgateApi.getInstance();
        return api != null && api.isFloodgatePlayer(player);
    }

    @Override
    public boolean modal(UUID player, String title, String content, String first, String second, IntConsumer answer) {
        ModalForm.Builder builder = ModalForm.builder().title(title).content(content).button1(first).button2(second);
        builder.validResultHandler(response -> answer.accept(response.clickedFirst() ? 0 : 1));
        builder.closedOrInvalidResultHandler(() -> answer.accept(-1));
        return FloodgateApi.getInstance().sendForm(player, builder.build());
    }
}
