package com.vocabtrainer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeysTest {
    @Test
    void aHintShowsOnlyTheLastFourCharactersOfALongKey() {
        assertEquals("••••wxyz", ApiKeys.hint("sk-proj-0123456789abcdefwxyz"));
        assertEquals("••••", ApiKeys.hint("short-key-123"), "a short key shows no characters");
        assertEquals("••••", ApiKeys.hint(null));
    }

    @Test
    void onlyVisibleAsciiCanBeSentInAHeader() {
        assertTrue(ApiKeys.isSendable("sk-proj_0123.ABC~xyz"));
        assertFalse(ApiKeys.isSendable("sk-proj 0123"));
        assertFalse(ApiKeys.isSendable("sk-proj\n0123"));
        assertFalse(ApiKeys.isSendable("sk-ｐｒｏｊ-0123"), "full-width letters pasted from a Chinese input method");
        assertFalse(ApiKeys.isSendable(""));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ApiKeys.requireSendable("sk-secret 0123", "The API key"));
        assertFalse(error.getMessage().contains("sk-secret"), error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.example.com/v1", "http://localhost:11434/v1", "http://LOCALHOST/v1",
        "http://127.0.0.1:1234/v1", "http://[::1]:8080/v1"})
    void httpsOrHttpToThisComputerMayCarryAKey(String url) {
        assertTrue(ApiKeys.isSafeToSendKey(URI.create(url)), url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://api.example.com/v1", "http://192.168.1.20:11434/v1", "http://10.0.0.5/v1",
        "http://localhost.example.com/v1"})
    void plainHttpToAnotherComputerMayNot(String url) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ApiKeys.requireSafeToSendKey(URI.create(url), "The AI base URL"));
        assertEquals("The AI base URL uses plain http, which would send the API key unencrypted. Use https, or http"
            + " only for a server on this computer (localhost, 127.0.0.1 or ::1).", error.getMessage());
    }
}
