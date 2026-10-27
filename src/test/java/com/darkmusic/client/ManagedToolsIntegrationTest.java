package com.darkmusic.client;

import com.darkmusic.client.tools.ManagedTools;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.tools.ToolPaths;
import com.darkmusic.client.tools.Toolchain;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit online validation: real published executables, real private audio, loopback media only.
 */
class ManagedToolsIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void publishedToolsInstallAndConvertTagProbeDownloadAndDecodeRealMusic() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("darkmusic.test.managedTools"), "Enable -PmanagedToolsIntegration for the real installer");
        TestMusic.files();
        Path data = Path.of(System.getProperty("darkmusic.test.managedData"));
        var runner = new ProcessRunner();
        var chain = new ManagedTools(data).install(runner, new ProcessRunner.Token(), text -> {
        });
        assertNotNull(ManagedTools.current(data));
        var automatic = Toolchain.resolve(new ToolPaths("", "", "", ""), data, runner, new ProcessRunner.Token());
        assertEquals(chain.ytDlp(), automatic.ytDlp());
        assertFalse(automatic.runtime().isBlank());
        var settings = new ToolPaths(chain.ytDlp().toString(), chain.ffmpeg().toString(), chain.ffprobe().toString(), chain.runtime().substring("deno:".length()));
        var checks = new DownloadIntegrationTest() {
            @Override
            ToolPaths settings() {
                return settings;
            }
        };
        checks.temp = Files.createDirectories(temporary.resolve("conversion"));
        checks.realMusicDecodesAcrossSupportedConvertedFormats();
        checks.temp = Files.createDirectories(temporary.resolve("download"));
        checks.realYtDlpDownloadsAndConvertsAuthorizedAudioAndLibraryFindsItImmediately();
        checks.temp = Files.createDirectories(temporary.resolve("tags"));
        checks.realFfmpegPreservesUnicodeMetadataAndArtworkWithoutInventingMissingAlbum();
    }
}
