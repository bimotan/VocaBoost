package com.vocabtrainer.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The chat completions URL for the AI base URL the user entered. Providers document either a base
 * URL ({@code https://api.deepseek.com}, {@code https://api.openai.com/v1}) or the full endpoint
 * ({@code .../v1/chat/completions}); both are accepted:
 * <ul>
 *   <li>a URL without a path gets {@code /chat/completions};</li>
 *   <li>a path whose last segment is an API version ({@code /v1}, {@code /openai/v1},
 *       {@code /api/paas/v4}, {@code /v1beta}) or {@code openai} (Gemini's
 *       {@code /v1beta/openai/}) gets {@code /chat/completions} appended;</li>
 *   <li>any other path is the endpoint itself and is used as given.</li>
 * </ul>
 */
final class AiEndpoint {
    static final String CHAT_COMPLETIONS = "chat/completions";
    /** The last path segment of a base URL: an API version, or "openai" for an OpenAI-compatible API beside another. */
    private static final Pattern BASE_SEGMENT = Pattern.compile("v\\d+(?:(?:alpha|beta)\\d*)?|openai",
        Pattern.CASE_INSENSITIVE);

    private AiEndpoint() {
    }

    /**
     * The endpoint requests are sent to.
     *
     * @throws IllegalArgumentException with a message for the user if {@code baseUrl} is not an
     *                                  http(s) URL with a host
     */
    static URI chatCompletions(String baseUrl) {
        String clean = baseUrl == null ? "" : baseUrl.strip();
        URI uri;
        try {
            uri = new URI(clean);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("The AI base URL is not a valid URL: " + clean);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            throw new IllegalArgumentException("The AI base URL must start with https:// and name a server,"
                + " for example https://api.openai.com/v1.");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String lastSegment = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        if (!trimmed.isEmpty() && !BASE_SEGMENT.matcher(lastSegment).matches()) {
            return uri;
        }
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + trimmed + "/" + CHAT_COMPLETIONS + query);
    }
}
