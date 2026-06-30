package io.hivekeeper.core.alerts;

import io.hivekeeper.core.alerts.AlertRules.Alert;
import io.hivekeeper.core.alerts.AlertRules.RadioView;
import io.hivekeeper.core.alerts.AlertRules.Snapshot;
import io.hivekeeper.core.alerts.AlertRules.Thresholds;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Java port parity with the web alerts.js rules. */
class AlertRulesTest {

    private static List<String> ids(List<Alert> a) {
        return a.stream().map(Alert::id).toList();
    }

    @Test
    void offlineAgentIsTheOnlyAlert() {
        List<Alert> a = AlertRules.evaluate(false, new Snapshot(true, 99, List.of()), Thresholds.DEFAULT);
        assertEquals(List.of("agent-offline"), ids(a));
        assertEquals("critical", a.get(0).severity());
    }

    @Test
    void warnsWhenStillCloudManaged() {
        List<Alert> a = AlertRules.evaluate(true, new Snapshot(true, 0, List.of()), Thresholds.DEFAULT);
        assertTrue(ids(a).contains("cloud-managed"));
        assertEquals("warning", a.stream().filter(x -> x.id().equals("cloud-managed")).findFirst().orElseThrow().severity());
    }

    @Test
    void warnsOnHighClientLoadOverTheThreshold() {
        assertTrue(ids(AlertRules.evaluate(true, new Snapshot(false, 31, List.of()), Thresholds.DEFAULT))
                .contains("high-clients"));
        assertTrue(ids(AlertRules.evaluate(true, new Snapshot(false, 30, List.of()), Thresholds.DEFAULT))
                .isEmpty());
        // a custom threshold changes the trip point
        assertTrue(ids(AlertRules.evaluate(true, new Snapshot(false, 11, List.of()), new Thresholds(10)))
                .contains("high-clients"));
    }

    @Test
    void emitsAnInfoAlertForRadiosWithWarningAdvisories() {
        Snapshot s = new Snapshot(false, 1, List.of(new RadioView("Wifi0", "3", "10", "20")));
        List<Alert> a = AlertRules.evaluate(true, s, Thresholds.DEFAULT);
        assertTrue(ids(a).contains("radio-wifi0"));
        Alert radio = a.stream().filter(x -> x.id().equals("radio-wifi0")).findFirst().orElseThrow();
        assertEquals("info", radio.severity());
        assertTrue(radio.message().contains("channel-24-overlap"));
    }

    @Test
    void aHealthySnapshotEmitsNothing() {
        Snapshot s = new Snapshot(false, 2, List.of(new RadioView("Wifi0", "6", "10", "20")));
        assertTrue(AlertRules.evaluate(true, s, Thresholds.DEFAULT).isEmpty());
    }

    @Test
    void worstSeverityRanksCriticalAboveWarningAboveInfo() {
        assertEquals("critical", AlertRules.worstSeverity(List.of(
                new Alert("a", "info", ""), new Alert("b", "critical", ""), new Alert("c", "warning", ""))));
        assertNull(AlertRules.worstSeverity(List.of()));
        assertTrue(AlertRules.severityRank("critical") < AlertRules.severityRank("warning"));
        assertTrue(AlertRules.severityRank("warning") < AlertRules.severityRank("info"));
    }
}
