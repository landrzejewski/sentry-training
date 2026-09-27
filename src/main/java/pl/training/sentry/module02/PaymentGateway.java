package pl.training.sentry.module02;

import java.io.IOException;
import java.net.SocketException;

/**
 * Klient zewnętrznej bramki płatności. Warianty odpowiadają stanom bramki w scenariuszach.
 *
 * <p>Kod domenowy, bez Sentry. Awaria bramki to błąd krytyczny: klient nie zapłaci, a zamówienie
 * utknie. Każdy filtr, który go odrzuci, ukrywa incydent produkcyjny.</p>
 */
@FunctionalInterface
public interface PaymentGateway {

    /**
     * Obciąża kartę i zwraca kod autoryzacji.
     *
     * @param amount kwota w groszach
     */
    String charge(String orderId, long amount) throws IOException;

    /** Bramka działa. */
    static PaymentGateway available() {
        return (orderId, amount) -> "AUTH-" + orderId;
    }

    /**
     * Bramka zrywa połączenie w trakcie obciążenia. Komunikat jest identyczny jak przy kliencie,
     * który zamknął przeglądarkę ({@link ResponseChannel#resetByClient()}): tekst wyjątku nie mówi,
     * po której stronie jest problem.
     */
    static PaymentGateway resettingConnection() {
        return (orderId, amount) -> {
            throw new SocketException("Connection reset");
        };
    }

    /** Rzadki błąd konfiguracji: bez klucza podpisu żadna płatność tego sprzedawcy nie przejdzie. */
    static PaymentGateway missingSigningKey() {
        return (orderId, amount) -> {
            throw new IllegalStateException("Brak klucza podpisu żądań dla sprzedawcy M-42");
        };
    }
}
