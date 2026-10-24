package com.darkmusic.client.lyrics;

import com.google.gson.*;
import com.google.gson.stream.*;

import java.io.IOException;

/**
 * Store source text once; legacy derived fields are ignored and rebuilt when read.
 */
public final class LyricsDocumentAdapter extends TypeAdapter<LyricsDocument> {
    @Override
    public void write(JsonWriter out, LyricsDocument d) throws IOException {
        if (d == null) {
            out.nullValue();
            return;
        }
        out.beginObject();
        out.name("provider").value(d.provider());
        out.name("recordId").value(d.recordId());
        out.name("title").value(d.title());
        out.name("artist").value(d.artist());
        out.name("album").value(d.album());
        out.name("duration").value(d.duration());
        out.name("language").value(d.language());
        out.name("variant").value(d.variant().name());
        out.name("generated").value(d.generated());
        out.name("instrumental").value(d.instrumental());
        out.name("text").value(d.text());
        out.endObject();
    }

    @Override
    public LyricsDocument read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }
        try {
            JsonObject o = JsonParser.parseReader(in).getAsJsonObject();
            LyricsDocument.Variant variant;
            try {
                variant = LyricsDocument.Variant.valueOf(text(o, "variant"));
            } catch (IllegalArgumentException unknown) {
                variant = LyricsDocument.Variant.UNKNOWN;
            }
            return LyricsDocument.of(text(o, "provider"), text(o, "recordId"), text(o, "title"), text(o, "artist"), text(o, "album"),
                o.has("duration") ? o.get("duration").getAsDouble() : 0, text(o, "language"), variant,
                o.has("generated") && o.get("generated").getAsBoolean(), o.has("instrumental") && o.get("instrumental").getAsBoolean(), text(o, "text"));
        } catch (RuntimeException invalid) {
            throw new JsonParseException("Invalid lyric document", invalid);
        }
    }

    private static String text(JsonObject o, String key) {
        return !o.has(key) || o.get(key).isJsonNull() ? "" : o.get(key).getAsString();
    }
}
