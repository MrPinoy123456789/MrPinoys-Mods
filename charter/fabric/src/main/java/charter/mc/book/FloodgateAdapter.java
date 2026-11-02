package charter.mc.book;

import charter.mc.CharterMod;
import net.fabricmc.loader.api.FabricLoader;
import org.geysermc.cumulus.component.util.ComponentType;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.response.CustomFormResponse;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The only class that touches Floodgate or Cumulus. It is loaded only when the Floodgate mod is present
 * ({@link #installIfPresent}), so Charter runs without it and none of Charter's tests ever load it. The Floodgate
 * and Cumulus jars are {@code compileOnly}: the server's own Floodgate provides them at run time.
 *
 * <p>Built against Floodgate API 2.2.4 and Cumulus 1.1.2 (see the Gradle file for why). A response arrives on a Netty
 * thread; {@link BedrockForms} moves it onto the server thread before anything runs.
 */
final class FloodgateAdapter implements BedrockForms.Sender {

    private FloodgateAdapter() {
    }

    /** Called once from {@link BookMod#register}. Never throws: a Floodgate that cannot be used leaves Java dialogs in place. */
    static void installIfPresent() {
        if (!FabricLoader.getInstance().isModLoaded("floodgate")) return;
        try {
            FloodgateApi.getInstance();
            BedrockForms.install(new FloodgateAdapter());
            CharterMod.LOG.info("Floodgate found: Bedrock players get the Charter book as native forms (bedrockNativeForms).");
        } catch (Throwable t) {
            CharterMod.LOG.warn("Floodgate is present but its API could not be used, so Bedrock players get the Java dialogs: {}", t.toString());
        }
    }

    @Override
    public boolean isBedrock(UUID id) {
        FloodgateApi api = FloodgateApi.getInstance();
        return api != null && api.isFloodgatePlayer(id);
    }

    @Override
    public boolean send(UUID id, BedrockForms.Form form, BedrockForms.Handler handler) {
        return switch (form) {
            case BedrockForms.Form.Simple s -> sendSimple(id, s, handler);
            case BedrockForms.Form.Modal m -> sendModal(id, m, handler);
            case BedrockForms.Form.Custom c -> sendCustom(id, c, handler);
        };
    }

    private static boolean sendSimple(UUID id, BedrockForms.Form.Simple form, BedrockForms.Handler handler) {
        SimpleForm.Builder builder = SimpleForm.builder().title(form.title()).content(form.content());
        for (BedrockForms.Entry entry : form.entries()) {
            if (entry.icon() == null) builder.button(entry.label());
            else builder.button(entry.label(), FormImage.Type.PATH, entry.icon());
        }
        builder.validResultHandler(response -> handler.pressed(response.clickedButtonId()));
        builder.closedOrInvalidResultHandler(handler::closed);
        return FloodgateApi.getInstance().sendForm(id, builder.build());
    }

    private static boolean sendModal(UUID id, BedrockForms.Form.Modal form, BedrockForms.Handler handler) {
        ModalForm.Builder builder = ModalForm.builder().title(form.title()).content(form.content())
                .button1(form.yes()).button2(form.no());
        builder.validResultHandler(response -> handler.pressed(response.clickedFirst() ? 0 : 1));
        builder.closedOrInvalidResultHandler(handler::closed);
        return FloodgateApi.getInstance().sendForm(id, builder.build());
    }

    private static boolean sendCustom(UUID id, BedrockForms.Form.Custom form, BedrockForms.Handler handler) {
        CustomForm.Builder builder = CustomForm.builder().title(form.title());
        List<BedrockForms.Field> inputs = new ArrayList<>();
        for (BedrockForms.Field field : form.fields()) {
            switch (field) {
                case BedrockForms.Field.Label l -> builder.label(l.text());
                case BedrockForms.Field.Dropdown d -> {
                    builder.dropdown(d.label(), d.shown(), d.initial());
                    inputs.add(d);
                }
                case BedrockForms.Field.Input i -> {
                    builder.input(i.label(), "", i.initial());
                    inputs.add(i);
                }
                case BedrockForms.Field.Slider s -> {
                    builder.slider(s.label(), s.min(), s.max(), s.step(), s.initial());
                    inputs.add(s);
                }
            }
        }
        builder.validResultHandler(response -> handler.submitted(values(response, inputs)));
        builder.closedOrInvalidResultHandler(handler::closed);
        return FloodgateApi.getInstance().sendForm(id, builder.build());
    }

    /** One string per input in order: the picked index, the typed text or the slider's number. Labels are skipped. */
    private static List<String> values(CustomFormResponse response, List<BedrockForms.Field> inputs) {
        List<String> out = new ArrayList<>();
        response.reset();
        response.includeLabels(true);
        List<ComponentType> types = response.getComponentTypes();
        int inputIndex = 0;
        for (int at = 0; at < types.size(); at++) {
            ComponentType type = types.get(at);
            if (type == ComponentType.LABEL) continue;
            BedrockForms.Field field = inputs.get(inputIndex++);
            out.add(switch (field) {
                case BedrockForms.Field.Dropdown d -> Integer.toString(response.getDropdown(at));
                case BedrockForms.Field.Input i -> response.getInput(at);
                case BedrockForms.Field.Slider s -> Integer.toString(Math.round(response.getSlider(at)));
                case BedrockForms.Field.Label l -> "";
            });
        }
        return out;
    }
}
