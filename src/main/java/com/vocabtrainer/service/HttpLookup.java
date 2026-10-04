package com.vocabtrainer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.util.ErrorMessages;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.vocabtrainer.util.Messages.tr;

/**
 * Sends the online dictionaries' requests and sorts out how each one ended: the body of a 2xx
 * answer, or a lookup result that says why there is none (not found, refused, rate limited, timed
 * out, unreachable, interrupted).
 */
final class HttpLookup {
    /** How long to wait for a connection to a dictionary. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** How long a dictionary may take to answer once connected. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    /** Wikimedia asks clients to say who they are and how to reach the maintainers. */
    static final String USER_AGENT = "VocaBoost/1.0 (https://github.com/bimotan/VocaBoost)";

    private static final Logger LOGGER = Logger.getLogger(HttpLookup.class.getName());

    private HttpLookup() {
    }

    /**
     * A client for the online dictionaries. One client is shared by all of them, so its connection
     * pool and worker threads are created once.
     */
    static HttpClient newClient() {
        return newClient(HttpClient.Redirect.NORMAL);
    }

    /**
     * A client for requests that carry an API key, which never follows a redirect: the JDK drops
     * {@code Authorization} when a redirect leads to another server, but passes other headers such
     * as {@code X-API-Key} on, also to plain http. A redirect then ends the lookup with a message.
     */
    static HttpClient newKeyClient() {
        return newClient(HttpClient.Redirect.NEVER);
    }

    private static HttpClient newClient(HttpClient.Redirect redirect) {
        return HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(redirect)
            .build();
    }

    /** The body of a 2xx answer, or the lookup result that explains why there is none. */
    record Reply(String body, DictionaryLookupResult failure) {
        boolean ok() {
            return failure == null;
        }
    }

    /**
     * Sends {@code request} to {@code dictionary} (its name in messages). HTTP 404 means the
     * dictionary does not have the word and gives {@code notFoundMessage}.
     */
    static Reply get(HttpClient client, HttpRequest request, String dictionary, String notFoundMessage) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            LOGGER.log(Level.WARNING, dictionary + " did not answer " + request.uri() + " in time", e);
            return failed(LookupOutcome.TIMEOUT,
                tr("dictionary.http.timeout", dictionary, seconds(request.timeout().orElse(REQUEST_TIMEOUT))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(null, DictionaryLookupResult.interrupted());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot reach " + dictionary + " at " + request.uri(), e);
            return failed(LookupOutcome.NETWORK_ERROR, tr("dictionary.http.network", dictionary, ErrorMessages.rootMessage(e)));
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return new Reply(response.body(), null);
        }
        if (status == 404) {
            return new Reply(null, DictionaryLookupResult.notFound(notFoundMessage));
        }
        LOGGER.warning(dictionary + " answered HTTP " + status + " for " + request.uri());
        if (status == 401 || status == 403) {
            return failed(LookupOutcome.AUTH_ERROR, tr("dictionary.http.refused", dictionary, String.valueOf(status)));
        }
        if (status >= 300 && status < 400) {
            return failed(LookupOutcome.SERVICE_ERROR, tr("dictionary.http.redirect", dictionary, String.valueOf(status)));
        }
        if (status == 429) {
            return failed(LookupOutcome.RATE_LIMITED, retryAfter(response)
                .map(seconds -> tr("dictionary.http.rateLimitedFor", dictionary, String.valueOf(seconds)))
                .orElse(tr("dictionary.http.rateLimited", dictionary)));
        }
        return failed(LookupOutcome.SERVICE_ERROR, tr("dictionary.http.serviceError", dictionary, String.valueOf(status)));
    }

    /** {@code word} as one URL path segment: every reserved character and space percent-encoded. */
    static String pathSegment(String word) {
        return URLEncoder.encode(word, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** A field's text with its whitespace collapsed; an array of strings is joined with "; ". */
    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.isContainerNode() && !value.isArray()) {
            return "";
        }
        if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode element : value) {
                if (element.isValueNode() && !element.asText("").isBlank()) {
                    parts.add(collapse(element.asText("")));
                }
            }
            return String.join("; ", parts);
        }
        return collapse(value.asText(""));
    }

    /** The first of {@code fields} that has text. */
    static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    /** "5" for five seconds, "0.3" for 300 milliseconds. */
    static String seconds(Duration duration) {
        return BigDecimal.valueOf(duration.toMillis()).movePointLeft(3).stripTrailingZeros().toPlainString();
    }

    private static String collapse(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static Optional<Long> retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
            .map(String::trim)
            .filter(value -> value.matches("\\d{1,6}"))
            .map(Long::parseLong);
    }

    private static Reply failed(LookupOutcome outcome, String message) {
        return new Reply(null, DictionaryLookupResult.unavailable(outcome, message));
    }
}
