# FluxShare

A fast, free and open-source file sharing app for Android.

## Features

- Adaptive file transfer
- Automatic connection discovery
- Fast local network transfers
- Secure file sharing
- Lightweight native Android application

## Technology

- Kotlin
- Jetpack Compose
- Android SDK

## Phase 1: transport and protocol

Phase 1 adds a transport-independent protocol layer and a working TCP transport. Android UI, discovery, pairing, encryption and the foreground service are intentionally **not** part of this phase.

### Modules

- `:protocol` - FluxShare framing and codec, manifest encoding and validation, incremental SHA-256.
- `:transport` - `TcpTransport`, `TcpTransportListener` and `TcpTransportSession`, tuned for bulk transfer (Nagle disabled, larger socket buffers).

### Wire format (protocol version 1)

Every frame is a 12-byte header followed by a payload:

```
magic "FLXS" | version (u8) | type (u8) | flags (u16) | payload length (u32) | payload
```

Payloads are capped at 8 MiB. Frame types: `HELLO`, `MANIFEST`, `MANIFEST_RESPONSE`, `DATA`, `FILE_DIGEST`, `ERROR`. Every field is length-prefixed and bounds-checked; invalid UTF-8, unknown types, unknown flags and truncated or oversized frames are rejected as `FluxShareError.ProtocolViolation`.

Manifests describe flat file names only. Absolute paths, path separators, `.`/`..`, control characters, non-portable Windows characters, Windows reserved device names, duplicates (case-insensitive and Unicode-normalized) and size overflow are rejected so a receiver can never be tricked into writing outside its destination directory.

### Limitations

- **TCP sessions are neither encrypted nor authenticated in Phase 1.** Traffic can be read or modified by anyone on the network; use only on trusted networks.
- `HELLO` identities are self-asserted; there is no pairing or device verification yet.
- No compression, resumption, keepalive, or application-level flow control beyond TCP.
- Directories and nested paths are not supported in manifests.
- Only the JVM-side TCP transport exists; Android integration (foreground service, discovery) comes later.
- The sender-side transfer engine is not implemented yet, so these modules are currently exercised by JVM tests rather than by the app UI.

### Tests

Focused JVM tests live in `protocol/src/test` and `transport/src/test` and run with `./gradlew :protocol:test :transport:test`.

## License

MIT License
