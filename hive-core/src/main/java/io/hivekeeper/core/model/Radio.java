package io.hivekeeper.core.model;

/** A radio interface on an access point (e.g. wifi0 / wifi1). */
public record Radio(String name, String mode, Integer channel, String power) {
}
