package pl.training.sentry.support;

import io.sentry.Hint;
import io.sentry.Sentry;
import io.sentry.SentryEnvelope;
import io.sentry.SentryEvent;
import io.sentry.SentryLogEvent;
import io.sentry.SentryMetricsEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.SentryTransaction;
import io.sentry.transport.ITransport;
import io.sentry.transport.RateLimiter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Uruchamia prawdziwe Sentry SDK z transportem w pamięci, żeby testy mogły sprawdzać
 * treść wysyłanych eventów.
 *
 * <p>Użycie (SDK ma stan globalny, więc testy oznaczamy
 * {@code @ResourceLock("sentry-global-state")} i zamykamy SDK po każdym teście):</p>
 * <pre>
 * try (CapturedTelemetry telemetry = SentryTestSupport.start(options -> {})) {
 *     Sentry.captureException(new IllegalStateException("x"));
 *     assertEquals(1, telemetry.events().size());
 * }
 * </pre>
 */
public final class SentryTestSupport {

    public static final String TEST_RELEASE = "sentry-training@test";
    public static final String TEST_ENVIRONMENT = "test";

    private SentryTestSupport() {
    }

    public static CapturedTelemetry start(Consumer<SentryOptions> customizer) {
        CapturedTelemetry telemetry = new CapturedTelemetry();
        Sentry.init(options -> {
            options.setDsn(TrainingSentry.OFFLINE_DSN);
            options.setRelease(TEST_RELEASE);
            options.setEnvironment(TEST_ENVIRONMENT);
            // Testy nie powinny zależeć od globalnego handlera wyjątków JVM; scenariusze, które go
            // potrzebują, włączają go jawnie przez customizer.
            options.setEnableUncaughtExceptionHandler(false);
            options.setTransportFactory((sentryOptions, requestDetails) ->
                    telemetry.transport(new EnvelopeDecoder(sentryOptions.getSerializer())));
            customizer.accept(options);
        });
        return telemetry;
    }

    /** Wszystko, co SDK przekazało do transportu od startu testu. */
    public static final class CapturedTelemetry implements AutoCloseable {

        private final List<CapturedItem> items = new CopyOnWriteArrayList<>();

        public List<CapturedItem> items() {
            return List.copyOf(items);
        }

        public List<SentryEvent> events() {
            return items.stream()
                    .filter(CapturedItem.Event.class::isInstance)
                    .map(item -> ((CapturedItem.Event) item).event())
                    .toList();
        }

        public SentryEvent singleEvent() {
            List<SentryEvent> events = events();
            if (events.size() != 1) {
                throw new AssertionError("Oczekiwano dokładnie jednego eventu, a SDK wysłało " + events.size());
            }
            return events.getFirst();
        }

        public List<SentryTransaction> transactions() {
            return items.stream()
                    .filter(CapturedItem.Transaction.class::isInstance)
                    .map(item -> ((CapturedItem.Transaction) item).transaction())
                    .toList();
        }

        public List<SentryLogEvent> logs() {
            return items.stream()
                    .filter(CapturedItem.Logs.class::isInstance)
                    .flatMap(item -> ((CapturedItem.Logs) item).logs().stream())
                    .toList();
        }

        /** Metryki ze wszystkich paczek. Przed odczytem trzeba wywołać {@link #flush()}. */
        public List<SentryMetricsEvent> metrics() {
            return items.stream()
                    .filter(CapturedItem.Metrics.class::isInstance)
                    .flatMap(item -> ((CapturedItem.Metrics) item).metrics().stream())
                    .toList();
        }

        /** Kolejne stany sesji Release Health w kolejności wysłania (start, aktualizacje, koniec). */
        public List<io.sentry.Session> sessionUpdates() {
            return items.stream()
                    .filter(CapturedItem.SessionUpdate.class::isInstance)
                    .map(item -> ((CapturedItem.SessionUpdate) item).session())
                    .toList();
        }

        public List<io.sentry.CheckIn> checkIns() {
            return items.stream()
                    .filter(CapturedItem.CheckIn.class::isInstance)
                    .map(item -> ((CapturedItem.CheckIn) item).checkIn())
                    .toList();
        }

        /** Wysyła dane buforowane przez SDK (np. paczki logów), zanim test zacznie asercje. */
        public void flush() {
            Sentry.flush(2_000);
        }

        @Override
        public void close() {
            Sentry.close();
        }

        private ITransport transport(EnvelopeDecoder decoder) {
            return new ITransport() {
                @Override
                public void send(SentryEnvelope envelope, Hint hint) {
                    items.addAll(decoder.decode(envelope));
                    // Bez potwierdzenia każdy test nieobsłużonego wyjątku czekałby 15 s na flush.
                    ConsoleEnvelopeTransport.confirmFlush(envelope, hint);
                }

                @Override
                public void flush(long timeoutMillis) {
                }

                @Override
                public RateLimiter getRateLimiter() {
                    return null;
                }

                @Override
                public void close(boolean isRestarting) {
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
