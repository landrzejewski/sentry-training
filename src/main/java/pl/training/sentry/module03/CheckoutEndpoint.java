package pl.training.sentry.module03;

import io.sentry.Breadcrumb;
import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;
import io.sentry.protocol.User;

import java.util.Map;

/**
 * Wejście requestu „złóż zamówienie” w checkout-api: dane do triage i raportowanie błędu.
 *
 * <p>Tag {@code business.operation} to kontrolowany wymiar, po którym filtruje się issues i po
 * którym mogą kierować Ownership Rules ({@code tags.business.operation:...}). User ma stabilny
 * identyfikator techniczny, więc licznik users w issue liczy unikalnych klientów z zachowanych
 * eventów. Szczegóły zamówienia są
 * w contexts, a początek przebiegu w breadcrumbs.</p>
 */
public final class CheckoutEndpoint {

    /** Odpowiedź, gdy zamówienia nie udało się złożyć. */
    public static final String ORDER_NOT_PLACED = "ORDER_NOT_PLACED";

    private final CheckoutService checkout;

    public CheckoutEndpoint(CheckoutService checkout) {
        this.checkout = checkout;
    }

    public String handle(Order order) {
        // Granica requestu: user, tagi i breadcrumbs żyją tylko do końca tego requestu (moduł 1).
        try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
            describeRequest(order);
            try {
                return checkout.placeOrder(order);
            } catch (CheckoutException failure) {
                // Jeden właściciel raportowania: błąd trafia do Sentry tutaj, raz na request.
                Sentry.captureException(failure);
                return ORDER_NOT_PLACED;
            }
        }
    }

    private static void describeRequest(Order order) {
        User user = new User();
        user.setId(order.customerId());
        Sentry.setUser(user);

        // Stała nazwa operacji biznesowej, a nie URL z identyfikatorem zamówienia.
        Sentry.setTag("business.operation", "checkout.place_order");

        Sentry.configureScope(scope -> scope.setContexts("checkout", Map.of(
                "order_id", order.id(),
                "payment_method", order.paymentMethod().name(),
                "loyalty_card", order.loyaltyCard() != null
        )));

        Breadcrumb start = new Breadcrumb("Rozpoczęto składanie zamówienia");
        start.setCategory("checkout");
        start.setData("order_id", order.id());
        start.setData("payment_method", order.paymentMethod().name());
        Sentry.addBreadcrumb(start);
    }
}
