package pl.training.sentry.module06;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Uzgodnienie płatności z wyciągiem bankowym: praca wykonywana przez joby z check-inami.
 *
 * <p>Kod domenowy bez Sentry. O monitorowaniu decyduje warstwa, która uruchamia zadanie
 * ({@link SettlementJob}, {@link StatementImportJob}), więc tę samą logikę da się testować
 * i uruchamiać bez check-inów.</p>
 */
public final class SettlementService {

    private final BankGateway bank;
    private final Set<String> expectedPayments;

    public SettlementService(BankGateway bank, Set<String> expectedPayments) {
        this.bank = bank;
        this.expectedPayments = Set.copyOf(expectedPayments);
    }

    public SettlementReport reconcile(LocalDate day) {
        List<String> received = bank.statement(day);
        int matched = (int) expectedPayments.stream().filter(received::contains).count();
        return new SettlementReport(day, matched, expectedPayments.size() - matched);
    }

    /**
     * Wynik uzgodnienia. Płatności nieuzgodnione to wynik biznesowy do wyjaśnienia przez dział
     * finansowy, a nie awaria zadania: zadanie wykonało swoją pracę.
     */
    public record SettlementReport(LocalDate day, int matched, int unmatched) {
    }
}
