package pl.training.sentry.module05.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import pl.training.sentry.module05.ChildSpans;
import pl.training.sentry.module05.spring.OrderStatusRepository.OrderStatus;

/**
 * Status i faktura zamówienia w aplikacji Spring Boot.
 *
 * <p>Transakcji nie tworzy ten kod: robi to {@code SentryTracingFilter} ze startera, z nazwą
 * opartą na szablonie trasy ({@code GET /api/orders/{orderId}/status}), a nie na rzeczywistym
 * identyfikatorze. Kontroler dodaje tylko child span dla odczytu z bazy jako dziecko tej
 * transakcji. Nieobsłużony wyjątek raportuje
 * {@code SentryExceptionResolver}, więc kontroler nie woła {@code captureException}.</p>
 */
@RestController
public class OrderStatusController {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusController.class);

    private final OrderStatusRepository repository = new OrderStatusRepository();
    private final LoyaltyClient loyalty;
    private final InvoiceRenderer invoices;

    public OrderStatusController(LoyaltyClient loyalty, InvoiceRenderer invoices) {
        this.loyalty = loyalty;
        this.invoices = invoices;
    }

    @GetMapping("/api/orders/{orderId}/status")
    public String status(@PathVariable("orderId") String orderId) {
        OrderStatus order = ChildSpans.trace("db.query", "load order status", span -> repository.find(orderId));
        int points = loyalty.pointsFor(order.customerId()).join();
        // Zwykły log SLF4J, bez kodu Sentry. Appender ze startera zamienia go na Structured Log
        // z trace i span ID transakcji requestu, bo powstaje na wątku requestu.
        log.info("Status zamówienia {}: {}", orderId, order.status());
        return order.status() + " (punkty: " + points + ")";
    }

    @GetMapping("/api/orders/{orderId}/invoice")
    public String invoice(@PathVariable("orderId") String orderId) {
        return invoices.render(orderId).join();
    }
}
