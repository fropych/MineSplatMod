package io.github.yromko.minesplat.inference;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ModelInstallFeedbackTest {
    @Test
    void explainsRequiredAndAvailableSpaceWithRecoveryAction() {
        Path folder = Path.of("models").toAbsolutePath();
        String message = ModelInstallFeedback.describe(new CompletionException(
                ModelInstallFeedback.insufficientSpace(folder, 6_000_000_000L, 250_000_000L)));
        assertTrue(message.contains(folder.getRoot().toString()));
        assertTrue(message.contains("Need 6.00 GB, available 250.0 MB"));
        assertTrue(message.contains("another drive in Settings"));
        assertTrue(message.endsWith("Details: logs/latest.log."));
    }

    @Test
    void distinguishesServerFailures() {
        assertTrue(ModelInstallFeedback.describe(ModelInstallFeedback.httpFailure(403, "core.bin"))
                .contains("Access was denied"));
        assertTrue(ModelInstallFeedback.describe(ModelInstallFeedback.httpFailure(404, "core.bin"))
                .contains("not found"));
        assertTrue(ModelInstallFeedback.describe(ModelInstallFeedback.httpFailure(429, "core.bin"))
                .contains("Wait a few minutes"));
        assertTrue(ModelInstallFeedback.describe(ModelInstallFeedback.httpFailure(503, "core.bin"))
                .contains("Retry later"));
    }

    @Test
    void recognizesConnectionAndFilesystemCauses() {
        assertTrue(ModelInstallFeedback.describe(new IOException("wrapper", new UnknownHostException("host")))
                .contains("DNS"));
        assertTrue(ModelInstallFeedback.describe(new HttpConnectTimeoutException("timeout"))
                .contains("timed out"));
        assertTrue(ModelInstallFeedback.describe(new ConnectException())
                .contains("firewall"));
        assertTrue(ModelInstallFeedback.describe(new AccessDeniedException("models"))
                .contains("permissions"));
    }

    @Test
    void recognizesSslEvenWhenItsDeepestCauseIsAnImplementationDetail() {
        SSLHandshakeException handshake = new SSLHandshakeException("handshake");
        handshake.initCause(new IOException("certificate path failed"));
        String message = ModelInstallFeedback.describe(new IOException("wrapper", handshake));
        assertTrue(message.contains("secure connection"));
        assertTrue(message.contains("system date"));
        assertFalse(message.contains("disable"));
    }

    @Test
    void preservesUnknownFailureTypeAndBoundsTechnicalOutput() {
        assertNull(ModelInstallFeedback.describe(null));
        assertTrue(ModelInstallFeedback.describe(new IOException()).startsWith("IOException"));
        String message = ModelInstallFeedback.describe(new IOException("line\n".repeat(1000)));
        assertTrue(message.length() <= 350);
        assertFalse(message.contains("line\n"));
    }

    @Test
    void toleratesCyclesInThirdPartyExceptions() {
        IOException first = new IOException("first");
        IOException second = new IOException("second", first);
        first.initCause(second);
        assertTrue(ModelInstallFeedback.describe(first).contains("second"));
    }

    @Test
    void formatsSizesIndependentlyOfSystemLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("6.00 GB", ModelInstallFeedback.formatBytes(6_000_000_000L));
            assertEquals("250.0 MB", ModelInstallFeedback.formatBytes(250_000_000L));
            assertEquals("0 B", ModelInstallFeedback.formatBytes(0));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
