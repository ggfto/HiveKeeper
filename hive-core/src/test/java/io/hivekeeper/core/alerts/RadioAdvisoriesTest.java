package io.hivekeeper.core.alerts;

import io.hivekeeper.core.alerts.RadioAdvisories.Advisory;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Java port parity with the web radioAdvisories.js rules. */
class RadioAdvisoriesTest {

    private static List<String> codes(List<Advisory> a) {
        return a.stream().map(Advisory::code).toList();
    }

    @Test
    void flagsWideChannelsByBand() {
        assertTrue(codes(RadioAdvisories.advise("wifi0", "6", "10", "40")).contains("width-24ghz"));
        assertTrue(codes(RadioAdvisories.advise("wifi1", "36", "10", "160")).contains("width-160"));
        List<Advisory> w80 = RadioAdvisories.advise("wifi1", "36", "10", "80");
        assertTrue(codes(w80).contains("width-80"));
        assertEquals("info", w80.stream().filter(a -> a.code().equals("width-80")).findFirst().orElseThrow().level());
    }

    @Test
    void flags24GhzChannelOverlapButNot1_6_11() {
        assertTrue(codes(RadioAdvisories.advise("wifi0", "3", "10", "20")).contains("channel-24-overlap"));
        assertTrue(codes(RadioAdvisories.advise("wifi0", "6", "10", "20")).isEmpty());
    }

    @Test
    void flagsHighTxPowerAtOrAbove18() {
        assertTrue(codes(RadioAdvisories.advise("wifi1", "36", "18", "20")).contains("high-power"));
        assertTrue(codes(RadioAdvisories.advise("wifi1", "36", "17", "20")).isEmpty());
    }

    @Test
    void skipsAutoAndBlankValues() {
        assertTrue(RadioAdvisories.advise("wifi0", "auto", "auto", "").isEmpty());
        assertTrue(RadioAdvisories.advise("wifi0", null, null, null).isEmpty());
    }

    @Test
    void judgesAThirdRadioByItsChannelInsteadOfSkippingIt() {
        // On an AP410C-1, wifi2 is a second 5 GHz radio. Keying off the interface name alone returned no
        // band, so the radio was silently exempt from every advisory.
        List<Advisory> out = RadioAdvisories.advise("wifi2", "44", null, "160");

        assertTrue(out.stream().anyMatch(a -> a.code().equals("width-160")));
    }

    @Test
    void letsTheChannelCorrectAMisleadingInterfaceName() {
        List<Advisory> out = RadioAdvisories.advise("wifi1", "6", null, "40");

        assertTrue(out.stream().anyMatch(a -> a.code().equals("width-24ghz")));
    }

    @Test
    void stillFallsBackToTheNamingConventionWhenTheRadioIsDown() {
        List<Advisory> out = RadioAdvisories.advise("wifi0", null, null, "40");

        assertTrue(out.stream().anyMatch(a -> a.code().equals("width-24ghz")));
    }
}
