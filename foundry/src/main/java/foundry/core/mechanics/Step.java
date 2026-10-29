package foundry.core.mechanics;

import java.util.Map;

/** One condition or action: a registered type name plus its parameters as text. Validated against {@link MechanicTypes}. */
public record Step(String type, Map<String, String> params) {
    public String param(String key, String dflt) {
        return params.getOrDefault(key, dflt);
    }

    public int intParam(String key, int dflt) {
        String v = params.get(key);
        return v == null ? dflt : Integer.parseInt(v);
    }

    public double decimalParam(String key, double dflt) {
        String v = params.get(key);
        return v == null ? dflt : Double.parseDouble(v);
    }
}
