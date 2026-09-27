package pl.training.sentry.module05;

import io.sentry.SamplingContext;
import io.sentry.SentryOptions;

import java.util.Map;

/**
 * Sampler tracingu usług checkoutu: respektuje decyzję rodzica, a własne stawki stosuje tylko
 * do trace, które sam rozpoczyna.
 *
 * <p>Decyzję podejmuje origin trace, a kolejne usługi ją dziedziczą, więc trace jest zachowany
 * albo odrzucony w całości (scenariusz 6). Kolejność decyzji w SDK 8.54.0: jawna decyzja na
 * tworzonej transakcji, niepusty wynik {@code tracesSampler}, decyzja rodzica,
 * {@code tracesSampleRate}. Sampler, który zwraca stawkę mimo decyzji rodzica, wygrywa z nią.</p>
 *
 * <p>PRODUKCJA: stawki w {@link #CHECKOUT_RATES} są dobrane pod demo (1.0 daje powtarzalny
 * wynik). W produkcji wynikają z ruchu, quota i krytyczności operacji.</p>
 */
public final class CheckoutTracesSampler implements SentryOptions.TracesSamplerCallback {

    /** Stawki dla trace rozpoczynanych przez usługę, po dokładnej nazwie transakcji. */
    public static final Map<String, Double> CHECKOUT_RATES = Map.of(
            // Health check: duży wolumen, mała wartość trace.
            "GET /health", 0.0,
            // Krytyczny flow o małym wolumenie.
            "POST /api/orders", 1.0
    );

    private final boolean respectParentDecision;
    private final Map<String, Double> rates;

    /**
     * @param respectParentDecision {@code false} to PUŁAPKA: sampler nadpisuje decyzję origin
     *                              i może zachować fragment trace bez rodzica albo odrzucić
     *                              fragment trace zachowanego przez origin
     */
    public CheckoutTracesSampler(boolean respectParentDecision, Map<String, Double> rates) {
        this.respectParentDecision = respectParentDecision;
        this.rates = Map.copyOf(rates);
    }

    @Override
    public Double sample(SamplingContext context) {
        if (respectParentDecision && context.getTransactionContext().getParentSampled() != null) {
            // null oznacza „bez zdania”: SDK użyje decyzji rodzica z sentry-trace, więc cały
            // przepływ jest zachowany albo odrzucony razem.
            return null;
        }
        // Brak wpisu także daje null. Bez rodzica SDK przejdzie wtedy do tracesSampleRate, który
        // przy przeciążonym transporcie obniża backpressure. Niepusty wynik samplera go omija.
        return rates.get(context.getTransactionContext().getName());
    }
}
