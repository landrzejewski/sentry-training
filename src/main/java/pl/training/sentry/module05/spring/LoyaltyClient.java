package pl.training.sentry.module05.spring;

import io.sentry.Sentry;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import pl.training.sentry.module05.ChildSpans;
import pl.training.sentry.module05.Latency;

import java.util.concurrent.CompletableFuture;

/**
 * Pobranie punktów programu lojalnościowego, asynchronicznie na {@code loyaltyExecutor}.
 *
 * <p>Executor ma {@code SentryTaskDecorator} ({@link AsyncExecutors}), więc zadanie widzi scopes
 * requestu: {@code Sentry.getSpan()} zwraca transakcję {@code http.server}, span powstaje w jej
 * drzewie, a log ma trace ID requestu.</p>
 */
@Component
public class LoyaltyClient {

    @Async(AsyncExecutors.LOYALTY)
    public CompletableFuture<Integer> pointsFor(String customerId) {
        int points = ChildSpans.trace("http.client", "GET loyalty /points", span -> {
            Latency.pause(60);
            return 1_250;
        });
        Sentry.logger().info("Pobrano punkty lojalnościowe: %d", points);
        return CompletableFuture.completedFuture(points);
    }
}
