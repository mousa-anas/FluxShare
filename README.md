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

Payloads are capped at 8 MiB. Frame types: `HELLO`, `MANIFEST`, `MANIFEST_RESPONSE`, `DATA`, `FILE_DIGEST`, `ERROR`, `TRANSFER_COMPLETE`. Every field is length-prefixed and bounds-checked; invalid UTF-8, unknown types, unknown flags and truncated or oversized frames are rejected as `FluxShareError.ProtocolViolation`.

`TRANSFER_COMPLETE` carries the transfer identifier and confirms that the receiver verified every file. The sender reports success only after it arrives; a session that closes after the last `FILE_DIGEST` without it counts as a failure.

Manifests describe flat file names only. Absolute paths, path separators, `.`/`..`, control characters, non-portable Windows characters, Windows reserved device names, duplicates (case-insensitive and Unicode-normalized) and size overflow are rejected so a receiver can never be tricked into writing outside its destination directory.

### Limitations

- **TCP sessions are neither encrypted nor authenticated in Phase 1.** Traffic can be read or modified by anyone on the network; use only on trusted networks.
- `HELLO` identities are self-asserted; there is no pairing or device verification yet.
- No compression, resumption, keepalive, or application-level flow control beyond TCP.
- Directories and nested paths are not supported in manifests.
- Only the JVM-side TCP transport exists; Android integration (foreground service, discovery) comes later.
- The `:transfer` module implements the sender and receiver engines, but the app UI does not drive them yet; they are currently exercised by JVM tests.

## Phase 3: authenticated and encrypted sessions

The `:security` module wraps a raw `TransportSession` in JDK TLS (`SSLEngine`) with mutual authentication based on self-signed X.509 identities. It implements no cryptography of its own; Bouncy Castle is used only to encode identity certificates.

- Each device has an ECDSA P-256 self-signed certificate that carries its `DeviceId` in a `fluxshare:device:<id>` SAN and is identified by the SHA-256 fingerprint of its DER encoding.
- Peers are pinned by fingerprint. A known device presenting a different certificate fails hard (`PeerIdentityChanged`); an unknown device is paired by comparing a ten-digit code derived from both fingerprints, or by scanning a `fluxshare://pair?id=<deviceId>&fp=<hex>` link.
- Mutual TLS: the server requires a client certificate. Only TLS 1.3 and TLS 1.2 with AEAD suites are enabled. Trust is decided after the handshake and before any application data is exchanged.
- The transfer engines are not wired to `:security` yet; that is Phase 3 part 2.

See [`security/README.md`](security/README.md) for the threat model and design.

### Tests

Focused JVM tests live in `protocol/src/test`, `transport/src/test`, `security/src/test` and `transfer/src/test` and run with `./gradlew :protocol:test :transport:test :security:test :transfer:test`.

## License

MIT License
