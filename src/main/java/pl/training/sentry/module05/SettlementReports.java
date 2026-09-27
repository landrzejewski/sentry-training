package pl.training.sentry.module05;

import java.util.List;

/**
 * Klient raportów rozliczeń udostępnianych przez dostawców płatności (symulowany).
 *
 * <p>Kod domenowy bez Sentry. API raportów {@code provider-b} ma awarię: tak wygląda nocny
 * job, który dla jednego dostawcy działa, a dla drugiego nie.</p>
 */
public final class SettlementReports {

    /** Identyfikatory płatności, które dostawca potwierdził jako rozliczone. */
    public List<String> fetchSettled(String provider) {
        Latency.pause(80);
        if ("provider-b".equals(provider)) {
            throw new IllegalStateException("Raport rozliczeń " + provider + " niedostępny: HTTP 503");
        }
        return List.of("PAY-1001", "PAY-1003");
    }
}
