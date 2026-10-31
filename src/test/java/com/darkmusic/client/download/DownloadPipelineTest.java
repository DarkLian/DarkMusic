package com.darkmusic.client.download;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DownloadPipelineTest {
    @Test
    void explicitFilenameExtensionKeepsExistingMp3IdentityAndRejectsPaths() {
        String old = DownloadPipeline.filename("音樂 / Song", "same-id");
        assertEquals(old, DownloadPipeline.filename("音樂 / Song", "same-id", "mp3"));
        assertEquals(old.substring(0, old.length() - 4) + ".opus", DownloadPipeline.filename("音樂 / Song", "same-id", "opus"));
        assertThrows(IllegalArgumentException.class, () -> DownloadPipeline.filename("Song", "id", "../wav"));
    }
}
