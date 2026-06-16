#!/usr/bin/env python3
"""
Interactive HiveOS CLI explorer for HiveKeeper.

The exec path (apply-config / RunRaw) cannot use `?` context help — `?` is an interactive line-editor feature
that hangs an exec channel. This opens a real PTY shell to the AP and drives `?` help to discover the exact
command grammar (radio, country-code, hostname, mgt0 ip, ...) so the guided config forms are precise, not guessed.

Usage:
  python scripts/hk-cli-explore.py [--host H] [--user U] [--password P] "interface wifi0 radio ?" "hostname ?" ...
  python scripts/hk-cli-explore.py --host 192.168.1.101 --preset dhcp      # does this AP do DHCP / reservations?
  python scripts/hk-cli-explore.py --host 192.168.1.101 --preset clients   # where could a client hostname come from?
With no probes it runs a named --preset batch (default: radio). A probe ending in `?` captures context help; a
probe without `?` is executed normally (newline) and its output captured. Everything here is read-only.
"""
import argparse
import sys
import time

import paramiko

# Named probe batches. `?` probes ask the AP for context help (grammar discovery); a plain command is run and
# its output captured. ALL read-only — nothing is applied. Use these to confirm grammar before writing a form.
PRESETS = {
    # Radio + identity grammar (the original default).
    "radio": [
        "?",
        "interface ?",
        "interface wifi0 ?",
        "interface wifi0 radio ?",
        "hostname ?",
        "country-code ?",
    ],
    # Does this AP run a DHCP server, and can it reserve/bind a client IP? If none of these reveal a server
    # node, the AP is a pure bridge (the upstream router owns DHCP) and IP reservation does not belong here.
    "dhcp": [
        "ip ?",
        "show ip ?",
        "dhcp-server ?",
        "ip dhcp-server ?",
        "show dhcp-server ?",
        "show dhcp ?",
        "interface mgt0 ?",
        "interface mgt0 dhcp ?",
    ],
    # `show station` carries no hostname; where else could one come from? Plus the ARP table (IP <-> MAC).
    "clients": [
        "show station ?",
        "show arp ?",
        "show arp",
        "show host ?",
        "show l2 ?",
        "show client ?",
        "show dns ?",
    ],
}


def read_until_idle(chan, idle=1.2, maxwait=10.0):
    buf = b""
    start = time.time()
    last = start
    while time.time() - last < idle and time.time() - start < maxwait:
        if chan.recv_ready():
            data = chan.recv(8192)
            buf += data
            last = time.time()
            # crude pager handling: if the AP pages, nudge it along
            tail = buf[-32:].decode("utf-8", "replace").lower()
            if "more" in tail and "--" in tail:
                chan.send(" ")
        else:
            time.sleep(0.1)
    return buf.decode("utf-8", "replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="192.168.1.100")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="aerohive")
    ap.add_argument("--preset", choices=sorted(PRESETS), default="radio",
                    help="a named probe batch (radio|dhcp|clients); ignored when explicit probes are given")
    ap.add_argument("probes", nargs="*")
    args = ap.parse_args()

    probes = args.probes or PRESETS[args.preset]

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(
        args.host,
        username=args.user,
        password=args.password,
        look_for_keys=False,
        allow_agent=False,
        timeout=12,
    )
    chan = client.invoke_shell(width=240, height=1000)
    time.sleep(1.0)
    banner = read_until_idle(chan, idle=1.0, maxwait=5)
    print("=== connected ===")
    print(banner.strip()[-300:])

    for probe in probes:
        chan.send("\x15")  # Ctrl-U: clear any partial line
        time.sleep(0.2)
        read_until_idle(chan, idle=0.4, maxwait=2)
        print("\n========== PROBE: " + probe + " ==========")
        if probe.endswith("?"):
            chan.send(probe)  # no newline; `?` triggers context help inline
            out = read_until_idle(chan, idle=1.2, maxwait=10)
            chan.send("\x15")  # clear the typed line so the next probe starts clean
            time.sleep(0.2)
            read_until_idle(chan, idle=0.4, maxwait=2)
        else:
            chan.send(probe + "\n")
            out = read_until_idle(chan, idle=1.2, maxwait=12)
        print(out.strip())

    chan.close()
    client.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001 - dev tool, surface any failure plainly
        print("EXPLORER ERROR: " + repr(e), file=sys.stderr)
        sys.exit(1)
