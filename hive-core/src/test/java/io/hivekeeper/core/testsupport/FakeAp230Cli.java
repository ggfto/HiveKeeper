package io.hivekeeper.core.testsupport;

import io.hivekeeper.core.drivers.CliExecutor;

/** A {@link CliExecutor} that replays real AP230 fixtures (and canned config) keyed by command verb. */
public final class FakeAp230Cli implements CliExecutor {

    @Override
    public String run(String command) {
        if (command.contains("running-config") && command.contains("users")) {
            return "ppsk-user demo password ***\n";
        }
        if (command.contains("running-config")) {
            return "hostname ap230-lab-1\nssid TESTE\n";
        }
        if (command.contains("hw-info")) {
            return Fixtures.load("/fixtures/ap230/show_hw_info.txt");
        }
        if (command.contains("station")) {
            return Fixtures.load("/fixtures/ap230/show_station.txt");
        }
        if (command.contains("interface") && command.contains("mgt0")) {
            return Fixtures.load("/fixtures/ap230/show_interface_mgt0.txt");
        }
        if (command.contains("interface")) {
            return Fixtures.load("/fixtures/ap230/show_interface.txt");
        }
        if (command.contains("version")) {
            return Fixtures.load("/fixtures/ap230/show_version.txt");
        }
        return "";
    }
}
