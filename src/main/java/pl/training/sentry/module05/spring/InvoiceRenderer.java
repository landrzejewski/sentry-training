package pl.training.sentry.module05.spring;

import io.sentry.Sentry;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import pl.training.sentry.module05.ChildSpans;
import pl.training.sentry.module05.Latency;

import java.util.concurrent.CompletableFuture;

/**
 * Generowanie faktury PDF, asynchronicznie na {@code reportExecutor}.
 *
 * <p>Kod jest taki sam jak w {@link LoyaltyClient}. Różni się tylko executor: ten nie ma
 * {@code SentryTaskDecorator}, więc zadanie działa w scopes wątku puli, bez transakcji
 * requestu. {@code Sentry.getSpan()} zwraca null, span nie powstaje, a log dostaje trace ID
 * z innego kontekstu. W waterfall requestu zostaje szeroka transakcja bez dzieci, czyli duży
 * self time bez wyjaśnienia.</p>
 */
@Component
public class InvoiceRenderer {

    @Async(AsyncExecutors.REPORTS)
    public CompletableFuture<String> render(String orderId) {
        String pdf = ChildSpans.trace("function", "render invoice pdf", span -> {
            Latency.pause(120);
            return "FV/" + orderId + ".pdf";
        });
        Sentry.logger().info("Wygenerowano fakturę %s", pdf);
        return CompletableFuture.completedFuture(pdf);
    }
}
