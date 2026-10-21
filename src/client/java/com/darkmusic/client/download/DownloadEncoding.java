package com.darkmusic.client.download;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Codec-preserving remux plans. Unknown codecs fall back to lossless PCM preservation, never MP3 silently.
 */
public final class DownloadEncoding {
    public enum Format {ORIGINAL, MP3}

    public record Plan(String extension, String codec, String warning) {
    }

    private DownloadEncoding() {
    }

    public static Plan plan(String codec) {
        return switch (codec) {
            case "aac", "alac" -> new Plan("m4a", "copy", "");
            case "opus" -> new Plan("opus", "copy", "");
            case "vorbis" -> new Plan("ogg", "copy", "");
            case "mp3" -> new Plan("mp3", "copy", "");
            case "flac" -> new Plan("flac", "copy", "");
            case "pcm_u8", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_s64le", "pcm_f32le", "pcm_f64le", "pcm_alaw",
                 "pcm_mulaw" -> new Plan("wav", "copy", "");
            case "pcm_s8", "pcm_s8_planar" -> new Plan("wav", "pcm_u8", "");
            case "pcm_s16be", "pcm_s16be_planar", "pcm_s16le_planar", "pcm_u16be", "pcm_u16le", "pcm_vidc" ->
                new Plan("wav", "pcm_s16le", "");
            case "pcm_s24be", "pcm_s24le_planar", "pcm_u24be", "pcm_u24le", "pcm_s24daud" ->
                new Plan("wav", "pcm_s24le", "");
            case "pcm_s32be", "pcm_s32le_planar", "pcm_u32be", "pcm_u32le", "pcm_dvd", "pcm_bluray" ->
                new Plan("wav", "pcm_s32le", "");
            case "pcm_s64be" -> new Plan("wav", "pcm_s64le", "");
            case "pcm_f32be" -> new Plan("wav", "pcm_f32le", "");
            case "pcm_f64be" -> new Plan("wav", "pcm_f64le", "");
            default -> new Plan("flac", "flac", com.darkmusic.client.text.Text.get("download.codec_fallback"));
        };
    }

    public static String codec(ProcessRunner runner, Toolchain tools, Path source, Path work, ProcessRunner.Token token) throws Exception {
        var r = runner.run(List.of(tools.ffprobe().toString(), "-v", "error", "-protocol_whitelist", "file,pipe", "-select_streams", "a:0", "-show_entries", "stream=codec_name", "-of", "json", source.toString()), work, token, Duration.ofSeconds(30), 32_000, s -> {
        });
        if (r.exit() != 0)
            throw new java.io.IOException(com.darkmusic.client.text.Text.get("download.validation_failed"), new java.io.IOException(r.output()));
        var array = JsonParser.parseString(r.output()).getAsJsonObject().getAsJsonArray("streams");
        if (array == null || array.isEmpty())
            throw new java.io.IOException(com.darkmusic.client.text.Text.get("download.media_missing"));
        return array.get(0).getAsJsonObject().get("codec_name").getAsString();
    }

    static List<String> remux(Toolchain tools, Path source, Path target, Path cover, JsonObject metadata, DownloadModels.Entry entry, Plan plan) {
        var args = new ArrayList<>(DownloadPipeline.convert(tools, source, target, cover, metadata, entry));
        args.set(args.indexOf("-map_metadata") + 1, "0");
        int codec = args.indexOf("libmp3lame");
        args.set(codec, plan.codec());
        int quality = args.indexOf("-q:a");
        args.subList(quality, quality + 2).clear();
        int id3 = args.indexOf("-id3v2_version");
        if (!plan.extension().equals("mp3")) args.subList(id3, id3 + 2).clear();
        if (plan.extension().equals("m4a")) {
            args.add(args.size() - 1, "-movflags");
            args.add(args.size() - 1, "+faststart");
        }
        if (plan.extension().equals("opus") || plan.extension().equals("ogg")) {
            // Ogg tags belong to the audio stream. Explicit values override inherited stream tags.
            var replacements = new ArrayList<String>();
            for (int i = 0; i + 1 < args.size(); i++)
                if (args.get(i).equals("-metadata")) replacements.addAll(List.of("-metadata:s:a:0", args.get(i + 1)));
            args.addAll(args.size() - 1, replacements);
        }
        return args;
    }

    static List<String> withPictureComment(List<String> command, Path pictureTags) {
        var args = new ArrayList<>(command);
        int map = args.indexOf("-map");
        args.addAll(map, List.of("-f", "ffmetadata", "-protocol_whitelist", "file,pipe", "-i", pictureTags.toString()));
        args.addAll(args.size() - 1, List.of("-map_metadata:s:a:0", "0:s:a:0", "-map_metadata:s:a:0", "1:g"));
        return args;
    }

    static Path pictureComment(ProcessRunner runner, Toolchain tools, Path cover, Path work, ProcessRunner.Token token) throws Exception {
        Path jpeg = work.resolve("embedded-cover.jpg");
        var converted = runner.run(List.of(tools.ffmpeg().toString(), "-nostdin", "-v", "error", "-y", "-protocol_whitelist", "file,pipe", "-i", cover.toString(), "-frames:v", "1", "-vf", "scale=1024:1024:force_original_aspect_ratio=decrease", jpeg.toString()), work, token, Duration.ofSeconds(30), 16_000, s -> {
        });
        if (converted.exit() != 0 || !java.nio.file.Files.isRegularFile(jpeg) || java.nio.file.Files.size(jpeg) > 8 * 1024 * 1024)
            throw new java.io.IOException(com.darkmusic.client.text.Text.get("download.thumbnail_failed"));
        byte[] bytes = java.nio.file.Files.readAllBytes(jpeg);
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        if (image == null)
            throw new java.io.IOException(com.darkmusic.client.text.Text.get("download.thumbnail_failed"));
        Path tags = work.resolve("artwork.ffmetadata");
        java.nio.file.Files.writeString(tags, ";FFMETADATA1\nMETADATA_BLOCK_PICTURE=" + com.darkmusic.client.library.OggArtwork.picture(bytes, image.getWidth(), image.getHeight()) + "\n");
        return tags;
    }
}
