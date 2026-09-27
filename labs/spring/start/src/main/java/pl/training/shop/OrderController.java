package pl.training.shop;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class OrderController {

    record Order(String id, String customerId, int total) {
    }

    private static final Map<String, Order> ORDERS = Map.of(
            "ORD-1", new Order("ORD-1", "c-7f3a9c", 207),
            "ORD-2", new Order("ORD-2", "c-19bd42", 89));

    private final InvoiceService invoices = new InvoiceService();

    /** Nieznany numer zamówienia kończy się NullPointerException, czyli błędem 500. */
    @GetMapping("/api/orders/{id}")
    public Map<String, Object> order(@PathVariable("id") String id) {
        Order order = ORDERS.get(id);
        return Map.of("id", order.id(), "total", order.total());
    }

    /** Serwis faktur bywa niedostępny: aplikacja łapie wyjątek i odpowiada 503. */
    @GetMapping("/api/orders/{id}/invoice")
    public ResponseEntity<String> invoice(@PathVariable("id") String id) {
        try {
            return ResponseEntity.ok(invoices.render(id));
        } catch (InvoiceService.InvoiceUnavailableException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Faktura będzie dostępna później");
        }
    }
}
