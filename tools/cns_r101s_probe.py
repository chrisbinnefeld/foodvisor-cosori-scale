#!/usr/bin/env python3
"""Scan, connect and decode the Cosori CNS-R101S (COSORI Nutrition Scale).

Requires `bleak` (pip install bleak). The scale sleeps quickly: it only
advertises for a short window after activity, so run this and then wake the
scale (press its button / put something on it).

Usage:
    python3 cns_r101s_probe.py [--address MAC] [--seconds N] [--tare]

Without --address it connects to the first device whose advertised service is
0x1910 and whose name starts with "COSORI".
"""
import argparse
import asyncio
import sys
import time

try:
    from bleak import BleakClient, BleakScanner
except ImportError:
    sys.exit("bleak fehlt: python3 -m venv .venv && .venv/bin/pip install bleak")

SERVICE = "00001910-0000-1000-8000-00805f9b34fb"
NOTIFY = "00002c12-0000-1000-8000-00805f9b34fb"
COMMAND = "00002c11-0000-1000-8000-00805f9b34fb"

UNITS = {0: "g", 1: "lb:oz", 2: "ml", 3: "floz",
         4: "ml-milk", 5: "floz-milk", 6: "oz"}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def checksum(data):
    return sum(data) & 0xFF


def frame(ptype, payload=b""):
    body = bytes([ptype, len(payload)]) + payload
    return bytes([0xFE, 0xEF, 0x00, 0x84]) + body + bytes([checksum(body)])


def parse(raw):
    """Return (type, payload) or None."""
    if len(raw) < 7 or raw[0] != 0xFE or raw[1] != 0xEF:
        return None
    ptype, length = raw[4], raw[5]
    if len(raw) < 6 + length + 1:
        return None
    payload = raw[6:6 + length]
    if checksum(raw[4:6 + length]) != raw[6 + length]:
        return None
    return ptype, payload


def on_notify(_sender, data):
    raw = bytes(data)
    parsed = parse(raw)
    if not parsed:
        log(f"[NOTIFY] {raw.hex()}  (kein gueltiger Frame)")
        return
    ptype, payload = parsed
    if ptype == 0xD0 and len(payload) >= 5:
        sign = -1 if payload[0] else 1
        raw_w = (payload[1] << 8) | payload[2]
        log(f"[0xD0] {sign * raw_w / 10.0:8.1f} g  "
            f"unit={UNITS.get(payload[3], payload[3])} settled={bool(payload[4])}")
    else:
        log(f"[0x{ptype:02X}] payload={payload.hex()}")


def matches(device, adv):
    name = (adv.local_name or device.name or "").upper()
    return "COSORI" in name or SERVICE in [str(u).lower() for u in adv.service_uuids]


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--address", help="MAC address, skips scanning")
    ap.add_argument("--seconds", type=int, default=60)
    ap.add_argument("--tare", action="store_true", help="send SET_UNIT(g) + SET_TARE after connect")
    args = ap.parse_args()

    address = args.address
    if not address:
        log("scanne (20 s) ... Waage aufwecken!")
        device = await BleakScanner.find_device_by_filter(matches, timeout=20.0)
        if device is None:
            sys.exit("Waage nicht gefunden")
        address = device.address

    async with BleakClient(address, timeout=20.0) as client:
        log(f"verbunden mit {address}")
        if args.tare:
            await client.write_gatt_char(COMMAND, frame(0xC0, b"\x00"), response=False)
            await asyncio.sleep(0.3)
            await client.write_gatt_char(COMMAND, frame(0xC1, b"\x01"), response=False)
            log("SET_UNIT(g) + SET_TARE gesendet")
        await client.start_notify(NOTIFY, on_notify)
        log(f"Notify aktiv, {args.seconds}s mitschneiden ...")
        await asyncio.sleep(args.seconds)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
