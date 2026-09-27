package pl.training.sentry.module09;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.sentry.SentryOptions;
import io.sentry.opentelemetry.otlp.OpenTelemetryOtlpEventProcessor;
import io.sentry.opentelemetry.otlp.OpenTelemetryOtlpPropagator;

import java.net.URI;
import java.time.Duration;

/**
 * Lekki wariant OTLP: OpenTelemetry tworzy i eksportuje spany, a Sentry SDK raportuje błędy
 * powiązane z bieżącym spanem OTel.
 *
 * <p>Klasa zbiera w jednym miejscu trzy elementy, które muszą do siebie pasować: procesor
 * eventów w {@code SentryOptions} i propagator {@code sentry} w propagatorach OTel (oba
 * z {@code sentry-opentelemetry-otlp}) oraz exporter OTLP na endpoint projektu wyliczony z tego
 * samego DSN.</p>
 */
public final class SentryOtlp {

    private SentryOtlp() {
    }

    /**
     * Rejestruje {@link OpenTelemetryOtlpEventProcessor}. Integracja nie dodaje go sama: bez tego
     * wywołania event błędu dostaje trace ID ze scope Sentry, niezwiązany z trace OTel.
     *
     * <p>Procesor czyta {@code Span.current()} w chwili tworzenia eventu i nadpisuje nim kontekst
     * trace ustawiony wcześniej ze scope Sentry. Gdy na wątku nie ma poprawnego spana OTel, event
     * zostaje z trace ID Sentry (scenariusz 5).</p>
     *
     * <p>Musi dostać tę samą instancję {@code SentryOptions}, która trafia do {@code Sentry.init}.
     * W tej topologii Sentry nie prowadzi własnego tracingu: {@code tracesSampleRate} zostaje
     * nieustawione, żeby nie powstał drugi graf spanów z drugą polityką samplingu.</p>
     */
    public static void configure(SentryOptions options) {
        boolean registered = options.getEventProcessors().stream()
                .anyMatch(OpenTelemetryOtlpEventProcessor.class::isInstance);
        if (!registered) {
            options.addEventProcessor(new OpenTelemetryOtlpEventProcessor());
        }
    }

    /**
     * Propagator {@code sentry}: inject zapisuje {@code sentry-trace} ze spana OTel w kontekście
     * (Baggage Sentry tylko wtedy, gdy przyszło w żądaniu), extract kontynuuje trace
     * z przychodzącego {@code sentry-trace}. Opcje ({@code tracePropagationTargets},
     * {@code strictTraceContinuation}) czyta z bieżącej instancji Sentry SDK.
     */
    public static TextMapPropagator propagator() {
        return new OpenTelemetryOtlpPropagator();
    }

    /**
     * Endpoint OTLP/HTTP traces projektu wyliczony z DSN:
     * {@code http://klucz@localhost:9000/2} daje
     * {@code http://localhost:9000/api/2/integration/otlp/v1/traces}.
     *
     * <p>SKRÓT SZKOLENIOWY. PRODUKCJA: endpoint i nagłówek kopiuje się z ustawień projektu
     * (Client Keys (DSN)), a host musi odpowiadać hostowi z DSN, także regionalnemu. Wyliczenie
     * z DSN daje adres w formacie z dokumentacji Sentry i adres przyjmowany przez lokalne
     * self-hosted, ale nie zastępuje wartości skopiowanej z Client Keys.</p>
     */
    public static String tracesEndpoint(String dsn) {
        URI uri = URI.create(dsn);
        String path = uri.getPath();
        int lastSlash = path.lastIndexOf('/');
        String pathPrefix = path.substring(0, lastSlash);
        String projectId = path.substring(lastSlash + 1);
        String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + port + pathPrefix
                + "/api/" + projectId + "/integration/otlp/v1/traces";
    }

    /** Nagłówek uwierzytelnienia OTLP. {@code sentry_key} to publiczny klucz DSN, nie token API. */
    public static String authHeader(String dsn) {
        String userInfo = URI.create(dsn).getUserInfo();
        String publicKey = userInfo.contains(":") ? userInfo.substring(0, userInfo.indexOf(':')) : userInfo;
        return "sentry sentry_key=" + publicKey;
    }

    /**
     * Exporter OTLP/HTTP z protobuf do projektu Sentry. Wynik jego pracy widać dopiero po
     * eksporcie, więc w demo stoi za {@code BatchSpanProcessor} i wymaga shutdown na końcu.
     */
    public static SpanExporter spanExporter(String dsn) {
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(tracesEndpoint(dsn))
                .addHeader("x-sentry-auth", authHeader(dsn))
                .setTimeout(Duration.ofSeconds(10))
                .build();
    }
}
