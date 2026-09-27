package pl.training.sentry.module09;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Właściciel OpenTelemetry SDK jednej usługi: {@code Resource}, sampler, procesory, propagatory
 * i kontrolowany shutdown.
 *
 * <p>Demo uruchamia dwie usługi w jednym procesie, więc każda ma własną instancję SDK: własny
 * {@code service.name}, sampler i kolejkę eksportu, tak jak dwa osobne procesy w produkcji.</p>
 *
 * <p>SDK nie jest rejestrowane w {@code GlobalOpenTelemetry}. Kod usług dostaje {@link Tracer}
 * i propagator jawnie, więc w jednym procesie mogą żyć dwie usługi, a testy nie dzielą stanu.</p>
 *
 * <p>PRODUKCJA: gdy SDK instaluje Java Agent albo Starter, aplikacja nie buduje drugiego
 * providera. Ta klasa odpowiada ręcznie zbudowanemu SDK, którego właścicielem jest aplikacja.</p>
 */
public final class ServiceTelemetry implements AutoCloseable {

    private final String serviceName;
    private final OpenTelemetrySdk sdk;

    private ServiceTelemetry(String serviceName, OpenTelemetrySdk sdk) {
        this.serviceName = serviceName;
        this.sdk = sdk;
    }

    /**
     * Buduje SDK usługi.
     *
     * @param processors każdy dostaje każdy span rejestrowany przez sampler; {@code SimpleSpanProcessor}
     *                   i {@code BatchSpanProcessor} domyślnie eksportują z nich tylko próbkowane
     */
    public static ServiceTelemetry create(
            String serviceName,
            String serviceVersion,
            String environment,
            Sampler sampler,
            List<SpanProcessor> processors
    ) {
        // Resource opisuje proces, nie request: identyfikator zamówienia czy użytkownika tu nie
        // pasuje. Scalenie z Resource.getDefault() zachowuje atrybuty telemetry.sdk.*, a przy
        // konflikcie klucza wygrywa wartość aplikacji (service.name zastępuje unknown_service:java).
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.builder()
                .put("service.namespace", "sentry-training")
                .put("service.name", serviceName)
                .put("service.version", serviceVersion)
                .put("deployment.environment.name", environment)
                // Odpowiednik tagu training.module z TrainingSentry. Obserwacja z lokalnego
                // self-hosted Sentry 26.9.0, nie gwarancja dokumentacji: atrybuty Resource są tam
                // zapisane z prefiksem resource., więc filtr w UI to resource.training.module:module09.
                .put("training.module", "module09")
                .build()));

        SdkTracerProviderBuilder tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(sampler);
        processors.forEach(tracerProvider::addSpanProcessor);

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider.build())
                // Propagatory składamy w jednym miejscu. W3C Trace Context i Baggage to standard
                // OTel, a propagator Sentry dodaje sentry-trace dla usług z Sentry SDK (przeglądarka,
                // aplikacja mobilna, usługa Sentry-native). Sam propagator Sentry nie zastępuje
                // traceparent: usługi z czystym OTel go nie rozumieją.
                .setPropagators(ContextPropagators.create(TextMapPropagator.composite(
                        W3CTraceContextPropagator.getInstance(),
                        W3CBaggagePropagator.getInstance(),
                        SentryOtlp.propagator())))
                .build();
        return new ServiceTelemetry(serviceName, sdk);
    }

    public String serviceName() {
        return serviceName;
    }

    /**
     * Tracer z nazwą instrumentation scope, czyli biblioteki, która tworzy spany. Scope nie ma
     * związku z {@code io.opentelemetry.context.Scope}; pozwala odróżnić spany dwóch
     * mechanizmów instrumentacji tej samej granicy (scenariusz 6).
     */
    public Tracer tracer(String instrumentationScope) {
        return sdk.getTracer(instrumentationScope, "1.0.0");
    }

    public TextMapPropagator propagator() {
        return sdk.getPropagators().getTextMapPropagator();
    }

    /**
     * Wysyła spany zakończone i czekające w kolejce procesorów. Spana, który trwa, nie kończy
     * i nie eksportuje (scenariusz 7).
     *
     * <p>PUŁAPKA: flush po każdym requeście niszczy batching. To narzędzie na koniec zadania
     * wsadowego albo przed zamrożeniem procesu (np. funkcja serverless), a nie w ścieżce requestu.</p>
     */
    public CompletableResultCode forceFlush() {
        return sdk.getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS);
    }

    /**
     * Shutdown: procesory wysyłają zaległe spany i zamykają exportery. Ręcznie zbudowane SDK nie
     * rejestruje shutdown hooka, a wątek {@code BatchSpanProcessor} jest daemonem, więc proces
     * zakończony bez tego wywołania gubi wszystko, co czekało w kolejce.
     */
    @Override
    public void close() {
        sdk.shutdown().join(10, TimeUnit.SECONDS);
    }
}
