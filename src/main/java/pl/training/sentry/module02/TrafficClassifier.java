package pl.training.sentry.module02;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;

/**
 * Zaufana warstwa aplikacji, która ustala pochodzenie ruchu: klient, pracownik albo sonda syntetyczna.
 *
 * <p>Klasyfikacja opiera się na zweryfikowanej tożsamości, a nie na adresie IP: w backendzie Java
 * adres, który Sentry widzi przy przyjęciu eventu, to zwykle serwer, proxy albo NAT, a nagłówek
 * {@code X-Forwarded-For} może ustawić każdy.
 * Kod domenowy, bez Sentry: wynik trafia do tagu {@code traffic.origin} w {@link CheckoutEndpoint}.</p>
 *
 * <p>PUŁAPKA: klasyfikacja po nagłówku, który klient ustawia sam (np. {@code X-Traffic: internal}
 * albo surowy {@code X-Forwarded-For} z adresem biura), pozwala każdemu ukryć swoje błędy przed
 * zespołem. Tu sonda musi znać sekret, a pracownik mieć token z katalogu pracowników.</p>
 */
public final class TrafficClassifier {

    /** Wartości tagu {@code traffic.origin}: mały, zamknięty słownik. */
    public enum Origin {
        EXTERNAL, INTERNAL, SYNTHETIC;

        public String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Nagłówek, w którym sonda syntetyczna przesyła wspólny sekret. */
    public static final String SYNTHETIC_CHECK_HEADER = "X-Synthetic-Check";

    private final Set<String> employeeTokens;
    private final byte[] probeSecret;

    /**
     * @param employeeTokens tokeny sesji, które katalog pracowników potwierdził jako należące do pracowników
     * @param probeSecret    sekret skonfigurowany w narzędziu do monitoringu syntetycznego
     */
    public TrafficClassifier(Set<String> employeeTokens, String probeSecret) {
        this.employeeTokens = Set.copyOf(employeeTokens);
        this.probeSecret = probeSecret.getBytes(StandardCharsets.UTF_8);
    }

    public Origin classify(IncomingRequest request) {
        String probe = request.header(SYNTHETIC_CHECK_HEADER);
        // Porównanie w stałym czasie, jak dla każdego sekretu.
        if (probe != null && MessageDigest.isEqual(probeSecret, probe.getBytes(StandardCharsets.UTF_8))) {
            return Origin.SYNTHETIC;
        }
        String authorization = request.header("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")
                && employeeTokens.contains(authorization.substring("Bearer ".length()))) {
            return Origin.INTERNAL;
        }
        return Origin.EXTERNAL;
    }
}
