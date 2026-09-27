package pl.training.sentry.module09;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.List;

/**
 * Nocny import stanów magazynowych: krótki proces, który startuje, przetwarza listę SKU
 * i kończy {@code main}.
 *
 * <p>Pokazuje, dlaczego krótki proces bez shutdown nie zostawia żadnego trace. W usłudze
 * działającej tygodniami kolejka {@code BatchSpanProcessor} opróżnia się co kilka sekund i brak
 * shutdown kosztuje ostatnie sekundy danych. W zadaniu, które trwa krócej niż opóźnienie paczki
 * (domyślnie 5 s), brak shutdown kosztuje wszystko.</p>
 */
public final class StockImportJob {

    private final ServiceTelemetry telemetry;
    private final Tracer tracer;
    private final StockReservation reservation;

    public StockImportJob(ServiceTelemetry telemetry) {
        this.telemetry = telemetry;
        this.tracer = telemetry.tracer(StockReservation.INSTRUMENTATION_SCOPE);
        this.reservation = new StockReservation(tracer);
    }

    /**
     * @param flushAfterEachSku PUŁAPKA „flush na wszelki wypadek”: {@code forceFlush()} po każdej
     *                          pozycji rozbija eksport na drobne paczki, a spana joba i tak nie
     *                          wyśle, bo w chwili flush jeszcze trwa
     */
    public void run(List<String> skus, boolean flushAfterEachSku) {
        Span job = tracer.spanBuilder("stock.import")
                .setNoParent()
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("stock.import.items", skus.size())
                .startSpan();
        try (Scope ignored = job.makeCurrent()) {
            for (String sku : skus) {
                reservation.reserve(sku, 1);
                if (flushAfterEachSku) {
                    telemetry.forceFlush();
                }
            }
        } finally {
            job.end();
        }
    }
}
