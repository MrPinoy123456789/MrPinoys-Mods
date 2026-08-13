package wayfarers.core;

/**
 * The visual body of an encounter (SPEC.md §5.2).
 *
 * <p>The {@code type} is a registry id string resolved by the Fabric layer so
 * the core never touches Minecraft classes.
 */
public record Body(String type, String name, boolean nameVisible, int count) {

    public Body {
        if (type == null) type = "";
        if (name == null) name = "";
        if (count < 1) count = 1;
    }

    public Body(String type, String name) {
        this(type, name, true, 1);
    }
}
