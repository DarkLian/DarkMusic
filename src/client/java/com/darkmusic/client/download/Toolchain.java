package com.darkmusic.client.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.darkmusic.client.download.DownloadModels.Tools;

public record Toolchain(Path ytDlp, Path ffmpeg, Path ffprobe, String runtime, String versions) {
    public static Toolchain resolve(Tools settings, Path config, ProcessRunner runner, ProcessRunner.Token token) throws Exception {
        Path directory = config.resolve("tools");
        Files.createDirectories(directory);
        Path yt = executable(settings.ytDlp(), "yt-dlp", directory, null);
        Path ff = executable(settings.ffmpeg(), "ffmpeg", directory, null);
        Path probe = executable(settings.ffprobe(), "ffprobe", directory, ff.getParent());
        String yv = version(yt, List.of("--ignore-config", "--version"), runner, token, config);
        String fv = version(ff, List.of("-version"), runner, token, config);
        String pv = version(probe, List.of("-version"), runner, token, config);
        String help = runner.run(List.of(yt.toString(), "--ignore-config", "--help"), config, token, Duration.ofSeconds(15), 250_000, s -> {
        }).output();
        for (String flag : List.of("--dump-single-json", "--flat-playlist", "--progress-template", "--no-playlist", "--no-plugin-dirs"))
            if (!help.contains(flag))
                throw new IOException("This yt-dlp is incompatible. Update it from github.com/yt-dlp/yt-dlp/releases.");
        var encoders = runner.run(List.of(ff.toString(), "-hide_banner", "-encoders"), config, token, Duration.ofSeconds(15), 250_000, s -> {
        });
        if (encoders.exit() != 0 || !encoders.output().contains("libmp3lame"))
            throw new IOException("FFmpeg must include the libmp3lame MP3 encoder.");
        String runtime = "";
        if (!settings.javascript().isBlank()) {
            Path js = checked(Path.of(settings.javascript()));
            runtime = runtime(js, runner, token, config);
        } else {
            for (String name : List.of("deno", "node"))
                try {
                    runtime = runtime(executable("", name, directory, null), runner, token, config);
                    break;
                } catch (IOException ignored) {
                }
        }
        return new Toolchain(yt, ff, probe, runtime, yv + " | " + fv + " | " + pv + (runtime.isEmpty() ? " | YouTube may need Deno/Node" : " | JavaScript runtime ready"));
    }

    private static String runtime(Path path, ProcessRunner runner, ProcessRunner.Token token, Path config) throws Exception {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        String kind = name.startsWith("deno") ? "deno" : name.startsWith("node") ? "node" : "";
        if (kind.isEmpty()) throw new IOException("JavaScript runtime must be a Deno or Node executable.");
        String value = version(path, List.of("--version"), runner, token, config);
        var match = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(value);
        if (!match.find()) throw new IOException("Could not identify the JavaScript runtime version.");
        int major = Integer.parseInt(match.group(1)), minor = Integer.parseInt(match.group(2));
        if (kind.equals("node") ? major < 22 : major < 2 || major == 2 && minor < 3)
            throw new IOException("YouTube needs Deno 2.3+ or Node 22+. Update the selected JavaScript runtime.");
        return kind + ":" + path;
    }

    private static String version(Path path, List<String> arguments, ProcessRunner runner, ProcessRunner.Token token, Path config) throws Exception {
        var command = new ArrayList<String>();
        command.add(path.toString());
        command.addAll(arguments);
        var result = runner.run(command, config, token, Duration.ofSeconds(15), 32_000, s -> {
        });
        if (result.exit() != 0) throw new IOException("Cannot run " + path.getFileName() + ": " + result.output());
        return result.output().lines().findFirst().orElse("Unknown version");
    }

    static Path executable(String configured, String name, Path tools, Path sibling) throws IOException {
        if (configured != null && !configured.isBlank()) return checked(Path.of(configured));
        String binary = name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "");
        var dirs = new ArrayList<Path>();
        dirs.add(tools);
        if (sibling != null) dirs.add(sibling);
        for (String entry : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
            if (!entry.isBlank()) dirs.add(Path.of(entry));
        for (Path dir : dirs) if (Files.isRegularFile(dir.resolve(binary))) return checked(dir.resolve(binary));
        throw new IOException("Missing " + name + ". Set its executable path in Download Tools or install it on PATH.");
    }

    private static Path checked(Path value) throws IOException {
        Path path = value.toAbsolutePath().normalize();
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!Files.isRegularFile(path) || !Files.isExecutable(path) || name.endsWith(".cmd") || name.endsWith(".bat") || name.endsWith(".ps1"))
            throw new IOException("Choose a runnable executable, not a shell script: " + path.getFileName());
        return path;
    }

    public List<String> base() {
        var args = new ArrayList<>(List.of(ytDlp.toString(), "--ignore-config", "--no-plugin-dirs", "--no-cache-dir", "--no-update", "--no-colors", "--encoding", "utf-8", "--socket-timeout", "15", "--retries", "2", "--extractor-retries", "2", "--ffmpeg-location", ffmpeg.getParent().toString()));
        if (!runtime.isEmpty()) args.addAll(List.of("--js-runtimes", runtime));
        return args;
    }
}
