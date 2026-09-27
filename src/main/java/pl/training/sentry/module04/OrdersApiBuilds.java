package pl.training.sentry.module04;

import java.util.UUID;

/**
 * Artefakty orders-api zbudowane przez CI, na których działają scenariusze i testy.
 *
 * <p>Release każdego builda wylicza CI jeden raz ({@link ReleaseName#forBuild}) i zapisuje w
 * artefakcie. UUID source bundle generuje build przy każdym uruchomieniu. Tutaj są stałe, bo
 * reprezentują dwa konkretne, już zamknięte buildy, a {@code README.md} pakietu
 * pokazuje, jak wysłać do lokalnego Sentry bundle o UUID {@link #SOURCE_BUNDLE_185}.</p>
 */
public final class OrdersApiBuilds {

    public static final String COMPONENT = "orders-api";

    public static final String REVISION_184 = "3b9e1d07c2a84f6e9b15d0c7a1f4e2b8c6d3a590";
    public static final String REVISION_185 = "8f24c7a4f735cb1dd0a4c2159a9db15fc6b7c728";

    public static final ReleaseName RELEASE_184 = ReleaseName.forBuild(COMPONENT, "5.4.0", "184");
    public static final ReleaseName RELEASE_185 = ReleaseName.forBuild(COMPONENT, "5.4.1", "185");

    public static final UUID SOURCE_BUNDLE_184 = UUID.fromString("0b6d2c52-4f3e-4d7a-9e21-6c8f3a1d5b47");
    public static final UUID SOURCE_BUNDLE_185 = UUID.fromString("1740e7df-8b9e-4ebd-a6f6-19f2f02ad9ad");

    private OrdersApiBuilds() {
    }

    /** Wersja, która wprowadziła błąd kuponu. */
    public static BuildArtifact build184() {
        return BuildArtifact.build(RELEASE_184.value(), REVISION_184, null, SOURCE_BUNDLE_184);
    }

    /** Wersja z niepełną poprawką. */
    public static BuildArtifact build185() {
        return BuildArtifact.build(RELEASE_185.value(), REVISION_185, null, SOURCE_BUNDLE_185);
    }

    /** Ta sama rewizja zbudowana bez source bundle, np. w jobie bez dostępu do tokenu Sentry. */
    public static BuildArtifact build185WithoutSourceBundle() {
        return BuildArtifact.build(RELEASE_185.value(), REVISION_185, null, null);
    }
}
