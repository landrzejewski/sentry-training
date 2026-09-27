package pl.training.sentry.module05.spring;

import io.sentry.spring7.SentryTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executory dla {@code @Async}: jeden z {@code SentryTaskDecorator}, drugi bez niego.
 *
 * <p>Scopes Sentry są związane z wątkiem, więc zadanie {@code @Async} nie dostaje kontekstu
 * requestu samo. Przenosi go {@code SentryTaskDecorator} ustawiony na
 * {@code ThreadPoolTaskExecutor}: rozwidla scopes wątku zlecającego (tak jak
 * {@code SentryWrapper}) i aktywuje je na czas zadania (scenariusz 7).</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncExecutors {

    public static final String LOYALTY = "loyaltyExecutor";
    public static final String REPORTS = "reportExecutor";

    @Bean(LOYALTY)
    public ThreadPoolTaskExecutor loyaltyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setThreadNamePrefix("loyalty-");
        executor.setTaskDecorator(new SentryTaskDecorator());
        return executor;
    }

    /**
     * PUŁAPKA: executor dodany później przez inny zespół, bez dekoratora. Kod działa poprawnie,
     * a jedynym objawem jest brak spanów i logi z obcym trace ID.
     */
    @Bean(REPORTS)
    public ThreadPoolTaskExecutor reportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setThreadNamePrefix("reports-");
        return executor;
    }
}
