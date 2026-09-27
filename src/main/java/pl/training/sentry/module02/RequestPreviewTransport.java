package pl.training.sentry.module02;

import io.sentry.Hint;
import io.sentry.ITransportFactory;
import io.sentry.SentryEnvelope;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Request;
import io.sentry.transport.ITransport;
import io.sentry.transport.RateLimiter;
import pl.training.sentry.support.CapturedItem;
import pl.training.sentry.support.EnvelopeDecoder;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Narzędzie demo modułu 2: dopisuje pod wydrukiem eventu sekcję {@code request} (URL, query,
 * nagłówki, cookies, body), której wspólny formatter konsoli nie pokazuje.
 *
 * <p>Nie jest częścią materiału merytorycznego. Moduł 2 dotyczy minimalizacji danych, więc
 * wydruk musi pokazać, które pola requestu faktycznie opuszczają proces. Dekorator owija
 * transport, więc widzi event po {@code beforeSend} i samplingu.</p>
 */
public final class RequestPreviewTransport implements ITransport {

    private static final String INDENT = "      ";

    private final ITransport delegate;
    private final EnvelopeDecoder decoder;
    private final PrintStream out;

    private RequestPreviewTransport(ITransport delegate, EnvelopeDecoder decoder, PrintStream out) {
        this.delegate = delegate;
        this.decoder = decoder;
        this.out = out;
    }

    /** Owija transport ustawiony wcześniej w opcjach (np. przez {@code TrainingSentry}). */
    public static void install(SentryOptions options) {
        ITransportFactory inner = options.getTransportFactory();
        options.setTransportFactory((sentryOptions, requestDetails) -> new RequestPreviewTransport(
                inner.create(sentryOptions, requestDetails),
                new EnvelopeDecoder(sentryOptions.getSerializer()),
                System.out));
    }

    @Override
    public void send(SentryEnvelope envelope, Hint hint) throws IOException {
        // Najpierw transport konsolowy wypisuje event, potem dopisujemy pod nim sekcję request.
        delegate.send(envelope, hint);
        for (CapturedItem item : decoder.decode(envelope)) {
            if (item instanceof CapturedItem.Event(SentryEvent event)) {
                out.print(describe(event.getRequest()));
            }
        }
    }

    static String describe(Request request) {
        StringBuilder text = new StringBuilder();
        if (request == null) {
            line(text, "request", "(brak)");
            return text.toString();
        }
        line(text, "request", request.getMethod() + " " + request.getUrl());
        if (request.getQueryString() != null) {
            line(text, "query", request.getQueryString());
        }
        if (request.getHeaders() != null && !request.getHeaders().isEmpty()) {
            Map<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            sorted.putAll(request.getHeaders());
            line(text, "headers", sorted.entrySet().stream()
                    .map(header -> header.getKey() + "=" + header.getValue())
                    .collect(Collectors.joining(", ")));
        }
        if (request.getCookies() != null) {
            line(text, "cookies", request.getCookies());
        }
        if (request.getData() != null) {
            line(text, "body", String.valueOf(request.getData()));
        }
        return text.toString();
    }

    private static void line(StringBuilder text, String label, String value) {
        text.append(INDENT).append(String.format("%-12s", label)).append(value).append(System.lineSeparator());
    }

    @Override
    public void flush(long timeoutMillis) {
        delegate.flush(timeoutMillis);
    }

    @Override
    public RateLimiter getRateLimiter() {
        return delegate.getRateLimiter();
    }

    @Override
    public void close(boolean isRestarting) throws IOException {
        delegate.close(isRestarting);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
