package pl.training.sentry.module01;

/**
 * Zamówienie w usłudze checkout-api: cała domena przykładów modułu 1.
 *
 * <p>Dwa tryby dostawy mają różne wymagania. {@code COURIER} potrzebuje adresu dostawy,
 * a {@code PICKUP_POINT} identyfikatora punktu odbioru i poprawnie nie ma adresu. Ta różnica
 * jest źródłem błędu diagnozowanego w scenariuszach.</p>
 *
 * @param customer klient albo {@code null} przy zakupie bez logowania (gość)
 */
public record Order(
        String id,
        Customer customer,
        DeliveryMode deliveryMode,
        Address deliveryAddress,
        String pickupPointId
) {

    public enum DeliveryMode {
        COURIER,
        PICKUP_POINT
    }

    public record Address(String street, String postalCode) {
    }

    /**
     * @param id    techniczny, pseudonimowy identyfikator klienta, zatwierdzony do telemetrii
     * @param email dane osobowe potrzebne do powiadomień o wysyłce, nie do telemetrii
     */
    public record Customer(String id, String email) {
    }

    public static Order courier(String id, Customer customer, Address deliveryAddress) {
        return new Order(id, customer, DeliveryMode.COURIER, deliveryAddress, null);
    }

    public static Order pickupPoint(String id, Customer customer, String pickupPointId) {
        return new Order(id, customer, DeliveryMode.PICKUP_POINT, null, pickupPointId);
    }
}
