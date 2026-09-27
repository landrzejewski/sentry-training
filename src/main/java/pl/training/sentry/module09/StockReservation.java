package pl.training.sentry.module09;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pokazuje cykl życia spana w kodzie domenowym: rezerwacja towaru w inventory-service z ręczną
 * instrumentacją OTel API.
 *
 * <p>{@link #reserve} to wersja docelowa: start, bieżący kontekst przez {@code Scope}, atrybuty,
 * status i dokładnie jedno {@code end()}. {@link #reserveCarelessly} zbiera dwa typowe błędy
 * z przeglądów kodu, żeby scenariusz 1 mógł pokazać obie wersje obok siebie.</p>
 *
 * <p>Stan magazynu: {@code SKU-1} jest dostępny, {@code SKU-EMPTY} wyprzedany (odmowa
 * biznesowa), a {@code SKU-LEGACY} leży w magazynie, którego system nie odpowiada (awaria).</p>
 */
public final class StockReservation {

    public static final String INSTRUMENTATION_SCOPE = "pl.training.inventory";

    /** Odmowa biznesowa: towaru brak. To poprawny wynik operacji, a nie awaria. */
    public static final class OutOfStockException extends RuntimeException {
        public OutOfStockException(String sku) {
            super("Brak towaru " + sku);
        }
    }

    private final Tracer tracer;
    private final Map<String, Integer> stock = new ConcurrentHashMap<>(Map.of("SKU-1", 1_000, "SKU-EMPTY", 0));

    public StockReservation(Tracer tracer) {
        this.tracer = tracer;
    }

    public void reserve(String sku, int quantity) {
        // Nazwa opisuje klasę operacji. SKU trafia do atrybutu: w nazwie spana tworzyłby
        // tyle nazw, ile produktów, i grupowanie po nazwie przestałoby działać.
        Span span = tracer.spanBuilder("inventory.reserve")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("inventory.sku", sku)
                .setAttribute("inventory.quantity", quantity)
                .startSpan();
        // startSpan() nie ustawia spana jako bieżącego. Dopiero makeCurrent() sprawia, że span
        // decrementStock() dostaje go jako rodzica. Zamknięcie Scope przywraca poprzedni kontekst,
        // ale spana nie kończy; robi to finally.
        try (Scope ignored = span.makeCurrent()) {
            decrementStock(sku, quantity);
        } catch (OutOfStockException exception) {
            // Odmowa biznesowa: zdarzenie wyjątku zostaje jako informacja, status zostaje UNSET.
            span.recordException(exception);
            throw exception;
        } catch (RuntimeException exception) {
            // recordException() dodaje tylko zdarzenie wyjątku. O porażce operacji mówi status
            // ERROR, ustawiany osobno; error.type pozwala grupować porażki bez parsowania zdarzeń.
            span.recordException(exception);
            span.setAttribute("error.type", exception.getClass().getName());
            span.setStatus(StatusCode.ERROR, exception.getMessage());
            throw exception;
        } finally {
            span.end();
        }
    }

    public void reserveCarelessly(String sku, int quantity) {
        Span span = tracer.spanBuilder("inventory.reserve")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("inventory.sku", sku)
                .setAttribute("inventory.quantity", quantity)
                .startSpan();
        try {
            // PUŁAPKA: brak makeCurrent(). Span istnieje, ale nie jest bieżący, więc span
            // decrementStock() bierze rodzica z Context.current(): bez rodzica zaczyna nowy trace,
            // a w requeście podczepia się pod span wyżej, z pominięciem inventory.reserve.
            decrementStock(sku, quantity);
        } catch (RuntimeException exception) {
            // PUŁAPKA: sam recordException(). Span ma zdarzenie wyjątku, ale status UNSET, więc
            // dla reguł opartych na statusie (tail sampling, alerty na odsetek błędów) operacja
            // się udała. Ingestia OTLP w Sentry odrzuca zdarzenia spanów, więc w Sentry UI po
            // błędzie nie zostaje żaden ślad poza zwykłym spanem.
            span.recordException(exception);
            throw exception;
        } finally {
            span.end();
        }
    }

    private void decrementStock(String sku, int quantity) {
        // Rodzic nie jest podany jawnie: SDK bierze bieżący kontekst wątku. Wyjątek opisuje
        // span nadrzędny inventory.reserve, który decyduje o wyniku całej operacji.
        Span span = tracer.spanBuilder("inventory.stock.decrement")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            if (sku.equals("SKU-LEGACY")) {
                throw new IllegalStateException("System magazynu WAW-2 nie odpowiada");
            }
            int available = stock.getOrDefault(sku, 0);
            if (available < quantity) {
                throw new OutOfStockException(sku);
            }
            stock.put(sku, available - quantity);
        } finally {
            span.end();
        }
    }
}
