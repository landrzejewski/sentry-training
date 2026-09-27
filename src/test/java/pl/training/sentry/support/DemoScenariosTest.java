package pl.training.sentry.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoScenariosTest {

    @Test
    void withoutNumbersEveryScenarioRuns() {
        DemoScenarios scenarios = DemoScenarios.from(new String[0], 1, 6);

        assertTrue(scenarios.includes(1));
        assertTrue(scenarios.includes(6));
        assertTrue(scenarios.includesAny(1, 6));
    }

    @Test
    void numbersSelectOnlyThoseScenarios() {
        // exec:java dzieli -Dexec.args="1 3" na dwa argumenty; przecinek też działa.
        for (String[] args : new String[][]{{"1", "3"}, {"1,3"}, {"1 3"}}) {
            DemoScenarios scenarios = DemoScenarios.from(args, 1, 6);

            assertTrue(scenarios.includes(1));
            assertFalse(scenarios.includes(2));
            assertTrue(scenarios.includes(3));
            assertFalse(scenarios.includesAny(4, 6));
        }
    }

    @Test
    void flagsOfTheDemoAreIgnored() {
        DemoScenarios scenarios = DemoScenarios.from(new String[]{"--plan", "8"}, 7, 12);

        assertTrue(scenarios.includes(8));
        assertFalse(scenarios.includes(7));
    }

    @Test
    void scenarioOutsideTheRangeIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DemoScenarios.from(new String[]{"9"}, 1, 6));

        assertEquals("Nie ma scenariusza [9]: to demo ma scenariusze 1-6", error.getMessage());
    }
}
