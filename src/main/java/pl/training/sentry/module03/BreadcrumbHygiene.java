package pl.training.sentry.module03;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryOptions;

/**
 * {@code beforeBreadcrumb}: oczyszcza breadcrumbs HTTP i odrzuca szum, zanim trafi do bufora.
 *
 * <p>Bufor breadcrumbs ma stały rozmiar ({@code maxBreadcrumbs}, domyślnie 100), a nowe wpisy
 * wypierają najstarsze. Odpytywanie o status płatności co kilkaset milisekund potrafi zapełnić
 * cały bufor identycznymi wpisami i wyprzeć początek przebiegu. Callback działa
 * przy dodawaniu wpisu, więc odrzucony wpis nie zajmuje miejsca w buforze.</p>
 */
public final class BreadcrumbHygiene implements SentryOptions.BeforeBreadcrumbCallback {

    @Override
    public Breadcrumb execute(Breadcrumb breadcrumb, Hint hint) {
        if (!"http".equals(breadcrumb.getCategory())) {
            return breadcrumb;
        }
        // Query string bywa nośnikiem tokenów i danych osobowych, a do odtworzenia przebiegu
        // wystarczą metoda, adres bez query i kod odpowiedzi.
        breadcrumb.removeData("http.query");

        // Udane odpytanie o status niczego nie wnosi: wynik odpytywania opisuje wyjątek
        // NO_DECISION. Odpytanie zakończone błędem albo bez odpowiedzi zostaje.
        if (isSuccessfulStatusPoll(breadcrumb)) {
            return null;
        }
        return breadcrumb;
    }

    private static boolean isSuccessfulStatusPoll(Breadcrumb breadcrumb) {
        return "GET".equals(breadcrumb.getData("method"))
                && breadcrumb.getData("url") instanceof String url
                && url.endsWith("/status")
                && Integer.valueOf(200).equals(breadcrumb.getData("status_code"));
    }
}
