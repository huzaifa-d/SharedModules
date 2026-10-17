package com.liskovsoft.sharedutils.okhttp;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 25, 28})
public class OkHttpCommonsTest {
    @Test
    public void preservesCertificateAndHostnameVerification() {
        OkHttpClient original = new OkHttpClient();
        OkHttpClient configured = OkHttpCommons.setupBuilder(original.newBuilder()).build();

        assertSame(original.sslSocketFactory(), configured.sslSocketFactory());
        assertSame(original.hostnameVerifier(), configured.hostnameVerifier());
    }

    @Test
    public void rejectsUntrustedCertificate() throws IOException {
        HeldCertificate certificate = new HeldCertificate.Builder()
                .addSubjectAlternativeName("localhost")
                .build();

        try (MockWebServer server = httpsServer(certificate)) {
            OkHttpClient client = configuredClient(new OkHttpClient.Builder());

            try (Response response = client.newCall(new Request.Builder()
                    .url(server.url("/").newBuilder().host("localhost").build()).build()).execute()) {
                fail("An untrusted certificate was accepted");
            } catch (SSLHandshakeException expected) {
                assertEquals(0, server.getRequestCount());
            }
        }
    }

    @Test
    public void acceptsTrustedCertificate() throws IOException {
        HeldCertificate certificate = new HeldCertificate.Builder()
                .addSubjectAlternativeName("localhost")
                .build();

        try (MockWebServer server = httpsServer(certificate)) {
            OkHttpClient client = trustedClient(certificate);

            try (Response response = client.newCall(new Request.Builder()
                    .url(server.url("/").newBuilder().host("localhost").build()).build()).execute()) {
                assertTrue(response.isSuccessful());
                assertEquals("verified", response.body().string());
            }
        }
    }

    @Test
    public void rejectsWrongHostnameEvenWhenCertificateIsTrusted() throws IOException {
        HeldCertificate certificate = new HeldCertificate.Builder()
                .addSubjectAlternativeName("wrong.example")
                .build();

        try (MockWebServer server = httpsServer(certificate)) {
            OkHttpClient client = trustedClient(certificate);

            try (Response response = client.newCall(new Request.Builder()
                    .url(server.url("/").newBuilder().host("localhost").build()).build()).execute()) {
                fail("A certificate for the wrong hostname was accepted");
            } catch (SSLPeerUnverifiedException expected) {
                assertEquals(0, server.getRequestCount());
            }
        }
    }

    private static MockWebServer httpsServer(HeldCertificate certificate) throws IOException {
        HandshakeCertificates certificates = new HandshakeCertificates.Builder()
                .heldCertificate(certificate)
                .build();
        MockWebServer server = new MockWebServer();
        server.useHttps(certificates.sslSocketFactory(), false);
        server.enqueue(new MockResponse().setBody("verified"));
        server.start();
        return server;
    }

    private static OkHttpClient trustedClient(HeldCertificate certificate) {
        HandshakeCertificates certificates = new HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate.certificate())
                .build();
        return configuredClient(new OkHttpClient.Builder()
                .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager()));
    }

    private static OkHttpClient configuredClient(OkHttpClient.Builder builder) {
        return OkHttpCommons.setupBuilder(builder)
                .proxy(Proxy.NO_PROXY)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
    }
}
