package pl.training.sentry.module02;

import io.sentry.Sentry;
import io.sentry.protocol.SentryId;

/**
 * Kontrolny event wysyłany po wdrożeniu na staging, zanim konfiguracja trafi na produkcję.
 *
 * <p>Scenariusz 1 wysyła go po poprawnej konfiguracji. Testy jednostkowe sprawdzają kontrakt bez
 * sieci, ale dopiero event, który faktycznie dotarł do Sentry, potwierdza projekt (DSN), environment,
 * release, tagi, stack trace z ramkami in-app i scrubbing.</p>
 *
 * <p>PRODUKCJA: kontrolny event wysyła się na stagingu albo w oknie wdrożenia, z tagiem, po którym
 * alerting go pomija. Issue kontrolne warto rozwiązać zaraz po sprawdzeniu.</p>
 */
public final class DeploymentSmokeCheck {

    private DeploymentSmokeCheck() {
    }

    public static SentryId sendControlEvent() {
        // Prawdziwy wyjątek, a nie captureMessage: event ma stack trace, więc przy okazji widać,
        // czy ramki pakietu aplikacji są oznaczone jako in-app.
        IllegalStateException control = new IllegalStateException(
                "Kontrolny błąd po wdrożeniu " + SentrySettings.SERVICE_NAME);
        return Sentry.captureException(control, scope -> scope.setTag("deployment.check", "true"));
    }
}
