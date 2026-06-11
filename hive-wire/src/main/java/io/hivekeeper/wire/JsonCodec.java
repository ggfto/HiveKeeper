package io.hivekeeper.wire;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Event;
import io.hivekeeper.core.api.Result;

/**
 * JSON (de)serialization for the core {@link Command} / {@link Event} / {@link Result} DTOs. This is
 * the ONLY place that depends on Jackson — hive-core stays serialization-agnostic. Polymorphism for
 * the sealed types is added here via mix-ins (Jackson auto-detects the permitted subtypes), so the
 * core records carry no framework annotations.
 *
 * <p>The wire format is intentionally the serialized in-process API: the same bytes that move a job
 * from a cloud control plane to an on-prem agent. JSON is used in v0.1 for readability; a binary codec
 * can be swapped in later without touching hive-core.
 */
public final class JsonCodec {

    private final ObjectMapper mapper;

    public JsonCodec() {
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        mapper.addMixIn(Command.class, SealedMixin.class);
        mapper.addMixIn(Event.class, SealedMixin.class);
        mapper.addMixIn(Result.class, SealedMixin.class);

        // SIMPLE_NAME needs the candidate subtypes registered. Derive them from the sealed hierarchy
        // so adding a new Command/Event/Result variant requires no change here.
        registerSealedSubtypes(Command.class);
        registerSealedSubtypes(Event.class);
        registerSealedSubtypes(Result.class);
    }

    private void registerSealedSubtypes(Class<?> sealedBase) {
        Class<?>[] permitted = sealedBase.getPermittedSubclasses();
        if (permitted == null) {
            return;
        }
        for (Class<?> sub : permitted) {
            mapper.registerSubtypes(new NamedType(sub, sub.getSimpleName()));
        }
    }

    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("serialize failed: " + e.getMessage(), e);
        }
    }

    public <T> T fromJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("deserialize failed: " + e.getMessage(), e);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
    private interface SealedMixin {
    }
}
