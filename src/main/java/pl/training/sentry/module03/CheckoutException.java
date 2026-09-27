package pl.training.sentry.module03;

/**
 * Błąd na granicy warstwy checkout: zamówienia nie udało się złożyć.
 *
 * <p>Typowy wyjątek zewnętrzny: opisuje granicę warstwy, a przydatna informacja o przyczynie jest
 * w {@code cause}. Bez {@code cause} event zawiera tylko ten wyjątek (scenariusz 1).</p>
 */
public class CheckoutException extends RuntimeException {

    public CheckoutException(String message) {
        super(message);
    }

    public CheckoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
