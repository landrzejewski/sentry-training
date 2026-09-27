package pl.training.sentry.support;

import io.sentry.Sentry;
import io.sentry.SentryEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("sentry-global-state")
class TrainingSentryTest {

    @Test
    void offlineModePrintsWhatWouldBeSentAndKeepsReleaseAndEnvironment() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);

        try (TrainingSentry.TrainingSession session =
                     TrainingSentry.init("module00", options -> {}, Map.of(), out)) {
            assertFalse(session.online());
            Sentry.captureException(new IllegalStateException("Brak konfiguracji przewoźnika"), scope ->
                    scope.setTag("carrier", "inpost"));
        }

        String printed = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("tryb=offline"), printed);
        assertTrue(printed.contains("java.lang.IllegalStateException: Brak konfiguracji przewoźnika"), printed);
        assertTrue(printed.contains("carrier=inpost"), printed);
        assertTrue(printed.contains("training.module=module00"), printed);
        assertTrue(printed.contains("release     " + TrainingSentry.DEFAULT_RELEASE), printed);
        assertTrue(printed.contains("environment " + TrainingSentry.DEFAULT_ENVIRONMENT), printed);
    }

    @Test
    void recordingTransportExposesDecodedEvents() {
        try (SentryTestSupport.CapturedTelemetry telemetry = SentryTestSupport.start(options -> {})) {
            Sentry.captureMessage("Wolne API przewoźnika");

            SentryEvent event = telemetry.singleEvent();
            assertEquals("Wolne API przewoźnika", event.getMessage().getFormatted());
            assertEquals(SentryTestSupport.TEST_RELEASE, event.getRelease());
            assertEquals(SentryTestSupport.TEST_ENVIRONMENT, event.getEnvironment());
        }
    }
}
