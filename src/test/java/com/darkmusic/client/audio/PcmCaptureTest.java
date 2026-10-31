package com.darkmusic.client.audio;

import com.darkmusic.client.media.ExternalAudio;
import com.darkmusic.client.testing.TestMusic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class PcmCaptureTest {
    @Test
    @Timeout(180)
    void twentyCapturesPerScenarioAreIdenticalAndMatchThePreExtractionGolden() throws Exception {
        ExternalAudio.configure(null);
        var clip = TestMusic.clip();
        var actual = new Properties();
        actual.setProperty("clip", PcmCapture.hash(clip.bytes()));
        for (var scenario : PcmCapture.Scenario.values()) {
            PcmCapture.Result first = null;
            for (int run = 0; run < 20; run++) {
                try (var capture = new PcmCapture(clip, scenario)) {
                    var result = capture.capture();
                    if (first == null) first = result;
                    else assertEquals(first, result, scenario + " repeat " + run);
                }
            }
            actual.setProperty(scenario.name(), first.sha256());
            actual.setProperty(scenario.name() + ".frames", Long.toString(first.frames()));
        }
        if ("true".equals(System.getenv("DARKMUSIC_PCM_RECORD"))) {
            var destination = Path.of(".local-tools/verification/deferred/pcm-current.properties");
            Files.createDirectories(destination.getParent());
            try (var out = Files.newOutputStream(destination)) {
                actual.store(out, "Pre-extraction PCM: hashes only, no private audio");
            }
            return;
        }
        var golden = new Properties();
        try (var in = getClass().getResourceAsStream("/pcm-capture-golden.properties")) {
            assertNotNull(in, "Record the baseline before moving playback state");
            golden.load(in);
        }
        Assumptions.assumeTrue(actual.getProperty("clip").equals(golden.getProperty("clip")), "Private collection clip differs; determinism still verified above");
        assertEquals(golden, actual);
    }
}
