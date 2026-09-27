package pl.training.sentry.module02.spring;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Frontend checkout-web: wysyła formularz płatności do {@link CheckoutApplication}.
 *
 * <p>Request wygląda jak prawdziwy ruch z przeglądarki i niesie wszystko, co zwykle trafia do
 * serwera: token sesji w {@code Authorization}, ciasteczka, własny nagłówek z tokenem,
 * {@code X-Forwarded-For} ustawiony przez klienta, e-mail w query string (link z kampanii) i body
 * z danymi karty. Scenariusz 8 sprawdza, co z tego trafia do Sentry.</p>
 */
public final class CheckoutWebClient {

    public static final String CUSTOMER_EMAIL = "anna.kowalska@example.com";
    public static final String CARD_NUMBER = "4111 1111 1111 1111";
    /** Adres z nagłówka, który klient może ustawić dowolnie: tu udaje adres z sieci biura. */
    public static final String SPOOFED_CLIENT_IP = "10.20.0.7";

    // HTTP/1.1, bo domyślny klient Javy dokleja nagłówki próby przejścia na HTTP/2 (Upgrade: h2c),
    // które tylko zaśmiecałyby wydruk.
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final URI checkoutUri;

    public CheckoutWebClient(int port) {
        this.checkoutUri = URI.create("http://localhost:" + port
                + "/api/checkout?coupon=WIOSNA26&email=anna.kowalska%40example.com");
    }

    /** Wysyła formularz płatności i zwraca status HTTP odpowiedzi. */
    public int submitPayment() throws IOException, InterruptedException {
        String body = """
                {"orderId":"ORD-8841","amount":12999,"email":"%s","cardNumber":"%s"}"""
                .formatted(CUSTOMER_EMAIL, CARD_NUMBER);
        HttpRequest request = HttpRequest.newBuilder(checkoutUri)
                .header("Content-Type", "application/json")
                .header("User-Agent", "checkout-web/5.2")
                .header("X-Request-Id", "req-5b1e")
                .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.c-7f3a9c.sig")
                .header("Cookie", "JSESSIONID=7D3F9A2C41; remember_me=anna.kowalska%40example.com")
                .header("X-Session-Token", "st_live_4f9c2a")
                .header("X-Forwarded-For", SPOOFED_CLIENT_IP)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
