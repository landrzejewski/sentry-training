package pl.training.sentry.module03;

import pl.training.sentry.module03.PaymentGatewayException.FailureReason;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Klient HTTP bramki płatności: jedna próba autoryzacji, bez ponowień.
 *
 * <p>Klasyfikuje każde niepowodzenie do {@link FailureReason} i zachowuje oryginalny wyjątek
 * w {@code cause}. Timeout ma więc w evencie dwa poziomy: {@link PaymentGatewayException}
 * z przyczyną domenową i {@code HttpTimeoutException} z ramkami JDK.</p>
 *
 * <p>Klasa nie zna Sentry. O każdym zakończonym wywołaniu informuje {@link HttpCallListener}, tak
 * jak klient HTTP informuje interceptory. Breadcrumbs z tych informacji tworzy warstwa
 * aplikacji ({@link HttpCallBreadcrumbs}).</p>
 */
public final class PaymentGatewayClient implements CheckoutService.Payments, AutoCloseable {

    /** Obserwator wywołań HTTP. {@code statusCode} jest {@code null}, gdy odpowiedź nie nadeszła. */
    @FunctionalInterface
    public interface HttpCallListener {

        HttpCallListener NONE = (method, url, statusCode) -> {
        };

        void onCompleted(String method, String url, Integer statusCode);
    }

    private static final Pattern DECISION = Pattern.compile("\"decision\"\\s*:\\s*\"(\\w+)\"");

    private final HttpClient http = HttpClient.newHttpClient();
    private final URI baseUri;
    private final Duration timeout;
    private final int maxStatusPolls;
    private final Duration pollInterval;
    private final String accessToken;
    private final HttpCallListener listener;

    /**
     * @param timeout        limit czasu jednego wywołania
     * @param maxStatusPolls ile razy odpytać o status, zanim płatność PENDING uznamy za nierozstrzygniętą
     * @param pollInterval   odstęp między odpytaniami o status
     * @param accessToken    token bramki; endpoint statusu przyjmuje go tylko w query string
     */
    public PaymentGatewayClient(URI baseUri, Duration timeout, int maxStatusPolls, Duration pollInterval,
                                String accessToken, HttpCallListener listener) {
        this.baseUri = baseUri;
        this.timeout = timeout;
        this.maxStatusPolls = maxStatusPolls;
        this.pollInterval = pollInterval;
        this.accessToken = accessToken;
        this.listener = listener == null ? HttpCallListener.NONE : listener;
    }

    /** Autoryzuje płatność i zwraca decyzję bramki. Dla PENDING odpytuje o status, aż decyzja zapadnie. */
    @Override
    public String authorize(Order order) {
        String decision = call("POST", "payments/" + order.id());
        int polls = 0;
        while ("PENDING".equals(decision)) {
            String statusCall = "payments/" + order.id() + "/status";
            if (polls == maxStatusPolls) {
                throw new PaymentGatewayException(FailureReason.NO_DECISION, "GET /" + statusCall,
                        "Brak decyzji bramki po " + polls + " odpytaniach o status: GET /" + statusCall, null);
            }
            pause("GET /" + statusCall);
            polls++;
            decision = call("GET", statusCall + "?access_token=" + accessToken);
        }
        return decision;
    }

    private String call(String method, String pathAndQuery) {
        URI uri = baseUri.resolve(pathAndQuery);
        // Typowy komunikat klienta HTTP: co się stało i przy którym wywołaniu, ze ścieżką zawierającą
        // identyfikator zamówienia. Query string pomijamy, bo niesie token.
        String target = method + " " + uri.getPath();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException timeoutException) {
            listener.onCompleted(method, uri.toString(), null);
            throw new PaymentGatewayException(FailureReason.TIMEOUT, target,
                    "Bramka płatności nie odpowiedziała w " + timeout.toMillis() + " ms: " + target, timeoutException);
        } catch (IOException ioException) {
            listener.onCompleted(method, uri.toString(), null);
            throw new PaymentGatewayException(FailureReason.CONNECTION_FAILED, target,
                    "Połączenie z bramką płatności nie powiodło się: " + target, ioException);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PaymentGatewayException(FailureReason.CONNECTION_FAILED, target,
                    "Przerwano oczekiwanie na bramkę płatności: " + target, interrupted);
        }
        listener.onCompleted(method, uri.toString(), response.statusCode());

        if (response.statusCode() != 200) {
            // Jedno miejsce zgłoszenia dla wszystkich kodów HTTP: 503 i 429 mają identyczny
            // stack trace, a różnią się tylko przyczyną i komunikatem.
            throw new PaymentGatewayException(FailureReason.fromHttpStatus(response.statusCode()), target,
                    "Bramka płatności zwróciła HTTP " + response.statusCode() + ": " + target, null);
        }
        Matcher decision = DECISION.matcher(response.body());
        if (!decision.find()) {
            throw new PaymentGatewayException(FailureReason.INVALID_RESPONSE, target,
                    "Odpowiedź bramki bez pola decision: " + target, null);
        }
        return decision.group(1);
    }

    private void pause(String target) {
        try {
            Thread.sleep(pollInterval);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PaymentGatewayException(FailureReason.CONNECTION_FAILED, target,
                    "Przerwano odpytywanie bramki płatności: " + target, interrupted);
        }
    }

    @Override
    public void close() {
        http.close();
    }
}
