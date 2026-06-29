package io.hivekeeper.core.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SsidSpecTest {

    @Test
    void defaultsToWpa2PskWhenSuiteOmitted() {
        assertEquals(SsidSpec.WPA2_PSK, SsidSpec.create("HK", "secretpass", null).security());
    }

    @Test
    void blankSuiteFallsBackToWpa2Psk() {
        assertEquals(SsidSpec.WPA2_PSK, new SsidSpec("HK", "secretpass", null, false, "  ", null).security());
    }

    @Test
    void enterpriseSuiteCarriesARadiusServer() {
        SsidSpec.RadiusSpec radius = new SsidSpec.RadiusSpec("10.0.0.5", "r4dsecret", 1812);
        SsidSpec spec = SsidSpec.createEnterprise("Corp", 10, SsidSpec.WPA2_8021X, radius);
        assertEquals(SsidSpec.WPA2_8021X, spec.security());
        assertEquals("10.0.0.5", spec.radius().server());
    }

    @Test
    void enterpriseSuiteRequiresARadiusServer() {
        assertThrows(IllegalArgumentException.class,
                () -> SsidSpec.create("Corp", "secretpass", null, SsidSpec.WPA2_8021X));
    }

    @Test
    void pskSuiteRejectsARadiusServer() {
        SsidSpec.RadiusSpec radius = new SsidSpec.RadiusSpec("10.0.0.5", "r4dsecret", null);
        assertThrows(IllegalArgumentException.class,
                () -> new SsidSpec("HK", "secretpass", null, false, SsidSpec.WPA2_PSK, radius));
    }

    @Test
    void radiusSpecRequiresServerAndSecret() {
        assertThrows(IllegalArgumentException.class, () -> new SsidSpec.RadiusSpec("", "secret", null));
        assertThrows(IllegalArgumentException.class, () -> new SsidSpec.RadiusSpec("10.0.0.5", "", null));
    }

    @Test
    void acceptsWpa3Sae() {
        assertEquals(SsidSpec.WPA3_SAE, SsidSpec.create("HK", "secretpass", null, SsidSpec.WPA3_SAE).security());
    }

    @Test
    void openSsidNeedsNoPassphrase() {
        SsidSpec spec = SsidSpec.create("HKguest", null, null, SsidSpec.OPEN);
        assertEquals(SsidSpec.OPEN, spec.security());
    }

    @Test
    void openSsidRejectsAPassphrase() {
        assertThrows(IllegalArgumentException.class, () -> SsidSpec.create("HKguest", "secretpass", null, SsidSpec.OPEN));
    }

    @Test
    void keyedSsidRequiresAPassphrase() {
        assertThrows(IllegalArgumentException.class, () -> SsidSpec.create("HK", null, null, SsidSpec.WPA3_SAE));
    }

    @Test
    void rejectsUnknownSuite() {
        assertThrows(IllegalArgumentException.class, () -> SsidSpec.create("HK", "secretpass", null, "wep-open"));
    }

    @Test
    void removalIgnoresSuiteAndKey() {
        SsidSpec spec = SsidSpec.removal("HK");
        assertEquals("HK", spec.name());
    }
}
