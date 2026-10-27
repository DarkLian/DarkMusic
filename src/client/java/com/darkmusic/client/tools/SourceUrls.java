package com.darkmusic.client.tools;

import com.darkmusic.client.text.Text;

import java.net.URI;
import java.util.Locale;

/**
 * Validates pasted media URLs and names the site they belong to.
 */
public final class SourceUrls {
    private SourceUrls() {
    }

    public static String url(String value) {
        if (value != null) value = value.strip();
        if (value == null || value.length() > 4096 || value.chars().anyMatch(c -> c <= 32 || c == 127))
            throw new IllegalArgumentException(Text.get("text.darkmusic.paste_a_single_http_or_https_video_collection_url"));
        try {
            URI uri = URI.create(value.strip());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
            return uri.toASCIIString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(Text.get("text.darkmusic.enter_a_valid_http_or_https_url_without_login_credentials"));
        }
    }

    public static String source(String value) {
        String host = URI.create(url(value)).getHost().toLowerCase(Locale.ROOT);
        if (host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com")) return "YouTube";
        if (host.equals("b23.tv") || host.equals("bilibili.com") || host.endsWith(".bilibili.com")) return "Bilibili";
        return "yt-dlp: " + host;
    }
}
