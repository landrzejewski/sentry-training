package pl.training.sentry.module02;

import java.io.IOException;
import java.net.SocketException;

/**
 * Połączenie z klientem, którym aplikacja odsyła odpowiedź. Warianty odpowiadają temu, co widzi
 * kod zapisujący odpowiedź, gdy klient zamknie przeglądarkę albo aplikację mobilną.
 *
 * <p>Kod domenowy, bez Sentry. Scenariusz 4 zestawia oba warianty: gołe {@code SocketException}
 * trafia do Sentry jako szum, a {@link ClientAbortedException} odrzuca {@code ignoredExceptionsForType}.</p>
 */
@FunctionalInterface
public interface ResponseChannel {

    void send(String body) throws IOException;

    /** Klient czeka na odpowiedź. */
    static ResponseChannel open() {
        return body -> {
        };
    }

    /**
     * Klient zamknął połączenie, a kod zapisujący do gniazda przepuszcza ogólny wyjątek sieciowy.
     * Tak wygląda szum w Sentry: tysiące {@code SocketException: Connection reset}, nie do odróżnienia
     * od awarii bramki po samym typie i komunikacie.
     */
    static ResponseChannel resetByClient() {
        return body -> {
            throw new SocketException("Connection reset");
        };
    }

    /**
     * Klient zamknął połączenie, a warstwa zapisu odpowiedzi klasyfikuje to na miejscu. Tylko ona
     * wie, że błąd dotyczy połączenia z klientem, więc tu powstaje dedykowany typ.
     */
    static ResponseChannel abortedByClient() {
        return body -> {
            try {
                resetByClient().send(body);
            } catch (SocketException exception) {
                throw new ClientAbortedException("Klient zamknął połączenie przed odebraniem odpowiedzi", exception);
            }
        };
    }
}
