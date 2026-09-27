package pl.training.sentry.module10;

import java.util.Locale;

/**
 * Pokazuje wymiar metryk o zamkniętym słowniku: terminalny wynik próby checkoutu, czyli wartości
 * atrybutu {@code checkout.outcome}.
 *
 * <p>Cztery wartości znane przed wdrożeniem. Widget grupujący po {@code checkout.outcome} ma więc
 * zawsze najwyżej cztery serie, a Monitor filtrujący po wartości {@code gateway_timeout} nie
 * przestanie działać po zmianie treści komunikatu błędu.</p>
 */
public enum CheckoutOutcome {

    /** Płatność przyjęta: jedyny sukces domenowy. */
    COMPLETED,
    /** Bramka odrzuciła płatność. Klient dostaje HTTP 200, ale zamówienie nie jest opłacone. */
    DECLINED,
    /** Bramka nie odpowiedziała w limicie czasu. Wynik płatności jest nieznany. */
    GATEWAY_TIMEOUT,
    /** Nieprzewidziany wyjątek w kodzie. */
    INTERNAL_ERROR;

    /** Wartość atrybutu: stała, mała litera, bez spacji. Zmiana tej wartości psuje zapisane widgety. */
    public String attributeValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
