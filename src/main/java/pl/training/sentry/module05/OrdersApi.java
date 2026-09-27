package pl.training.sentry.module05;

import com.sun.net.httpserver.HttpServer;
import pl.training.sentry.module05.CheckoutService.ChecksMode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Usługa orders-api: {@code POST /api/orders} (checkout) i {@code GET /health}.
 *
 * <p>Serwer HTTP z JDK ({@code com.sun.net.httpserver}), bez frameworka. Instrumentację
 * granicy requestu zapewnia {@link TracedHttpHandler}, a wywołanie payments-api
 * {@link TracingHttpClient}. Konfiguracja z konstruktora odpowiada wdrożonej wersji usługi:
 * adres payments-api, flagi i sposób wykonania sprawdzeń.</p>
 */
public final class OrdersApi implements AutoCloseable {

    public static final String SERVICE = "orders-api";

    private final HttpServer server;
    private final ExecutorService requestThreads = Executors.newFixedThreadPool(4);
    private final ExecutorService checkWorkers = Executors.newFixedThreadPool(4);

    public OrdersApi(URI paymentsBaseUri, FeatureFlagProvider flagProvider, ChecksMode checksMode) throws IOException {
        CheckoutService checkout = new CheckoutService(
                paymentsBaseUri, new CheckoutFlags(flagProvider), checksMode, checkWorkers);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/orders", new TracedHttpHandler(SERVICE, "POST /api/orders",
                form -> checkout.checkout(form.get("orderId"), form.get("customerId"))));
        server.createContext("/health", new TracedHttpHandler(SERVICE, "GET /health",
                form -> new TracedHttpHandler.Response(200, "UP")));
        server.setExecutor(requestThreads);
        server.start();
    }

    /** Adres usługi z nazwą hosta {@code localhost}, np. {@code http://localhost:50123}. */
    public URI baseUri() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
        server.stop(0);
        requestThreads.shutdownNow();
        checkWorkers.shutdownNow();
    }
}
