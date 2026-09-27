package pl.training.sentry.module07;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Surowy materiał z REST API Sentry: issue, jeden jego event i lista issues do briefingu.
 *
 * <p>W trybie online pochodzi z {@link SentryApiClient}. W trybie offline z plików
 * {@code src/main/resources/module07/*.json}: to odpowiedzi lokalnego Sentry dla eventu
 * wysłanego przez {@link DiscountCodeEndpoint}, zapisane bez zmian w strukturze, skrócone
 * o pola, których przykłady nie czytają. Lista issues ma dodatkowo kilka ręcznie
 * przygotowanych pozycji (regresja, różne priorytety, przypisany właściciel, issue z innego
 * projektu, starsza migawka tego samego issue), żeby filtr i ranking briefingu miały co
 * porządkować.</p>
 *
 * @param origin skąd pochodzą dane; demo wypisuje to wprost, żeby nikt nie wziął pliku za produkcję
 */
public record IssueMaterial(Object issue, Object event, String origin) {

    public static IssueMaterial sample() {
        return new IssueMaterial(Json.parse(resource("issue.json")), Json.parse(resource("event.json")),
                "plik src/main/resources/module07 (zapis odpowiedzi lokalnego Sentry)");
    }

    public static List<?> sampleIssues() {
        return Json.parse(resource("issues.json")) instanceof List<?> list ? list : List.of();
    }

    public String issueRef() {
        return Json.text(issue, "shortId") + " (id " + Json.text(issue, "id") + ")";
    }

    private static String resource(String name) {
        try (InputStream in = IssueMaterial.class.getResourceAsStream("/module07/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Brak zasobu /module07/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
