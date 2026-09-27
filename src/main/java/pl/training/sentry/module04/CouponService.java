package pl.training.sentry.module04;

import java.util.Map;

/**
 * Rabaty z kodów kuponów w usłudze orders-api: kod domenowy z błędem, bez Sentry.
 *
 * <p>Historia wydań, na której opierają się scenariusze: wersja {@code 5.4.0+184} wprowadziła
 * kampanię BLACK-WEEK i błąd, a {@code 5.4.1+185} miała go naprawić, lecz poprawka objęła tylko
 * część przypadków. W przykładzie kod jest jeden. Wersję procesu wyznacza artefakt, z którego
 * proces wystartował, i tylko release w evencie mówi Sentry, która to była wersja. Od niego zależy,
 * w którym release issue widziano po raz pierwszy i ostatni, oraz czy event po rozwiązaniu issue
 * w danym release jest regresją.</p>
 */
public final class CouponService {

    private static final Map<String, Integer> DISCOUNTS = Map.of(
            "BLACK-WEEK", 20,
            "WELCOME", 10
    );

    /** Rabat procentowy dla kodu kuponu wpisanego przez klienta. */
    public int discountPercent(String couponCode) {
        // Celowy błąd: wyszukiwanie rozróżnia wielkość liter. Klient, który wpisze kod z ulotki
        // małymi literami, dostaje wyjątek zamiast rabatu.
        Integer discount = DISCOUNTS.get(couponCode.strip());
        if (discount == null) {
            throw new IllegalArgumentException("Nieznany kod kuponu: " + couponCode);
        }
        return discount;
    }
}
