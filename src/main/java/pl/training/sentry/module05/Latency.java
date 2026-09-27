package pl.training.sentry.module05;

/**
 * Symulowany czas pracy zależności (baza, usługa zewnętrzna, obliczenia).
 *
 * <p>Przykłady nie łączą się z prawdziwą bazą ani dostawcą płatności. Stałe opóźnienia
 * sprawiają, że waterfall w konsoli i w Sentry ma czytelne, powtarzalne proporcje.</p>
 */
public final class Latency {

    private Latency() {
    }

    public static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Przerwano oczekiwanie na zależność", exception);
        }
    }
}
