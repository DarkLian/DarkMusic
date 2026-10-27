package com.darkmusic.client.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OutputTailTest {
    @Test
    void diagnosticCaptureGrowsOnlyAsNeededAndRetainsTheTail() throws Exception {
        var output = new ProcessRunner.OutputTail(16_000_000);
        var field = output.getClass().getDeclaredField("data");
        field.setAccessible(true);
        assertTrue(((char[]) field.get(output)).length <= 4096);
        var small = new ProcessRunner.OutputTail(5000);
        String text = "0123456789".repeat(1200);
        for (char c : text.toCharArray()) small.append(c);
        assertEquals(text.substring(text.length() - 5000), small.text());
    }
}
