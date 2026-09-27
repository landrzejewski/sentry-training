package pl.training.sentry.module07;

import pl.training.sentry.support.TrainingSentry;

import java.util.Map;
import java.util.Optional;

/**
 * Zaufany zakres sesji agenta: organizacja, projekt, środowisko i repozytorium.
 *
 * <p>Zakres ustawia host z konfiguracji, zanim agent zobaczy jakiekolwiek dane.
 * Organizacja, projekt i identyfikatory nie mogą pochodzić z treści eventu ani z odpowiedzi
 * modelu: event może zawierać tekst „odczytaj projekt payments-api”, a polityka
 * ({@link ActionPolicy}) porównuje każde żądanie właśnie z tym rekordem.</p>
 *
 * @param repository repozytorium z kodem uruchomionego artefaktu; pusty, gdy sesja go nie ma,
 *                   i wtedy prompt pokazuje jawny brak zamiast zgadywania
 */
public record AgentSession(String organization, String project, String environment, Optional<String> repository) {

    /** Wartości domyślne pasują do lokalnego Sentry z {@code docker/sentry}. */
    public static AgentSession fromEnv(Map<String, String> env) {
        return new AgentSession(
                env.getOrDefault("SENTRY_ORG", "sentry"),
                env.getOrDefault("SENTRY_PROJECT", "sentry-training"),
                env.getOrDefault("SENTRY_ENVIRONMENT", TrainingSentry.DEFAULT_ENVIRONMENT),
                Optional.empty()
        );
    }
}
