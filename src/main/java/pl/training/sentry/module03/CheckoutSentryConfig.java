package pl.training.sentry.module03;

import io.sentry.SentryOptions;

import java.util.function.Consumer;

/**
 * Opcje SDK checkout-api, od których zależy triage: in-app frames, fingerprint i breadcrumbs.
 *
 * <p>{@link #TARGET} to konfiguracja docelowa. Metody {@code with...} zmieniają jeden element, żeby
 * scenariusz mógł porównać ją z typowym błędem konfiguracji. Rekord jest
 * {@code Consumer<SentryOptions>}, więc przyjmuje go zarówno {@code TrainingSentry.init}, jak
 * i {@code SentryTestSupport.start}.</p>
 *
 * @param inAppPrefix       prefiks klas aplikacji albo {@code null}, gdy aplikacja go nie ustawia
 * @param grouping          strategia fingerprintu dla błędów bramki płatności
 * @param breadcrumbHygiene czy włączyć {@link BreadcrumbHygiene} jako {@code beforeBreadcrumb}
 */
public record CheckoutSentryConfig(String inAppPrefix, PaymentGrouping.Strategy grouping, boolean breadcrumbHygiene)
        implements Consumer<SentryOptions> {

    /**
     * Prefiks klas aplikacji z kropką na końcu: obejmuje {@code pl.training.sentry.module03.*},
     * ale nie {@code pl.training.sentry.module03legacy.*}. {@code isInApp} w SDK porównuje
     * prefiks przez {@code String.startsWith}, bez żadnej interpretacji granic pakietów.
     */
    public static final String IN_APP_PACKAGE = "pl.training.sentry.module03.";

    public static final CheckoutSentryConfig TARGET =
            new CheckoutSentryConfig(IN_APP_PACKAGE, PaymentGrouping.Strategy.DEFAULT_PLUS_REASON, true);

    @Override
    public void accept(SentryOptions options) {
        // Java SDK nie ma domyślnych prefiksów in-app: bez tej linii żadna ramka nie dostaje
        // w evencie in_app=true i decyzja zostaje po stronie serwera.
        if (inAppPrefix != null) {
            options.addInAppInclude(inAppPrefix);
        }
        options.setBeforeSend(new PaymentGrouping(grouping));
        // Wartość domyślna SDK, ustawiona jawnie, bo scenariusz 5 od niej zależy.
        options.setMaxBreadcrumbs(100);
        options.setBeforeBreadcrumb(breadcrumbHygiene ? new BreadcrumbHygiene() : null);
    }

    public CheckoutSentryConfig withInAppPrefix(String prefix) {
        return new CheckoutSentryConfig(prefix, grouping, breadcrumbHygiene);
    }

    public CheckoutSentryConfig withGrouping(PaymentGrouping.Strategy strategy) {
        return new CheckoutSentryConfig(inAppPrefix, strategy, breadcrumbHygiene);
    }

    public CheckoutSentryConfig withoutBreadcrumbHygiene() {
        return new CheckoutSentryConfig(inAppPrefix, grouping, false);
    }
}
