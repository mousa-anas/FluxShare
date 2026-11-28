package app.fluxshare.security

import app.fluxshare.core.DeviceId
import app.fluxshare.core.FluxShareError
import app.fluxshare.core.FluxShareResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustDecisionTest {

    @Test
    fun `unknown peers see the same pairing code and both trust each other`() = runBlocking {
        withTimeout(30_000) {
            val clientIdentity = testIdentity("pair-client")
            val serverIdentity = testIdentity("pair-server")
            val clientTrust = InMemoryPeerTrustStore()
            val serverTrust = InMemoryPeerTrustStore()
            val clientCodes = mutableListOf<PairingCode>()
            val serverCodes = mutableListOf<PairingCode>()

            val sessions = establish(
                clientIdentity,
                serverIdentity,
                clientTrust = clientTrust,
                serverTrust = serverTrust,
                confirmClient = { _, code -> clientCodes += code; true },
                confirmServer = { _, code -> serverCodes += code; true },
            )

            assertEquals(1, clientCodes.size)
            assertEquals(1, serverCodes.size)
            assertEquals(clientCodes.single(), serverCodes.single())
            assertTrue(Regex("[0-9]{3} [0-9]{3} [0-9]{4}").matches(clientCodes.single().display))

            assertEquals(
                serverIdentity.fingerprint,
                clientTrust.fingerprintFor(serverIdentity.deviceId),
            )
            assertEquals(
                clientIdentity.fingerprint,
                serverTrust.fingerprintFor(clientIdentity.deviceId),
            )

            sessions.client.close()
            sessions.server.close()
        }
    }

    @Test
    fun `declining pairing fails the session and no application data flows`() = runBlocking {
        withTimeout(30_000) {
            val clientIdentity = testIdentity("decline-client")
            val serverIdentity = testIdentity("decline-server")
            val serverTrust = InMemoryPeerTrustStore()
            val wire = MemoryWire()

            coroutineScope {
                val serverSecurity = DefaultSessionSecurity(
                    serverIdentity,
                    serverTrust,
                    { _, _ -> false },
                )
                val clientSecurity = DefaultSessionSecurity(
                    clientIdentity,
                    InMemoryPeerTrustStore(),
                    { _, _ -> true },
                )

                val serverDeferred = async { serverSecurity.acceptSecure(wire.server) }
                val clientResult = clientSecurity.secure(wire.client, serverIdentity.deviceId)
                val serverResult = serverDeferred.await()

                assertTrue(serverResult is FluxShareResult.Failure, "Expected decline but was $serverResult")
                assertEquals(FluxShareError.SecurityFailure, serverResult.error)
                assertTrue(serverTrust.trustedPeers().isEmpty())

                // The accepting side must not be able to read application data.
                val clientSession = clientResult.getOrFail()
                assertEquals(-1, withTimeout(5_000) { clientSession.read(ByteArray(8)) })
                clientSession.close()
            }
        }
    }

    @Test
    fun `an already paired peer is never prompted`() = runBlocking {
        withTimeout(30_000) {
            val clientIdentity = testIdentity("known-client")
            val serverIdentity = testIdentity("known-server")
            var prompts = 0

            val sessions = establish(
                clientIdentity,
                serverIdentity,
                confirmClient = { _, _ -> prompts++; false },
                confirmServer = { _, _ -> prompts++; false },
            )

            assertEquals(0, prompts)
            assertTrue(sessions.client.isOpen)

            sessions.client.close()
            sessions.server.close()
        }
    }

    @Test
    fun `a known device with a different certificate fails without prompting`() = runBlocking {
        withTimeout(30_000) {
            val clientIdentity = testIdentity("rotate-client")
            val originalServer = testIdentity("rotate-server")
            // Same DeviceId, new key pair: a reinstalled or impersonated device.
            val rotatedServer = InMemoryIdentityStore(originalServer.deviceId).loadOrCreate().getOrFail()
            val clientTrust = InMemoryPeerTrustStore(
                mapOf(originalServer.deviceId to originalServer.fingerprint),
            )
            var clientPrompts = 0
            var serverPrompts = 0
            val wire = MemoryWire()

            coroutineScope {
                val clientSecurity = DefaultSessionSecurity(
                    clientIdentity,
                    clientTrust,
                    { _, _ -> clientPrompts++; true },
                )
                val serverSecurity = DefaultSessionSecurity(
                    rotatedServer,
                    InMemoryPeerTrustStore(mapOf(clientIdentity.deviceId to clientIdentity.fingerprint)),
                    { _, _ -> serverPrompts++; true },
                )

                val serverDeferred = async { serverSecurity.acceptSecure(wire.server) }
                val clientResult = clientSecurity.secure(wire.client, rotatedServer.deviceId)
                serverDeferred.await()

                assertTrue(clientResult is FluxShareResult.Failure, "Expected failure but was $clientResult")
                val error = clientResult.error
                assertTrue(
                    error is FluxShareError.PeerIdentityChanged,
                    "Expected PeerIdentityChanged but was $error",
                )
                assertEquals(0, clientPrompts)
                assertEquals(0, serverPrompts)
                assertEquals(
                    originalServer.fingerprint,
                    clientTrust.fingerprintFor(originalServer.deviceId),
                )
            }
        }
    }

    @Test
    fun `fails when the authenticated device is not the expected one`() = runBlocking {
        withTimeout(30_000) {
            val clientIdentity = testIdentity("expected-client")
            val serverIdentity = testIdentity("expected-server")
            val wire = MemoryWire()

            coroutineScope {
                val serverSecurity = DefaultSessionSecurity(
                    serverIdentity,
                    InMemoryPeerTrustStore(mapOf(clientIdentity.deviceId to clientIdentity.fingerprint)),
                    { _, _ -> true },
                )
                val clientSecurity = DefaultSessionSecurity(
                    clientIdentity,
                    InMemoryPeerTrustStore(mapOf(serverIdentity.deviceId to serverIdentity.fingerprint)),
                    { _, _ -> true },
                )

                val serverDeferred = async { serverSecurity.acceptSecure(wire.server) }
                val clientResult = clientSecurity.secure(wire.client, DeviceId("some-other-device"))
                serverDeferred.await()

                assertTrue(clientResult is FluxShareResult.Failure, "Expected failure but was $clientResult")
                assertEquals(
                    FluxShareError.SecurityFailure,
                    clientResult.error,
                )
                assertFalse(wire.client.isOpen)
            }
        }
    }

    @Test
    fun `rate limits pairing prompts and allows them again after a minute`() = runBlocking {
        withTimeout(60_000) {
            var now = 1_000L
            var prompts = 0
            val clientIdentity = testIdentity("limit-client")
            val clientSecurity = DefaultSessionSecurity(
                identity = clientIdentity,
                trustStore = InMemoryPeerTrustStore(),
                confirmPairing = { _, _ -> prompts++; false },
                promptLimiter = PairingPromptLimiter(maxPromptsPerMinute = 1, clockMillis = { now }),
            )

            suspend fun attempt() {
                val serverIdentity = testIdentity("limit-server-${prompts}-${now}")
                val wire = MemoryWire()
                val serverSecurity = DefaultSessionSecurity(
                    serverIdentity,
                    InMemoryPeerTrustStore(mapOf(clientIdentity.deviceId to clientIdentity.fingerprint)),
                    { _, _ -> true },
                )
                coroutineScope {
                    val serverDeferred = async { serverSecurity.acceptSecure(wire.server) }
                    val result = clientSecurity.secure(wire.client, serverIdentity.deviceId)
                    assertTrue(result is FluxShareResult.Failure, "Expected failure but was $result")
                }
            }

            attempt()
            attempt()
            attempt()
            assertEquals(1, prompts, "Only one prompt within the one-minute window")

            now += PairingPromptLimiter.WINDOW_MILLIS
            attempt()
            assertEquals(2, prompts, "The window must release the limit")
        }
    }
}
