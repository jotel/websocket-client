/*
 * Copyright 2012 Jotel Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package net.jotel.ws.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.commons.lang.UnhandledException;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.junit.Ignore;
import org.junit.Test;

public class WebSocketClientTest {

    static TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return null;
        }

        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
        }

        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
        }
    } };

    static {
        // Install the all-trusting trust manager
        try {
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String string, SSLSession ssls) {
                    return true;
                }
            });
                        
            SSLContext.setDefault(sc);                        
        } catch (Exception e) {
        }
    }

    @Ignore 
    @Test
    public void test() throws URISyntaxException, IOException, NoSuchAlgorithmException, InterruptedException {
        URI uri = new URI("ws://localhost:9080");

        WebSocketClient ws = new WebSocketClient();
        ws.setWebSocketUri(uri);
        ws.connect();
        try {

            WebSocketListener listener = new WebSocketListener() {
                @Override
                public void onMessage(String message) {
                    System.out.println(message);
                }

                @Override
                public void onError(Exception ex) {
                    ex.printStackTrace();
                }

				@Override
                public void onMessage(byte[] message) {
                    System.out.println(Arrays.toString(message));	                
                }

				@Override
                public void onClose(Integer statusCode, String message) {
	                // TODO Auto-generated method stub
	                
                }

				@Override
                public void onConnect() {
	                // TODO Auto-generated method stub
	                
                }
            };

            ws.addListener(listener);
            String request = "Hello";
            for (int i = 0; i < 100; i++) {
                ws.send((request + " " + i).getBytes());
            }

            Thread.sleep(10000L);
        } finally {
            ws.close();
        }
    }

    @Test
    public void reconnect() throws Exception {

        final List<Exception> exceptions = new ArrayList<Exception>();

        URI uri = new URI("ws://not-existing-domain-name:8080/websocket/ws/subscribe");
        final WebSocketClient c = new WebSocketClient();
        c.setWebSocketUri(uri);
        c.setReconnectEnabled(true);
        c.setReconnectInterval(100L);
        c.setReconnectAttempts(2);
        c.addListener(new WebSocketListener() {
            @Override
            public void onMessage(String message) {
            }

            @Override
            public void onMessage(byte[] message) {
            }

            @Override
            public void onError(Exception ex) {
                exceptions.add(ex);
            }

			@Override
            public void onClose(Integer statusCode, String message) {
	            // TODO Auto-generated method stub
	            
            }

			@Override
            public void onConnect() {
	            // TODO Auto-generated method stub
	            
            }
        });

        try {
            c.connect();
            fail("Expected WebSocketException");
        } catch (WebSocketException ex) {
            // expected
            assertEquals(3, exceptions.size());

            for (Exception e : exceptions) {
                Throwable rootCause = ExceptionUtils.getRootCause(e);
                if (rootCause == null) {
                    rootCause = e;
                }

                assertTrue(rootCause instanceof UnknownHostException);
            }
        }

        exceptions.clear();
        c.setReconnectAttempts(0);

        try {
            c.connect();
            fail("Expected WebSocketException");
        } catch (WebSocketException ex) {
            // expected
            assertEquals(1, exceptions.size());

            for (Exception e : exceptions) {
                Throwable rootCause = ExceptionUtils.getRootCause(e);
                if (rootCause == null) {
                    rootCause = e;
                }

                assertTrue(rootCause instanceof UnknownHostException);
            }
        }

        exceptions.clear();
        c.setReconnectAttempts(-1);

        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<?> future = executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    c.connect();
                    fail("Expected WebSocketException");
                } catch (WebSocketException ex) {
                    throw new UnhandledException(ex);
                }
            }
        });

        Thread.sleep(2000L);

        c.setReconnectEnabled(false);

        Thread.sleep(2000L);

        executor.shutdown();
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));

        try {
            future.get();
            fail("Expected WebSocketException");
        } catch (Exception ex) {
            // expected
            assertTrue(exceptions.size() > 1);

            for (Exception e : exceptions) {
                Throwable rootCause = ExceptionUtils.getRootCause(e);
                if (rootCause == null) {
                    rootCause = e;
                }

                assertTrue(rootCause instanceof UnknownHostException);
            }
        }
    }

    @Test
    public void testWebSocketUriNotSet() {
        WebSocketClient ws = new WebSocketClient();

        try {
            ws.connect();
            fail("WebSocketException expected");
        } catch (WebSocketException ex) {
            // expected
        }
    }

    @Test
    public void testValidateWebSocketUri() throws WebSocketException, URISyntaxException {

        // should not fail
        WebSocketClient.validateWebSocketUri(new URI("ws://localhost/path?query"));

        // should not fail
        WebSocketClient.validateWebSocketUri(new URI("wss://localhost/path?query"));

        // should not fail
        WebSocketClient.validateWebSocketUri(new URI("ws://localhost"));

        // should not fail
        WebSocketClient.validateWebSocketUri(new URI("ws://localhost"));

        try {
            // fragment is present
            WebSocketClient.validateWebSocketUri(new URI("http://localhost/path?query#part"));
            fail("WebSocketException expected");
        } catch (WebSocketException ex) {
            // expected
        }

        try {
            // wrong scheme
            WebSocketClient.validateWebSocketUri(new URI("http://localhost/path?query"));
            fail("WebSocketException expected");
        } catch (WebSocketException ex) {
            // expected
        }

        try {
            // uri is not absolute
            WebSocketClient.validateWebSocketUri(new URI("test"));

            fail("WebSocketException expected");
        } catch (WebSocketException ex) {
            // expected
        }

        try {
            // host is empty
            WebSocketClient.validateWebSocketUri(new URI("ws:/path"));

            fail("WebSocketException expected");
        } catch (WebSocketException ex) {
            // expected
        }

    }   
}
