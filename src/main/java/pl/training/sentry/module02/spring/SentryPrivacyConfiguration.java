package pl.training.sentry.module02.spring;

import io.sentry.SentryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.training.sentry.module02.EventDataScrubber;
import pl.training.sentry.module02.FilteringBeforeSend;
import pl.training.sentry.module02.SentryOptionsConfigurer;

/**
 * {@code beforeSend} aplikacji Spring Boot jako bean typu {@code SentryOptions.BeforeSendCallback}.
 *
 * <p>Starter sam znajduje ten bean i ustawia go w opcjach. Musi być dokładnie jeden: przy dwóch
 * beanach tego typu kontekst nie wystartuje (test scenariusza 7
 * {@code twoBeforeSendCallbackBeansPreventStartup}). To ta sama klasa co w czystej Javie, więc reguły
 * filtrowania i scrubbingu mają jedne testy.</p>
 *
 * <p>Scenariusz 8 uruchamia aplikację raz bez tej konfiguracji, a raz z nią, żeby pokazać, co
 * {@code send-default-pii=false} usuwa samo, a czego nie.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SentryPrivacyConfiguration {

    @Bean
    SentryOptions.BeforeSendCallback checkoutBeforeSend() {
        return new FilteringBeforeSend(SentryOptionsConfigurer.SYNTHETIC_CHECK_PATHS, new EventDataScrubber());
    }
}
