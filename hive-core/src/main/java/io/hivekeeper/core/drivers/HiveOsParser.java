package io.hivekeeper.core.drivers;

import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.Station;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort parser for HiveOS / IQ Engine {@code show} output.
 *
 * <p>PROVISIONAL: these patterns are written from documented output shapes and MUST be validated and
 * adjusted against real golden captures from a live AP230 (the week-1 de-risk step). Unmatched fields
 * degrade to {@code null} rather than throwing, so a format drift never crashes inventory — it just
 * leaves a field blank, which is visible and fixable.
 */
final class HiveOsParser {

    private static final Pattern MAC = Pattern.compile("^[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}");
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");

    private HiveOsParser() {
    }

    static Device parse(DeviceId id, HiveOsCapture c) {
        String ver = nz(c.showVersion());
        String iface = nz(c.showInterface());
        return new Device(
                id,
                firstGroup(ver, "(?im)^\\s*hostname[:\\s]+(\\S+)"),
                firstGroup(ver, "(?im)^\\s*platform[:\\s]+(\\S+)"),
                firstGroup(ver, "(?im)^\\s*serial(?:\\s*number)?[:\\s]+(\\S+)"),
                firstGroup(ver, "(?im)\\bHiveOS\\s+([0-9][^\\s]*)"),
                firstGroup(ver, "(?im)^\\s*uptime[:\\s]+(.+?)\\s*$"),
                firstGroup(iface, "(?im)^\\s*mgt0\\b[^\\n]*?(\\d{1,3}(?:\\.\\d{1,3}){3})"),
                List.of(),
                parseStations(c.showStation()));
    }

    static List<Station> parseStations(String showStation) {
        List<Station> out = new ArrayList<>();
        if (showStation == null) {
            return out;
        }
        for (String line : showStation.split("\\R")) {
            String trimmed = line.strip();
            Matcher mm = MAC.matcher(trimmed);
            if (!mm.find()) {
                continue;
            }
            String macAddr = mm.group();
            Matcher im = IPV4.matcher(trimmed);
            String ipAddr = im.find() ? im.group() : null;
            out.add(new Station(macAddr, ipAddr, null, null, null, null));
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
