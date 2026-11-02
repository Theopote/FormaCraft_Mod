package com.formacraft.common.component.community;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Strict, bounded parsing shared by the community wire adapters. */
final class CommunityJson {
    private CommunityJson() {}
    static JsonObject read(byte[] bytes, int limit) throws IOException {
        if (bytes == null) throw new CommunityPackages.Failure("invalid_request");
        if (bytes.length > limit) throw new CommunityPackages.Failure("request_too_large");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            // A BOM is not part of this JSON contract; JsonReader would silently consume it.
            if (!text.isEmpty() && text.charAt(0) == '\ufeff') throw new IOException("BOM");
            var reader = new JsonReader(new StringReader(text)); reader.setStrictness(Strictness.STRICT);
            JsonElement root = value(reader, 0, new int[]{0});
            if (reader.peek() != JsonToken.END_DOCUMENT || !root.isJsonObject()) throw new IOException("Object required");
            return root.getAsJsonObject();
        } catch (IOException | RuntimeException invalid) {
            if (invalid instanceof CommunityPackages.Failure failure) throw failure;
            throw new CommunityPackages.Failure("invalid_request", invalid);
        }
    }
    private static JsonElement value(JsonReader reader, int depth, int[] nodes) throws IOException {
        if (depth > 16 || ++nodes[0] > 4096) throw new IOException("JSON complexity limit");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); var object = new JsonObject();
                while (reader.hasNext()) {
                    String key = reader.nextName(); validUnicode(key);
                    if (object.has(key)) throw new IOException("Duplicate property");
                    object.add(key, value(reader, depth + 1, nodes));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); var array = new JsonArray();
                while (reader.hasNext()) array.add(value(reader, depth + 1, nodes));
                reader.endArray(); yield array;
            }
            case STRING -> { String text = reader.nextString(); validUnicode(text); yield new JsonPrimitive(text); }
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Unexpected JSON token");
        };
    }
    private static void validUnicode(String value) throws IOException {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IOException("Unpaired surrogate");
            } else if (Character.isLowSurrogate(c)) throw new IOException("Unpaired surrogate");
        }
    }
    static void keys(JsonObject value, String... allowed) throws IOException {
        if (!Set.of(allowed).containsAll(value.keySet())) throw new CommunityPackages.Failure("unsupported_constraint");
    }
    static JsonObject object(JsonElement value) throws IOException {
        if (value == null || !value.isJsonObject()) throw new CommunityPackages.Failure("invalid_request");
        return value.getAsJsonObject();
    }
    static boolean absent(JsonElement value) { return value == null || value.isJsonNull(); }
    static String text(JsonElement value, int max) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new CommunityPackages.Failure("invalid_request");
        String text = value.getAsString();
        if (text.isBlank() || text.codePointCount(0, text.length()) > max || text.codePoints().anyMatch(Character::isISOControl))
            throw new CommunityPackages.Failure("invalid_request");
        return text;
    }
    static String optionalText(JsonElement value, int max) throws IOException { return absent(value) ? null : text(value, max); }
    static boolean bool(JsonElement value) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new CommunityPackages.Failure("invalid_request");
        return value.getAsBoolean();
    }
    static int integer(JsonElement value, int min, int max) throws IOException {
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            int n = value.getAsBigDecimal().intValueExact();
            if (n < min || n > max) throw new ArithmeticException();
            return n;
        } catch (ArithmeticException | NumberFormatException invalid) { throw new CommunityPackages.Failure("invalid_request", invalid); }
    }
    static Set<String> strings(JsonElement value, int count, int width) throws IOException {
        if (value == null) return Set.of();
        if (!value.isJsonArray() || value.getAsJsonArray().size() > count) throw new CommunityPackages.Failure("invalid_request");
        Set<String> result = new TreeSet<>();
        for (JsonElement item : value.getAsJsonArray()) if (!result.add(text(item, width))) throw new CommunityPackages.Failure("invalid_request");
        return Collections.unmodifiableSet(result);
    }
}
