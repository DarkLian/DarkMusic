package com.darkmusic.client.lyrics;

import com.darkmusic.client.library.SourceLinks;

import java.net.*;
import java.util.*;

/**
 * Network policy for caption requests; automatic work accepts canonical supported videos only.
 */
public final class CaptionUrls {
    private CaptionUrls() {
    }

    public static String automatic(String value) {
        String safe = SourceLinks.safe(value);
        if (safe.isEmpty()) return "";
        try {
            URI uri = URI.create(safe);
            if (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443) return "";
            String host = uri.getHost().toLowerCase(Locale.ROOT), path = Objects.toString(uri.getPath(), "");
            String id = "";
            if (Set.of("youtu.be", "www.youtu.be").contains(host)) id = path.substring(Math.min(1, path.length()));
            else if (Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "www.youtube-nocookie.com").contains(host)) {
                if (path.equals("/watch")) id = query(uri, "v");
                else if (path.matches("/(?:shorts|embed|live)/[^/]+")) id = path.substring(path.lastIndexOf('/') + 1);
            }
            if (id.matches("[A-Za-z0-9_-]{11}")) return "https://www.youtube.com/watch?v=" + id;
            if (Set.of("bilibili.com", "www.bilibili.com", "m.bilibili.com").contains(host)
                && path.matches("/video/(?:BV[A-Za-z0-9]+|av[0-9]+)/?")) {
                String page = query(uri, "p");
                return "https://www.bilibili.com" + path.replaceAll("/$", "") + (page.matches("[1-9][0-9]{0,4}") ? "?p=" + page : "");
            }
        } catch (RuntimeException invalid) {
        }
        return "";
    }

    private static String query(URI uri, String key) {
        for (String part : Objects.toString(uri.getRawQuery(), "").split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && URLDecoder.decode(pair[0], java.nio.charset.StandardCharsets.UTF_8).equals(key))
                return URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8);
        }
        return "";
    }

    public static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) return (bytes[0] & 0xfe) != 0xfc;
        int a = bytes[0] & 255, b = bytes[1] & 255;
        return a != 0 && a != 127 && a < 224 && !(a == 100 && b >= 64 && b <= 127)
            && !(a == 198 && (b == 18 || b == 19)) && !(a == 169 && b == 254);
    }

    /**
     * Called off-thread immediately before starting the external tool.
     */
    public static String publicUrl(String value) throws java.io.IOException {
        String safe = SourceLinks.safe(value);
        if (safe.isEmpty()) throw new java.io.IOException("Invalid caption URL");
        URI uri = URI.create(safe);
        if (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)
            throw new java.io.IOException("Unsupported caption port");
        for (InetAddress address : InetAddress.getAllByName(uri.getHost()))
            if (!publicAddress(address)) throw new java.io.IOException("Non-public caption address");
        return safe;
    }
}
