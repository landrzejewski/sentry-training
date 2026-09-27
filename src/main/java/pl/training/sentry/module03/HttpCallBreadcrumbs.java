package pl.training.sentry.module03;

import io.sentry.Breadcrumb;
import io.sentry.Sentry;

/**
 * Breadcrumb dla każdego wywołania HTTP klienta bramki, tak jak robią to integracje klientów HTTP.
 *
 * <p>Używa {@link Breadcrumb#http(String, String, Integer)}, tej samej fabryki, której używa np.
 * integracja Spring dla {@code RestTemplate} ({@code SentrySpanClientHttpRequestInterceptor}):
 * adres trafia do {@code url} bez query string, query string osobno do {@code http.query}, a kod
 * odpowiedzi do {@code status_code}. Dzięki temu scenariusz 5 pokazuje realny problem
 * z breadcrumbs, których kod aplikacji nie pisze ręcznie.</p>
 */
public final class HttpCallBreadcrumbs implements PaymentGatewayClient.HttpCallListener {

    @Override
    public void onCompleted(String method, String url, Integer statusCode) {
        // PUŁAPKA: query string z tokenem ląduje w http.query. Integracja zapisuje go w dobrej
        // wierze, więc usunąć go musi konfiguracja SDK, najwcześniej beforeBreadcrumb
        // (BreadcrumbHygiene), zanim wpis trafi do bufora.
        Sentry.addBreadcrumb(Breadcrumb.http(url, method, statusCode));
    }
}
