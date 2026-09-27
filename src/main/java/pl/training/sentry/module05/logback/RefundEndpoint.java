package pl.training.sentry.module05.logback;

import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;
import io.sentry.SentryAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import pl.training.sentry.module05.logback.RefundService.RefundFailedException;

import java.util.Map;

/**
 * Granica operacji {@code POST /api/refunds} (w Spring Boot byłby to kontroler z filtrem): własne
 * scopes i trace requestu, MDC i jedyny raport błędu jako {@code log.error} z wyjątkiem.
 *
 * <p>Granica nie woła {@code captureException}: error event tworzy SentryAppender z wpisu ERROR
 * (scenariusze 8 i 10).
 * MDC ustawia i czyści ta sama granica, bo wątek obsługi requestu wraca do puli (scenariusz 9).</p>
 */
public final class RefundEndpoint {

    /** Request zwrotu: zamówienie, kanał (web, mobile) i identyfikator korelacji z nagłówka. */
    public record Request(String orderId, String channel, String correlationId, String customerEmail) {
    }

    /** Które pola trafiają do MDC. */
    public enum MdcFields {
        /** Pola techniczne: identyfikator korelacji, zamówienie, kanał. */
        TECHNICAL,
        /** PUŁAPKA: dodatkowo e-mail klienta, „żeby support widział w logach, kogo dotyczy zwrot”. */
        WITH_CUSTOMER_EMAIL
    }

    private static final Logger log = LoggerFactory.getLogger(RefundEndpoint.class);

    private final RefundService service;
    private final ErrorReporting reporting;
    private final MdcFields mdcFields;

    public RefundEndpoint(ErrorReporting reporting, MdcFields mdcFields, Map<String, Integer> gatewayTimeouts) {
        this.service = new RefundService(new RefundGateway(reporting, gatewayTimeouts), reporting);
        this.reporting = reporting;
        this.mdcFields = mdcFields;
    }

    /** Obsługa requestu. Zwraca kod HTTP: 202 przyjęty, 502 dostawca nie odpowiada. */
    public int handle(Request request) {
        // Świeże scopes requestu, jak w TracedHttpHandler: breadcrumbs z logów jednego requestu nie
        // trafią do eventu następnego.
        try (ISentryLifecycleToken requestScopes = Sentry.forkedRootScopes("refund").makeCurrent()) {
            // Tracing jest w tych scenariuszach wyłączony. continueTrace bez nagłówków i tak daje
            // requestowi własny trace ID, więc event i logi jednego zwrotu mają wspólny trace, inny
            // niż pozostałe zwroty.
            Sentry.continueTrace(null, null);
            // Atrybut scope trafia do każdego Structured Log tego requestu, także z Logback, ale nie
            // do tagów eventu. Tak identyfikator zamówienia jest w logach bez wysokiej kardynalności tagów.
            Sentry.setAttribute(SentryAttribute.stringAttribute("order.id", request.orderId()));
            putMdc(request);
            try {
                service.refund(request.orderId());
                return 202;
            } catch (RefundFailedException exception) {
                // Jedyny wpis ERROR z wyjątkiem dla tej awarii: z niego SentryAppender tworzy event.
                log.error("Zwrot zamówienia {} nieudany, klient dostaje 502", request.orderId(), exception);
                if (reporting == ErrorReporting.LOG_AND_CAPTURE) {
                    // PUŁAPKA: drugi raport tego samego obiektu wyjątku. Odrzuca go deduplikacja SDK.
                    Sentry.captureException(exception);
                }
                return 502;
            } finally {
                // Wątek wraca do puli: wartości z MDC nie mogą przejść do następnego requestu.
                MDC.clear();
            }
        }
    }

    private void putMdc(Request request) {
        // Nazwy kluczy są kontraktem: te same w każdej usłudze i we wzorcu logów konsoli.
        MDC.put("correlation_id", request.correlationId());
        MDC.put("order_id", request.orderId());
        MDC.put("channel", request.channel());
        if (mdcFields == MdcFields.WITH_CUSTOMER_EMAIL) {
            // PUŁAPKA: SentryAppender kopiuje do eventu całe MDC (klucze z contextTags jako tagi,
            // resztę do contexts „MDC”), niezależnie od sendDefaultPii. E-mail wyjdzie z aplikacji
            // z każdym eventem utworzonym z logu.
            MDC.put("customer_email", request.customerEmail());
        }
    }
}
