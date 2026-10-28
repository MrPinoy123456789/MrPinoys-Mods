package lemon;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class LemonConfig {
    private static final Map<String, Integer> DEFAULTS = Map.of(
            "lemonIdleSeconds", 20, "lemonAskIdleSeconds", 120, "lemonLlmLapseSeconds", 300,
            "lemonFallbackSeconds", 45, "lemonLingerSeconds", 4, "lemonThinkSeconds", 90);
    private static final Map<String, Integer> VALUES = new LinkedHashMap<>(DEFAULTS);
    private static String onInteract = "";
    private static final Map<String, String> HOLDING = new LinkedHashMap<>();

    static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("lemon/lemon.json");
        try {
            if (!Files.exists(path)) {
                JsonObject defaults = new JsonObject();
                DEFAULTS.forEach(defaults::addProperty);
                defaults.addProperty("onInteract", "");
                defaults.add("onInteractHolding", new JsonObject());
                Files.createDirectories(path.getParent());
                Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(defaults));
            }
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            DEFAULTS.forEach((key, fallback) -> {
                int value = root.has(key) ? root.get(key).getAsInt() : fallback;
                if (value < minimum(key) || value > maximum(key)) {
                    throw new IllegalArgumentException("Invalid timing: " + key);
                }
                VALUES.put(key, value);
            });
            onInteract = root.has("onInteract") ? root.get("onInteract").getAsString() : "";
            HOLDING.clear();
            if (root.has("onInteractHolding")) {
                root.getAsJsonObject("onInteractHolding").entrySet()
                        .forEach(entry -> HOLDING.put(entry.getKey(), entry.getValue().getAsString()));
            }
        } catch (IOException | RuntimeException e) {
            VALUES.clear();
            VALUES.putAll(DEFAULTS);
            onInteract = "";
            HOLDING.clear();
            LemonMod.LOG.warn("Could not load Lemon config; using defaults", e);
        }
    }

    private static int minimum(String key) {
        return switch (key) {
            case "lemonIdleSeconds" -> 3;
            case "lemonAskIdleSeconds", "lemonThinkSeconds" -> 10;
            case "lemonLlmLapseSeconds" -> 30;
            case "lemonFallbackSeconds" -> 5;
            default -> 1;
        };
    }

    private static int maximum(String key) {
        return switch (key) {
            case "lemonAskIdleSeconds" -> 1800;
            case "lemonLlmLapseSeconds" -> 3600;
            case "lemonLingerSeconds" -> 60;
            default -> 600;
        };
    }

    static int lemonIdleSeconds() { return VALUES.get("lemonIdleSeconds"); }
    static int lemonAskIdleSeconds() { return VALUES.get("lemonAskIdleSeconds"); }
    static int lemonLlmLapseSeconds() { return VALUES.get("lemonLlmLapseSeconds"); }
    static int lemonFallbackSeconds() { return VALUES.get("lemonFallbackSeconds"); }
    static int lemonLingerSeconds() { return VALUES.get("lemonLingerSeconds"); }
    static int lemonThinkSeconds() { return VALUES.get("lemonThinkSeconds"); }

    static void interact(ServerPlayer player) {
        String command = interactionCommand(player.getMainHandItem(), onInteract, HOLDING);
        if (!command.isBlank()) {
            player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
        }
    }

    static String interactionCommand(net.minecraft.world.item.ItemStack held, String fallback, Map<String, String> hooks) {
        String command = fallback;
        for (var entry : hooks.entrySet()) {
            String selector = entry.getKey();
            Identifier id = Identifier.tryParse(selector.startsWith("#") ? selector.substring(1) : selector);
            if (id == null) {
                continue;
            }
            boolean matches = selector.startsWith("#")
                    ? held.is(TagKey.<Item>create(Registries.ITEM, id))
                    : BuiltInRegistries.ITEM.getKey(held.getItem()).equals(id);
            if (matches) {
                command = entry.getValue();
                break;
            }
        }
        return command;
    }
}
