# Nikon connection and data-flow notes

This document describes the implementation in the 3.26 source baseline. PTP/IP and Nikon-specific operation behavior is not a public compatibility promise; firmware and camera configuration can change the result.

## Connection and pairing

1. The user joins the camera's Wi-Fi in camera-AP mode, joins the phone hotspot after setting the camera to STA, or joins the same router in router mode. The app takes the camera IPv4 address from the user or local discovery.
2. PTP/IP command and event TCP sockets connect to port `15740`. For a routed Wi-Fi target, the app selects the Android Wi-Fi `Network` whose route reaches the camera and creates sockets from that network's socket factory. The phone-hotspot path relies on Android's active local routing rather than choosing a different Wi-Fi `Network` object.
3. The command channel sends an `InitCommandRequest` with a persistent app-generated GUID, the friendly name `LutCameraPro`, and protocol version. It does not put a Nikon or Capture One product identity in that field.
4. After the camera's `InitCommandAck`, the app opens the event channel using the returned connection number, opens PTP session 1, and requests `GetDeviceInfo`. A transport connection and an open PTP session do not themselves mean every camera capability is permitted.
5. If the camera advertises only the restricted operations recognized by the client, the app blocks ordinary control/live-view operations and allows only the limited pairing/diagnostic flow. The experimental Nikon pairing operations are `0x952B` and `0x935A`; the user must complete and verify the camera's on-screen wizard. This compatibility path needs testing on every intended model/firmware.
6. The app persists its own client GUID so an accepted pairing can survive ordinary restarts. A recovery helper can restore that same GUID from an exported trace containing both the app's sent identity and the camera's accepted handshake. This recovers a prior identity; it does not create authorization for a camera that has never accepted it.

The code serializes command-channel PTP transactions with a mutex so concurrent UI actions cannot interleave packet data phases and responses. PTP/IP packets are length-checked, transaction IDs are checked, data phases are bounded, and a sent transaction is drained before cancellation is allowed to interrupt its caller.

## Live view and controls

- Live view starts with Nikon operation `0x9201`, waits for the camera-ready response, then requests frames with `0x9203`; it stops with `0x9202`.
- Received JPEGs pass through a conflated channel and decode on a worker dispatcher. If decoding is slower than acquisition, stale frames can be dropped to keep the preview current.
- The metrics distinguish receive FPS, decode FPS, payload throughput, request time, decode time, and dropped frames. They do not measure screen refresh.
- Shutter, focus, and exposure controls use PTP operations and device-property codes. The app checks returned status codes; controls are model/firmware dependent and should be described as supported only where tested.

## Still-photo path

- When auto-JPEG is enabled and the camera exposes the relevant event mechanism, the client handles newly added object events, reads object metadata, retrieves the object, verifies JPEG boundaries, then stages it in the private photo inbox.
- Camera object information/download use `GetObjectInfo` (`0x1008`) and `GetObject` (`0x1009`). Cameras can allow camera control but deny these reads; the app reports that separately.
- SD-card listing and download use the PTP object/storage operations. The Z 30 STA results have not been reliable enough to call this feature verified.
- LUT, frame, and watermark processing happens after transfer in the app's photo workflow. No image payload is written into the connection trace.

## Diagnostics and privacy

`NikonTrace` keeps a bounded rolling set of text logs under the app's private files directory. ZIP export includes connection route/environment details and sampled PTP/IP packet summaries/hex. Large live-view payloads are represented by a short prefix; full image data is not exported. Logs can still contain local IP addresses, phone model, camera identifiers, and persistent pairing identity or pairing traffic. Review and redact an archive before publishing it in an issue. Hotspot credentials are not collected by this logger.

## Source and protocol references

- PTP/IP framing and standard PTP operation names/codes are implemented in the app's Kotlin source.
- The Nikon pairing sequence was compared with the public `open-nikonz/zf-capture-one-helper` research project; that project is Apache-2.0. No source files or fixture files from it are bundled here. See `THIRD_PARTY_NOTICES.md`.
- `libgphoto2` is not a dependency and no libgphoto2 source/header is bundled in this tree. Earlier source review informed protocol investigation; the repository should preserve the distinction between protocol facts and copied code.
- This project does not include Nikon SDK code or claim Nikon authorization. This note is a technical provenance record, not a legal conclusion about patents, anti-circumvention rules, or every future jurisdiction.
