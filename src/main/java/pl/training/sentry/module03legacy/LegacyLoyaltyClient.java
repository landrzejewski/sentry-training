package pl.training.sentry.module03legacy;

/**
 * Stary klient programu lojalnościowego: biblioteka innego zespołu, dołączana do checkout-api jako jar.
 *
 * <p>Należy do przykładów modułu 3 (scenariusz 2 w {@code pl.training.sentry.module03}).
 * Ma osobny pakiet o nazwie zaczynającej się tak samo jak pakiet aplikacji, żeby pokazać pułapkę
 * prefiksu in-app: {@code pl.training.sentry.module03} bez kropki na końcu oznacza jako kod
 * aplikacji także ramki tej biblioteki.</p>
 */
public final class LegacyLoyaltyClient {

    /** Rabat w procentach należny posiadaczowi karty. */
    public int discountPercent(String cardNumber) {
        int points = pointsFor(parseCardNumber(cardNumber));
        return Math.min(points / 100, 15);
    }

    private int parseCardNumber(String cardNumber) {
        // Biblioteka zna tylko format kart sprzed migracji: LOY-<cyfry>. Nowe karty LOY2-<hex>
        // kończą się tutaj NumberFormatException z częścią numeru karty w komunikacie.
        return Integer.parseInt(cardNumber.substring(cardNumber.indexOf('-') + 1));
    }

    private int pointsFor(int cardId) {
        return cardId % 1000 * 3;
    }
}
