package pl.training.sentry.module02.spring;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pl.training.sentry.module02.CheckoutService;
import pl.training.sentry.module02.PaymentGateway;

import java.io.IOException;
import java.util.Map;

/**
 * {@code POST /api/checkout}: formularz płatności z frontendu checkout-web.
 *
 * <p>Kontroler nie zna Sentry. Wyjątek z bramki opuszcza metodę, a integracja Spring
 * ({@code SentryExceptionResolver}) raportuje go jako błąd nieobsłużony, razem z danymi requestu,
 * które dołączył filtr {@code SentrySpringFilter}. O tym, które z tych danych trafią do eventu,
 * decydują właściwości {@code sentry.send-default-pii}, {@code sentry.max-request-body-size}
 * i bean {@code BeforeSendCallback} (scenariusz 8).</p>
 */
@RestController
public class CheckoutController {

    /**
     * Treść formularza. E-mail i numer karty są potrzebne do płatności, ale nie do diagnozy błędu.
     *
     * @param amount kwota w groszach
     */
    public record CheckoutForm(String orderId, long amount, String email, String cardNumber) {
    }

    private final CheckoutService checkout;

    CheckoutController(PaymentGateway gateway) {
        this.checkout = new CheckoutService(gateway);
    }

    @PostMapping("/api/checkout")
    public Map<String, String> checkout(@RequestBody CheckoutForm form) throws IOException {
        String authorization = checkout.authorize(form.orderId(), form.amount());
        return Map.of("status", "PAID", "authorization", authorization);
    }
}
