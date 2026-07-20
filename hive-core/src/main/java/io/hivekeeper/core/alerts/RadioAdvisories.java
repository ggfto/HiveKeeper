package io.hivekeeper.core.alerts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Best-practice advisories for an AP radio config. A faithful Java port of the web {@code radioAdvisories.js}
 * so the server-side fleet poller and the in-console view share one rule set. Pure and side-effect-free.
 *
 * <p>Why these rules: Wi-Fi is a shared, half-duplex medium (CSMA/CA) — wide channels and high TX power don't
 * add capacity; they shrink the pool of non-overlapping channels and enlarge cells, raising airtime contention
 * and co-channel interference under density. {@code iface} is {@code wifi0} (2.4 GHz) / {@code wifi1} (5 GHz);
 * channel/power/width may be numbers, numeric strings, {@code auto}, or blank (non-numeric values are skipped);
 * width (MHz) is optional — only a live read knows it (the inventory does not capture it).
 */
public final class RadioAdvisories {

    /** One advisory: a best-practice flag, not a hard error. */
    public record Advisory(String level, String code, String message) {
    }

    private static final Set<Integer> NON_OVERLAPPING_24 = Set.of(1, 6, 11);
    /** HiveOS radio power is 1-20 dBm; at/above this the cell is large enough to warn about. */
    private static final int HIGH_POWER_DBM = 18;

    private RadioAdvisories() {
    }

    public static List<Advisory> advise(String iface, String channel, String power, String width) {
        List<Advisory> out = new ArrayList<>();
        Double ch = toNum(channel);
        String b = band(iface, ch);
        Double pw = toNum(power);
        Double w = toNum(width);

        // Channel width: wider than 20 MHz on 2.4 GHz overlaps the band; wide channels on 5 GHz eat reuse.
        if ("2.4".equals(b) && w != null && w > 20) {
            out.add(new Advisory("warning", "width-24ghz",
                    w + " MHz on 2.4 GHz: the band only fits three non-overlapping 20 MHz channels (1, 6, 11); "
                            + "a wider channel overlaps neighbors and raises co-channel interference. Use 20 MHz."));
        } else if ("5".equals(b) && w != null && w >= 160) {
            out.add(new Advisory("warning", "width-160",
                    "160 MHz uses eight 20 MHz channels, leaving little room for channel reuse; 40-80 MHz is "
                            + "usually a better trade-off in dense deployments."));
        } else if ("5".equals(b) && w != null && w >= 80) {
            out.add(new Advisory("info", "width-80",
                    "80 MHz consumes four 20 MHz channels. In dense deployments a narrower channel (40 MHz) gives "
                            + "more reuse and lower latency."));
        }

        // 2.4 GHz channel overlap: only 1/6/11 don't step on each other.
        if ("2.4".equals(b) && ch != null && !NON_OVERLAPPING_24.contains(ch.intValue())) {
            out.add(new Advisory("warning", "channel-24-overlap",
                    "Channel " + ch.intValue() + " on 2.4 GHz overlaps adjacent channels. Use 1, 6 or 11."));
        }

        // High TX power: large cells cause AP<->client asymmetry and co-channel interference between APs.
        if (pw != null && pw >= HIGH_POWER_DBM) {
            out.add(new Advisory("warning", "high-power",
                    "TX power " + trimNum(pw) + " dBm is near maximum. Large cells cause AP<->client asymmetry and "
                            + "co-channel interference; in dense areas lower the power or use auto (ACSP)."));
        }
        return out;
    }

    /**
     * Which band a radio is on. The CHANNEL decides it; the {@code wifi0} = 2.4 / {@code wifi1} = 5 naming
     * convention is only a fallback for a radio that is down.
     *
     * <p>That convention holds on a two-radio AP and breaks on an AP410C-1, where {@code wifi1} and
     * {@code wifi2} are both 5 GHz. Keying solely off the name meant every advisory returned nothing for a
     * third radio — no warning and no error, just a radio quietly exempt from every check.
     */
    private static String band(String iface, Double channel) {
        if (channel != null && channel > 0) {
            return channel <= 14 ? "2.4" : "5";
        }
        if (iface == null) {
            return null;
        }
        String i = iface.toLowerCase(Locale.ROOT);
        if (i.equals("wifi0")) {
            return "2.4";
        }
        if (i.equals("wifi1")) {
            return "5";
        }
        return null;
    }

    /** Parses a value to a number, treating null / blank / {@code auto} / non-numeric as "not set" (null). */
    private static Double toNum(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("auto")) {
            return null;
        }
        try {
            double n = Double.parseDouble(s);
            return Double.isFinite(n) ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimNum(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
