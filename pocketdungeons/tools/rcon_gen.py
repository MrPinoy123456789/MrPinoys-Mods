#!/usr/bin/env python3
"""Minimal Minecraft RCON client. Sends commands to a running dedicated server.

Usage: python tools/rcon_gen.py <command> [<command> ...]
Each command is sent, its response printed, and a short pause inserted so the
server can process it before the next one.
"""
import socket
import struct
import sys
import time

HOST = "127.0.0.1"
PORT = 25575
PASSWORD = "temppass"


def _recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("RCON connection closed")
        buf += chunk
    return buf


def _recv_packet(sock):
    length = struct.unpack("<i", _recv_exact(sock, 4))[0]
    body = _recv_exact(sock, length)
    pid = struct.unpack("<ii", body[:8])[0]
    payload = body[8:-2]  # strip two trailing NULs
    return pid, payload.decode("utf-8", "replace")


def _send(sock, pid, ptype, payload):
    data = payload.encode("utf-8") + b"\x00\x00"
    packet = struct.pack("<iii", len(data) + 8, pid, ptype) + data
    sock.sendall(packet)


def main():
    cmds = sys.argv[1:]
    if not cmds:
        print("no commands given")
        return 1
    sock = socket.create_connection((HOST, PORT), timeout=10)
    try:
        _send(sock, 1, 3, PASSWORD)
        pid, resp = _recv_packet(sock)
        if pid == -1:
            print("RCON auth failed")
            return 1
        print("RCON auth ok")
        for cmd in cmds:
            _send(sock, 2, 2, cmd)
            pid, resp = _recv_packet(sock)
            print(f"> {cmd}\n{resp}")
            time.sleep(3)
    finally:
        sock.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
