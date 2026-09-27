package pl.training.sentry.module10;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Kolejka potwierdzeń płatności czekających na zaksięgowanie. Kod domenowy bez Sentry.
 *
 * <p>Scenariusz 2 pokazuje na niej różnicę między counterem (ile wpłynęło) a gauge (ile czeka
 * teraz).</p>
 */
public final class PaymentQueue {

    private final Deque<String> pending = new ArrayDeque<>();

    public void enqueue(String orderId) {
        pending.addLast(orderId);
    }

    /** Konsument księguje najwyżej {@code max} potwierdzeń i zwraca, ile faktycznie przetworzył. */
    public int consume(int max) {
        int processed = 0;
        while (processed < max && !pending.isEmpty()) {
            pending.removeFirst();
            processed++;
        }
        return processed;
    }

    public int depth() {
        return pending.size();
    }
}
