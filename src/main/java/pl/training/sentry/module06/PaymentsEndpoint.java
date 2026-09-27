package pl.training.sentry.module06;

import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;
import pl.training.sentry.module06.BankGateway.BankTimeoutException;
import pl.training.sentry.module06.BankGateway.CardDeclinedException;
import pl.training.sentry.module06.BankGateway.Payment;

import java.util.Map;

/**
 * Pokazuje klasyfikację błędów requestu „obciąż kartę” w payments-api: szum z oczekiwanych
 * wyjątków powstaje już w instrumentacji (scenariusze 5 i 6).
 *
 * <p>Ten sam wyjątek domenowy może dać event, który budzi dyżur, event, który tylko zasila
 * trend, albo brak eventu. Decyduje o tym kod raportujący, a nie konfiguracja Alertu.</p>
 */
public final class PaymentsEndpoint {

    /** Jak endpoint raportuje błędy obciążenia karty. */
    public enum Reporting {
        /** PUŁAPKA: każdy wyjątek tak samo, przez wspólnego pomocnika z fingerprintem z komunikatu. */
        CARELESS,
        /** Klasyfikacja: awaria banku, odmowa karty, reszta przez granicę requestu. */
        CLASSIFIED
    }

    private final PaymentService payments;

    public PaymentsEndpoint(PaymentService payments) {
        this.payments = payments;
    }

    /** Obsługa jednego requestu. Zwraca odpowiedź dla klienta. */
    public String charge(Payment payment, Reporting reporting) {
        // Własny isolation scope na request, jak w module 1: dane requestu nie przeciekają
        // do eventów kolejnych requestów na tym samym wątku.
        try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
            try {
                payments.charge(payment);
                return "CHARGED";
            } catch (RuntimeException failure) {
                if (reporting == Reporting.CARELESS) {
                    PaymentTelemetry.reportCarelessly(failure);
                    return failure instanceof CardDeclinedException ? "DECLINED" : "ERROR";
                }
                return classifyAndReport(failure, payment);
            }
        }
    }

    private static String classifyAndReport(RuntimeException failure, Payment payment) {
        return switch (failure) {
            case BankTimeoutException bankDown -> {
                PaymentTelemetry.reportBankTimeout(bankDown, "charge",
                        Map.of("payment_id", payment.id(), "merchant", payment.merchant()));
                yield "RETRY_LATER";
            }
            case CardDeclinedException declined -> {
                PaymentTelemetry.reportDecline(declined, payment);
                yield "DECLINED";
            }
            default -> {
                // Granica requestu raportuje wszystko, czego kod nie przewidział. W aplikacji
                // webowej robi to integracja frameworka. Trafia tu też DuplicatePaymentException:
                // oczekiwane zachowanie, które odcina dopiero filtr SDK
                // (PaymentTelemetry.configure).
                Sentry.captureException(failure);
                yield "ERROR";
            }
        };
    }
}
