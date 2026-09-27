package pl.training.sentry.module05;

import java.util.List;

/**
 * Księga płatności czekających na rozliczenie u dostawcy (symulowana baza danych).
 *
 * <p>Kod domenowy zadania uzgadniania płatności, bez Sentry. Używa go
 * {@link PaymentReconciliationJob}.</p>
 */
public final class SettlementLedger {

    public List<String> loadUnsettled(String provider) {
        Latency.pause(40);
        return List.of("PAY-1001", "PAY-1002", "PAY-1003");
    }
}
