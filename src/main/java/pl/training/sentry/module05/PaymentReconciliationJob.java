package pl.training.sentry.module05;

import io.sentry.ISentryLifecycleToken;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SentryAttribute;
import io.sentry.SentryAttributes;
import io.sentry.SentryLogLevel;
import io.sentry.SpanStatus;
import io.sentry.TransactionOptions;
import io.sentry.logger.SentryLogParameters;

import java.util.List;

/**
 * Nocne uzgadnianie płatności z raportem dostawcy: samodzielne zadanie bez przychodzącego
 * requestu.
 *
 * <p>Pokazuje, co daje własna {@code ITransaction} zadaniu, którego nie otwiera żaden request
 * (scenariusz 1). {@link #runWithoutTransaction} to wersja z typowym błędem, a {@link #run}
 * wersja docelowa: transakcja z child spanami, błąd powiązany ze spanem i podsumowanie jako
 * jeden log z atrybutami. Obie wykonują ten sam kod domenowy, więc różnicę w Sentry robi tylko
 * granica operacji.</p>
 */
public final class PaymentReconciliationJob {

    public enum Outcome { RECONCILED, FAILED }

    private final SettlementLedger ledger = new SettlementLedger();
    private final SettlementReports reports = new SettlementReports();

    /**
     * PUŁAPKA: job bez własnej operacji wejściowej. Błąd i logi trafiają do Sentry, ale nie ma
     * transakcji ani spanów, a każde uruchomienie ma ten sam trace ID. Jest to identyfikator
     * propagation context skopiowany ze scope utworzonego przy {@code Sentry.init}, więc wspólny
     * trace nie oznacza tu wspólnego wykonania (moduł 1 pokazał to samo dla eventów).
     */
    public Outcome runWithoutTransaction(String provider) {
        try (ISentryLifecycleToken jobScopes = Sentry.forkedRootScopes("payments.reconcile").makeCurrent()) {
            describeRun(provider);
            try {
                int matched = reconcile(provider);
                logSummary(provider, Outcome.RECONCILED, matched);
                return Outcome.RECONCILED;
            } catch (RuntimeException exception) {
                Sentry.captureException(exception);
                logSummary(provider, Outcome.FAILED, 0);
                return Outcome.FAILED;
            }
        }
    }

    /** Wersja docelowa: jedno uruchomienie joba to jedna transakcja z własnym trace. */
    public Outcome run(String provider) {
        // Każde uruchomienie dostaje świeże scopes sklonowane z root scopes, a nie ze scope wątku
        // schedulera. Tag i atrybut ustawione niżej znikają razem z tokenem, więc nie trzeba ich
        // usuwać ręcznie i nie przeciekną do następnego zadania na tym samym wątku.
        // PUŁAPKA: na wątku, który wywołał Sentry.init, bieżące scopes to same root scopes.
        // Transakcja związana tam bez forka trafiłaby do kopii scopes każdego nowego wątku.
        try (ISentryLifecycleToken jobScopes = Sentry.forkedRootScopes("payments.reconcile").makeCurrent()) {
            // bindToScope: transakcja staje się aktywnym spanem scope. Dzięki temu ChildSpans,
            // logi i captureException znajdują ją przez Sentry.getSpan() bez przekazywania obiektu.
            TransactionOptions options = new TransactionOptions();
            options.setBindToScope(true);
            ITransaction transaction = Sentry.startTransaction("payments.reconcile", "task", options);
            // PUŁAPKA: log wysłany przed startTransaction dostałby jeszcze trace ID z propagation
            // context, a nie z transakcji, i nie byłoby go w trace tego uruchomienia.
            describeRun(provider);
            try {
                int matched = reconcile(provider);
                transaction.setStatus(SpanStatus.OK);
                // Podsumowanie przed finish(): po zakończeniu transakcji log nie miałby już
                // aktywnego spanu i dostałby identyfikatory z propagation context.
                logSummary(provider, Outcome.RECONCILED, matched);
                return Outcome.RECONCILED;
            } catch (RuntimeException exception) {
                transaction.setThrowable(exception);
                transaction.setStatus(SpanStatus.INTERNAL_ERROR);
                // Jeden właściciel capture: granica joba. Span raportu ma już status i throwable,
                // ale sam nie tworzy issue. Job nie rzuca dalej, bo scheduler i tak ponowi
                // uruchomienie jutro; gdyby scheduler raportował wyjątki sam, ten capture trzeba
                // by usunąć, żeby nie było dwóch eventów.
                Sentry.captureException(exception);
                logSummary(provider, Outcome.FAILED, 0);
                return Outcome.FAILED;
            } finally {
                transaction.finish();
            }
        }
    }

    private void describeRun(String provider) {
        // Tag: wymiar filtrowania eventów i transakcji. Atrybut scope: pole dołączane do każdego
        // Structured Log w tym zakresie (logi nie biorą tagów scope).
        Sentry.setTag("job.name", "payments.reconcile");
        Sentry.setAttribute(SentryAttribute.stringAttribute("workflow.kind", "payment-reconciliation"));
        Sentry.logger().info("Start uzgadniania płatności dostawcy %s", provider);
    }

    private int reconcile(String provider) {
        List<String> unsettled = ChildSpans.trace("db.query", "load unsettled payments", span -> {
            List<String> payments = ledger.loadUnsettled(provider);
            span.setData("payments.count", payments.size());
            return payments;
        });
        List<String> settled = ChildSpans.trace("http.client", "GET settlement report", span -> {
            // Kod dostawcy to stabilna, niskokardynalna wartość: nadaje się do danych spanu.
            span.setData("payment.provider", provider);
            return reports.fetchSettled(provider);
        });
        return (int) unsettled.stream().filter(settled::contains).count();
    }

    /**
     * Jeden wpis wyniku operacji (wide event) zamiast serii cienkich logów etapów. Log o poziomie
     * ERROR pozostaje logiem: nie tworzy issue, issue tworzy captureException.
     */
    private void logSummary(String provider, Outcome outcome, int matched) {
        SentryAttributes attributes = SentryAttributes.of(
                SentryAttribute.stringAttribute("payment.provider", provider),
                SentryAttribute.stringAttribute("reconcile.outcome", outcome.name().toLowerCase()),
                SentryAttribute.integerAttribute("payments.matched", matched)
        );
        Sentry.logger().log(
                outcome == Outcome.FAILED ? SentryLogLevel.ERROR : SentryLogLevel.INFO,
                SentryLogParameters.create(attributes),
                "Uzgadnianie płatności %s zakończone: %s",
                provider,
                outcome.name().toLowerCase()
        );
    }
}
