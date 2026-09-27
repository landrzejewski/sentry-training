package pl.training.sentry.module05.logback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Klient bramki zwrotów dostawcy płatności (symulacja). Kod domenowy: loguje przez SLF4J
 * i nie zna Sentry.
 *
 * <p>Bramka odpowiada albo przekracza limit czasu. Klient ponawia żądanie do
 * {@value #MAX_ATTEMPTS} razy i każde ponowienie zapisuje jako WARN: to stan operacyjny, który
 * pomaga zrozumieć błąd, ale sam nie jest błędem, bo kolejna próba może się udać.</p>
 */
public final class RefundGateway {

    /** Wyjątek bramki: dostawca nie odpowiedział w żadnej z prób. */
    public static final class GatewayTimeoutException extends RuntimeException {
        public GatewayTimeoutException(String message) {
            super(message);
        }
    }

    public static final int MAX_ATTEMPTS = 3;
    public static final int TIMEOUT_MS = 2_000;
    /** Liczba przekroczeń czasu, po której zwrot nie uda się w żadnej próbie. */
    public static final int ALWAYS = MAX_ATTEMPTS;

    private static final Logger log = LoggerFactory.getLogger(RefundGateway.class);

    private final ErrorReporting reporting;
    private final Map<String, Integer> timeoutsBeforeSuccess;

    /**
     * @param timeoutsBeforeSuccess stan dostawcy w scenariuszu: ile razy bramka nie odpowie na zwrot
     *                              danego zamówienia, zanim go przyjmie ({@link #ALWAYS}: wcale)
     */
    public RefundGateway(ErrorReporting reporting, Map<String, Integer> timeoutsBeforeSuccess) {
        this.reporting = reporting;
        this.timeoutsBeforeSuccess = timeoutsBeforeSuccess;
    }

    public String refund(String orderId) {
        int timeouts = timeoutsBeforeSuccess.getOrDefault(orderId, 0);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            log.debug("POST /refunds zamówienie {}, próba {}", orderId, attempt);
            if (attempt > timeouts) {
                return "RF-" + orderId;
            }
            if (attempt < MAX_ATTEMPTS) {
                log.warn("Bramka zwrotów nie odpowiedziała w {} ms (próba {} z {}), ponawiam",
                        TIMEOUT_MS, attempt, MAX_ATTEMPTS);
            }
        }
        GatewayTimeoutException failure = new GatewayTimeoutException(
                "Bramka zwrotów nie odpowiedziała w " + TIMEOUT_MS + " ms w " + MAX_ATTEMPTS + " próbach");
        if (reporting == ErrorReporting.LOG_ON_EVERY_LAYER) {
            // PUŁAPKA: warstwa, która nie wie, czy błąd jest końcowy, loguje go jako ERROR.
            log.error("Bramka zwrotów nie odpowiada", failure);
        }
        throw failure;
    }
}
