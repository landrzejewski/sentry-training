package pl.training.sentry.module05.spring;

import pl.training.sentry.module05.Latency;

import java.util.Map;

/**
 * Odczyt statusu zamówienia (symulowana baza danych). Kod domenowy bez Sentry.
 *
 * <p>Zamówienie {@code ORD-7003} pochodzi z migracji starego systemu i ma status, którego
 * nowy kod nie zna: tak wygląda błąd danych, który ujawnia się dopiero dla jednego rekordu.</p>
 */
public final class OrderStatusRepository {

    public enum Status { PAID, SHIPPED, DELIVERED }

    public record OrderStatus(String orderId, String customerId, Status status) {
    }

    private static final Map<String, String[]> ROWS = Map.of(
            "ORD-7001", new String[]{"c-7f3a9c", "SHIPPED"},
            "ORD-7002", new String[]{"c-19bd42", "PAID"},
            "ORD-7003", new String[]{"c-5e21aa", "LEGACY_HOLD"}
    );

    public OrderStatus find(String orderId) {
        Latency.pause(40);
        String[] row = ROWS.get(orderId);
        if (row == null) {
            throw new IllegalArgumentException("Nie ma zamówienia " + orderId);
        }
        // Status spoza enuma kończy się IllegalArgumentException z valueOf.
        return new OrderStatus(orderId, row[0], Status.valueOf(row[1]));
    }
}
