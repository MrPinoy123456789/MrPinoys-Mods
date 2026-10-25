package charter.mc.store;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes a sealed interface of records (such as {@code Term}) as an
 * object with a {@code "type"} field naming the record. The names are the
 * permitted records' simple names, so they never need a registry: a new term
 * added to the sealed list is covered at once.
 */
public final class SealedAdapter<B> implements TypeAdapterFactory {

    private final Class<B> base;
    private final Map<String, Class<?>> byName = new LinkedHashMap<>();

    public SealedAdapter(Class<B> base) {
        if (!base.isSealed()) throw new IllegalArgumentException(base.getName() + " is not sealed");
        this.base = base;
        for (Class<?> permitted : base.getPermittedSubclasses()) byName.put(permitted.getSimpleName(), permitted);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        if (type.getRawType() != base) return null;
        TypeAdapterFactory self = this;
        return (TypeAdapter<T>) new TypeAdapter<B>() {
            @Override
            public void write(JsonWriter out, B value) throws IOException {
                if (value == null) {
                    out.nullValue();
                    return;
                }
                TypeAdapter<B> delegate = (TypeAdapter<B>) gson.getDelegateAdapter(self, TypeToken.get(value.getClass()));
                JsonObject tree = delegate.toJsonTree(value).getAsJsonObject();
                JsonObject tagged = new JsonObject();
                tagged.addProperty("type", value.getClass().getSimpleName());
                for (Map.Entry<String, JsonElement> e : tree.entrySet()) tagged.add(e.getKey(), e.getValue());
                gson.toJson(tagged, out);
            }

            @Override
            public B read(JsonReader in) throws IOException {
                if (in.peek() == JsonToken.NULL) {
                    in.nextNull();
                    return null;
                }
                JsonObject tree = gson.fromJson(in, JsonObject.class);
                String name = tree.has("type") ? tree.get("type").getAsString() : "";
                Class<?> type = byName.get(name);
                if (type == null) throw new JsonParseException("unknown " + base.getSimpleName() + " type: " + name);
                tree.remove("type");
                TypeAdapter<?> delegate = gson.getDelegateAdapter(self, TypeToken.get(type));
                return (B) delegate.fromJsonTree(tree);
            }
        };
    }
}
