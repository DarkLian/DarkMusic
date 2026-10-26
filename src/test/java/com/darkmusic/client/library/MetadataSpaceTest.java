package com.darkmusic.client.library;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetadataSpaceTest {
    @Test
    void lowSpaceAndOverflowFailBeforeTouchingFiles() throws Exception {
        assertThrows(java.io.IOException.class, () -> MediaFiles.requireEditSpace(10_000_000, 29_999_999));
        assertThrows(java.io.IOException.class, () -> MediaFiles.requireEditSpace(Long.MAX_VALUE, Long.MAX_VALUE));
        MediaFiles.requireEditSpace(10_000_000, 40_000_000);
    }
}