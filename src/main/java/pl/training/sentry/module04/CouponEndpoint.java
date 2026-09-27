package pl.training.sentry.module04;

import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;

import java.util.Locale;
import java.util.Map;

/**
 * Wejście requestu „zastosuj kupon” w orders-api i obsługa jego błędu.
 *
 * <p>Endpoint nie ustawia release ani dist. To właściwości procesu: SDK dopisuje je do każdego
 * eventu z opcji ustawionych przy starcie ({@code MainEventProcessor} bierze
 * {@code options.getRelease()} i {@code options.getDist()}, gdy event ich nie ma). Wszystkie
 * eventy jednej uruchomionej kopii artefaktu mają więc identyczny release.</p>
 */
public final class CouponEndpoint {

    /** Odpowiedź, gdy kuponu nie udało się zastosować: zamówienie przechodzi bez rabatu. */
    public static final int NO_DISCOUNT = 0;

    private final CouponService coupons = new CouponService();

    /** Obsługa jednego requestu we własnym isolation scope (moduł 1). */
    public int applyCoupon(String orderId, String couponCode) {
        try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
            Sentry.setTag("coupon.campaign", couponCode.strip().toUpperCase(Locale.ROOT));
            Sentry.configureScope(scope -> scope.setContexts("order", Map.of("order_id", orderId)));
            try {
                return coupons.discountPercent(couponCode);
            } catch (IllegalArgumentException exception) {
                // Klient dostaje zamówienie bez rabatu, a zespół event z wersją, w której to się stało.
                Sentry.captureException(exception);
                return NO_DISCOUNT;
            }
        }
    }
}
