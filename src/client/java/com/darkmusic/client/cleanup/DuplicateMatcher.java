package com.darkmusic.client.cleanup;

import com.darkmusic.client.library.Track;
import com.darkmusic.client.download.ProcessRunner;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.function.Consumer;

/**
 * Bounded inverted retrieval, followed by independent evidence checks. No transitive matches.
 */
public final class DuplicateMatcher {
    public record Entry(Track track, String stamp, String hash, String source, int[] fingerprint) {
        public Entry {
            fingerprint = fingerprint == null ? new int[0] : fingerprint.clone();
        }
    }

    public record Matches(List<DuplicateResult> pairs, long comparisons, boolean limited) {
    }

    private static final Set<String> DECORATIONS = Set.of("official", "audio", "video", "music", "mv", "pv", "hd", "hq", "4k", "1080p", "lyrics", "lyric", "visualizer", "youtube", "bilibili");
    private static final Set<String> VERSIONS = Set.of("live", "remix", "instrumental", "cover", "acoustic", "karaoke", "remaster", "remastered", "sped", "slowed", "現場", "翻唱", "伴奏");

    public static String normalize(String value) {
        String s = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replaceAll("\\.(mp3|flac|wav|ogg|opus|m4a|aac|mp4|webm|aiff?)$", "")
            .replaceAll("\\[(?:[a-z0-9_-]{11}|bv[a-z0-9]+)\\]$", "")
            .replaceAll("[\\p{P}\\p{S}\\s_]+", " ").strip();
        return Arrays.stream(s.split(" +")).filter(t -> !DECORATIONS.contains(t)).collect(java.util.stream.Collectors.joining(" "));
    }

    static Set<String> tokens(String text) {
        var result = new LinkedHashSet<String>();
        for (String token : normalize(text).split(" +")) {
            if (token.length() > 1) result.add(token);
            if (token.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                int[] cp = token.codePoints().toArray();
                for (int i = 0; i + 1 < cp.length; i++) result.add(new String(cp, i, 2));
            }
        }
        return result;
    }

    static double similarity(String a, String b) {
        a = normalize(a);
        b = normalize(b);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        var x = tokens(a);
        var y = tokens(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        long common = x.stream().filter(y::contains).count();
        return 2.0 * common / (x.size() + y.size());
    }

    public static String sourceId(String url) {
        // Internal archive identity, shared with the downloader; never treated as an external link.
        if (url != null && url.startsWith("darkmusic-source:") && url.length() <= 8192)
            return url.substring("darkmusic-source:".length());
        try {
            URI u = URI.create(url);
            String host = Objects.toString(u.getHost(), "").toLowerCase(Locale.ROOT);
            String path = Objects.toString(u.getPath(), "");
            var query = new HashMap<String, String>();
            for (String part : Objects.toString(u.getRawQuery(), "").split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2) query.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
            if (host.equals("youtu.be") || host.endsWith(".youtu.be")) return youtube(path.substring(1).split("/")[0]);
            if (host.equals("youtube.com") || host.endsWith(".youtube.com")) {
                if (query.containsKey("v")) return youtube(query.get("v"));
                if (path.matches("/(shorts|embed|live)/[^/]+.*")) return youtube(path.split("/")[2]);
            }
            if (host.equals("bilibili.com") || host.endsWith(".bilibili.com")) {
                var m = java.util.regex.Pattern.compile("/(BV[A-Za-z0-9]+|av[0-9]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(path);
                if (m.find()) return "bilibili:" + m.group(1) + ":" + query.getOrDefault("p", "1");
            }
            // No arbitrary URL query stripping: unrelated generic collection entries must not collapse.
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    private static String youtube(String id) {
        return id.matches("[A-Za-z0-9_-]{11}") ? "youtube:" + id : "";
    }

    public Matches match(List<Entry> entries, DuplicatePolicy policy, ProcessRunner.Token token, Consumer<List<DuplicateResult>> partial) {
        var indexes = new HashMap<String, ArrayList<Integer>>();
        var output = new ArrayList<DuplicateResult>();
        long comparisons = 0;
        boolean limited = false;
        for (int i = 0; i < entries.size(); i++) {
            token.check();
            var entry = entries.get(i);
            var keys = keys(entry);
            var votes = new HashMap<Integer, Integer>();
            for (String key : keys) {
                var bucket = indexes.get(key);
                if (bucket != null) for (int other : bucket) votes.merge(other, 1, Integer::sum);
            }
            var retrieved = votes.entrySet().stream().sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(DuplicatePolicy.MAX_RETRIEVED).toList();
            if (votes.size() > retrieved.size()) limited = true;
            for (var candidate : retrieved) {
                token.check();
                comparisons++;
                var result = compare(entries.get(candidate.getKey()), entry, policy);
                if (result != null) {
                    output.add(result);
                    if (output.size() >= DuplicatePolicy.MAX_RESULTS) {
                        limited = true;
                        break;
                    }
                }
            }
            if (output.size() >= DuplicatePolicy.MAX_RESULTS) break;
            for (String key : keys) {
                var bucket = indexes.computeIfAbsent(key, k -> new ArrayList<>());
                if (bucket.size() < DuplicatePolicy.BUCKET_SIZE) bucket.add(i);
                else {
                    // Keep representative early entries and recent neighbours, never enumerate a giant bucket.
                    bucket.set(32 + i % 32, i);
                    limited = true;
                }
            }
            if (i % 1000 == 0 && !output.isEmpty()) partial.accept(List.copyOf(output));
        }
        output.sort(Comparator.comparing(DuplicateResult::strength).thenComparing(r -> r.a().title()));
        return new Matches(List.copyOf(output), comparisons, limited);
    }

    private static Set<String> keys(Entry e) {
        var keys = new LinkedHashSet<String>();
        if (!e.hash().isEmpty()) keys.add("h:" + e.hash());
        String source = sourceId(e.source());
        if (!source.isEmpty()) keys.add("s:" + source);
        String title = normalize(e.track().title()), name = normalize(e.track().file().getFileName().toString());
        if (!title.isEmpty()) keys.add("t:" + title);
        if (!name.isEmpty()) keys.add("n:" + name);
        // Token keys are duration-bucketed; adjacent buckets preserve tolerance near boundaries.
        int duration = (int) (e.track().duration() / 15);
        var words = new LinkedHashSet<>(tokens(title));
        words.addAll(tokens(name));
        for (String word : words.stream().sorted(Comparator.comparingInt(String::length).reversed()).limit(12).toList())
            for (int d = -1; d <= 1; d++) keys.add("w:" + word + ":" + (duration + d));
        for (long key : FingerprintService.keys(e.fingerprint())) keys.add("a:" + key);
        return keys;
    }

    public static DuplicateResult compare(Entry a, Entry b, DuplicatePolicy p) {
        if (a.track().id().equals(b.track().id()) || a.track().file().equals(b.track().file())) return null;
        var reasons = EnumSet.noneOf(DuplicateResult.Evidence.class);
        if (!a.hash().isEmpty() && a.hash().equals(b.hash())) reasons.add(DuplicateResult.Evidence.EXACT);
        String source = sourceId(a.source());
        if (!source.isEmpty() && source.equals(sourceId(b.source()))) reasons.add(DuplicateResult.Evidence.SOURCE);
        boolean length = p.duration(a.track().duration(), b.track().duration());
        double title = similarity(a.track().title(), b.track().title());
        double name = similarity(a.track().file().getFileName().toString(), b.track().file().getFileName().toString());
        if (title >= p.title && length) reasons.add(DuplicateResult.Evidence.TITLE_DURATION);
        if (name >= p.filename && (length || p == DuplicatePolicy.LOOSE && similarity(a.track().artist(), b.track().artist()) >= p.artist))
            reasons.add(DuplicateResult.Evidence.FILENAME);
        var audio = FingerprintService.compare(a.fingerprint(), b.fingerprint(), p);
        if (audio.matched())
            reasons.add(audio.partial() ? DuplicateResult.Evidence.OVERLAP : DuplicateResult.Evidence.AUDIO);
        if (p == DuplicatePolicy.STRICT && !reasons.contains(DuplicateResult.Evidence.EXACT) && !reasons.contains(DuplicateResult.Evidence.SOURCE)
            && !reasons.contains(DuplicateResult.Evidence.AUDIO) && !reasons.contains(DuplicateResult.Evidence.OVERLAP)
            && similarity(a.track().artist(), b.track().artist()) < p.artist) reasons.clear();
        if (reasons.isEmpty()) return null;
        var va = tokens(a.track().title() + " " + a.track().file().getFileName());
        va.retainAll(VERSIONS);
        var vb = tokens(b.track().title() + " " + b.track().file().getFileName());
        vb.retainAll(VERSIONS);
        boolean versions = !va.equals(vb);
        if (versions) reasons.add(DuplicateResult.Evidence.VERSION);
        var strength = reasons.contains(DuplicateResult.Evidence.EXACT) ? DuplicateResult.Strength.STRONG : versions ? DuplicateResult.Strength.VERSIONS
            : reasons.contains(DuplicateResult.Evidence.SOURCE) || reasons.contains(DuplicateResult.Evidence.AUDIO) ? DuplicateResult.Strength.STRONG : DuplicateResult.Strength.POSSIBLE;
        return new DuplicateResult(DuplicateResult.pair(a.track().id(), b.track().id()), a.track(), b.track(), a.stamp(), b.stamp(), reasons, strength);
    }
}
