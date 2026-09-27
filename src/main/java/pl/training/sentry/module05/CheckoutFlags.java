package pl.training.sentry.module05;

import io.sentry.Sentry;

/**
 * Ewaluacja flag funkcjonalnych checkoutu z zapisem wyniku w Sentry.
 *
 * <p>Wynik flagi to kontekst porównania wariantów: czy błąd albo wolny request dotyczy tylko
 * nowej ścieżki. {@code Sentry.addFeatureFlag} zapisuje wynik w dwóch miejscach:</p>
 * <ul>
 *   <li>w isolation scope, skąd trafia do contextu {@code flags} każdego error eventu
 *   wysłanego w tym requeście (w issue: sekcja Feature Flags);</li>
 *   <li>w aktywnym spanie, jako dana {@code flag.evaluation.<nazwa>}, po której filtruje się
 *   i porównuje spany w Trace Explorer.</li>
 * </ul>
 *
 * <p>PUŁAPKA: do spanu trafia tylko ewaluacja wykonana, gdy span jest aktywny. Flaga sprawdzona
 * przed startem transakcji (np. w filtrze przed instrumentacją) będzie w error events, ale nie
 * w danych transakcji. Span przyjmuje najwyżej 10 flag, scope pamięta ostatnie
 * {@code maxFeatureFlags} ewaluacji (domyślnie 100).</p>
 */
public final class CheckoutFlags {

    public static final String NEW_PAYMENT_FLOW = "checkout.new-payment-flow";

    private final FeatureFlagProvider provider;

    public CheckoutFlags(FeatureFlagProvider provider) {
        this.provider = provider;
    }

    public boolean isEnabled(String flag, String customerId) {
        boolean enabled = provider.isEnabled(flag, customerId);
        // Zapisujemy także false: bez niego eventy wariantu kontrolnego nie miałyby flagi i nie
        // dałoby się porównać obu grup.
        Sentry.addFeatureFlag(flag, enabled);
        return enabled;
    }
}
