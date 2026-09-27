package pl.training.sentry.module05;

import com.sun.net.httpserver.HttpServer;
import io.sentry.Sentry;
import io.sentry.SentryAttribute;
import io.sentry.SentryAttributes;
import io.sentry.SentryLogLevel;
import io.sentry.logger.SentryLogParameters;
import pl.training.sentry.module05.PaymentProvider.AmountOverLimitException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Usługa payments-api: {@code POST /authorize}, wywoływana przez orders-api.
 *
 * <p>W produkcji to osobny proces, zwykle w osobnym projekcie Sentry. Tutaj działa w tym samym
 * JVM co orders-api i dzieli z nim konfigurację SDK, ale łączy się z nim wyłącznie przez HTTP.
 * Dlatego ciągłość trace zależy tylko od nagłówków {@code sentry-trace} i {@code baggage},
 * tak jak między dwoma serwerami.</p>
 */
public final class PaymentsApi implements AutoCloseable {

    public static final String SERVICE = "payments-api";

    private final HttpServer server;
    private final ExecutorService requestThreads = Executors.newFixedThreadPool(4);
    private final PaymentProvider provider = new PaymentProvider();

    public PaymentsApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/authorize", new TracedHttpHandler(SERVICE, "POST /authorize", form -> {
            String providerCode = form.get("provider");
            long amount = Long.parseLong(form.get("amount"));
            try {
                String authorization = ChildSpans.trace("http.client", "POST provider /authorize", span -> {
                    span.setData("payment.provider", providerCode);
                    return provider.authorize(providerCode, amount);
                });
                log(SentryLogLevel.INFO, providerCode, "authorized");
                return new TracedHttpHandler.Response(200, authorization);
            } catch (AmountOverLimitException rejected) {
                // Odmowa dostawcy to poprawna odpowiedź tej usługi (HTTP 422), a nie jej błąd,
                // więc payments-api nie tworzy issue. Zostaje span ze statusem i log z wynikiem.
                log(SentryLogLevel.WARN, providerCode, "rejected_over_limit");
                return new TracedHttpHandler.Response(422, "AMOUNT_OVER_LIMIT");
            }
        }));
        server.setExecutor(requestThreads);
        server.start();
    }

    /** Adres z nazwą hosta {@code localhost}, zgodny z allowlistą propagacji w demo. */
    public URI baseUri() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    /** Ten sam serwer pod adresem IP. Adres nie pasuje do allowlisty zakotwiczonej na nazwie hosta. */
    public URI baseUriByIp() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void log(SentryLogLevel level, String providerCode, String outcome) {
        Sentry.logger().log(
                level,
                SentryLogParameters.create(SentryAttributes.of(
                        SentryAttribute.stringAttribute("payment.provider", providerCode),
                        SentryAttribute.stringAttribute("payment.outcome", outcome))),
                "Autoryzacja u %s: %s",
                providerCode,
                outcome
        );
    }

    @Override
    public void close() {
        server.stop(0);
        requestThreads.shutdownNow();
    }
}
