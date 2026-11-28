# :security — authenticated, encrypted sessions

` :security` turns a raw `TransportSession` into a `SecureSession` using nothing
but JDK TLS (`SSLEngine`) and standard X.509. It implements no cipher, key
exchange, MAC or handshake of its own. The only third-party dependency is
Bouncy Castle, and only to *encode* the self-signed identity certificate.

## Threat model

The adversary is on the same local network and can read, modify, replay, drop
and reorder traffic, and can run a rogue FluxShare device that speaks the
protocol correctly.

### In scope

- **Confidentiality** — traffic is TLS; file contents, manifests and identifiers
  are never sent in the clear.
- **Integrity** — AEAD only; a modified record fails to decrypt and the session
  fails instead of delivering altered bytes.
- **Peer authentication** — mutual TLS: both sides present an ECDSA P-256
  certificate and prove possession of its key.
- **Impersonation after pairing** — a paired peer is pinned by the SHA-256
  fingerprint of its certificate. A different certificate for the same
  `DeviceId` is a hard failure (`FluxShareError.PeerIdentityChanged`), never a
  prompt and never an automatic re-trust.
- **Man in the middle during pairing** — the first session is authenticated by a
  ten-digit code derived from both fingerprints, compared out of band
  (`PairingCode`), or by scanning a `fluxshare://pair` link that carries the
  peer fingerprint. A substituted certificate changes the code.

### Out of scope

- Compromised devices: malware, a rooted phone, a stolen unlocked device, or a
  user who taps "accept" for an attacker's code. The out-of-band comparison is
  the only defence there, and it depends on the user.
- Traffic analysis, timing and other side channels, and anonymity.
- Denial of service: an attacker on the LAN can always flood or jam.

## Design

### Identity

Each installation has one long-lived identity:

- a self-signed X.509 certificate, ECDSA P-256 (`secp256r1`), valid for ten
  years, with `digitalSignature` key usage and server/client auth extended key
  usage;
- the `DeviceId` in a subject alternative name URI `fluxshare:device:<id>`, which
  the peer reads and verifies;
- `CertificateFingerprint` = SHA-256 of the certificate DER, which is the only
  thing peers pin.

`IdentityStore.loadOrCreate()` hides persistence. `InMemoryIdentityStore` is for
tests, `Pkcs12IdentityStore` is the JVM implementation (atomic write, owner-only
permissions). The private key lives in a `KeyStore` and is only exposed through
a `KeyManager`; `DeviceIdentity` has no accessor for key material. The Android
Keystore implementation will implement the same interface.

### TLS policy

- Protocols: TLS 1.3 and TLS 1.2 only.
- Suites: AES-GCM and ChaCha20-Poly1305 only, with ECDHE-ECDSA suites as the
  TLS 1.2 fallback. CBC, RSA key exchange, static DH and 3DES stay disabled.
- Server side requires a client certificate (`needClientAuth`).
- No hostname or CA validation: there is no PKI. The trust manager accepts any
  certificate *during* the handshake so it can complete, and the real decision
  is made afterwards, before any application byte is read or written.
- Session resumption is disabled, so every session performs a full handshake and
  therefore a fresh trust decision.

### Trust decision (after handshake, before data)

| Peer state | Result |
| --- | --- |
| Fingerprint pinned for that `DeviceId` | accepted, no prompt |
| `DeviceId` known, different fingerprint | `PeerIdentityChanged`, connection closed, no prompt |
| Unknown `DeviceId` | `confirmPairing(deviceId, code)`; accept pins the certificate, decline or timeout fails with `SecurityFailure` |

Unknown-peer prompts are rate limited (`PairingPromptLimiter`, default three per
minute, configurable) so a rogue device cannot flood the user with dialogs.

### Session

`TlsSecureSession` drives `SSLEngine` wrap/unwrap loops, including delegated
tasks, buffer growth, TLS 1.3 post-handshake messages and `KeyUpdate` during
reads. The engine lock is never held across a blocking read or write, so one
coroutine may read while another writes. `close()` closes the underlying session
so a blocked read returns promptly, and sends `close_notify` best-effort, bounded
by a watchdog so shutdown can never hang.

Application data that arrives with the last handshake flight is buffered inside
the channel and only handed out by `read()`, which the caller only reaches after
`DefaultSessionSecurity` accepted the peer.

### Known limitations

- `close_notify` is best effort. A truncated TLS stream is reported as end of
  stream; truncation is detected at the protocol layer (frame lengths, per-file
  digests and `TRANSFER_COMPLETE`), not by TLS alone.
- TLS 1.2 has no encrypted handshake; certificates and `DeviceId` are visible to
  a passive attacker (the fingerprints are public anyway). TLS 1.3 is preferred
  whenever both peers support it.
- Pins live in memory for now; a restart of the JVM process forgets paired peers.
- The JVM identity file is protected by a password the caller supplies and by
  file permissions, not by hardware.
- A blocked transport *write* cannot be cancelled by coroutine cancellation; the
  close path handles it with a watchdog, but callers should still close the
  session to abort a stuck write.

## API

```kotlin
val security = DefaultSessionSecurity(identity, trustStore) { deviceId, code ->
    askTheUserToCompare(deviceId, code) // true when the codes match
}

// connecting side: fails unless the peer proves exactly this DeviceId
val client: SecureSession = security.secure(rawSession, expectedDeviceId).getOrThrow()

// accepting side: returns whichever DeviceId the peer proved
val server: SecureSession = security.acceptSecure(rawSession).getOrThrow()
```

Both return a `SecureSession`, which is a `TransportSession`, so `FluxFrameStream`
and the transfer engines use it exactly like a raw session.

## Tests

`./gradlew :security:test` covers mutual handshake and negotiation, pairing
(shared code, accept, decline, pinned peers, certificate rotation, prompt
flooding), pairing links, confidentiality and integrity of the byte stream
(recorded wire bytes, one flipped ciphertext byte), multi-MiB and mixed-size
transfers, concurrent read/write, close-unblocks-read, handshake timeout, a real
`FluxFrameStream` round trip and a loopback `TcpTransport` session.
