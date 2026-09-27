package pl.training.sentry.module01;

import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import io.sentry.protocol.User;

import java.util.Map;

/**
 * Pokazuje, jak opisać request checkout dla Sentry: tag, context, user i breadcrumb, każda
 * informacja w mechanizmie zgodnym z jej rolą.
 *
 * <p>{@link #describeRequest} to wersja docelowa, a {@link #describeRequestCarelessly} zbiera
 * typowe błędy z przeglądów kodu (wysoka kardynalność tagów, dane osobowe w user i breadcrumb),
 * żeby scenariusz 3 mógł pokazać obie wersje obok siebie.</p>
 *
 * <p>Wszystkie wywołania piszą do isolation scope (w SDK 8.x {@code defaultScopeType} ma wartość
 * {@code ScopeType.ISOLATION}). Dane żyją więc do końca requestu i trafiają do każdego eventu
 * wysłanego w jego trakcie, także z innej klasy. Dlatego request musi mieć własny isolation
 * scope ({@link CheckoutEndpoint#handle}).</p>
 */
public final class CheckoutTelemetry {

    private CheckoutTelemetry() {
    }

    public static void describeRequest(Order order, String checkoutVariant) {
        // Tag: wymiar o kilku stałych wartościach. Po nim filtruje się issues i porównuje
        // rozkład wartości, np. czy błąd dotyczy tylko jednego trybu dostawy.
        Sentry.setTag("delivery.mode", order.deliveryMode().name());
        Sentry.setTag("checkout.variant", checkoutVariant);

        // Context: stan tej jednej operacji, potrzebny przy analizie konkretnego eventu.
        // Identyfikator zamówienia jest tutaj, a nie w tagu: każde zamówienie ma inny, więc jako
        // wymiar agregacji nic nie wnosi. Statyczne API nie ma setContexts, stąd configureScope,
        // które bez podania typu scope także pisze do isolation scope.
        Sentry.configureScope(scope -> scope.setContexts("shipping", Map.of(
                "order_id", order.id(),
                "has_delivery_address", order.deliveryAddress() != null,
                "has_pickup_point", order.pickupPointId() != null
        )));

        // User: zatwierdzony identyfikator techniczny. Wystarcza do korelacji eventów i do
        // policzenia dotkniętych użytkowników. Gość nie ma tożsamości, więc user zostaje pusty.
        if (order.customer() != null) {
            User user = new User();
            user.setId(order.customer().id());
            Sentry.setUser(user);
        }

        // Breadcrumb: krok poprzedzający ewentualny błąd. Tylko dane potrzebne do odtworzenia
        // przebiegu, bez payloadu zamówienia.
        Breadcrumb breadcrumb = new Breadcrumb("Rozpoczęto generowanie etykiety");
        breadcrumb.setCategory("checkout.label");
        breadcrumb.setData("order_id", order.id());
        breadcrumb.setData("delivery_mode", order.deliveryMode().name());
        Sentry.addBreadcrumb(breadcrumb);
    }

    public static void describeRequestCarelessly(Order order, String checkoutVariant) {
        Sentry.setTag("delivery.mode", order.deliveryMode().name());
        Sentry.setTag("checkout.variant", checkoutVariant);

        // PUŁAPKA: identyfikator zamówienia jako tag „na wszelki wypadek”. Każde zamówienie to
        // nowa wartość, więc rozkład tagu niczego nie pokazuje. Tag ma sens tylko przy
        // uzasadnionym przypadku wyszukiwania (np. support szuka eventów konkretnego zamówienia).
        // Przy okazji zniknął stan zamówienia: bez contextu nie widać, czego brakowało.
        Sentry.setTag("order.id", order.id());

        // PUŁAPKA: e-mail jako tożsamość użytkownika. To dane osobowe wysłane bez zatwierdzonej
        // podstawy, a do liczenia użytkowników wystarczy identyfikator techniczny.
        if (order.customer() != null) {
            User user = new User();
            user.setEmail(order.customer().email());
            Sentry.setUser(user);
        }

        // PUŁAPKA: cały obiekt w breadcrumb. toString() rekordu wypisuje wszystkie pola, także
        // e-mail klienta. Breadcrumbs nie są miejscem na payloady ani dane osobowe.
        Sentry.addBreadcrumb("Generowanie etykiety dla " + order, "checkout.label");
    }
}
