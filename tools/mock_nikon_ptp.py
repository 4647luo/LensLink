#!/usr/bin/env python3
"""Minimal Nikon PTP/IP camera simulator for protocol work without a real camera.

It implements the PTP/IP transport handshake, event channel, OpenSession and
GetDeviceInfo. Unknown operations return OperationNotSupported (0x2005) instead
of pretending to be a full camera. That keeps the mock honest: the Android client
can finish its connection state machine, while live view, capture and gallery
still need a real body or a later, explicitly implemented mock.

Examples:
    python tools/mock_nikon_ptp.py --advertise
    python tools/mock_nikon_ptp.py --host 0.0.0.0 --port 15740 --model "Z50II (Mock)"
"""

from __future__ import annotations

import argparse
import logging
import socket
import struct
import threading
import time
import uuid
from typing import Optional

LOG = logging.getLogger("mock-nikon-ptp")

PTP_IP_VERSION = 0x00010000
PTP_PORT = 15740

# PTP/IP packet types
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

# PTP response codes
RC_OK = 0x2001
RC_OPERATION_NOT_SUPPORTED = 0x2005
RC_PARAMETER_NOT_SUPPORTED = 0x2006
RC_DEVICE_BUSY = 0x2019

# PTP operations used by the Android client during connect
OP_GET_DEVICE_INFO = 0x1001
OP_OPEN_SESSION = 0x1002
OP_GET_STORAGE_IDS = 0x1004
OP_GET_STORAGE_INFO = 0x1005
OP_GET_NUM_OBJECTS = 0x1006
OP_GET_OBJECT_HANDLES = 0x1007
OP_GET_OBJECT_INFO = 0x1008
OP_GET_OBJECT = 0x1009
OP_GET_THUMB = 0x100A
OP_GET_DEVICE_PROP_DESC = 0x1014
OP_GET_DEVICE_PROP_VALUE = 0x1015
OP_SET_DEVICE_PROP_VALUE = 0x1016
OP_NIKON_DEVICE_READY = 0x90C8
OP_NIKON_CHANGE_APPLICATION_MODE = 0x9435


def _packet(ptype: int, payload: bytes = b"") -> bytes:
    return struct.pack("<II", 8 + len(payload), ptype) + payload


def _read_exact(sock: socket.socket, size: int) -> bytes:
    data = bytearray(size)
    view = memoryview(data)
    pos = 0
    while pos < size:
        chunk = sock.recv(size - pos)
        if not chunk:
            raise ConnectionError("peer closed while reading PTP/IP packet")
        view[pos:pos + len(chunk)] = chunk
        pos += len(chunk)
    return bytes(data)


def _read_packet(sock: socket.socket, label: str = "") -> tuple[int, bytes]:
    header = _read_exact(sock, 8)
    length, ptype = struct.unpack("<II", header)
    if length < 8 or length > 128 * 1024 * 1024:
        raise ValueError(f"invalid PTP/IP packet length {length}")
    payload = _read_exact(sock, length - 8)
    LOG.debug("%s rx type=%d length=%d payload=%d", label, ptype, length, len(payload))
    return ptype, payload


def _utf16le(value: str, nul: bool = True) -> bytes:
    text = value + ("\x00" if nul else "")
    return text.encode("utf-16le")


def _ptp_string(value: str) -> bytes:
    """PTP DeviceInfo string: one-byte character count, then UTF-16LE characters."""
    data = _utf16le(value)
    return bytes([len(value) + 1]) + data


def _array16(values: list[int]) -> bytes:
    return struct.pack("<I", len(values)) + b"".join(struct.pack("<H", v) for v in values)


def _device_info(model: str, firmware: str, serial: str) -> bytes:
    # The Android client parses this exact order. Keep the layout standard PTP.
    operations = [
        OP_GET_DEVICE_INFO,
        OP_OPEN_SESSION,
        OP_GET_STORAGE_IDS,
        OP_GET_STORAGE_INFO,
        OP_GET_NUM_OBJECTS,
        OP_GET_OBJECT_HANDLES,
        OP_GET_OBJECT_INFO,
        OP_GET_OBJECT,
        OP_GET_THUMB,
        OP_GET_DEVICE_PROP_DESC,
        OP_GET_DEVICE_PROP_VALUE,
        OP_SET_DEVICE_PROP_VALUE,
        OP_NIKON_DEVICE_READY,
        OP_NIKON_CHANGE_APPLICATION_MODE,
    ]
    properties = [0x5001, 0x5005, 0x5007, 0x500D, 0x500F, 0x5010]
    capture_formats = [0x3801]  # JPEG
    image_formats = [0x3801]
    return b"".join([
        struct.pack("<HIH", 100, 0, 0),  # standard version, vendor extension id/version
        _ptp_string(""),                 # vendor extension description
        struct.pack("<H", 0),            # functional mode
        _array16(operations),
        _array16([]),                    # events
        _array16(properties),
        _array16(capture_formats),
        _array16(image_formats),
        _ptp_string("Nikon"),
        _ptp_string(model),
        _ptp_string(firmware),
        _ptp_string(serial),
    ])


def _send_response(sock: socket.socket, transaction: int, code: int, params: Optional[list[int]] = None) -> None:
    params = params or []
    payload = struct.pack("<HI", code, transaction)
    payload += b"".join(struct.pack("<I", p) for p in params)
    sock.sendall(_packet(OPERATION_RESPONSE, payload))
    LOG.info("tx response tx=%d code=0x%04X params=%s", transaction, code, params)


def _send_data(sock: socket.socket, transaction: int, data: bytes, code: int = RC_OK) -> None:
    sock.sendall(_packet(START_DATA, struct.pack("<IQ", transaction, len(data))))
    sock.sendall(_packet(END_DATA, struct.pack("<I", transaction) + data))
    _send_response(sock, transaction, code)


def _consume_data_phase(sock: socket.socket, label: str) -> None:
    """Consume a host-to-camera data phase so the command stream stays aligned."""
    while True:
        ptype, payload = _read_packet(sock, label)
        if ptype == START_DATA:
            continue
        if ptype in (DATA, END_DATA):
            if ptype == END_DATA:
                return
            continue
        raise ValueError(f"unexpected packet type {ptype} during data phase")


class MockCamera:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.server: Optional[socket.socket] = None
        self.stop_event = threading.Event()
        self.connection_number = 1
        self.command_socket: Optional[socket.socket] = None
        self.event_socket: Optional[socket.socket] = None
        self.session_open = False
        self.transaction = 0
        self.device_info = _device_info(args.model, args.firmware, args.serial)

    def serve_forever(self) -> None:
        self.server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind((self.args.host, self.args.port))
        self.server.listen(4)
        self.server.settimeout(1.0)
        LOG.info("PTP/IP mock listening on %s:%d (model=%s)", self.args.host, self.args.port, self.args.model)
        while not self.stop_event.is_set():
            try:
                client, address = self.server.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            LOG.info("client connected from %s:%d", address[0], address[1])
            threading.Thread(target=self._handle_client, args=(client, address), daemon=True).start()
        self.close()

    def _handle_client(self, client: socket.socket, address: tuple[str, int]) -> None:
        client.settimeout(30.0)
        try:
            ptype, payload = _read_packet(client, f"{address[0]}")
            if ptype == INIT_COMMAND_REQUEST:
                self._init_command(client, payload, address)
            elif ptype == INIT_EVENT_REQUEST:
                self._init_event(client, payload, address)
            else:
                LOG.warning("first packet type=%d is not an init request", ptype)
                client.close()
        except (ConnectionError, OSError, ValueError) as exc:
            LOG.info("client %s disconnected: %s", address[0], exc)
        finally:
            try:
                client.close()
            except OSError:
                pass

    def _init_command(self, client: socket.socket, payload: bytes, address: tuple[str, int]) -> None:
        if len(payload) < 20:
            raise ValueError("short InitCommandRequest")
        guid = payload[:16]
        # The name is UTF-16LE and null-terminated; version follows it.
        name_bytes = payload[16:-4]
        name = name_bytes.decode("utf-16le", errors="replace").rstrip("\x00")
        version = struct.unpack("<I", payload[-4:])[0]
        LOG.info("InitCommandRequest guid=%s name=%r version=0x%08X", uuid.UUID(bytes=guid), name, version)
        if self.args.reject:
            client.sendall(_packet(INIT_FAIL, struct.pack("<I", self.args.reject)))
            LOG.info("rejected handshake reason=%d", self.args.reject)
            return

        self.command_socket = client
        ack_payload = (
            struct.pack("<I", self.connection_number)
            + self.args.responder_guid
            + _utf16le(self.args.responder_name)
            + struct.pack("<I", PTP_IP_VERSION)
        )
        client.sendall(_packet(INIT_COMMAND_ACK, ack_payload))
        LOG.info("InitCommandAck connection=%d responder=%r", self.connection_number, self.args.responder_name)
        self._command_loop(client)

    def _init_event(self, client: socket.socket, payload: bytes, address: tuple[str, int]) -> None:
        if len(payload) < 4:
            raise ValueError("short InitEventRequest")
        connection = struct.unpack("<I", payload[:4])[0]
        if connection != self.connection_number:
            LOG.warning("event channel for unexpected connection %d", connection)
        self.event_socket = client
        client.sendall(_packet(INIT_EVENT_ACK))
        LOG.info("InitEventAck connection=%d", connection)
        while not self.stop_event.is_set():
            try:
                ptype, payload = _read_packet(client, "event")
            except (ConnectionError, OSError, ValueError):
                break
            if ptype == PING:
                client.sendall(_packet(PONG))
            elif ptype == INIT_EVENT_REQUEST:
                client.sendall(_packet(INIT_EVENT_ACK))
            else:
                LOG.debug("event packet type=%d ignored", ptype)

    def _command_loop(self, client: socket.socket) -> None:
        while not self.stop_event.is_set():
            try:
                ptype, payload = _read_packet(client, "command")
            except (ConnectionError, OSError, ValueError) as exc:
                LOG.info("command channel closed: %s", exc)
                return
            if ptype == PING:
                client.sendall(_packet(PONG))
                continue
            if ptype != OPERATION_REQUEST:
                LOG.warning("unexpected command packet type=%d", ptype)
                continue
            if len(payload) < 10:
                LOG.warning("short operation request")
                continue
            data_phase, opcode, transaction = struct.unpack_from("<IHI", payload, 0)
            params = list(struct.unpack_from("<" + "I" * ((len(payload) - 10) // 4), payload, 10)) if len(payload) > 10 else []
            LOG.info("op=0x%04X tx=%d data_phase=%d params=%s", opcode, transaction, data_phase, params)
            if data_phase == 2:
                _consume_data_phase(client, "command")
            self._dispatch(client, opcode, transaction, params)

    def _dispatch(self, client: socket.socket, opcode: int, transaction: int, params: list[int]) -> None:
        if opcode == OP_OPEN_SESSION:
            self.session_open = True
            _send_response(client, transaction, RC_OK)
        elif opcode == OP_GET_DEVICE_INFO:
            _send_data(client, transaction, self.device_info)
        elif opcode == OP_NIKON_DEVICE_READY or opcode == OP_NIKON_CHANGE_APPLICATION_MODE:
            _send_response(client, transaction, RC_OK)
        elif opcode in (OP_GET_STORAGE_IDS, OP_GET_STORAGE_INFO, OP_GET_NUM_OBJECTS, OP_GET_OBJECT_HANDLES):
            # The mock has no storage. Return an empty array rather than fake files.
            if opcode in (OP_GET_STORAGE_IDS, OP_GET_OBJECT_HANDLES):
                _send_data(client, transaction, struct.pack("<I", 0))
            else:
                _send_response(client, transaction, RC_OPERATION_NOT_SUPPORTED)
        else:
            _send_response(client, transaction, RC_OPERATION_NOT_SUPPORTED)

    def close(self) -> None:
        self.stop_event.set()
        for sock in (self.command_socket, self.event_socket, self.server):
            if sock is not None:
                try:
                    sock.close()
                except OSError:
                    pass


class MDNSAdvertiser:
    """Small DNS-SD responder for _ptp._tcp.local. No third-party dependency."""

    SERVICE = "_ptp._tcp.local"
    MULTICAST = "224.0.0.251"

    def __init__(self, instance: str, host: str, address: str, port: int):
        self.instance = instance
        self.host = host
        self.address = address
        self.port = port
        self.sock: Optional[socket.socket] = None
        self.stop_event = threading.Event()
        self.thread: Optional[threading.Thread] = None

    @staticmethod
    def _name(name: str) -> bytes:
        out = bytearray()
        for label in name.rstrip(".").split("."):
            out.append(len(label))
            out += label.encode("utf-8")
        out.append(0)
        return bytes(out)

    def _record(self, name: str, rtype: int, rdata: bytes, ttl: int = 120) -> bytes:
        return self._name(name) + struct.pack("!HHIH", rtype, 1, ttl, len(rdata)) + rdata

    def _response(self) -> bytes:
        instance = f"{self.instance}.{self.SERVICE}"
        host = f"{self.host}.local"
        txt = b"".join(bytes([len(v)]) + v.encode() for v in (
            "model=" + self.instance,
            "vendor=Nikon",
            "app=LensLink mock",
        ))
        answers = [
            self._record(self.SERVICE, 12, self._name(instance)),  # PTR
            self._record(instance, 33, struct.pack("!HHH", 0, 0, self.port) + self._name(host)),  # SRV
            self._record(instance, 16, txt),  # TXT
            self._record(host, 1, socket.inet_aton(self.address)),  # A
        ]
        header = struct.pack("!HHHHHH", 0, 0x8400, 0, len(answers), 0, 0)
        return header + b"".join(answers)

    def start(self) -> None:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("", 5353))
            mreq = struct.pack("4s4s", socket.inet_aton(self.MULTICAST), socket.inet_aton("0.0.0.0"))
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
            sock.settimeout(0.5)
        except OSError as exc:
            LOG.warning("mDNS advertise unavailable: %s", exc)
            LOG.warning("继续只跑 PTP/IP；手机端请手动填写本机 IP")
            return
        self.sock = sock
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()
        LOG.info("mDNS advertising %s._ptp._tcp.local on %s:%d", self.instance, self.address, self.port)

    def _loop(self) -> None:
        assert self.sock is not None
        last_announce = 0.0
        while not self.stop_event.is_set():
            try:
                self.sock.settimeout(0.5)
                data, address = self.sock.recvfrom(4096)
                # Answer any DNS query that mentions _ptp or _services.
                if b"_ptp" in data or b"_services" in data:
                    self.sock.sendto(self._response(), (self.MULTICAST, 5353))
                    if address[0] != "0.0.0.0":
                        self.sock.sendto(self._response(), address)
            except socket.timeout:
                pass
            except OSError as exc:
                LOG.debug("mDNS read error: %s", exc)
            now = time.monotonic()
            if now - last_announce > 30:
                try:
                    assert self.sock is not None
                    self.sock.sendto(self._response(), (self.MULTICAST, 5353))
                except OSError:
                    pass
                last_announce = now

    def stop(self) -> None:
        self.stop_event.set()
        if self.thread:
            self.thread.join(timeout=1)
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass


def _local_ip() -> str:
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.connect(("8.8.8.8", 80))
        ip = sock.getsockname()[0]
        sock.close()
        return ip
    except OSError:
        return socket.gethostbyname(socket.gethostname())


def main() -> int:
    parser = argparse.ArgumentParser(description="Mock Nikon PTP/IP camera (handshake + device info only)")
    parser.add_argument("--host", default="0.0.0.0", help="bind address (default: all interfaces)")
    parser.add_argument("--port", type=int, default=PTP_PORT, help="PTP/IP TCP port (default: 15740)")
    parser.add_argument("--model", default="Z50II (Mock)", help="model reported in DeviceInfo")
    parser.add_argument("--firmware", default="MOCK 1.0", help="firmware string reported in DeviceInfo")
    parser.add_argument("--serial", default="MOCK0001", help="serial number reported in DeviceInfo")
    parser.add_argument("--responder-name", default="Nikon Z50II Mock", help="responder name in InitCommandAck")
    parser.add_argument("--advertise", action="store_true", help="advertise _ptp._tcp.local over mDNS")
    parser.add_argument("--mdns-ip", default="", help="IP to put in the mDNS A record (default: auto)")
    parser.add_argument("--reject", type=int, default=0, metavar="REASON",
                        help="reply InitFail with this reason instead of accepting (for failure tests)")
    parser.add_argument("--verbose", "-v", action="store_true", help="print packet-level logs")
    args = parser.parse_args()

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    if not 1 <= args.port <= 65535:
        parser.error("port must be between 1 and 65535")
    args.responder_guid = uuid.uuid4().bytes

    camera = MockCamera(args)
    advertiser = None
    if args.advertise:
        advertiser = MDNSAdvertiser(
            instance=args.model,
            host="mock-nikon",
            address=args.mdns_ip or _local_ip(),
            port=args.port,
        )
        advertiser.start()

    try:
        camera.serve_forever()
    except KeyboardInterrupt:
        LOG.info("stopping on Ctrl+C")
    finally:
        camera.close()
        if advertiser:
            advertiser.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
