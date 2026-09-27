package pl.training.sentry.module05;

import io.sentry.ISpan;
import io.sentry.Sentry;
import io.sentry.SentryAttribute;
import io.sentry.SentryAttributes;
import io.sentry.SentryLogLevel;
import io.sentry.SentryWrapper;
import io.sentry.logger.SentryLogParameters;
import io.sentry.protocol.User;
import pl.training.sentry.module05.OrderChecks.Cart;
import pl.training.sentry.module05.OrderChecks.Stock;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Checkout w orders-api: kroki zamówienia z ręcznymi spanami, wywołanie payments-api
 * i podsumowanie w jednym Structured Log.
 *
 * <p>Tryb {@link ChecksMode} odpowiada trzem wersjom tego samego kodu, które zespół mógłby
 * wdrożyć po kolei: utrata kontekstu Sentry na puli wątków (scenariusz 3) i kształt waterfall
 * przy sprawdzeniach sekwencyjnych i równoległych (scenariusz 4).</p>
 */
public final class CheckoutService {

    /** Jak wykonywane są dwa niezależne sprawdzenia: ocena ryzyka i rezerwacja towaru. */
    public enum ChecksMode {
        /** Jedno po drugim na wątku requestu. */
        SEQUENTIAL,
        /** PUŁAPKA: równolegle na puli wątków, bez przeniesienia kontekstu Sentry. */
        PARALLEL_WITHOUT_CONTEXT,
        /** Równolegle, z kontekstem przeniesionym przez SentryWrapper i jawnym rodzicem spanu. */
        PARALLEL_WITH_CONTEXT
    }

    /** Wyjątek granicy orders-api: payments-api nie autoryzowało płatności. */
    public static final class PaymentFailedException extends RuntimeException {
        public PaymentFailedException(String message) {
            super(message);
        }
    }

    private final OrderChecks checks = new OrderChecks();
    private final TracingHttpClient payments = new TracingHttpClient();
    private final URI authorizeUri;
    private final CheckoutFlags flags;
    private final ChecksMode mode;
    private final ExecutorService workers;

    /**
     * @param workers pula wątków orders-api dla równoległych sprawdzeń (w produkcji wspólna dla
     *                wielu requestów, dlatego kontekst trzeba przenosić jawnie)
     */
    public CheckoutService(URI paymentsBaseUri, CheckoutFlags flags, ChecksMode mode, ExecutorService workers) {
        this.authorizeUri = paymentsBaseUri.resolve("/authorize");
        this.flags = flags;
        this.mode = mode;
        this.workers = workers;
    }

    public TracedHttpHandler.Response checkout(String orderId, String customerId) throws Exception {
        User user = new User();
        user.setId(customerId);
        Sentry.setUser(user);

        // Flaga sprawdzana na początku requestu, gdy aktywna jest sama transakcja: wynik trafia
        // wtedy do danych transakcji, a nie do przypadkowego child spanu.
        boolean newPaymentFlow = flags.isEnabled(CheckoutFlags.NEW_PAYMENT_FLOW, customerId);

        String outcome = "payment_failed";
        Cart cart = null;
        try {
            cart = ChildSpans.trace("db.query", "load cart", span -> checks.loadCart(orderId));
            // Celowo bez spanu: lokalne przeliczenie cen widać w waterfall tylko jako przerwę
            // między db.query a kolejnym spanem, czyli jako self time transakcji.
            cart = checks.recalculatePrices(cart);

            runChecks(orderId, customerId);

            String instruction = newPaymentFlow
                    ? PaymentInstructions.newFlow(cart)
                    : PaymentInstructions.currentFlow(cart);
            TracingHttpClient.Result payment = payments.post(authorizeUri, instruction);
            if (!payment.successful()) {
                // Błąd kontraktu z payments-api to błąd orders-api: tu powstaje issue. Status
                // client spanu (invalid_argument dla 422) ustawił już klient HTTP.
                throw new PaymentFailedException("payments-api odrzuciło autoryzację: HTTP "
                        + payment.status() + " " + payment.body());
            }
            outcome = "confirmed";
            return new TracedHttpHandler.Response(200, "ORDER_CONFIRMED " + payment.body());
        } finally {
            logSummary(cart, outcome, newPaymentFlow);
        }
    }

    private void runChecks(String orderId, String customerId) {
        switch (mode) {
            case SEQUENTIAL -> {
                ChildSpans.trace("function", "fraud check", span -> checks.isRisky(customerId));
                Stock stock = ChildSpans.trace("http.client", "GET warehouse /stock", span -> checks.reserveStock(orderId));
                logStockFallback(stock);
            }
            case PARALLEL_WITHOUT_CONTEXT -> {
                // PUŁAPKA: zadanie wykonuje się na wątku puli, który ma własne scopes. Nie ma tam
                // transakcji requestu, więc Sentry.getSpan() zwraca null i spany nie powstaną,
                // log dostanie trace ID z innego kontekstu, a event z tego wątku nie miałby
                // usera, tagów ani flag requestu.
                CompletableFuture<Boolean> risky = CompletableFuture.supplyAsync(
                        () -> ChildSpans.trace("function", "fraud check", span -> checks.isRisky(customerId)),
                        workers);
                CompletableFuture<Stock> stock = CompletableFuture.supplyAsync(() -> {
                    Stock reserved = ChildSpans.trace("http.client", "GET warehouse /stock", span -> checks.reserveStock(orderId));
                    logStockFallback(reserved);
                    return reserved;
                }, workers);
                CompletableFuture.allOf(risky, stock).join();
            }
            case PARALLEL_WITH_CONTEXT -> {
                // Rodzic pobrany na wątku requestu. Przekazany jawnie, bo w wątku roboczym
                // Sentry.getSpan() zwróciłoby ostatni niezakończony span transakcji, którym może
                // być span równoległej gałęzi.
                ISpan parent = Sentry.getSpan();
                CompletableFuture<Boolean> risky = CompletableFuture.supplyAsync(
                        withRequestContext(() -> ChildSpans.trace(parent, "function", "fraud check",
                                span -> checks.isRisky(customerId))),
                        workers);
                CompletableFuture<Stock> stock = CompletableFuture.supplyAsync(withRequestContext(() -> {
                    Stock reserved = ChildSpans.trace(parent, "http.client", "GET warehouse /stock",
                            span -> checks.reserveStock(orderId));
                    logStockFallback(reserved);
                    return reserved;
                }), workers);
                CompletableFuture.allOf(risky, stock).join();
            }
        }
    }

    /**
     * Opakowanie trzeba wykonać na wątku requestu, przed przekazaniem zadania do puli:
     * {@code wrapSupplier} rozwidla scopes w chwili wywołania i aktywuje je w wątku roboczym na
     * czas zadania. Wywołane dopiero w wątku roboczym rozwidliłoby niewłaściwy kontekst.
     */
    private static <T> Supplier<T> withRequestContext(Supplier<T> task) {
        return SentryWrapper.wrapSupplier(task);
    }

    private void logStockFallback(Stock stock) {
        if (stock.fromCache()) {
            // Log wymagający korelacji powstaje w kontekście requestu: wtedy prowadzi do trace.
            // PUŁAPKA: przy równoległych gałęziach span_id logu to ostatni niezakończony span
            // transakcji, więc może wskazywać sąsiednią gałąź. Trace ID jest pewny, span ID nie.
            Sentry.logger().warn("Magazyn nie odpowiedział na czas, rezerwacja na podstawie stanu z cache");
        }
    }

    /** Wynik checkoutu jako jeden wpis z typowanymi atrybutami (wide event). */
    private void logSummary(Cart cart, String outcome, boolean newPaymentFlow) {
        SentryAttributes attributes = SentryAttributes.of(
                SentryAttribute.stringAttribute("checkout.outcome", outcome),
                SentryAttribute.stringAttribute("checks.mode", mode.name().toLowerCase()),
                SentryAttribute.booleanAttribute("checkout.new_payment_flow", newPaymentFlow),
                SentryAttribute.integerAttribute("cart.item_count", cart == null ? 0 : cart.itemCount())
        );
        Sentry.logger().log(
                "confirmed".equals(outcome) ? SentryLogLevel.INFO : SentryLogLevel.WARN,
                SentryLogParameters.create(attributes),
                "Checkout zakończony: %s",
                outcome
        );
    }
}
