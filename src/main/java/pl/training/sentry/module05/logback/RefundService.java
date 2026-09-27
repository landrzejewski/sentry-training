package pl.training.sentry.module05.logback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.training.sentry.module05.logback.RefundGateway.GatewayTimeoutException;

/**
 * Zwrot płatności za zamówienie. Kod domenowy: loguje przez SLF4J i nie zna Sentry.
 *
 * <p>Wpisy INFO opisują przebieg operacji. Przy {@link ErrorReporting#SINGLE_OWNER} serwis nie
 * loguje błędu bramki, tylko opakowuje go z {@code cause}: decyzję, czy to błąd dla użytkownika,
 * podejmuje granica.</p>
 */
public final class RefundService {

    /** Wyjątek serwisu: zwrotu nie da się teraz zlecić. */
    public static final class RefundFailedException extends RuntimeException {
        public RefundFailedException(String message) {
            super(message);
        }

        public RefundFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundGateway gateway;
    private final ErrorReporting reporting;

    public RefundService(RefundGateway gateway, ErrorReporting reporting) {
        this.gateway = gateway;
        this.reporting = reporting;
    }

    public String refund(String orderId) {
        log.info("Zwrot zamówienia {}: zlecenie u dostawcy", orderId);
        try {
            String refundId = gateway.refund(orderId);
            log.info("Zwrot zamówienia {} przyjęty przez dostawcę jako {}", orderId, refundId);
            return refundId;
        } catch (GatewayTimeoutException exception) {
            if (reporting == ErrorReporting.LOG_ON_EVERY_LAYER) {
                // PUŁAPKA: ERROR bez wyjątku to event bez stack trace, a nowy wyjątek bez cause
                // zrywa łańcuch, po którym SDK rozpoznaje ten sam błąd.
                log.error("Zwrot zamówienia {} nieudany: {}", orderId, exception.getMessage());
                throw new RefundFailedException("Zwrot zamówienia " + orderId + " nieudany: " + exception.getMessage());
            }
            throw new RefundFailedException("Zwrot zamówienia " + orderId + " nieudany", exception);
        }
    }
}
