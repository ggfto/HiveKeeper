package io.hivekeeper.core.drivers;

import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.Station;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for HiveOS / IQ Engine {@code show} output, calibrated against a live AP230 running
 * HiveOS 10.6r1a (captures under {@code src/test/resources/fixtures/ap230/}). Fields:
 * <ul>
 *   <li>model + serial from {@code show hw-info} ("Product name", "Serial number");</li>
 *   <li>firmware + uptime from {@code show version} ("HiveOS &lt;ver&gt;", "Uptime");</li>
 *   <li>management IP from {@code show interface mgt0} ("IP addr=");</li>
 *   <li>stations from {@code show station}, grouped under "SSID=" headers; MACs use the Aerohive
 *       {@code xxxx:xxxx:xxxx} form as well as the standard {@code xx:xx:xx:xx:xx:xx} form.</li>
 * </ul>
 * Unmatched fields degrade to {@code null} rather than throwing.
 */
final class HiveOsParser {

    private static final Pattern MAC = Pattern.compile(
            "(?:[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}|[0-9a-fA-F]{4}(?::[0-9a-fA-F]{4}){2})");
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");
    private static final Pattern SSID = Pattern.compile("(?i)SSID=([^,\\s:]+)");
    private static final Pattern RSSI = Pattern.compile("(-\\d{1,3})\\(\\d+\\)");

    private HiveOsParser() {
    }

    static Device parse(DeviceId id, HiveOsCapture c) {
        String ver = nz(c.showVersion());
        String hw = nz(c.showHwInfo());
        String mgt = nz(c.showInterfaceMgt0());

        String model = firstGroup(hw, "(?im)^\\s*Product name\\s*:?\\s*(\\S+)");
        if (model == null) {
            model = firstGroup(ver, "(?im)^\\s*Platform\\s*:?\\s*(\\S+)");
        }

        return new Device(
                id,
                null,  // hostname is not exposed by these commands (default/unset on the test AP)
                model,
                firstGroup(hw, "(?im)^\\s*Serial number\\s*:?\\s*(\\S+)"),
                firstGroup(ver, "(?im)\\bHiveOS\\s+([0-9][^\\s]*)"),
                firstGroup(ver, "(?im)^\\s*Uptime\\s*:?\\s*(.+?)\\s*$"),
                firstGroup(mgt, "(?im)\\bIP addr\\s*=\\s*(\\d{1,3}(?:\\.\\d{1,3}){3})"),
                List.of(),
                parseStations(c.showStation()));
    }

    static List<Station> parseStations(String showStation) {
        List<Station> out = new ArrayList<>();
        if (showStation == null) {
            return out;
        }
        String currentSsid = null;
        for (String line : showStation.split("\\R")) {
            Matcher sm = SSID.matcher(line);
            if (sm.find()) {
                currentSsid = sm.group(1);
            }
            String trimmed = line.strip();
            Matcher mm = MAC.matcher(trimmed);
            // A station row begins with a MAC; header/legend lines do not.
            if (!mm.find() || mm.start() != 0) {
                continue;
            }
            Matcher im = IPV4.matcher(trimmed);
            String ip = im.find() ? im.group() : null;
            Matcher rm = RSSI.matcher(trimmed);
            Integer rssi = rm.find() ? Integer.valueOf(rm.group(1)) : null;
            out.add(new Station(mm.group(), ip, null, currentSsid, null, rssi));
        }
        return out;
    }

    private static String firstGroup(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
