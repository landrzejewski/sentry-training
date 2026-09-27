package pl.training.sentry.module04;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Przebieg pipeline wydania jako lista wykonanych kroków i kontrola kontraktu między nimi.
 *
 * <p>Model jest celowo mały: pięć kroków, które decydują o tym, co zobaczy Sentry, i pięć
 * reguł. Nie jest adapterem do żadnego systemu CI i nie sprawdza danych w Sentry. Pokazuje, jakie
 * dowody pipeline musi zebrać, żeby release, artefakt, source bundle i deploy opisywały ten sam
 * build.</p>
 *
 * <p>Kontrakt nie wymaga uploadu po zamknięciu artefaktu. {@code sentry-maven-plugin} wysyła
 * source bundle w trakcie buildu i Sentry to akceptuje, bo dopasowuje bundle po UUID. Model
 * sprawdza więc to, co musi być prawdą w obu wariantach: wysłany bundle ma UUID z wdrożonego
 * artefaktu i trafił do Sentry przed wdrożeniem.</p>
 *
 * @param steps kroki w kolejności, w jakiej się zakończyły
 */
public record ReleasePipeline(List<Step> steps) {

    /** Krok pipeline z danymi, które zostawia jako dowód. */
    public sealed interface Step {

        /** Zamknięcie artefaktu: od tej chwili jego bajty i skrót się nie zmieniają. */
        record Seal(BuildArtifact artifact) implements Step {
        }

        /** Rejestracja release w Sentry, np. {@code sentry-cli releases new}. */
        record RegisterRelease(String release) implements Step {
        }

        /** Upload JVM source bundle, np. {@code sentry-cli debug-files upload --type jvm}. */
        record UploadSourceBundle(String bundleId) implements Step {
        }

        /** Wdrożenie artefaktu pobranego z repozytorium artefaktów. */
        record Deploy(BuildArtifact artifact) implements Step {
        }

        /** Rekord deploy w Sentry, np. {@code sentry-cli deploys new}. */
        record RecordDeploy(String release) implements Step {
        }
    }

    public ReleasePipeline {
        steps = List.copyOf(steps);
    }

    public static ReleasePipeline of(Step... steps) {
        return new ReleasePipeline(List.of(steps));
    }

    /** Naruszenia kontraktu. Pusta lista oznacza, że pipeline zebrał komplet spójnych dowodów. */
    public List<String> violations() {
        List<String> violations = new ArrayList<>();
        Optional<Step.Seal> seal = find(Step.Seal.class);
        Optional<Step.RegisterRelease> register = find(Step.RegisterRelease.class);
        Optional<Step.UploadSourceBundle> upload = find(Step.UploadSourceBundle.class);
        Optional<Step.Deploy> deploy = find(Step.Deploy.class);
        Optional<Step.RecordDeploy> record = find(Step.RecordDeploy.class);
        Map<String, Optional<? extends Step>> required = new LinkedHashMap<>();
        required.put("zamknięcie artefaktu", seal);
        required.put("rejestracja release", register);
        required.put("upload source bundle", upload);
        required.put("wdrożenie", deploy);
        required.put("rekord deploy", record);
        required.forEach((name, step) -> {
            if (step.isEmpty()) {
                violations.add("brak dowodu kroku: " + name);
            }
        });
        if (!violations.isEmpty()) {
            return violations;
        }

        // Kolejność: dane dla Sentry muszą istnieć, zanim nowy artefakt wyśle pierwszy event.
        if (isAfter(seal.get(), deploy.get())) {
            violations.add("wdrożono artefakt, zanim pipeline go zamknął");
        }
        if (isAfter(register.get(), deploy.get())) {
            violations.add("release zarejestrowano po wdrożeniu: pierwsze eventy utworzą go bez commitów i metadanych");
        }
        if (isAfter(upload.get(), deploy.get())) {
            violations.add("source bundle wysłano po wdrożeniu: eventy sprzed uploadu zostaną bez source context,"
                    + " Sentry nie przetworzy ich ponownie");
        }
        if (isAfter(deploy.get(), record.get())) {
            violations.add("rekord deploy powstał przed wdrożeniem: opisuje intencję, a nie potwierdzone wdrożenie");
        }

        // Jedna nazwa release we wszystkich narzędziach.
        String built = seal.get().artifact().buildInfo().getProperty("release");
        if (!register.get().release().equals(built)) {
            violations.add("CI zarejestrowało release " + register.get().release() + ", a artefakt (i SDK) używa " + built);
        }
        if (!record.get().release().equals(built)) {
            violations.add("rekord deploy wskazuje release " + record.get().release() + ", a artefakt " + built);
        }

        // Build once, deploy many: wdrożone bajty i upload pochodzą z zamkniętego artefaktu.
        BuildArtifact deployed = deploy.get().artifact();
        if (!deployed.digest().equals(seal.get().artifact().digest())) {
            violations.add("wdrożono inne bajty niż zamknięte (skrót " + shortDigest(deployed)
                    + " zamiast " + shortDigest(seal.get().artifact()) + "), np. po ponownym buildzie");
        }
        Optional<String> deployedBundle = deployed.sourceBundleId();
        if (deployedBundle.isEmpty()) {
            violations.add("wdrożony artefakt nie ma " + BuildArtifact.DEBUG_META + ": eventy nie wskażą żadnego source bundle");
        } else if (!deployedBundle.get().equals(upload.get().bundleId())) {
            violations.add("wysłany bundle " + upload.get().bundleId() + " nie pasuje do wdrożonego artefaktu,"
                    + " który wskazuje " + deployedBundle.get());
        }
        return violations;
    }

    private <T extends Step> Optional<T> find(Class<T> type) {
        return steps.stream().filter(type::isInstance).map(type::cast).findFirst();
    }

    /** Czy krok {@code first} zakończył się później niż {@code second}. */
    private boolean isAfter(Step first, Step second) {
        return steps.indexOf(first) > steps.indexOf(second);
    }

    private static String shortDigest(BuildArtifact artifact) {
        return artifact.digest().substring(0, 12);
    }
}
