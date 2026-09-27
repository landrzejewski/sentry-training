package pl.training.sentry.module02;

import java.io.IOException;

/**
 * Klient zamknął połączenie, zanim aplikacja wysłała odpowiedź: oczekiwany przebieg, nie defekt.
 *
 * <p>Scenariusz 4 odrzuca go przez {@code ignoredExceptionsForType}. Dedykowany typ nadaje warstwa,
 * która wie, co się stało ({@link ResponseChannel}), podobnie jak kontener servletów zamienia błąd
 * zapisu do gniazda na własny wyjątek przerwanego połączenia. Dzięki temu filtr po dokładnej klasie odrzuca tylko ten
 * przypadek, a identycznie brzmiący {@code SocketException: Connection reset} z bramki płatności
 * nadal trafia do Sentry.</p>
 */
public class ClientAbortedException extends IOException {

    public ClientAbortedException(String message, Throwable cause) {
        super(message, cause);
    }
}
