/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.ssl;

import java.security.KeyStore;
import java.util.Collections;

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.EmptyArrays;

import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.TrustManagerFactorySpi;
import javax.net.ssl.X509TrustManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ReferenceCountedOpenSslEngineTest extends OpenSslEngineTest {

    @Override
    protected SslProvider sslClientProvider() {
        return SslProvider.OPENSSL_REFCNT;
    }

    @Override
    protected SslProvider sslServerProvider() {
        return SslProvider.OPENSSL_REFCNT;
    }

    @Override
    protected void cleanupClientSslContext(SslContext ctx) {
        ReferenceCountUtil.release(ctx);
    }

    @Override
    protected void cleanupClientSslEngine(SSLEngine engine) {
        ReferenceCountUtil.release(unwrapEngine(engine));
    }

    @Override
    protected void cleanupServerSslContext(SslContext ctx) {
        ReferenceCountUtil.release(ctx);
    }

    @Override
    protected void cleanupServerSslEngine(SSLEngine engine) {
        ReferenceCountUtil.release(unwrapEngine(engine));
    }

    @MethodSource("newTestParams")
    @ParameterizedTest
    public void testNotLeakOnException(SSLEngineTestParam param) throws Exception {
        clientSslCtx = wrapContext(param, SslContextBuilder.forClient()
                                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                        .sslProvider(sslClientProvider())
                                        .protocols(param.protocols())
                                        .ciphers(param.ciphers())
                                        .build());

        assertThrows(NullPointerException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                clientSslCtx.newEngine(null);
            }
        });
    }

    @SuppressWarnings("deprecation")
    @Override
    protected SslContext wrapContext(SSLEngineTestParam param, SslContext context) {
        if (context instanceof ReferenceCountedOpenSslContext) {
            if (param instanceof OpenSslEngineTestParam) {
                ((ReferenceCountedOpenSslContext) context).setUseTasks(((OpenSslEngineTestParam) param).useTasks);
            }
            // Explicit enable the session cache as its disabled by default on the client side.
            ((ReferenceCountedOpenSslContext) context).sessionContext().setSessionCacheEnabled(true);
        }
        return context;
    }

    @MethodSource("newTestParams")
    @ParameterizedTest
    public void testSniWithPort(SSLEngineTestParam param) throws Exception {
        if (clientSslContextProvider() != null) {
            // Not supported when using conscrypt
            return;
        }
        String fqdn = "something.netty.io";
        SelfSignedCertificate cert = new SelfSignedCertificate(fqdn);
        clientSslCtx = wrapContext(param, SslContextBuilder
            .forClient()
            .trustManager(new TrustManagerFactory(new TrustManagerFactorySpi() {
                @Override
                protected void engineInit(KeyStore keyStore) {
                    // NOOP
                }
                @Override
                protected TrustManager[] engineGetTrustManagers() {
                    // Provide a custom trust manager, this manager trust all certificates
                    return new TrustManager[] {
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(
                                java.security.cert.X509Certificate[] x509Certificates, String s) {
                                // NOOP
                            }

                            @Override
                            public void checkServerTrusted(
                                java.security.cert.X509Certificate[] x509Certificates, String s) {
                                // NOOP
                            }

                            @Override
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                return EmptyArrays.EMPTY_X509_CERTIFICATES;
                            }
                        }
                    };
                }

                @Override
                protected void engineInit(ManagerFactoryParameters managerFactoryParameters) {
                }
            }, null, TrustManagerFactory.getDefaultAlgorithm()) {
            })
            .sslContextProvider(clientSslContextProvider())
            .sslProvider(sslClientProvider())
            .build());

        SSLEngine client = wrapEngine(clientSslCtx.newEngine(UnpooledByteBufAllocator.DEFAULT, "127.0.0.1", 1234));

        if (client instanceof OpenSslErrorStackAssertSSLEngine) {
            OpenSslErrorStackAssertSSLEngine engine = (OpenSslErrorStackAssertSSLEngine) client;
            engine.setStrictSniNames(false);
        } else {
            throw new IllegalStateException("the right class: " + client.getClass().getSimpleName());
        }

        SSLParameters sslParameters = client.getSSLParameters();
        sslParameters.setServerNames(Collections.<SNIServerName>singletonList(
            new SNIHostName((fqdn + ":456").getBytes())));
        client.setSSLParameters(sslParameters);

        serverSslCtx = wrapContext(param, SslContextBuilder
            .forServer(cert.certificate(), cert.privateKey())
            .sslContextProvider(serverSslContextProvider())
            .sslProvider(sslServerProvider())
            .build());

        SSLEngine server = wrapEngine(serverSslCtx.newEngine(UnpooledByteBufAllocator.DEFAULT));
        try {
            handshake(param.type(), param.delegate(), client, server);
        } finally {
            cleanupClientSslEngine(client);
            cleanupServerSslEngine(server);
            cert.delete();
        }
    }

    @MethodSource("newTestParams")
    @ParameterizedTest
    public void parentContextIsRetainedByChildEngines(SSLEngineTestParam param) throws Exception {
        SslContext clientSslCtx = wrapContext(param, SslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .sslProvider(sslClientProvider())
            .protocols(param.protocols())
            .ciphers(param.ciphers())
            .build());

        SSLEngine engine = clientSslCtx.newEngine(UnpooledByteBufAllocator.DEFAULT);
        assertEquals(ReferenceCountUtil.refCnt(clientSslCtx), 2);

        cleanupClientSslContext(clientSslCtx);
        assertEquals(ReferenceCountUtil.refCnt(clientSslCtx), 1);

        cleanupClientSslEngine(engine);
        assertEquals(ReferenceCountUtil.refCnt(clientSslCtx), 0);
    }
}
