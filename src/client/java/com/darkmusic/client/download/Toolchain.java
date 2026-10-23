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
        Path managed = ManagedTools.current(config);
        Path directory = managed == null ? config.resolve("tools") : managed;
        Files.createDirectories(directory);
        Path yt = role(DownloadDiagnostics.Tool.YT_DLP, () -> executable(settings.ytDlp(), "yt-dlp", directory, null));
        Path ff = role(DownloadDiagnostics.Tool.FFMPEG, () -> executable(settings.ffmpeg(), "ffmpeg", directory, null));
        Path probe = role(DownloadDiagnostics.Tool.FFPROBE, () -> executable(settings.ffprobe(), "ffprobe", directory, ff.getParent()));
        String yv = role(DownloadDiagnostics.Tool.YT_DLP, () -> version(yt, List.of("--ignore-config", "--version"), runner, token, config));
        String fv = role(DownloadDiagnostics.Tool.FFMPEG, () -> version(ff, List.of("-version"), runner, token, config));
        String pv = role(DownloadDiagnostics.Tool.FFPROBE, () -> version(probe, List.of("-version"), runner, token, config));
        var helpResult = runner.run(List.of(yt.toString(), "--ignore-config", "--help"), config, token, Duration.ofSeconds(15), 250_000, s -> {
        });
        if (helpResult.exit() != 0)
            throw new DownloadDiagnostics.Failure(DownloadDiagnostics.Category.TOOL_INCOMPATIBLE, DownloadDiagnostics.Stage.TOOLS,
                DownloadDiagnostics.Tool.YT_DLP, helpResult.exit(), helpResult.output(), null);
        String help = helpResult.output();
        for (String flag : List.of("--dump-single-json", "--flat-playlist", "--progress-template", "--no-playlist", "--no-plugin-dirs"))
            if (!help.contains(flag))
                throw new DownloadDiagnostics.Failure(DownloadDiagnostics.Category.TOOL_INCOMPATIBLE, DownloadDiagnostics.Stage.TOOLS,
                    DownloadDiagnostics.Tool.YT_DLP, null, "Unsupported option", null);
        var encoders = runner.run(List.of(ff.toString(), "-hide_banner", "-encoders"), config, token, Duration.ofSeconds(15), 250_000, s -> {
        });
        if (encoders.exit() != 0 || !encoders.output().contains("libmp3lame"))
            throw new DownloadDiagnostics.Failure(DownloadDiagnostics.Category.TOOL_INCOMPATIBLE, DownloadDiagnostics.Stage.TOOLS,
                DownloadDiagnostics.Tool.FFMPEG, encoders.exit(), "Missing MP3 encoder", null);
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
        if (kind.isEmpty())
            throw incompatibleRuntime();
        String value = version(path, List.of("--version"), runner, token, config);
        var match = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(value);
        if (!match.find())
            throw incompatibleRuntime();
        int major = Integer.parseInt(match.group(1)), minor = Integer.parseInt(match.group(2));
        if (kind.equals("node") ? major < 22 : major < 2 || major == 2 && minor < 3)
            throw incompatibleRuntime();
        return kind + ":" + path;
    }

    private static DownloadDiagnostics.Failure incompatibleRuntime() {
        return new DownloadDiagnostics.Failure(DownloadDiagnostics.Category.TOOL_INCOMPATIBLE, DownloadDiagnostics.Stage.TOOLS,
            DownloadDiagnostics.Tool.JAVASCRIPT, null, "Incompatible JavaScript runtime", null);
    }

    private static <T> T role(DownloadDiagnostics.Tool tool, DownloadDiagnostics.Checked<T> operation) throws Exception {
        try {
            return DownloadDiagnostics.at(DownloadDiagnostics.Stage.TOOLS, tool, operation);
        } catch (DownloadDiagnostics.Failure failure) {
            throw new DownloadDiagnostics.Failure(failure.category, failure.stage, tool, failure.exitCode, failure.excerpt, failure);
        }
    }

    private static DownloadDiagnostics.Tool tool(String name) {
        name = name.toLowerCase(Locale.ROOT);
        return name.startsWith("yt-dlp") ? DownloadDiagnostics.Tool.YT_DLP : name.startsWith("ffmpeg") ? DownloadDiagnostics.Tool.FFMPEG
            : name.startsWith("ffprobe") ? DownloadDiagnostics.Tool.FFPROBE : name.startsWith("deno") || name.startsWith("node")
            ? DownloadDiagnostics.Tool.JAVASCRIPT : DownloadDiagnostics.Tool.TOOLS;
    }

    private static String version(Path path, List<String> arguments, ProcessRunner runner, ProcessRunner.Token token, Path config) throws Exception {
        var command = new ArrayList<String>();
        command.add(path.toString());
        command.addAll(arguments);
        var result = runner.run(command, config, token, Duration.ofSeconds(15), 32_000, s -> {
        });
        if (result.exit() != 0)
            throw new DownloadDiagnostics.Failure(DownloadDiagnostics.Category.TOOL_INCOMPATIBLE, DownloadDiagnostics.Stage.TOOLS,
                tool(path.getFileName().toString()), result.exit(), result.output(), null);
        return result.output().lines().findFirst().orElse("Unknown version");
    }

    public static Path executable(String configured, String name, Path tools, Path sibling) throws IOException {
        if (configured != null && !configured.isBlank()) return checked(Path.of(configured));
        return executable(name, tools, sibling, System.getenv().getOrDefault("PATH", ""));
    }

    static Path executable(String name, Path tools, Path sibling, String searchPath) throws IOException {
        String binary = name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "");
        var dirs = new ArrayList<Path>();
        dirs.add(tools);
        if (sibling != null) dirs.add(sibling);
        for (String entry : searchPath.split(java.io.File.pathSeparator)) {
            entry = entry.strip();
            if (entry.startsWith("\"") && entry.endsWith("\"") && entry.length() >= 2)
                entry = entry.substring(1, entry.length() - 1);
            if (!entry.isBlank()) try {
                dirs.add(Path.of(entry));
            } catch (java.nio.file.InvalidPathException ignored) {
            }
        }
        for (Path dir : dirs)
            try {
                if (Files.isRegularFile(dir.resolve(binary))) return checked(dir.resolve(binary));
            } catch (IOException | SecurityException ignored) {
            }
        throw new DownloadDiagnostics.Failure(DownloadDiagnostics.Category.TOOL_MISSING, DownloadDiagnostics.Stage.TOOLS,
            tool(name), null, "Missing executable", null);
    }

    private static Path checked(Path value) throws IOException {
        Path path = value.toAbsolutePath().normalize();
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!Files.isRegularFile(path) || !Files.isExecutable(path) || name.endsWith(".cmd") || name.endsWith(".bat") || name.endsWith(".ps1"))
            throw new DownloadDiagnostics.Failure(Files.isRegularFile(path) ? DownloadDiagnostics.Category.TOOL_INCOMPATIBLE : DownloadDiagnostics.Category.TOOL_MISSING,
                DownloadDiagnostics.Stage.TOOLS, tool(name), null, "Selected executable cannot run", null);
        return path;
    }

    public List<String> base() {
        var args = new ArrayList<>(List.of(ytDlp.toString(), "--ignore-config", "--no-plugin-dirs", "--no-cache-dir", "--no-update", "--no-colors", "--encoding", "utf-8", "--socket-timeout", "15", "--retries", "2", "--extractor-retries", "2", "--ffmpeg-location", ffmpeg.getParent().toString()));
        if (!runtime.isEmpty()) args.addAll(List.of("--js-runtimes", runtime));
        return args;
    }
}
