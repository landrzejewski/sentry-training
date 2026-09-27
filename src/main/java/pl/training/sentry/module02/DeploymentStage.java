package pl.training.sentry.module02;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Zamknięty słownik etapów wdrożenia checkout-api i ich wartości {@code environment} w Sentry.
 *
 * <p>Wartość {@code environment} powstaje w Sentry po pierwszym evencie i nie da się jej usunąć
 * (można ją tylko ukryć), a {@code production}, {@code Production} i {@code prod} to trzy różne
 * środowiska, bo Sentry rozróżnia wielkość liter. Dlatego do SDK trafia zawsze
 * stała z enumu, nigdy surowy tekst z konfiguracji wdrożenia.</p>
 */
public enum DeploymentStage {

    /**
     * Laptop developera. Celowo mapuje się na {@code development} zamiast wprowadzać osobną nazwę:
     * kontrakt i tak wyłącza SDK dla tego etapu ({@link SentrySettings}), a gdyby ktoś wymusił
     * wysyłkę, słownik środowisk w Sentry pozostanie zamknięty.
     */
    LOCAL("development"),
    DEVELOPMENT("development"),
    STAGING("staging"),
    PREVIEW("preview"),
    PRODUCTION("production");

    private final String sentryEnvironment;

    DeploymentStage(String sentryEnvironment) {
        this.sentryEnvironment = sentryEnvironment;
    }

    public String sentryEnvironment() {
        return sentryEnvironment;
    }

    /**
     * Odczytuje etap z konfiguracji wdrożenia, np. zmiennej {@code APP_STAGE}.
     *
     * <p>Wielkość liter nie ma znaczenia ({@code Production} daje {@link #PRODUCTION}), bo wartość
     * wysyłana do Sentry i tak pochodzi z enumu. Nieznana nazwa kończy start aplikacji błędem:
     * {@code prod} albo literówka {@code stagign} nie może po cichu utworzyć nowego środowiska
     * ani wyglądać jak produkcja.</p>
     */
    public static DeploymentStage fromConfig(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("brak APP_STAGE, dozwolone: " + allowedNames());
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(stage -> stage.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "nieznany APP_STAGE '" + value + "', dozwolone: " + allowedNames()));
    }

    private static String allowedNames() {
        return Arrays.stream(values())
                .map(stage -> stage.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
    }
}
