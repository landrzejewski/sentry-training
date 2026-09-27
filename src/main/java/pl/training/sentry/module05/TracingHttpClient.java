package pl.training.sentry.module05;

import io.sentry.Sentry;
import io.sentry.util.TracingUtils;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Wywołanie usługi downstream z client spanem i nagłówkami trace: odpowiednik instrumentowanego
 * klienta HTTP, jakiego dostarczają integracje (np. {@code RestClient} z buildera Spring Boot).
 *
 * <p>Ciągłość trace z usługą downstream zależy od trzech rzeczy: client spanu, który zostaje jej
 * rodzicem, adresu pasującego do {@code tracePropagationTargets} i nagłówków, które faktycznie
 * wychodzą z procesu (scenariusz 2).</p>
 */
public final class TracingHttpClient {

    /** Wynik wywołania: kod HTTP i treść odpowiedzi. */
    public record Result(int status, String body) {
        public boolean successful() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public Result post(URI uri, String form) throws IOException {
        // Opis spanu to metoda i adres bez query string: stała wartość dla jednej usługi docelowej.
        return ChildSpans.trace("http.client", "POST " + uri.getScheme() + "://" + uri.getAuthority() + uri.getPath(), span -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form));

            // Tak samo robią integracje SDK: nagłówki powstają z client spanu (sentry-trace niesie
            // jego span ID jako rodzica dla usługi downstream), ale tylko gdy adres pasuje do
            // tracePropagationTargets. Bez aktywnego spanu SDK bierze identyfikatory z propagation
            // context, więc błędy nadal się połączą, choć waterfall nie powstanie.
            // Ręczny wariant bez sprawdzania allowlisty to Sentry.getTraceparent() i
            // Sentry.getBaggage(); nazwa getTraceparent jest historyczna, zwraca sentry-trace.
            TracingUtils.TracingHeaders headers =
                    TracingUtils.traceIfAllowed(Sentry.getCurrentScopes(), uri.toString(), null, span);
            if (headers != null) {
                request.header(headers.getSentryTraceHeader().getName(), headers.getSentryTraceHeader().getValue());
                if (headers.getBaggageHeader() != null) {
                    request.header(headers.getBaggageHeader().getName(), headers.getBaggageHeader().getValue());
                }
            }

            HttpResponse<String> response = send(request.build());
            span.setData("http.response.status_code", response.statusCode());
            span.setStatus(ChildSpans.statusForHttp(response.statusCode()));
            return new Result(response.statusCode(), response.body());
        });
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Przerwano wywołanie " + request.uri());
        }
    }
}
