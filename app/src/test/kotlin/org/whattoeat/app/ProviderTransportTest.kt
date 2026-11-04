package org.whattoeat.app

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.whattoeat.core.Catalog

class ProviderTransportTest {
    private fun secureServer(block: (MockWebServer, OkHttpClient) -> Unit) {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .followRedirects(false).followSslRedirects(false).build()
        try { block(server, client) } finally { server.shutdown() }
    }
    @Test fun configuredBasePathAndAuthorizationAreSentOnlyToChosenProvider() = secureServer { server, client ->
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{\"foodIds\":[\"food-001\"]}"}}]}"""))
        val provider = CompatibleModelProvider(ModelConfig(server.url("/v1").toString(), "test-model", "test-only-key"), client)
        val result = runBlocking { provider.normalize("番茄炒蛋", Catalog.load()) }
        assertEquals(listOf("food-001"), result)
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer test-only-key", request.getHeader("Authorization"))
        assertFalse(request.body.readUtf8().contains("test-only-key"))
    }
    @Test fun redirectsFailWithoutFollowingOrSavingAnAnswer() = secureServer { server, client ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        val provider = CompatibleModelProvider(ModelConfig(server.url("/v1/").toString(), "test", "test-key"), client)
        assertThrows(Exception::class.java) { runBlocking { provider.normalize("番茄炒蛋", Catalog.load()) } }
        assertEquals(1, server.requestCount)
    }
    @Test fun providerFailureDoesNotExposeResponseOrCredentials() = secureServer { server, client ->
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret-server-detail"))
        val provider = CompatibleModelProvider(ModelConfig(server.url("/v1").toString(), "test", "test-key"), client)
        val error = assertThrows(Exception::class.java) { runBlocking { provider.normalize("番茄炒蛋", Catalog.load()) } }
        assertFalse(error.message.orEmpty().contains("secret-server-detail"))
        assertFalse(error.message.orEmpty().contains("test-key"))
    }
}
