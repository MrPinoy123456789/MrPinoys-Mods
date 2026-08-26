#!/usr/bin/env python3
"""Minimal RCON client for driving the pocketdungeons dev server (Source RCON
protocol, no third-party dependency).

Used for in-world verification of milestone work -- proving a change did the
thing in a running 26.2 server, not just that it compiled. See docs/LIVE_TEST_PASS.md
for what to verify this way.

Usage:
    python tools/rcon.py "<command 1>" "<command 2>" ...
    python tools/rcon.py "sleep:2.5" "<command after a pause>"

Reads connection details from environment variables, all optional:
    PD_RCON_HOST      default 127.0.0.1
    PD_RCON_PORT      default 25575
    PD_RCON_PASSWORD  default pdverify

Before use:
    1. cp run/server.properties run/server.properties.bak
    2. set enable-rcon=true and rcon.password=<something> in run/server.properties
    3. ./gradlew runServer --offline   (in the background; wait for "RCON running")
    4. run this script
    5. mv run/server.properties.bak run/server.properties   -- always restore this,
       run/ is gitignored but a live server should never be left with RCON open
"""
import os
import socket
import struct
import sys
import time


class Rcon:
    def __init__(self, host, port, password):
        self.s = socket.create_connection((host, port), timeout=15)
        self._send(3, password)
        typ, _ = self._recv()
        if typ == -1:
            raise SystemExit("RCON auth failed -- check rcon.password in server.properties")

    def _send(self, typ, body, rid=1):
        data = struct.pack("<ii", rid, typ) + body.encode("utf8") + b"\x00\x00"
        self.s.sendall(struct.pack("<i", len(data)) + data)

    def _recv(self):
        length = struct.unpack("<i", self._read(4))[0]
        payload = self._read(length)
        rid, typ = struct.unpack("<ii", payload[:8])
        return typ, payload[8:-2].decode("utf8", "replace")

    def _read(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.s.recv(n - len(buf))
            if not chunk:
                raise SystemExit("RCON connection closed unexpectedly")
            buf += chunk
        return buf

    def cmd(self, command):
        self._send(2, command)
        _, body = self._recv()
        return body


def main():
    host = os.environ.get("PD_RCON_HOST", "127.0.0.1")
    port = int(os.environ.get("PD_RCON_PORT", "25575"))
    password = os.environ.get("PD_RCON_PASSWORD", "pdverify")

    r = Rcon(host, port, password)
    for arg in sys.argv[1:]:
        if arg.startswith("sleep:"):
            time.sleep(float(arg.split(":", 1)[1]))
            continue
        print(">>> " + arg)
        print(r.cmd(arg))


if __name__ == "__main__":
    main()
