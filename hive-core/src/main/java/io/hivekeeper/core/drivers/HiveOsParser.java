package io.hivekeeper.core.drivers;

import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.Radio;
import io.hivekeeper.core.model.Station;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for HiveOS / IQ Engine {@code show} output, calibrated against a live AP230 running
 * HiveOS 10.6r1a (captures under {@code src/test/resources/fixtures/ap230/}). Fields:
 * <ul>
 *   <li>model + serial from {@code show hw-info};</li>
 *   <li>firmware + uptime from {@code show version};</li>
 *   <li>management IP from {@code show interface mgt0};</li>
 *   <li>hive name + radios from {@code show interface};</li>
 *   <li>stations from {@code show station} (Aerohive {@code xxxx:xxxx:xxxx} MACs, SSID grouping).</li>
 * </ul>
 * Unmatched fields degrade to {@code null}/empty rather than throwing.
 */
final class HiveOsParser {

    private static final Pattern MAC = Pattern.compile(
            "(?:[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}|[0-9a-fA-F]{4}(?::[0-9a-fA-F]{4}){2})");
    private static final Pattern MAC_TOKEN = Pattern.compile(
            "^(?:[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}|[0-9a-fA-F]{4}(?::[0-9a-fA-F]{4}){2})$");
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");
    private static final Pattern SSID = Pattern.compile("(?i)SSID=([^,\\s:]+)");
    private static final Pattern RSSI = Pattern.compile("(-\\d{1,3})\\(\\d+\\)");
    private static final Pattern RADIO_NAME = Pattern.compile("^Wifi\\d+$");
    private static final Pattern LEADING_INT = Pattern.compile("^(\\d+)");

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

        InterfaceInfo interfaces = parseInterfaces(nz(c.showInterface()));

        return Device.builder()
                .id(id)
                .hostname(null)  // not exposed by these commands (default/unset on the test AP)
                .model(model)
                .serial(firstGroup(hw, "(?im)^\\s*Serial number\\s*:?\\s*(\\S+)"))
                .firmwareVersion(firstGroup(ver, "(?im)\\bHiveOS\\s+([0-9][^\\s]*)"))
                .uptime(firstGroup(ver, "(?im)^\\s*Uptime\\s*:?\\s*(.+?)\\s*$"))
                .managementIp(firstGroup(mgt, "(?im)\\bIP addr\\s*=\\s*(\\d{1,3}(?:\\.\\d{1,3}){3})"))
                .hiveName(interfaces.hiveName())
                .radios(interfaces.radios())
                .stations(parseStations(c.showStation()))
                .build();
    }

    /** Holder for what {@code show interface} yields beyond a per-radio list. */
    private record InterfaceInfo(String hiveName, List<Radio> radios) {
    }

    /**
     * The physical radio interfaces, lowercased for use in CLI lines ({@code wifi0}, {@code wifi1}, …).
     *
     * <p>Read from the device rather than assumed. An AP230 and an AP630 have two radios, an AP410C-1 has
     * three (its second and third are both 5 GHz), and nothing stops a future model having four — binding
     * to a hardcoded {@code wifi0}/{@code wifi1} pair silently leaves the extra radios carrying no SSID.
     */
    static List<String> radioInterfaceNames(String showInterface) {
        return parseInterfaces(showInterface).radios().stream()
                .map(radio -> radio.name().toLowerCase(Locale.ROOT))
                .toList();
    }

    /**
     * Parses {@code show interface}: a whitespace-aligned table with columns
     * Name, MAC, Mode, State, Chan(Width), VLAN, Radio, Hive, SSID. Data rows are identified by a MAC
     * in column 2; the hive name is the first non-"-" Hive value, and radios are the physical
     * {@code WifiN} rows (not the {@code WifiN.x} virtual interfaces).
     */
    static InterfaceInfo parseInterfaces(String showInterface) {
        String hiveName = null;
        List<Radio> radios = new ArrayList<>();
        if (showInterface == null || showInterface.isBlank()) {
            return new InterfaceInfo(null, radios);
        }
        for (String line : showInterface.split("\\R")) {
            String[] t = line.strip().split("\\s+");
            if (t.length < 9 || !MAC_TOKEN.matcher(t[1]).matches()) {
                continue;  // legend/header/separator line, not a data row
            }
            String name = t[0];
            String mode = t[2];
            String chanWidth = t[4];
            String hive = t[7];
            if (hiveName == null && !"-".equals(hive)) {
                hiveName = hive;
            }
            if (RADIO_NAME.matcher(name).matches()) {
                radios.add(new Radio(name, "-".equals(mode) ? null : mode, parseChannel(chanWidth), null));
            }
        }
        return new InterfaceInfo(hiveName, radios);
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
            if (!mm.find() || mm.start() != 0) {
                continue;  // a station row begins with a MAC; header/legend lines do not
            }
            Matcher im = IPV4.matcher(trimmed);
            String ip = im.find() ? im.group() : null;
            Matcher rm = RSSI.matcher(trimmed);
            Integer rssi = rm.find() ? Integer.valueOf(rm.group(1)) : null;
            out.add(new Station(mm.group(), ip, null, currentSsid, null, rssi));
        }
        return out;
    }

    private static Integer parseChannel(String chanWidth) {
        if (chanWidth == null) {
            return null;
        }
        Matcher m = LEADING_INT.matcher(chanWidth);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String firstGroup(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
