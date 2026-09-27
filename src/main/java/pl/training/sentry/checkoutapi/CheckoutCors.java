package pl.training.sentry.checkoutapi;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Pokazuje politykę CORS, która przepuszcza nagłówki trace z frontendu z innego originu
 * ({@code localhost:4200} wywołuje {@code localhost:8095}), i pułapkę polityki, która ich nie zna.
 *
 * <p>Gdy przeglądarka dokleja do requestu {@code sentry-trace} i {@code baggage}, preflight
 * {@code OPTIONS} musi te nagłówki dopuścić, inaczej przeglądarka nie wyśle właściwego requestu.
 * Obie ścieżki obsługuje ten sam kontroler, różnią się wyłącznie polityką CORS.</p>
 */
@Configuration(proxyBeanMethods = false)
public class CheckoutCors implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Dokładne originy i dokładna lista nagłówków zamiast "*": nagłówki trace dopuszczamy
        // tylko dla własnego frontendu.
        registry.addMapping("/api/**")
                .allowedOrigins(CheckoutApiApplication.FRONTEND_ORIGINS)
                .allowedMethods("GET", "POST")
                .allowedHeaders("content-type", "sentry-trace", "baggage");

        // PUŁAPKA: polityka sprzed wdrożenia Sentry w przeglądarce (albo gateway z własną
        // allowlistą nagłówków). Adres pasuje do tracePropagationTargets, więc przeglądarka
        // dokleja nagłówki trace, a preflight ich nie dopuszcza. Skutek jest gorszy niż
        // rozerwany trace: przeglądarka blokuje cały request, zanim dotrze on do kontrolera.
        // Spring Framework 7 odpowiada na taki preflight statusem 200, ale w
        // Access-Control-Allow-Headers zwraca tylko content-type, więc w logach backendu
        // wszystko wygląda poprawnie. W Sentry z backendu jest wtedy tylko transakcja
        // OPTIONS w osobnym trace (preflight nie niesie nagłówków trace), a POST nie ma wcale.
        registry.addMapping("/legacy-api/**")
                .allowedOrigins(CheckoutApiApplication.FRONTEND_ORIGINS)
                .allowedMethods("GET", "POST")
                .allowedHeaders("content-type");
    }
}
