package pl.training.sentry.module04;

import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.DebugImage;
import io.sentry.protocol.SentryStackFrame;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.training.sentry.module04.ReleasePipeline.Step;
import pl.training.sentry.support.SentryTestSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.training.sentry.module04.OrdersApiBuilds.RELEASE_184;
import static pl.training.sentry.module04.OrdersApiBuilds.RELEASE_185;
import static pl.training.sentry.module04.OrdersApiBuilds.REVISION_184;
import static pl.training.sentry.module04.OrdersApiBuilds.REVISION_185;
import static pl.training.sentry.module04.OrdersApiBuilds.SOURCE_BUNDLE_184;
import static pl.training.sentry.module04.OrdersApiBuilds.SOURCE_BUNDLE_185;

/**
 * Scenariusze {@link Module04Demo} sprawdzone na treści eventów, które SDK przekazało do transportu.
 *
 * <p>Każda klasa zagnieżdżona odpowiada jednemu scenariuszowi. Testy uruchamiają prawdziwe SDK
 * z transportem w pamięci (albo prawdziwy kontekst Spring Boot ze starterem Sentry), więc asercje
 * dotyczą release, dist i debug_meta po przejściu przez cały potok SDK.</p>
 */
@ResourceLock("sentry-global-state")
class Module04ScenariosTest {

    private final CouponEndpoint coupons = new CouponEndpoint();

    @Nested
    class Scenario1ReleaseNameRules {

        @Test
        void rejectsValuesThatSentryRejectsAsRelease() {
            List<String> rejected = List.of("latest", "LATEST", "Latest", ".", "..", "orders-api/5.4.1",
                    "orders-api\\5.4.1", "orders-api@5.4.1\t185", "orders-api@5.4.1\n", "orders-api@5.4.1\f",
                    " latest ", "x".repeat(201), "   ", "");
            for (String candidate : rejected) {
                assertTrue(ReleaseName.violation(candidate).isPresent(), candidate);
                assertThrows(IllegalArgumentException.class, () -> new ReleaseName(candidate));
            }
        }

        @Test
        void acceptsLimitLengthShaAndBareNumberButOnlyConventionHasComponent() {
            assertTrue(ReleaseName.violation("x".repeat(200)).isEmpty());
            assertEquals(Optional.of("orders-api"), RELEASE_185.component());
            assertEquals("orders-api@5.4.1+185", RELEASE_185.value());
            // Poprawne dla Sentry, ale bez prefiksu usługi: kolizja z innym projektem w organizacji.
            assertEquals(Optional.empty(), new ReleaseName(REVISION_185).component());
            assertEquals(Optional.empty(), new ReleaseName("5.4.1").component());
        }

        @Test
        void sdkSendsInvalidReleaseWithoutAnyValidation() {
            try (var telemetry = SentryTestSupport.start(options -> options.setRelease("latest"))) {
                coupons.applyCoupon("ORD-1", "black-week");

                assertEquals("latest", telemetry.singleEvent().getRelease());
            }
        }

        @Test
        void distDoesNotSeparateTwoBuildsSharingOneReleaseName() {
            List<SentryEvent> events = new ArrayList<>();
            for (var build : List.of(
                    BuildArtifact.build("orders-api@5.4.1", REVISION_184, "185", SOURCE_BUNDLE_184),
                    BuildArtifact.build("orders-api@5.4.1", REVISION_185, "186", SOURCE_BUNDLE_185))) {
                try (var telemetry = SentryTestSupport.start(OrdersApiStartup.sentryConfiguration(build, Optional.empty()))) {
                    coupons.applyCoupon("ORD-1", "black-week");
                    events.add(telemetry.singleEvent());
                }
            }

            assertEquals(events.get(0).getRelease(), events.get(1).getRelease());
            assertEquals(List.of("185", "186"), events.stream().map(SentryEvent::getDist).toList());
        }
    }

    @Nested
    class Scenario2ReleaseFromArtifact {

        @Test
        void releaseComesFromArtifactWrittenByBuild() {
            try (var telemetry = SentryTestSupport.start(
                    OrdersApiStartup.sentryConfiguration(OrdersApiBuilds.build185(), Optional.empty()))) {
                assertEquals(CouponEndpoint.NO_DISCOUNT, coupons.applyCoupon("ORD-1", "black-week"));

                SentryEvent event = telemetry.singleEvent();
                assertEquals(RELEASE_185.value(), event.getRelease());
                assertNull(event.getDist());
            }
        }

        @Test
        void externalConfigurationLetsRuntimePropertyOverrideReleaseFromCode() {
            withSystemProperty("sentry.release", RELEASE_184.value(), () -> {
                try (var telemetry = SentryTestSupport.start(
                        OrdersApiStartup.withExternalConfiguration(OrdersApiBuilds.build185()))) {
                    coupons.applyCoupon("ORD-1", "black-week");

                    assertEquals(RELEASE_184.value(), telemetry.singleEvent().getRelease());
                }
            });
        }

        @Test
        void withoutExternalConfigurationRuntimePropertyIsIgnoredBySdk() {
            withSystemProperty("sentry.release", RELEASE_184.value(), () -> {
                // Pominięta kontrola startowa, żeby pokazać samo zachowanie SDK: bez konfiguracji
                // zewnętrznej właściwość sentry.release nie ma wpływu na release.
                try (var telemetry = SentryTestSupport.start(
                        OrdersApiStartup.sentryConfiguration(OrdersApiBuilds.build185(), Optional.empty()))) {
                    coupons.applyCoupon("ORD-1", "black-week");

                    assertEquals(RELEASE_185.value(), telemetry.singleEvent().getRelease());
                }
            });
        }

        @Test
        void startupCheckStopsProcessWhenRuntimeOverrideDiffersFromArtifact() {
            BuildArtifact build185 = OrdersApiBuilds.build185();

            assertThrows(IllegalStateException.class,
                    () -> OrdersApiStartup.sentryConfiguration(build185, Optional.of(RELEASE_184.value())));
            // Ta sama wartość w manifeście nie jest konfliktem.
            OrdersApiStartup.sentryConfiguration(build185, Optional.of(RELEASE_185.value()));
        }

        @Test
        void startupCheckReadsSystemProperty() {
            withSystemProperty("sentry.release", RELEASE_184.value(), () ->
                    assertThrows(IllegalStateException.class,
                            () -> OrdersApiStartup.sentryConfiguration(OrdersApiBuilds.build185())));
        }

        @Test
        void startupCheckRejectsInvalidReleaseInArtifact() {
            BuildArtifact manualBuild = BuildArtifact.build("latest", REVISION_185, null, SOURCE_BUNDLE_185);

            assertThrows(IllegalStateException.class,
                    () -> OrdersApiStartup.sentryConfiguration(manualBuild, Optional.empty()));
        }

        @Test
        void validationInsideInitCallbackIsSwallowedAndSdkStartsWithPreviousRelease() {
            BuildArtifact manualBuild = BuildArtifact.build("latest", REVISION_185, null, SOURCE_BUNDLE_185);

            try (var telemetry = SentryTestSupport.start(OrdersApiStartup.validatingInsideInit(manualBuild))) {
                assertTrue(Sentry.isEnabled());
                coupons.applyCoupon("ORD-1", "black-week");

                // Release ustawiony przed callbackiem (w testach TEST_RELEASE, w demo release szkoleniowy).
                assertEquals(SentryTestSupport.TEST_RELEASE, telemetry.singleEvent().getRelease());
            }
        }
    }

    @Nested
    class Scenario3SpringBootGitCommitAsRelease {

        @Test
        void withoutSentryReleaseStarterUsesBareCommitIdFromGitProperties() {
            assertEquals(REVISION_185, releaseOfSpringEvent(null));
        }

        @Test
        void explicitSentryReleaseMatchesNameRegisteredByCi() {
            assertEquals(OrdersApiSpringApp.CI_RELEASE, releaseOfSpringEvent(OrdersApiSpringApp.CI_RELEASE));
        }

        @Test
        void closingSpringContextClosesSdk() {
            try (ConfigurableApplicationContext context = OrdersApiSpringApp.start(null, CapturingSentry.class)) {
                assertTrue(Sentry.isEnabled());
            }
            assertFalse(Sentry.isEnabled());
        }

        private String releaseOfSpringEvent(String sentryRelease) {
            CapturingSentry.EVENTS.clear();
            try (ConfigurableApplicationContext context = OrdersApiSpringApp.start(sentryRelease, CapturingSentry.class)) {
                context.getBean(CouponEndpoint.class).applyCoupon("ORD-1", "black-week");
            }
            assertEquals(1, CapturingSentry.EVENTS.size());
            return CapturingSentry.EVENTS.getFirst().getRelease();
        }
    }

    @Nested
    class Scenario4RegressionAfterRelease {

        @Test
        void eachEventCarriesReleaseOfProcessThatSentItWhileStackTraceStaysTheSame() {
            List<SentryEvent> events = new ArrayList<>();
            List<Consumer<SentryOptions>> processes = List.of(
                    OrdersApiStartup.sentryConfiguration(OrdersApiBuilds.build184(), Optional.empty()),
                    OrdersApiStartup.sentryConfiguration(OrdersApiBuilds.build185(), Optional.empty()));
            for (Consumer<SentryOptions> process : processes) {
                try (var telemetry = SentryTestSupport.start(process)) {
                    coupons.applyCoupon("ORD-1", "black-week");
                    events.add(telemetry.singleEvent());
                }
            }

            assertEquals(List.of(RELEASE_184.value(), RELEASE_185.value()),
                    events.stream().map(SentryEvent::getRelease).toList());
            assertEquals(frames(events.get(0)), frames(events.get(1)));
        }

        @Test
        void uppercaseCouponWorksAndSendsNothing() {
            try (var telemetry = SentryTestSupport.start(
                    OrdersApiStartup.sentryConfiguration(OrdersApiBuilds.build185(), Optional.empty()))) {
                assertEquals(20, coupons.applyCoupon("ORD-1", "BLACK-WEEK"));
                assertTrue(telemetry.events().isEmpty());
            }
        }
    }

    @Nested
    class Scenario5SourceBundleInEvent {

        @Test
        void bundleIdFromArtifactLandsInDebugMetaAsJvmImage() {
            SentryEvent event = eventFrom(OrdersApiStartup.sentryConfiguration(OrdersApiBuilds.build185(), Optional.empty()));

            DebugImage image = event.getDebugMeta().getImages().getFirst();
            assertEquals(DebugImage.JVM, image.getType());
            assertEquals(SOURCE_BUNDLE_185.toString(), image.getDebugId());
        }

        @Test
        void hardcodedBundleIdMakesSdkSkipTheFileFromCurrentBuild() {
            SentryEvent event = eventFrom(OrdersApiStartup.withHardcodedBundleId(
                    OrdersApiBuilds.build185(), SOURCE_BUNDLE_184.toString()));

            assertEquals(List.of(SOURCE_BUNDLE_184.toString()), debugIds(event));
        }

        @Test
        void buildWithoutSourceBundleSendsEventWithoutDebugMeta() {
            SentryEvent event = eventFrom(OrdersApiStartup.sentryConfiguration(
                    OrdersApiBuilds.build185WithoutSourceBundle(), Optional.empty()));

            assertNull(event.getDebugMeta());
        }

        @Test
        void applicationFramesAreMarkedInAppByPackagePrefix() {
            SentryEvent event = eventFrom(OrdersApiStartup.sentryConfiguration(OrdersApiBuilds.build185(), Optional.empty()));

            SentryStackFrame thrower = event.getExceptions().getLast().getStacktrace().getFrames().getLast();
            assertEquals(CouponService.class.getName(), thrower.getModule());
            assertEquals("CouponService.java", thrower.getFilename());
            assertEquals(Boolean.TRUE, thrower.isInApp());
        }

        private SentryEvent eventFrom(Consumer<SentryOptions> configuration) {
            try (var telemetry = SentryTestSupport.start(configuration)) {
                coupons.applyCoupon("ORD-1", "black-week");
                return telemetry.singleEvent();
            }
        }

        private List<String> debugIds(SentryEvent event) {
            return event.getDebugMeta().getImages().stream().map(DebugImage::getDebugId).toList();
        }
    }

    @Nested
    class Scenario6PipelineContract {

        private final BuildArtifact sealed = OrdersApiBuilds.build185();
        private final String bundle = SOURCE_BUNDLE_185.toString();

        @Test
        void consistentPipelineHasNoViolations() {
            assertEquals(List.of(), ReleasePipeline.of(
                    new Step.Seal(sealed),
                    new Step.RegisterRelease(RELEASE_185.value()),
                    new Step.UploadSourceBundle(bundle),
                    new Step.Deploy(sealed),
                    new Step.RecordDeploy(RELEASE_185.value())).violations());
        }

        @Test
        void rebuildInDeployJobChangesDigestAndBundleId() {
            BuildArtifact rebuilt = sealed.rebuild();

            assertEquals(sealed.revision(), rebuilt.revision());
            assertNotEquals(sealed.digest(), rebuilt.digest());
            List<String> violations = ReleasePipeline.of(
                    new Step.Seal(sealed),
                    new Step.RegisterRelease(RELEASE_185.value()),
                    new Step.UploadSourceBundle(bundle),
                    new Step.Deploy(rebuilt),
                    new Step.RecordDeploy(RELEASE_185.value())).violations();
            assertEquals(2, violations.size(), violations.toString());
        }

        @Test
        void wrongOrderAndBareShaAreReportedSeparately() {
            List<String> violations = ReleasePipeline.of(
                    new Step.Seal(sealed),
                    new Step.RegisterRelease(REVISION_185),
                    new Step.RecordDeploy(REVISION_185),
                    new Step.Deploy(sealed),
                    new Step.UploadSourceBundle(bundle)).violations();

            assertEquals(4, violations.size(), violations.toString());
        }

        @Test
        void missingStepIsReported() {
            List<String> violations = ReleasePipeline.of(
                    new Step.Seal(sealed),
                    new Step.RegisterRelease(RELEASE_185.value()),
                    new Step.Deploy(sealed),
                    new Step.RecordDeploy(RELEASE_185.value())).violations();

            assertEquals(List.of("brak dowodu kroku: upload source bundle"), violations);
        }

        @Test
        void digestCoversEveryFileAndIgnoresInsertionOrder() {
            Map<String, String> files = sealed.files();
            BuildArtifact reordered = new BuildArtifact(new java.util.LinkedHashMap<>(files).reversed());

            assertEquals(sealed.digest(), reordered.digest());
            assertNotEquals(sealed.digest(), OrdersApiBuilds.build185WithoutSourceBundle().digest());
        }
    }

    /** Konfiguracja testowa: starter przekazuje ten callback do opcji SDK, a callback zatrzymuje event. */
    @Configuration(proxyBeanMethods = false)
    static class CapturingSentry {

        static final List<SentryEvent> EVENTS = new CopyOnWriteArrayList<>();

        @Bean
        SentryOptions.BeforeSendCallback capturingBeforeSend() {
            return (event, hint) -> {
                EVENTS.add(event);
                return null;
            };
        }
    }

    private static List<String> frames(SentryEvent event) {
        return event.getExceptions().getLast().getStacktrace().getFrames().stream()
                .map(frame -> frame.getModule() + "." + frame.getFunction())
                .toList();
    }

    private static void withSystemProperty(String key, String value, Runnable action) {
        System.setProperty(key, value);
        try {
            action.run();
        } finally {
            System.clearProperty(key);
        }
    }
}
