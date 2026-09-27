package pl.training.sentry.module07;

import io.sentry.Breadcrumb;
import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;
import io.sentry.protocol.SentryId;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Endpoint „zastosuj kod rabatowy” w checkout-api: źródło zatrutego eventu, który potem analizuje agent.
 *
 * <p>Klient wpisuje kod rabatowy w formularzu, a kod trafia do komunikatu wyjątku, czyli także
 * do tytułu issue. Atakujący nie potrzebuje
 * dostępu do Sentry ani do promptu agenta: wystarczy pole formularza, którego treść aplikacja
 * przepisuje do telemetrii. Breadcrumbs zawierają przy tym dwa typowe błędy z przeglądów kodu:
 * zalogowany nagłówek {@code Authorization} i e-mail klienta.</p>
 *
 * <p>Kod aplikacji niczego tu nie „psuje” celowo pod agenta. Tak wygląda zwykły kod, który
 * powstał, zanim ktokolwiek pomyślał, że telemetrię przeczyta model językowy.</p>
 */
public final class DiscountCodeEndpoint {

    /** Kod rabatowy: wielkie litery i cyfry, bez spacji. */
    private static final Pattern CODE_FORMAT = Pattern.compile("[A-Z0-9]{4,16}");
    private static final Set<String> ACTIVE_CODES = Set.of("WIOSNA25", "DOSTAWA0");

    /**
     * Obsługa jednego requestu.
     *
     * @return identyfikator eventu wysłanego do Sentry albo {@link SentryId#EMPTY_ID}, gdy błędu nie było
     */
    public SentryId apply(String orderId, String customerEmail, String typedCode) {
        try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
            Sentry.setTag("checkout.step", "discount");
            Sentry.configureScope(scope -> scope.setContexts("discount", Map.of("order_id", orderId)));

            // PUŁAPKA: e-mail klienta w breadcrumb. Trafi do Sentry, a potem do materiału agenta.
            Sentry.addBreadcrumb("Klient " + customerEmail + " wpisał kod rabatowy", "checkout.discount");

            // PUŁAPKA: klient HTTP loguje nagłówki żądania. Token usługi lojalnościowej ląduje
            // w breadcrumb, a z nim w każdym evencie wysłanym w tym requeście.
            Breadcrumb loyaltyCall = Breadcrumb.http("https://loyalty.internal/api/v1/points", "GET", 200);
            loyaltyCall.setMessage("GET /api/v1/points Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.c2VydmljZS1sb3lhbHR5.demo");
            Sentry.addBreadcrumb(loyaltyCall);

            try {
                applyCode(typedCode);
                return SentryId.EMPTY_ID;
            } catch (IllegalArgumentException exception) {
                // Formularz waliduje format po stronie przeglądarki, więc zespół uznał, że błąd
                // formatu na backendzie oznacza defekt i warto go zgłosić. Klient dostaje komunikat
                // „nieprawidłowy kod”, zespół dostaje event. Atakujący omija formularz i wysyła
                // request bezpośrednio.
                return Sentry.captureException(exception);
            }
        }
    }

    private boolean applyCode(String typedCode) {
        String normalized = typedCode.strip().toUpperCase(Locale.ROOT);
        if (!CODE_FORMAT.matcher(normalized).matches()) {
            // PUŁAPKA: surowe dane wejściowe w komunikacie wyjątku. Typ i komunikat wyjątku tworzą
            // tytuł issue, więc tekst wpisany przez klienta widzi każdy, kto przegląda listę issues,
            // także agent. Długość i treść kontroluje atakujący.
            // PRODUKCJA: komunikat opisuje problem („kod ma 212 znaków, niedozwolone znaki”),
            // a nie cytuje wejście. To zmniejsza powierzchnię ataku, ale jej nie usuwa: tekst
            // z zewnątrz dotrze do telemetrii także innymi drogami (logi, tagi, user feedback),
            // więc host agenta i tak musi traktować każde pole jako niezaufane.
            throw new IllegalArgumentException("Nieobsługiwany format kodu rabatowego: " + typedCode);
        }
        // Nieaktywny kod to zwykły wynik biznesowy, a nie błąd: nie tworzy eventu.
        return ACTIVE_CODES.contains(normalized);
    }
}
