package pl.training.sentry.module02;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Request HTTP w postaci, którą w aplikacji webowej dostarcza framework.
 *
 * <p>Część scenariuszy w czystej Javie nie uruchamia serwera, żeby wydruk skupiał się na
 * konfiguracji SDK. Scenariusze 7 i 8 wysyłają prawdziwe requesty do aplikacji Spring Boot.</p>
 *
 * @param query   query string bez znaku {@code ?} albo {@code null}
 * @param headers nagłówki; nazwy bez rozróżniania wielkości liter, jak w HTTP
 */
public record IncomingRequest(String method, String path, String query, Map<String, String> headers) {

    public IncomingRequest {
        TreeMap<String, String> caseInsensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        caseInsensitive.putAll(headers);
        headers = Collections.unmodifiableMap(caseInsensitive);
    }

    public String header(String name) {
        return headers.get(name);
    }
}
