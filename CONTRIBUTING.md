# Contributing

Bug reports and pull requests are welcome. Please include the camera model, firmware, Android version, connection mode, and the exact operation that failed. Check diagnostic ZIPs for private identifiers and IP addresses before attaching them.

## Development rules

- Keep PTP/IP command transactions serialized and validate packet lengths, transaction IDs, and response codes.
- Do not add unbounded image or packet logging. Diagnostic output must remain bounded and must not contain complete JPEG/RAW payloads or Wi-Fi passwords.
- Mark model/firmware-specific behavior as experimental until it has been reproduced on real hardware. Separate app/socket connection success from camera authorization and file-read success.
- Add focused unit coverage for parsers and packet framing when changing those paths. Do not commit camera logs, photos, signing keys, local SDK paths, APKs, or personal test data.
- Keep Nikon trademarks and product identifiers descriptive. Do not present this project as an official Nikon application.

Unless a contribution explicitly says otherwise, submitting it for inclusion means it is offered under the repository's Apache-2.0 license, consistent with section 5 of that license.
