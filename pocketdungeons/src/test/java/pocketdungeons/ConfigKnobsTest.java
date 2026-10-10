package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps {@link PocketDungeonsConfig}'s one-place knob list honest. A knob is declared once, as a
 * {@code Knob} constant; this test fails when a declared knob is missing from the file a first
 * boot writes, has no public accessor named after its key (or one of the wrong type), or when a
 * public accessor exists that no knob backs. It also scans the source so a knob cannot be added
 * the old way, by reading a key by hand outside the list.
 */
public class ConfigKnobsTest {

    private static final Path SOURCE = Paths.get("src/main/java/pocketdungeons/PocketDungeonsConfig.java");

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        Path dir = Files.createTempDirectory("pd-config-knobs");
        PocketDungeonsConfig.load(dir);
        JsonObject written = JsonParser.parseString(
                Files.readString(dir.resolve("pocketdungeons.json"), StandardCharsets.UTF_8)).getAsJsonObject();

        testKeysAreUniqueAndAllWritten(written);
        testEveryKnobHasATypedAccessor();
        testNoAccessorWithoutAKnob();
        testSourceHasNoKnobOutsideTheList();

        System.out.println("ConfigKnobsTest passed (" + PocketDungeonsConfig.knobs().size() + " knobs)");
    }

    private static void testKeysAreUniqueAndAllWritten(JsonObject written) {
        List<PocketDungeonsConfig.Knob<?>> knobs = PocketDungeonsConfig.knobs();
        check(knobs.size() > 90, "the knob list is populated, got " + knobs.size());
        Set<String> seen = new HashSet<>();
        List<String> writtenKeys = new ArrayList<>(written.keySet());
        for (int i = 0; i < knobs.size(); i++) {
            PocketDungeonsConfig.Knob<?> knob = knobs.get(i);
            check(seen.add(knob.key()), "key declared twice: " + knob.key());
            check(written.has(knob.key()), "knob " + knob.key() + " is missing from the written defaults");
            check(writtenKeys.get(i).equals(knob.key()),
                    "written key " + i + " is " + writtenKeys.get(i) + " but the knob list has " + knob.key());
            JsonElement expected = knob.defaultJson();
            check(written.get(knob.key()).equals(expected),
                    "the written default of " + knob.key() + " is " + written.get(knob.key()) + ", not " + expected);
        }
        check(writtenKeys.size() == knobs.size(),
                "the written defaults hold " + writtenKeys.size() + " keys for " + knobs.size() + " knobs");
    }

    private static void testEveryKnobHasATypedAccessor() throws Exception {
        for (PocketDungeonsConfig.Knob<?> knob : PocketDungeonsConfig.knobs()) {
            if (!knob.hasAccessor()) {
                continue;
            }
            Method accessor;
            try {
                accessor = PocketDungeonsConfig.class.getMethod(knob.key());
            } catch (NoSuchMethodException e) {
                throw new AssertionError("knob " + knob.key() + " has no public accessor named " + knob.key() + "()");
            }
            check(Modifier.isStatic(accessor.getModifiers()), knob.key() + "() must be static");
            Object def = knob.defaultValue();
            Class<?> expected = def instanceof Integer ? int.class
                    : def instanceof Double ? double.class
                    : def instanceof Boolean ? boolean.class
                    : def instanceof String ? String.class
                    : def instanceof List<?> ? List.class : null;
            check(expected != null, "knob " + knob.key() + " has a default of an unexpected type");
            check(accessor.getReturnType() == expected,
                    knob.key() + "() returns " + accessor.getReturnType() + ", expected " + expected);
            check(String.valueOf(accessor.invoke(null)).equals(String.valueOf(def)),
                    knob.key() + "() does not return the knob's default after a first boot");
        }
    }

    private static void testNoAccessorWithoutAKnob() {
        Set<String> keys = new HashSet<>();
        for (PocketDungeonsConfig.Knob<?> knob : PocketDungeonsConfig.knobs()) {
            if (knob.hasAccessor()) {
                keys.add(knob.key());
            }
        }
        for (Method m : PocketDungeonsConfig.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())
                    && m.getParameterCount() == 0 && m.getReturnType() != void.class) {
                check(keys.contains(m.getName()), "public accessor " + m.getName() + "() is backed by no knob");
            }
        }
    }

    private static void testSourceHasNoKnobOutsideTheList() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        Matcher declared = Pattern.compile("private static final Knob<").matcher(source);
        int count = 0;
        while (declared.find()) {
            count++;
        }
        check(count == PocketDungeonsConfig.knobs().size(),
                "the source declares " + count + " Knob constants but the list holds "
                        + PocketDungeonsConfig.knobs().size());
        check(!Pattern.compile("readInt\\(root, \"|readDouble\\(root, \"|readBoolean\\(root, \"|readString\\(root, \"")
                .matcher(source).find(), "a key is read by hand; declare it as a Knob instead");
        check(!Pattern.compile("(?m)^    private static (int|double|boolean|String) \\w+ = ").matcher(source).find(),
                "a plain static field holds a setting; declare it as a Knob instead");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
