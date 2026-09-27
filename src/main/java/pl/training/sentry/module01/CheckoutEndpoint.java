package pl.training.sentry.module01;

import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;

/**
 * Pokazuje granicę requestu „wygeneruj etykietę” w checkout-api: osobny isolation scope dla
 * każdego requestu i ręczne {@code captureException} dla błędu, który aplikacja obsługuje sama.
 */
public final class CheckoutEndpoint {

    /** Odpowiedź, gdy etykiety nie udało się wygenerować: zamówienie trafia do ręcznej obsługi. */
    public static final String MANUAL_HANDLING = "MANUAL_HANDLING";

    private final ShippingLabelService labels = new ShippingLabelService();

    /**
     * Pełna obsługa jednego requestu: własny isolation scope, opis requestu, generowanie etykiety.
     *
     * @param checkoutVariant wariant checkoutu z testu A/B, w którym klient złożył zamówienie
     */
    public String handle(Order order, String checkoutVariant) {
        // Granica requestu. Statyczne Sentry.setTag, setUser i addBreadcrumb piszą w SDK 8.x
        // do isolation scope, więc każdy request potrzebuje własnego. W aplikacji webowej
        // zapewnia go integracja frameworka, w czystej Javie kod aplikacji. Zamknięcie tokenu
        // przywraca stan wątku sprzed requestu (scenariusz 6 pokazuje, co dzieje się bez tego).
        try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
            CheckoutTelemetry.describeRequest(order, checkoutVariant);
            return generateLabel(order);
        }
    }

    /**
     * Generuje etykietę. Błąd obsługuje: klient dostaje odpowiedź, a zespół dostaje event.
     */
    public String generateLabel(Order order) {
        try {
            return labels.createLabel(order);
        } catch (RuntimeException exception) {
            // Wyjątek nie opuści tej metody, więc żaden automatyczny mechanizm go nie zobaczy.
            // Ręczny captureException jest tu jedynym sygnałem dla zespołu.
            // PRODUKCJA: bez niego klient widzi komunikat „etykieta będzie później”, logi
            // milczą, a kolejka ręcznej obsługi rośnie bez niczyjej wiedzy.
            Sentry.captureException(exception);
            return MANUAL_HANDLING;
        }
    }
}
