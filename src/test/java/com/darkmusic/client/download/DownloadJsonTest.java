package com.darkmusic.client.download;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class DownloadJsonTest {
    @Test
    void previewDoesNotCarryCaptionEvidenceEvenWhenMetadataContainsIt() {
        var plain = JsonParser.parseString("{\"id\":\"song\",\"title\":\"Song\",\"url\":\"https://example.com/song\"}").getAsJsonObject();
        var enriched = plain.deepCopy();
        enriched.add("subtitles", JsonParser.parseString("{\"ja\":[{}]}"));
        enriched.add("automatic_captions", JsonParser.parseString("{\"en\":[{}]}"));
        assertEquals(DownloadJson.preview("https://example.com/song", 1, plain), DownloadJson.preview("https://example.com/song", 1, enriched));
        assertTrue(Arrays.stream(DownloadModels.Entry.class.getRecordComponents()).noneMatch(field -> field.getName().equals("lyrics")));
    }
}
