package pl.training.sentry.module02;

import io.sentry.SentryEvent;
import io.sentry.protocol.Request;
import io.sentry.protocol.User;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Minimalizacja danych w {@code beforeSend}: usuwa z eventu pola, które nie powinny opuścić procesu.
 *
 * <p>Pierwsza warstwa ochrony danych, działa przed wysłaniem. Jej efekt widać w wydruku requestu
 * w scenariuszach 5 i 8. Scrubber:</p>
 * <ul>
 *   <li>zostawia z URL tylko schemat, host, port i ścieżkę (bez credentials, query i fragmentu),
 *   a nieczytelny URL usuwa w całości;</li>
 *   <li>usuwa query string, body, cookies i zmienne środowiskowe requestu;</li>
 *   <li>przepuszcza tylko nagłówki z listy dozwolonych, bez względu na wielkość liter;</li>
 *   <li>z user context zostawia tylko {@code user.id};</li>
 *   <li>usuwa extras, bo to otwarty słownik bez schematu.</li>
 * </ul>
 *
 * <p>Nagłówki filtruje lista dozwolonych, nie zakazanych. PUŁAPKA: lista zakazanych
 * ({@code Authorization}, {@code Cookie}) przepuszcza każdy nowy nagłówek z sekretem, np.
 * {@code X-Session-Token} dodany przez inny zespół. Lista dozwolonych w najgorszym razie usunie
 * nagłówek potrzebny do diagnozy, a to widać przy pierwszym evencie i łatwo poprawić.</p>
 *
 * <p>To nie jest uniwersalny sanitizer. Nie analizuje wiadomości wyjątków, custom contexts,
 * breadcrumbs ani załączników: te kanały wymagają zatwierdzonego schematu w miejscu emisji,
 * a server-side data scrubbing w Sentry jest drugą warstwą. Klasa jest null-safe, bo działa
 * dla każdego eventu, także z zadań w tle, które nie mają requestu ani usera.</p>
 */
public final class EventDataScrubber {

    private static final Set<String> ALLOWED_HEADERS =
            Set.of("accept", "content-type", "content-length", "host", "user-agent", "x-request-id");

    public SentryEvent scrub(SentryEvent event) {
        Request request = event.getRequest();
        if (request != null) {
            scrubRequest(request);
        }
        scrubUser(event);
        event.setExtras(null);
        return event;
    }

    private static void scrubRequest(Request request) {
        request.setUrl(withoutCredentialsQueryAndFragment(request.getUrl()));
        request.setQueryString(null);
        request.setFragment(null);
        request.setData(null);
        request.setCookies(null);
        request.setEnvs(null);

        Map<String, String> headers = request.getHeaders();
        if (headers != null) {
            Map<String, String> allowed = new TreeMap<>();
            headers.forEach((name, value) -> {
                if (ALLOWED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    allowed.put(name, value);
                }
            });
            request.setHeaders(allowed);
        }
    }

    private static void scrubUser(SentryEvent event) {
        User user = event.getUser();
        if (user == null) {
            return;
        }
        if (user.getId() == null) {
            // Nic zatwierdzonego do wysłania: IP, e-mail czy nazwa użytkownika nie zostają same.
            event.setUser(null);
            return;
        }
        User safeUser = new User();
        safeUser.setId(user.getId());
        event.setUser(safeUser);
    }

    private static String withoutCredentialsQueryAndFragment(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null) {
                return uri.getRawPath();
            }
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (URISyntaxException exception) {
            // Nieczytelny URL mógł zawierać sekret, więc nie wysyłamy go w surowej postaci.
            return null;
        }
    }
}
