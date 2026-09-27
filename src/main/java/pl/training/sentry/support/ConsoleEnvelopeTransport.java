package pl.training.sentry.support;

import io.sentry.Hint;
import io.sentry.SentryEnvelope;
import io.sentry.SentryEnvelopeItem;
import io.sentry.SentryOptions;
import io.sentry.hints.DiskFlushNotification;
import io.sentry.transport.ITransport;
import io.sentry.transport.RateLimiter;
import io.sentry.util.HintUtils;

import java.io.IOException;
import java.io.PrintStream;

/**
 * Transport szkoleniowy: wypisuje na konsolę każdy envelope, który SDK chce wysłać.
 *
 * <p>Transport to ostatni etap potoku SDK. Gdy {@code send} zostaje wywołane, event
 * przeszedł już przez scope, integracje, event processory, {@code beforeSend}
 * i sampling. Wydruk pokazuje więc dokładnie to, co trafiłoby do Sentry, a nie to,
 * co kod próbował wysłać.</p>
 *
 * <p>Z delegatem (tryb online) wydruk jest tylko podglądem, a envelope idzie dalej do
 * standardowego transportu HTTP. Bez delegata (tryb offline) nic nie opuszcza procesu.</p>
 *
 * <p>PRODUKCJA: to narzędzie do nauki i debugowania. W aplikacji produkcyjnej nie
 * podmienia się transportu i nie loguje pełnej treści eventów, bo mogą zawierać dane
 * osobowe.</p>
 */
public final class ConsoleEnvelopeTransport implements ITransport {

    /** Włącza wydruk pełnego JSON każdego itemu: {@code -Dsentry.demo.json=true}. */
    public static final String PRINT_JSON_PROPERTY = "sentry.demo.json";

    private final EnvelopeDecoder decoder;
    private final EnvelopeFormatter formatter = new EnvelopeFormatter();
    private final ITransport delegate;
    private final PrintStream out;
    private final boolean printJson;

    public ConsoleEnvelopeTransport(SentryOptions options, ITransport delegate, PrintStream out) {
        this.decoder = new EnvelopeDecoder(options.getSerializer());
        this.delegate = delegate;
        this.out = out;
        this.printJson = Boolean.getBoolean(PRINT_JSON_PROPERTY);
    }

    @Override
    public void send(SentryEnvelope envelope, Hint hint) throws IOException {
        for (SentryEnvelopeItem item : envelope.getItems()) {
            CapturedItem captured = decoder.decode(item);
            // Pusty raport klienta pojawia się przy zamykaniu SDK i niczego nie uczy.
            if (!isEmptyClientReport(captured)) {
                out.println(formatter.format(captured));
                if (printJson) {
                    out.println("      json        " + EnvelopeDecoder.json(item));
                }
            }
        }
        if (delegate != null) {
            delegate.send(envelope, hint);
        } else {
            confirmFlush(envelope, hint);
        }
    }

    /**
     * Potwierdza „zapis” envelope wątkowi, który na niego czeka.
     *
     * <p>{@code UncaughtExceptionHandlerIntegration} blokuje umierający wątek, dopóki transport
     * nie potwierdzi zapisu eventu, maksymalnie przez {@code flushTimeoutMillis} (domyślnie 15 s).
     * Transport HTTP potwierdza w wątku wysyłki po zapisie envelope w cache na dysku (bez
     * {@code cacheDirPath} ten zapis nic nie robi), jeszcze przed wysłaniem HTTP. Transport,
     * który nie potwierdza, zatrzymuje każdy nieobsłużony wyjątek na pełny timeout. W trybie
     * offline wydruk jest jedynym zapisem, więc potwierdzamy od razu.</p>
     */
    static void confirmFlush(SentryEnvelope envelope, Hint hint) {
        if (hint != null
                && HintUtils.getSentrySdkHint(hint) instanceof DiskFlushNotification notification
                && notification.isFlushable(envelope.getHeader().getEventId())) {
            notification.markFlushed();
        }
    }

    @Override
    public void flush(long timeoutMillis) {
        if (delegate != null) {
            delegate.flush(timeoutMillis);
        }
    }

    @Override
    public RateLimiter getRateLimiter() {
        // Bez delegata nie ma odpowiedzi serwera, więc nie ma też limitów narzuconych przez Sentry.
        return delegate == null ? null : delegate.getRateLimiter();
    }

    @Override
    public void close(boolean isRestarting) throws IOException {
        if (delegate != null) {
            delegate.close(isRestarting);
        }
    }

    @Override
    public void close() throws IOException {
        close(false);
    }

    private static boolean isEmptyClientReport(CapturedItem item) {
        return item instanceof CapturedItem.ClientReport(var report)
                && (report.getDiscardedEvents() == null || report.getDiscardedEvents().isEmpty());
    }
}
