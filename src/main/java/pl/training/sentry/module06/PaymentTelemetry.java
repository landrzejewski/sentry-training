package pl.training.sentry.module06;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import io.sentry.SentryOptions;
import pl.training.sentry.module06.BankGateway.BankTimeoutException;
import pl.training.sentry.module06.BankGateway.CardDeclinedException;
import pl.training.sentry.module06.BankGateway.Payment;
import pl.training.sentry.module06.PaymentService.DuplicatePaymentException;

import java.util.List;
import java.util.Map;

/**
 * Pokazuje, co aplikacja płatności mówi Sentry o błędach: tagi, level, fingerprint i filtr SDK,
 * od których zależą grouping, filtry Alertów, Ownership Rules i redukcja szumu.
 *
 * <p>Konfiguracja Alertu i Ownership Rules żyje w Sentry UI, ale działa tylko na danych, które
 * przyszły w evencie: reguła z filtrem {@code component = payments} nie zadziała dla eventu bez
 * tego tagu.</p>
 *
 * <p>{@link #reportCarelessly} zbiera typowe błędy z przeglądów kodu, pozostałe metody to wersja
 * docelowa. Scenariusze 5 i 6 pokazują je obok siebie.</p>
 */
public final class PaymentTelemetry {

    private PaymentTelemetry() {
    }

    /**
     * Filtry SDK dla payments-api: odrzucenie oczekiwanych wyjątków przed wysłaniem.
     *
     * <p>Filtr przed wysłaniem to najsilniejsze narzędzie redukcji szumu: odrzuconego eventu
     * nie da się później przeanalizować ani policzyć w Sentry. SDK notuje go tylko jako liczbę
     * w client report (powód {@code event_processor} dla ignorowanego typu, {@code before_send}
     * dla {@code beforeSend}), który transport HTTP dołącza do kolejnej wysyłki.
     * Stosuje się go wyłącznie dla przypadków znanych z pewnością i bez wartości diagnostycznej.</p>
     */
    public static void configure(SentryOptions options) {
        // Powtórzone żądanie z tym samym kluczem idempotencji to poprawne zachowanie API, które
        // granica requestu raportuje razem z każdym nieprzewidzianym wyjątkiem. Nie ma czego
        // naprawiać ani analizować, a przy słabym zasięgu mobilnym takich żądań są tysiące.
        // Dopasowanie po typie, a nie po treści komunikatu.
        // PUŁAPKA: SDK porównuje dokładną klasę wyjątku, podklasy nie są odrzucane.
        // PUŁAPKA: options.setIgnoredErrors dopasowuje wzorce do tekstu „pełna.nazwa.Klasy: treść”.
        // Wzorzec „.*Timeout.*” dodany dla hałaśliwego klienta HTTP odetnie też
        // BankTimeoutException, a wraz z nim alert o awarii banku. Zmiana treści komunikatu
        // w bibliotece potrafi z kolei po cichu wyłączyć filtr.
        options.addIgnoredExceptionForType(DuplicatePaymentException.class);
    }

    /**
     * Awaria banku: błąd, który ma dotrzeć do zespołu payments jako jedno Issue na awarię.
     *
     * @param operation stabilna nazwa operacji ({@code charge}, {@code statement-import},
     *                  {@code health-check}), wymiar filtrowania o kilku wartościach
     */
    public static void reportBankTimeout(BankTimeoutException failure, String operation, Map<String, Object> details) {
        Sentry.captureException(failure, scope -> {
            // Tagi o kontrolowanej kardynalności: po component Ownership Rules wskazują zespół
            // (reguła „tags.component:payments #payments”), a Alert filtruje produkcyjne błędy
            // krytycznego komponentu. Brak tagu nie daje błędu, tylko ciszę: filtr nie pasuje.
            scope.setTag("component", "payments");
            scope.setTag("operation", operation);
            scope.setTag("bank", BankGateway.BANK_CODE);
            scope.setLevel(SentryLevel.ERROR);
            // Stabilny fingerprint: jedna awaria banku to jedno Issue, niezależnie od liczby
            // płatności i miejsca w kodzie. Domyślny grouping po stack trace rozdzieliłby
            // timeouty z endpointu, joba importu i health checku na osobne Issues, a refaktor
            // ścieżki wywołań utworzyłby nowe Issue i ponownie uruchomił alert „nowe Issue”.
            // Operacja zostaje w tagu, więc rozkład w Issue pokazuje, co dotknęła awaria.
            scope.setFingerprint(List.of("bank-timeout", BankGateway.BANK_CODE));
            // Identyfikatory i nazwa sprzedawcy do analizy konkretnego eventu: w contexts,
            // nie w fingerprincie.
            scope.setContexts("bank_call", details);
        });
    }

    /**
     * Odmowa banku: wynik biznesowy, który zespół chce widzieć jako trend, a nie jako defekt.
     */
    public static void reportDecline(CardDeclinedException declined, Payment payment) {
        Sentry.captureException(declined, scope -> {
            scope.setTag("component", "payments");
            scope.setTag("operation", "charge");
            // Jawna klasyfikacja ustawiana wyłącznie w kontrolowanym kodzie. Alert produkcyjny
            // wyklucza expected=true (albo level niższy niż error), a Issue można zarchiwizować
            // „do eskalacji”: dane zostają, a nagły skok odmów (np. bank odrzuca wszystko)
            // wróci jako Escalating. Po filtrze przed wysłaniem tego skoku nie byłoby widać.
            scope.setTag("expected", "true");
            scope.setTag("decline.reason", declined.reason());
            scope.setLevel(SentryLevel.WARNING);
            // Jedno Issue na powód odmowy: kilka wartości, stabilnych między wersjami kodu.
            scope.setFingerprint(List.of("card-declined", declined.reason()));
            scope.setContexts("payment", Map.of("payment_id", payment.id()));
        });
    }

    /** Wspólny pomocnik raportujący z przeglądu kodu: każdy wyjątek tak samo. */
    public static void reportCarelessly(RuntimeException failure) {
        Sentry.captureException(failure, scope ->
                // PUŁAPKA: fingerprint z komunikatu, żeby „rozdzielić różne błędy tego samego
                // typu”. Komunikat zawiera identyfikator płatności i nazwę sprzedawcy.
                // Wartość fingerprintu równą komunikatowi wyjątku Sentry parametryzuje jak sam
                // komunikat: liczby i losowe identyfikatory zamienia na symbole (pay_<random_id>),
                // ale nazwę sprzedawcy zostawia (sprawdzone na self-hosted 26.9.0, grouping
                // newstyle:2026-01-20). Identyfikator w wartości innej niż komunikat, np.
                // „charge-” + id płatności, nie jest parametryzowany. Skutek: Issue na każdego
                // sprzedawcę zamiast jednego na awarię, alert „nowe Issue” dla każdego, throttling
                // per Issue nic nie ogranicza, a archiwizacja po issueId nie obejmuje kolejnego
                // sprzedawcy. Brak tagów i level error dla wszystkiego, także dla odmowy karty,
                // więc filtry Alertu nie mają na czym działać.
                scope.setFingerprint(List.of(failure.getClass().getSimpleName(), failure.getMessage())));
    }
}
