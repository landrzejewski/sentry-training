package pl.training.sentry.module04;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Artefakt wdrożeniowy usługi orders-api w postaci listy plików, tak jak widzi je JAR.
 *
 * <p>Build zapisuje do artefaktu dwa pliki metadanych:</p>
 * <ul>
 *   <li>{@value #BUILD_INFO}: release i rewizja wyliczone raz w CI (w Maven np. przez filtrowanie
 *   zasobów), opcjonalnie dist; aplikacja czyta je przy starcie, zamiast liczyć release sama;</li>
 *   <li>{@value #DEBUG_META}: identyfikator source bundle w formacie, który zapisuje
 *   {@code sentry-maven-plugin} ({@code io.sentry.bundle-ids=<UUID>}), a SDK czyta przy starcie;
 *   build bez source bundle (np. z forka bez tokenu, {@code skipSourceBundle}) nie ma w modelu
 *   tego pliku.</li>
 * </ul>
 *
 * <p>Klasa nie zna Sentry SDK. Skrót SHA-256 ({@link #digest()}) obejmuje wszystkie pliki, więc
 * każda zmiana po zamknięciu artefaktu, także nowy UUID po ponownym buildzie tej samej rewizji,
 * daje inny skrót.</p>
 *
 * @param files ścieżka w artefakcie i treść pliku; bytecode jest tu tylko opisem, bo liczy się
 *              wyłącznie to, czy bajty są te same
 */
public record BuildArtifact(Map<String, String> files) {

    public static final String BUILD_INFO = "build-info.properties";
    public static final String DEBUG_META = "sentry-debug-meta.properties";

    public BuildArtifact {
        // Kolejność plików nie może wpływać na skrót, stąd posortowana, niemodyfikowalna kopia.
        files = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(files));
    }

    /**
     * To, co robi job budujący: kompiluje rewizję i zapisuje metadane.
     *
     * @param release        wartość zapisana w artefakcie bez walidacji, bo ręczny build może
     *                       wpisać cokolwiek; waliduje ją aplikacja przy starcie
     * @param dist           dystrybucja albo {@code null}
     * @param sourceBundleId UUID source bundle albo {@code null}, gdy build nie przygotował bundle
     */
    public static BuildArtifact build(String release, String revision, String dist, UUID sourceBundleId) {
        Map<String, String> files = new TreeMap<>();
        files.put("pl/training/sentry/module04/CouponService.class", "bytecode z rewizji " + revision);
        StringBuilder buildInfo = new StringBuilder()
                .append("release=").append(escape(release)).append('\n')
                .append("revision=").append(revision).append('\n');
        if (dist != null) {
            buildInfo.append("dist=").append(dist).append('\n');
        }
        files.put(BUILD_INFO, buildInfo.toString());
        if (sourceBundleId != null) {
            files.put(DEBUG_META, "io.sentry.bundle-ids=" + sourceBundleId + "\n");
        }
        return new BuildArtifact(files);
    }

    /** Wynik tej samej rewizji zbudowanej ponownie: plugin generuje przy każdym buildzie nowy UUID. */
    public BuildArtifact rebuild() {
        return build(buildInfo().getProperty("release"), revision(), buildInfo().getProperty("dist"),
                sourceBundleId().isPresent() ? UUID.randomUUID() : null);
    }

    public Properties buildInfo() {
        return properties(BUILD_INFO).orElseThrow(() ->
                new IllegalStateException("Artefakt nie ma " + BUILD_INFO + ": build nie zapisał release"));
    }

    public String revision() {
        return buildInfo().getProperty("revision");
    }

    /** Zawartość {@value #DEBUG_META} albo pusty wynik, gdy build nie przygotował source bundle. */
    public Optional<Properties> debugMeta() {
        return properties(DEBUG_META);
    }

    public Optional<String> sourceBundleId() {
        return debugMeta().map(meta -> meta.getProperty("io.sentry.bundle-ids"));
    }

    /** Skrót SHA-256 wszystkich plików: dowód, że wdrożono dokładnie te bajty, które zamknięto. */
    public String digest() {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            files.forEach((path, content) -> {
                sha256.update(path.getBytes(StandardCharsets.UTF_8));
                sha256.update((byte) 0);
                sha256.update(content.getBytes(StandardCharsets.UTF_8));
                sha256.update((byte) 0);
            });
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Optional<Properties> properties(String path) {
        String content = files.get(path);
        if (content == null) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(content));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return Optional.of(properties);
    }

    /** Zapis w formacie properties, żeby tabulator albo nowa linia w release przetrwały odczyt. */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
