package charter.mc.store;

import charter.engine.Action;
import charter.food.Provisions;
import charter.grant.Holder;
import charter.history.HistoryEvent;
import charter.id.ClaimId;
import charter.id.ContractId;
import charter.id.CoreId;
import charter.id.GrantId;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.id.VoteId;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * The one Gson the store uses. Ids are written as bare UUID strings (so they can
 * be map keys), a {@link Holder} as {@code {"player": id}}, and actions and events
 * as their record fields plus a {@code "type"} name from the registries.
 */
public final class CharterJson {

    public final TypeRegistry<Action> actions;
    public final TypeRegistry<HistoryEvent> events;
    public final Gson gson;
    /** One line per record, for the history file and the siege log. */
    public final Gson compact;

    public CharterJson(TypeRegistry<Action> actions, TypeRegistry<HistoryEvent> events) {
        this.actions = actions;
        this.events = events;
        this.gson = builder().setPrettyPrinting().create();
        this.compact = builder().create();
    }

    private GsonBuilder builder() {
        GsonBuilder b = new GsonBuilder()
                .disableHtmlEscaping()
                .enableComplexMapKeySerialization()
                .serializeSpecialFloatingPointValues();
        id(b, OrgId.class, OrgId::new, OrgId::value);
        id(b, PlayerId.class, PlayerId::new, PlayerId::value);
        id(b, CoreId.class, CoreId::new, CoreId::value);
        id(b, ClaimId.class, ClaimId::new, ClaimId::value);
        id(b, ContractId.class, ContractId::new, ContractId::value);
        id(b, GrantId.class, GrantId::new, GrantId::value);
        id(b, VoteId.class, VoteId::new, VoteId::value);
        id(b, OfficeId.class, OfficeId::new, OfficeId::value);
        b.registerTypeAdapter(Provisions.class, new ProvisionsAdapter().nullSafe());
        b.registerTypeAdapterFactory(new HolderFactory());
        b.registerTypeAdapterFactory(new Tagged<>(Action.class, actions));
        b.registerTypeAdapterFactory(new Tagged<>(HistoryEvent.class, events));
        return b;
    }

    private static <T> void id(GsonBuilder b, Class<T> type, Function<UUID, T> make, Function<T, UUID> value) {
        b.registerTypeAdapter(type, new TypeAdapter<T>() {
            @Override
            public void write(JsonWriter out, T id) throws IOException {
                out.value(value.apply(id).toString());
            }

            @Override
            public T read(JsonReader in) throws IOException {
                return make.apply(UUID.fromString(in.nextString()));
            }
        }.nullSafe());
    }

    /** A name the registry does not know, so the record cannot be read back. */
    public static final class UnknownType extends JsonParseException {
        public final String tag;

        UnknownType(String what, String tag) {
            super("unknown " + what + " type: " + tag);
            this.tag = tag;
        }
    }

    /** Writes a registered record with its {@code "type"} name; reads it back by that name. */
    private static final class Tagged<B> implements TypeAdapterFactory {
        private final Class<B> base;
        private final TypeRegistry<B> registry;

        Tagged(Class<B> base, TypeRegistry<B> registry) {
            this.base = base;
            this.registry = registry;
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
                    String tag = registry.tag(value.getClass()).orElseThrow(() -> new IllegalStateException(
                            value.getClass().getName() + " is not registered as a " + registry.what()
                                    + " type; register it from its feature's register call"));
                    TypeAdapter<B> delegate = (TypeAdapter<B>) gson.getDelegateAdapter(self, TypeToken.get(value.getClass()));
                    JsonObject tree = delegate.toJsonTree(value).getAsJsonObject();
                    JsonObject tagged = new JsonObject();
                    tagged.addProperty("type", tag);
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
                    String tag = tree.has("type") ? tree.get("type").getAsString() : "";
                    Class<? extends B> concrete = registry.type(tag).orElseThrow(() -> new UnknownType(registry.what(), tag));
                    return gson.getDelegateAdapter(self, TypeToken.get(concrete)).fromJsonTree(tree);
                }
            };
        }
    }

    private static final class HolderFactory implements TypeAdapterFactory {
        @Override
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (!Holder.class.isAssignableFrom(type.getRawType())) return null;
            TypeAdapter<UUID> uuid = gson.getAdapter(UUID.class);
            return (TypeAdapter<T>) new TypeAdapter<Holder>() {
                @Override
                public void write(JsonWriter out, Holder holder) throws IOException {
                    if (holder == null) {
                        out.nullValue();
                        return;
                    }
                    out.beginObject();
                    switch (holder) {
                        case Holder.OfPlayer p -> out.name("player").value(p.player().value().toString());
                        case Holder.OfOffice o -> out.name("office").value(o.office().value().toString());
                        case Holder.OfOrg o -> out.name("org").value(o.org().value().toString());
                    }
                    out.endObject();
                }

                @Override
                public Holder read(JsonReader in) throws IOException {
                    if (in.peek() == JsonToken.NULL) {
                        in.nextNull();
                        return null;
                    }
                    in.beginObject();
                    String kind = in.nextName();
                    UUID id = uuid.read(in);
                    in.endObject();
                    return switch (kind) {
                        case "player" -> new Holder.OfPlayer(new PlayerId(id));
                        case "office" -> new Holder.OfOffice(new OfficeId(id));
                        case "org" -> new Holder.OfOrg(new OrgId(id));
                        default -> throw new JsonParseException("unknown holder kind: " + kind);
                    };
                }
            };
        }
    }

    private static final class ProvisionsAdapter extends TypeAdapter<Provisions> {
        @Override
        public void write(JsonWriter out, Provisions p) throws IOException {
            out.beginObject();
            for (Map.Entry<String, Integer> e : p.counts().entrySet()) out.name(e.getKey()).value(e.getValue());
            out.endObject();
        }

        @Override
        public Provisions read(JsonReader in) throws IOException {
            Provisions p = new Provisions();
            Map<String, Integer> counts = new TreeMap<>();
            in.beginObject();
            while (in.hasNext()) counts.put(in.nextName(), in.nextInt());
            in.endObject();
            counts.forEach(p::add);
            return p;
        }
    }
}
