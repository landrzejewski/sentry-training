package pl.training.sentry.module05;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Klient sklepu internetowego, czyli przeglądarka w uproszczeniu: wysyła requesty do orders-api.
 *
 * <p>Nie używa Sentry SDK. Gdy ma udawać przeglądarkę z browser SDK, dokłada nagłówek
 * {@code sentry-trace} tak, jak zrobiłaby to instrumentacja fetch: trace ID, span ID requestu
 * w przeglądarce i decyzja samplingu podjęta w przeglądarce (origin trace, scenariusz 6).</p>
 */
public final class StorefrontClient {

    /** Decyzja samplingu przeglądarki dołączana do requestu albo jej brak. */
    public enum BrowserTrace {
        /** Request bez nagłówków trace: orders-api staje się originem trace. */
        NONE,
        /** Przeglądarka zachowała trace ({@code -1} na końcu {@code sentry-trace}). */
        SAMPLED,
        /** Przeglądarka odrzuciła trace ({@code -0} na końcu {@code sentry-trace}). */
        NOT_SAMPLED
    }

    /** Odpowiedź i trace ID wysłany przez przeglądarkę (albo {@code null}). */
    public record Result(int status, String body, String browserTraceId) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public Result placeOrder(URI ordersApi, String orderId, String customerId, BrowserTrace browserTrace)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(ordersApi.resolve("/api/orders"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("orderId=" + orderId + "&customerId=" + customerId));
        String traceId = null;
        if (browserTrace != BrowserTrace.NONE) {
            traceId = randomHex(16);
            String sampled = browserTrace == BrowserTrace.SAMPLED ? "1" : "0";
            request.header("sentry-trace", traceId + "-" + randomHex(8) + "-" + sampled);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), response.body(), traceId);
    }

    public int healthCheck(URI service) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(service.resolve("/health")).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }
}
