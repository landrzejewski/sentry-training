package pl.training.sentry.module02.spring;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import pl.training.sentry.module02.RequestPreviewTransport;
import pl.training.sentry.support.TrainingSentry;

/**
 * Podpięcie trybu offline i online szkolenia do SDK, które inicjalizuje starter.
 *
 * <p>Starter najpierw wiąże właściwości {@code sentry.*} z opcjami, potem wywołuje wszystkie beany
 * {@code Sentry.OptionsConfiguration} (swój ma najwyższy priorytet), a na końcu {@code Sentry.init}.
 * Ten bean ustawia więc DSN, environment, release, tag {@code training.module} i transport
 * konsolowy tak samo jak {@code TrainingSentry.init} w czystej Javie.</p>
 *
 * <p>PUŁAPKA: wartość ustawiona w beanie wygrywa z właściwością. {@code sentry.environment=staging}
 * (w pliku albo jako {@code SENTRY_ENVIRONMENT} w zmiennych przekazanych aplikacji) nic tu nie zmieni,
 * bo bean nadpisze environment po powiązaniu właściwości (test
 * {@code optionsConfigurationBeanOverridesSentryProperties}).
 * W produkcji każda opcja powinna mieć jedno źródło: albo właściwość, albo kod.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SentryTrainingConfiguration {

    @Bean
    @Order(0)
    Sentry.OptionsConfiguration<SentryOptions> trainingSentryOptions() {
        return options -> {
            TrainingSentry.applyTrainingDefaults(options, "module02");
            RequestPreviewTransport.install(options);
        };
    }
}
