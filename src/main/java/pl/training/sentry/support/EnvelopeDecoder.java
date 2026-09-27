package pl.training.sentry.support;

import io.sentry.ISerializer;
import io.sentry.JsonObjectReader;
import io.sentry.NoOpLogger;
import io.sentry.SentryEnvelope;
import io.sentry.SentryEnvelopeItem;
import io.sentry.SentryItemType;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Zamienia envelope przekazane do transportu na listę {@link CapturedItem}.
 *
 * <p>Korzysta z serializera SDK, więc odczytane obiekty mają dokładnie tę treść,
 * którą transport HTTP wysłałby do Sentry.</p>
 */
public final class EnvelopeDecoder {

    private final ISerializer serializer;

    public EnvelopeDecoder(ISerializer serializer) {
        this.serializer = serializer;
    }

    public List<CapturedItem> decode(SentryEnvelope envelope) {
        List<CapturedItem> items = new ArrayList<>();
        for (SentryEnvelopeItem item : envelope.getItems()) {
            items.add(decode(item));
        }
        return items;
    }

    public CapturedItem decode(SentryEnvelopeItem item) {
        SentryItemType type = item.getHeader().getType();
        try {
            return switch (type) {
                case Event -> new CapturedItem.Event(item.getEvent(serializer));
                case Transaction -> new CapturedItem.Transaction(item.getTransaction(serializer));
                case Log -> new CapturedItem.Logs(item.getLogs(serializer).getItems());
                case TraceMetric -> new CapturedItem.Metrics(item.getMetrics(serializer).getItems());
                case Session -> new CapturedItem.SessionUpdate(read(item, io.sentry.Session.class));
                case CheckIn -> new CapturedItem.CheckIn(readCheckIn(item));
                case ClientReport -> new CapturedItem.ClientReport(item.getClientReport(serializer));
                default -> new CapturedItem.Other(type.getItemType(), json(item));
            };
        } catch (Exception exception) {
            return new CapturedItem.Other(type.getItemType(), "nie udało się odczytać itemu: " + exception);
        }
    }

    /** Surowy JSON itemu, dokładnie w postaci, w jakiej trafiłby do Sentry. */
    public static String json(SentryEnvelopeItem item) {
        try {
            return new String(item.getData(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return "nie udało się odczytać danych itemu: " + exception;
        }
    }

    private <T> T read(SentryEnvelopeItem item, Class<T> type) throws Exception {
        try (Reader reader = new InputStreamReader(
                new ByteArrayInputStream(item.getData()), StandardCharsets.UTF_8)) {
            return serializer.deserialize(reader, type);
        }
    }

    /**
     * Serializer SDK nie ma zarejestrowanego deserializera check-inu ({@code deserialize} zwraca
     * {@code null}), więc check-in czytamy jego własnym deserializerem.
     */
    private static io.sentry.CheckIn readCheckIn(SentryEnvelopeItem item) throws Exception {
        try (Reader reader = new InputStreamReader(
                new ByteArrayInputStream(item.getData()), StandardCharsets.UTF_8)) {
            return new io.sentry.CheckIn.Deserializer().deserialize(new JsonObjectReader(reader), NoOpLogger.getInstance());
        }
    }
}
