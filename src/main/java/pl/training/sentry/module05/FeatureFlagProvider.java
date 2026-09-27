package pl.training.sentry.module05;

import java.util.Map;
import java.util.Set;

/**
 * Dostawca flag funkcjonalnych (w produkcji np. LaunchDarkly, Unleash albo własna tabela).
 *
 * <p>Kod domenowy bez Sentry: odpowiada tylko, czy flaga jest włączona dla danego klienta.
 * Zapis wyniku do Sentry należy do {@link CheckoutFlags}.</p>
 *
 * @param enabledFor klienci objęci rolloutem, osobno dla każdej flagi
 */
public record FeatureFlagProvider(Map<String, Set<String>> enabledFor) {

    public static FeatureFlagProvider allDisabled() {
        return new FeatureFlagProvider(Map.of());
    }

    public boolean isEnabled(String flag, String customerId) {
        return enabledFor.getOrDefault(flag, Set.of()).contains(customerId);
    }
}
