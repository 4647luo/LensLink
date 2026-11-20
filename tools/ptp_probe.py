#!/usr/bin/env python3
"""Read-only Nikon PTP/IP connectivity probe.

It performs the transport handshake, opens session 1, asks for DeviceInfo and
prints every packet type / response code it sees. It does not write camera
properties, trigger capture, or change application mode.

Examples:
    python tools/ptp_probe.py --host 192.168.1.1
    python tools/ptp_probe.py --host 192.168.1.1 --port 15740 --verbose
"""

from __future__ import annotations

import argparse
import logging
import socket
import struct
import sys
import uuid
from typing import Optional

LOG = logging.getLogger("ptp-probe")

PTP_IP_VERSION = 0x00010000
PTP_PORT = 15740

INIT_COMMAND_REQUEST = 1
INIT_COMMAND_ACK = 2
INIT_EVENT_REQUEST = 3
INIT_EVENT_ACK = 4
INIT_FAIL = 5
OPERATION_REQUEST = 6
OPERATION_RESPONSE = 7
EVENT = 8
START_DATA = 9
DATA = 10
CANCEL_TRANSACTION = 11
END_DATA = 12
PING = 13
PONG = 14

OP_GET_DEVICE_INFO = 0x1001
OP_OPEN_SESSION = 0x1002

RC_NAMES = {
    0x2000: "Undefined",
    0x2001: "OK",
    0x2002: "GeneralError",
    0x2003: "SessionNotOpen",
    0x2004: "InvalidTransactionID",
    0x2005: "OperationNotSupported",
    0x2006: "ParameterNotSupported",
    0x2007: "IncompleteTransfer",
    0x2008: "InvalidStorageID",
    0x2009: "InvalidObjectHandle",
    0x200A: "DevicePropNotSupported",
    0x200B: "InvalidObjectFormatCode",
    0x200C: "StoreFull",
    0x200D: "ObjectAlreadyExists",
    0x200E: "ObjectNotFound",
    0x200F: "StoreReadOnly",
    0x2010: "AccessDenied",
    0x2011: "NoThumbnailPresent",
    0x2012: "SelfTestFailed",
    0x2013: "PartialDeletion",
    0x2014: "StoreNotAvailable",
    0x2015: "SpecificationByFormatUnsupported",
    0x2016: "NoValidObjectInfo",
    0x2017: "InvalidCodeFormat",
    0x2018: "UnknownVendorCode",
    0x2019: "DeviceBusy",
    0x201A: "InvalidParentObject",
    0x201B: "InvalidDevicePropFormat",
    0x201C: "InvalidDevicePropValue",
    0x201D: "InvalidParameter",
    0x201E: "SessionAlreadyOpen",
    0x201F: "TransactionCancelled",
    0x2020: "OpeningStoreFailed",
    0x2021: "ClosingStoreFailed",
    0x2022: "UnspecifiedDestination",
    0x2023: "InvalidObjectPropCode",
    0x2024: "InvalidObjectPropFormat",
    0x2025: "InvalidObjectPropValue",
    0x2026: "InvalidObjectReference",
    0x2027: "GroupNotSupported",
    0x2028: "InvalidDataset",
}


def _read_exact(sock: socket.socket, size: int) -> bytes:
    data = bytearray(size)
    pos = 0
    while pos < size:
        chunk = sock.recv(size - pos)
        if not chunk:
            raise ConnectionError("peer closed while reading PTP/IP packet")
        data[pos:pos + len(chunk)] = chunk
        pos += len(chunk)
    return bytes(data)


def _read_packet(sock: socket.socket, label: str) -> tuple[int, bytes]:
    header = _read_exact(sock, 8)
    length, ptype = struct.unpack("<II", header)
    if length < 8 or length > 128 * 1024 * 1024:
        raise ValueError(f"invalid packet length {length}")
    payload = _read_exact(sock, length - 8)
    LOG.info("[%s] rx type=%d len=%d payload=%d", label, ptype, length, len(payload))
    return ptype, payload


def _packet(ptype: int, payload: bytes = b"") -> bytes:
    return struct.pack("<II", 8 + len(payload), ptype) + payload


def _utf16le(value: str) -> bytes:
    return (value + "\x00").encode("utf-16le")


def _decode_ptp_string(data: bytes, offset: int) -> tuple[str, int]:
    count = data[offset]
    offset += 1
    chars = []
    for _ in range(count):
        chars.append(chr(struct.unpack_from("<H", data, offset)[0]))
        offset += 2
    return "".join(chars).rstrip("\x00"), offset


def _decode_array16(data: bytes, offset: int) -> tuple[list[int], int]:
    count = struct.unpack_from("<I", data, offset)[0]
    offset += 4
    values = [struct.unpack_from("<H", data, offset + i * 2)[0] for i in range(count)]
    return values, offset + count * 2


def decode_device_info(data: bytes) -> dict:
    offset = 0
    standard, vendor_id, vendor_version = struct.unpack_from("<HIH", data, offset)
    offset += 8
    vendor_desc, offset = _decode_ptp_string(data, offset)
    functional_mode = struct.unpack_from("<H", data, offset)[0]
    offset += 2
    operations, offset = _decode_array16(data, offset)
    events, offset = _decode_array16(data, offset)
    properties, offset = _decode_array16(data, offset)
    capture_formats, offset = _decode_array16(data, offset)
    image_formats, offset = _decode_array16(data, offset)
    manufacturer, offset = _decode_ptp_string(data, offset)
    model, offset = _decode_ptp_string(data, offset)
    firmware, offset = _decode_ptp_string(data, offset)
    serial, offset = _decode_ptp_string(data, offset)
    return {
        "standard": standard,
        "vendor_id": vendor_id,
        "vendor_version": vendor_version,
        "vendor_desc": vendor_desc,
        "functional_mode": functional_mode,
        "operations": operations,
        "events": events,
        "properties": properties,
        "capture_formats": capture_formats,
        "image_formats": image_formats,
        "manufacturer": manufacturer,
        "model": model,
        "firmware": firmware,
        "serial": serial,
        "parsed": offset,
        "total": len(data),
    }


class Probe:
    def __init__(self, host: str, port: int, timeout: float, verbose: bool):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.verbose = verbose
        self.transaction = 0
        self.command: Optional[socket.socket] = None
        self.event: Optional[socket.socket] = None
        self.connection_number = 0

    def connect(self) -> bool:
        self.command = socket.create_connection((self.host, self.port), timeout=self.timeout)
        self.command.settimeout(self.timeout)
        guid = uuid.uuid4().bytes
        init = guid + _utf16le("LensLink Probe") + struct.pack("<I", PTP_IP_VERSION)
        self.command.sendall(_packet(INIT_COMMAND_REQUEST, init))
        LOG.info("[command] tx InitCommandRequest guid=%s", uuid.UUID(bytes=guid))

        ptype, payload = _read_packet(self.command, "command")
        if ptype == INIT_FAIL:
            reason = struct.unpack_from("<I", payload, 0)[0] if len(payload) >= 4 else -1
            LOG.error("camera rejected handshake: InitFail reason=%d", reason)
            return False
        if ptype != INIT_COMMAND_ACK or len(payload) < 28:
            LOG.error("invalid InitCommandAck: type=%s len=%d", ptype, len(payload))
            return False

        self.connection_number = struct.unpack_from("<I", payload, 0)[0]
        responder_guid = uuid.UUID(bytes=payload[4:20])
        # responder name ends four bytes before the protocol version
        name = payload[20:-4].decode("utf-16le", errors="replace").rstrip("\x00")
        version = struct.unpack_from("<I", payload, len(payload) - 4)[0]
        LOG.info("[command] InitCommandAck connection=%d responder=%r version=0x%08X guid=%s",
                 self.connection_number, name, version, responder_guid)
        return True

    def connect_event(self) -> bool:
        try:
            self.event = socket.create_connection((self.host, self.port), timeout=self.timeout)
            self.event.settimeout(self.timeout)
            self.event.sendall(_packet(INIT_EVENT_REQUEST, struct.pack("<I", self.connection_number)))
            LOG.info("[event] tx InitEventRequest connection=%d", self.connection_number)
            ptype, _ = _read_packet(self.event, "event")
            if ptype != INIT_EVENT_ACK:
                LOG.error("[event] expected InitEventAck, got type=%d", ptype)
                return False
            LOG.info("[event] InitEventAck")
            return True
        except OSError as exc:
            LOG.warning("[event] channel unavailable: %s", exc)
            return False

    def request(self, opcode: int, params: Optional[list[int]] = None) -> tuple[int, bytes]:
        params = params or []
        self.transaction += 1
        tx = self.transaction
        request = struct.pack("<IHI", 1, opcode, tx)
        request += b"".join(struct.pack("<I", p) for p in params)
        assert self.command is not None
        self.command.sendall(_packet(OPERATION_REQUEST, request))
        LOG.info("[command] tx op=0x%04X tx=%d params=%s", opcode, tx, params)

        data = bytearray()
        expected: Optional[int] = None
        while True:
            ptype, payload = _read_packet(self.command, "command")
            if ptype == START_DATA:
                expected = struct.unpack_from("<Q", payload, 4)[0]
                LOG.info("[command] StartData tx=%d size=%d", tx, expected)
            elif ptype in (DATA, END_DATA):
                data += payload[4:]
                LOG.info("[command] %s tx=%d bytes=%d", "EndData" if ptype == END_DATA else "Data", tx, len(payload) - 4)
            elif ptype == OPERATION_RESPONSE:
                code, rtx = struct.unpack_from("<HI", payload, 0)
                if rtx != tx:
                    raise ValueError(f"transaction mismatch: sent {tx}, got {rtx}")
                if expected is not None and len(data) != expected:
                    LOG.warning("data length mismatch: declared %d, got %d", expected, len(data))
                LOG.info("[command] response tx=%d code=0x%04X %s", tx, code, RC_NAMES.get(code, "Unknown"))
                return code, bytes(data)
            elif ptype == EVENT:
                event_code = struct.unpack_from("<H", payload, 0)[0]
                LOG.info("[command] event 0x%04X (ignored)", event_code)
            elif ptype == PING:
                assert self.command is not None
                self.command.sendall(_packet(PONG))
            else:
                raise ValueError(f"unexpected packet type {ptype}")

    def run(self) -> int:
        if not self.connect():
            return 2
        self.connect_event()
        code, _ = self.request(OP_OPEN_SESSION, [1])
        if code != 0x2001:
            LOG.error("OpenSession failed: 0x%04X %s", code, RC_NAMES.get(code, "Unknown"))
            return 3
        code, data = self.request(OP_GET_DEVICE_INFO)
        if code != 0x2001:
            LOG.error("GetDeviceInfo failed: 0x%04X %s", code, RC_NAMES.get(code, "Unknown"))
            return 4
        try:
            info = decode_device_info(data)
        except Exception as exc:
            LOG.error("DeviceInfo parse failed: %s (bytes=%d)", exc, len(data))
            return 5
        print()
        print("相机信息")
        print(f"  厂商      : {info['manufacturer']}")
        print(f"  型号      : {info['model']}")
        print(f"  固件      : {info['firmware']}")
        print(f"  序列号    : {info['serial']}")
        print(f"  操作数    : {len(info['operations'])}")
        print(f"  属性数    : {len(info['properties'])}")
        print(f"  解析字节  : {info['parsed']}/{info['total']}")
        print("  操作码    : " + ", ".join(f"0x{v:04X}" for v in info["operations"]))
        # The client treats 0x952B + 0x935A without 0x9201/0x9203 as "pairing required".
        restricted = 0x952B in info["operations"] and 0x935A in info["operations"] \
            and 0x9201 not in info["operations"] and 0x9203 not in info["operations"]
        print(f"  待配对态  : {'是' if restricted else '否'}")
        return 0

    def close(self) -> None:
        for sock in (self.command, self.event):
            if sock:
                try:
                    sock.close()
                except OSError:
                    pass


def main() -> int:
    parser = argparse.ArgumentParser(description="Read-only Nikon PTP/IP connectivity probe")
    parser.add_argument("--host", required=True, help="camera or mock IP")
    parser.add_argument("--port", type=int, default=PTP_PORT, help="PTP/IP TCP port (default: 15740)")
    parser.add_argument("--timeout", type=float, default=5.0, help="socket timeout in seconds")
    parser.add_argument("--verbose", "-v", action="store_true", help="packet-level debug logs")
    args = parser.parse_args()

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    probe = Probe(args.host, args.port, args.timeout, args.verbose)
    try:
        return probe.run()
    except (OSError, ValueError, ConnectionError) as exc:
        LOG.error("probe failed: %s", exc)
        return 1
    finally:
        probe.close()


if __name__ == "__main__":
    sys.exit(main())
