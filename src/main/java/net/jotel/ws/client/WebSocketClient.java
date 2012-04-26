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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.CharEncoding;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketClient {
	private static final String CRLF = "\r\n";
	private static final String HOST_HEADER = "Host";
	private static final String UPGRADE_HEADER = "Upgrade";
	private static final String CONNECTION_HEADER = "Connection";
	private static final String ORIGIN_HEADER = "Origin";
	private static final String SEC_WEB_SOCKET_KEY_HEADER = "Sec-WebSocket-Key";
	private static final String SEC_WEB_SOCKET_VERSION_HEADER = "Sec-WebSocket-Version";
	private static final String SEC_WEB_SOCKET_PROTOCOL_HEADER = "Sec-WebSocket-Protocol";

	private static final int CLOSE_OPCODE = 0x8;
	private static final int TEXT_OPCODE = 0x1;
	private static final int BINARY_OPCODE = 0x2;
	static final int PING_OPCODE = 0x9;
	private static final int PONG_OPCODE = 0xa;

	public final static int MASK_SIZE = 4;

	public static final boolean DEFAULT_RECONNECT_ENABLED = false;
	public static final int DEFAULT_RECONNECT_ATTEMPTS = 6;
	public static final long DEFAULT_RECONNECT_INTERVAL = 5000L;
	public static final long DEFAULT_CLOSING_HANDSHAKE_TIMEOUT = 10000L;
	public static final long DEFAULT_PING_INTERVAL = 5000L;

	private static final Logger log = LoggerFactory.getLogger(WebSocketClient.class);

	private static final SecureRandom rnd = new SecureRandom();

	private boolean reconnectEnabled = DEFAULT_RECONNECT_ENABLED;
	private int reconnectAttempts = DEFAULT_RECONNECT_ATTEMPTS;
	private long reconnectInterval = DEFAULT_RECONNECT_INTERVAL;
	private long pingInterval = DEFAULT_PING_INTERVAL;
	private long closingHandshakeTimeout = DEFAULT_CLOSING_HANDSHAKE_TIMEOUT;

	private List<WebSocketListener> listeners = new ArrayList<WebSocketListener>();

	private boolean secure;
	private String host;
	private int port;
	private String resourceName;
	private String protocol;
	private String origin;

	Map<String, String> extraHeaders = new LinkedHashMap<String, String>();

	private Object sync = new Object();

	private boolean closed;
	private boolean closingHandshakeStarted;

	private Socket socket;
	private OutputStream outputStream;
	private InputStream inputStream;

	private WebSocketReaderThread readerThread;
	private WebSocketKeepAliveThread keepAliveThread;

	private AtomicBoolean reconnecting = new AtomicBoolean(false);

	private static class SecWebSocketKey {
		public static final int SIZE = 16;
		public static final String SERVER_KEY_HASH = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

		private byte[] nounce;
		private String key;
		private String serverKey;

		public SecWebSocketKey() throws WebSocketException {
			nounce = new byte[SIZE];
			rnd.nextBytes(nounce);
			key = Base64.encodeBase64String(nounce);
			serverKey = generateServerKey();
		}

		/**
		 * @return the key
		 */
		public String getKey() {
			return key;
		}

		public boolean isServerKeyValid(String serverKey) throws WebSocketException {
			return this.serverKey.equalsIgnoreCase(serverKey);
		}

		private String generateServerKey() throws WebSocketException {
			StringBuilder sb = new StringBuilder();
			sb.append(key).append(SERVER_KEY_HASH);

			final MessageDigest instance;
			try {
				instance = MessageDigest.getInstance("SHA-1");
				instance.update(sb.toString().getBytes(CharEncoding.ISO_8859_1));

				final byte[] digest = instance.digest();

				return Base64.encodeBase64String(digest);
			} catch (Exception ex) {
				throw new WebSocketException(ex);
			}
		}
	}

	public WebSocketClient() {
	}

	public WebSocketClient(String uri) throws WebSocketException, URISyntaxException {
		this(uri, null);
	}

	public WebSocketClient(String uri, String protocol) throws WebSocketException, URISyntaxException {
		this(new URI(uri), protocol);
	}

	public WebSocketClient(URI uri) throws WebSocketException {
		this(uri, null);
	}

	public WebSocketClient(URI uri, String protocol) throws WebSocketException {
		this(uri, protocol, null);
	}

	public WebSocketClient(URI uri, String protocol, Map<String, String> extraHeaders) throws WebSocketException {
		setWebSocketProtocol(protocol);
		setWebSocketUri(uri);
		setExtraHeaders(extraHeaders);
	}

	public void setWebSocketProtocol(String protocol) throws WebSocketException {
		synchronized (sync) {
			assertIsNotConnected();
			this.protocol = protocol;
		}
	}

	public void setWebSocketUri(URI uri) throws WebSocketException {
		synchronized (sync) {
			assertIsNotConnected();

			validateWebSocketUri(uri);

			secure = "wss".equalsIgnoreCase(uri.getScheme());
			host = uri.getHost();
			port = uri.getPort();
			if (port == -1) {
				port = secure ? 443 : 80;
			}

			resourceName = uri.getPath();
			if (StringUtils.isBlank(resourceName)) {
				resourceName = "/";
			}

			String query = uri.getQuery();
			if (query != null) {
				resourceName = resourceName + "?" + query;
			}

			origin = (secure ? "https" : "http") + "://" + host;
		}
	}

	public Map<String, String> getExtraHeaders() {
		return Collections.unmodifiableMap(extraHeaders);
	}

	public void setExtraHeaders(Map<String, String> extraHeaders) throws WebSocketException {
		synchronized (sync) {
			assertIsNotConnected();

			if (extraHeaders != null) {
				validateExtraHeaders(extraHeaders);

				this.extraHeaders.clear();
				this.extraHeaders.putAll(extraHeaders);
			} else {
				this.extraHeaders.clear();
			}
		}
	}

	private void validateExtraHeaders(Map<String, String> headers) throws WebSocketException {
		for (String name : headers.keySet()) {
			if (StringUtils.isBlank(name)) {
				throw new WebSocketException("Extrea header name cannot be blank!");
			}

			if (HOST_HEADER.equalsIgnoreCase(name) || UPGRADE_HEADER.equalsIgnoreCase(name) || CONNECTION_HEADER.equalsIgnoreCase(name) || ORIGIN_HEADER.equalsIgnoreCase(name)
			        || SEC_WEB_SOCKET_KEY_HEADER.equalsIgnoreCase(name) || SEC_WEB_SOCKET_VERSION_HEADER.equalsIgnoreCase(name) || SEC_WEB_SOCKET_PROTOCOL_HEADER.equalsIgnoreCase(name)) {
				throw new WebSocketException("Extra header named: " + name + " is not allowed!");
			}

		}
	}

	public static void validateWebSocketUri(URI uri) throws WebSocketException {
		if (uri == null) {
			throw new WebSocketException("URI is empty!");
		}

		if (!uri.isAbsolute()) {
			throw new WebSocketException("Absolute URI required!");
		}

		if (!StringUtils.equalsIgnoreCase("ws", uri.getScheme()) && !StringUtils.equalsIgnoreCase("wss", uri.getScheme())) {
			throw new WebSocketException("Unsupported URI scheme!");
		}

		if (StringUtils.isNotEmpty(uri.getFragment())) {
			throw new WebSocketException("URI fragment is not allowed!");
		}

		if (StringUtils.isBlank(uri.getHost())) {
			throw new WebSocketException("URI host component is empty!");
		}
	}

	public void addListener(WebSocketListener listener) {
		synchronized (listeners) {
			listeners.add(listener);
		}
	}

	public void removeListener(WebSocketListener listener) {
		synchronized (listeners) {
			listeners.remove(listener);
		}
	}

	public List<WebSocketListener> getListeners() {
		synchronized (listeners) {
			return Collections.unmodifiableList(listeners);
		}
	}

	/**
	 * @return the reconnectEnabled
	 */
	public boolean isReconnectEnabled() {
		return reconnectEnabled;
	}

	/**
	 * @param reconnectEnabled
	 *            the reconnectEnabled to set
	 */
	public void setReconnectEnabled(boolean reconnectEnabled) {
		this.reconnectEnabled = reconnectEnabled;
	}

	/**
	 * @return the reconnectAttempts
	 */
	public int getReconnectAttempts() {
		return reconnectAttempts;
	}

	/**
	 * @param reconnectAttempts
	 *            the reconnectAttempts to set
	 */
	public void setReconnectAttempts(int reconnectAttempts) {
		this.reconnectAttempts = reconnectAttempts;
	}

	/**
	 * @return the reconnectInterval
	 */
	public long getReconnectInterval() {
		return reconnectInterval;
	}

	/**
	 * @param reconnectInterval
	 *            the reconnectInterval to set
	 */
	public void setReconnectInterval(long reconnectInterval) {
		this.reconnectInterval = reconnectInterval;
	}

	public long getPingInterval() {
		return pingInterval;
	}

	public void setPingInterval(long pingInterval) {
		this.pingInterval = pingInterval;
	}

	/**
	 * @return the closingHandshakeTimeout
	 */
	public long getClosingHandshakeTimeout() {
		return closingHandshakeTimeout;
	}

	/**
	 * @param closingHandshakeTimeout
	 *            the closingHandshakeTimeout to set
	 */
	public void setClosingHandshakeTimeout(long closingHandshakeTimeout) {
		this.closingHandshakeTimeout = closingHandshakeTimeout;
	}

	public boolean isClosed() {
		synchronized (sync) {
			return closed || closingHandshakeStarted;
		}
	}

	public boolean isConnected() {
		synchronized (sync) {
			return !isClosed() && readerThread != null && readerThread.isActive();
		}
	}

	public void connect() throws WebSocketException {
		synchronized (sync) {
			assertIsConfigured();
			assertIsNotConnected();
			assertClosingHandshakeIsNotStarted();

			// 4.1. Opening handshake

			// 4.1.2 If the user agent is not configured to use a proxy, then
			// open a TCP connection to the host given by
			// /host/ and the port given by /port/.

			int n = 0;
			do {
				try {
					tryConnect();
				} catch (IOException ex) {
					log.warn("Connection failed: {}", ex.getMessage());

					triggerOnError(ex);

					n++;
					if (!reconnectEnabled || (reconnectAttempts >= 0 && n > reconnectAttempts)) {
						log.error("Connection failed, reaching max number of attemps #{}", n);
						throw new WebSocketException(ex);
					}
					log.info("Trying to reconnect, attemp #{}", n + 1);
					try {
						Thread.sleep(reconnectInterval);
					} catch (InterruptedException e) {
						throw new WebSocketException(e);
					}
				}
			} while (!isConnected());

			triggerOnConnect();
		}
	}

	void reconnect() throws WebSocketException {
		if (!reconnecting.compareAndSet(false, true)) {
			return;
		}

		try {
			log.info("Reconnecting");

			synchronized (sync) {
				// try to close current connection
				try {
					close();
				} catch (IOException e) {
					// ignore
				}

				// reset closing flag
				closed = false;

				connect();
			}
		} finally {
			reconnecting.set(false);
		}
	}

	private void tryConnect() throws IOException {
		log.info("Trying to connect");

		socket = createSocket();

		try {
			outputStream = socket.getOutputStream();
			inputStream = socket.getInputStream();

			handshake();
		} catch (IOException ex) {
			Socket s = socket;
			socket = null;
			outputStream = null;
			inputStream = null;

			try {
				s.close();
			} catch (Exception e) {
				// ignore
			}
			throw ex;
		}

		readerThread = new WebSocketReaderThread(this);
		readerThread.start();

		keepAliveThread = new WebSocketKeepAliveThread(this);
		keepAliveThread.start();
	}

	public void send(byte[] bytes) throws WebSocketException {
		send(BINARY_OPCODE, bytes);
	}

	public void send(String str) throws WebSocketException {
		try {
			send(TEXT_OPCODE, str.getBytes(CharEncoding.UTF_8));
		} catch (UnsupportedEncodingException e) {
			throw new WebSocketException(e);
		}
	}

	private void send(int opcode, byte[] bytes) throws WebSocketException {
		synchronized (sync) {
			assertIsConnected();

			int n = 0;
			boolean sent = false;
			do {
				try {
					trySend(opcode, bytes);
					sent = true;
				} catch (IOException ex) {
					log.warn("Sending failed: {}", ex.getMessage());

					n++;

					if (!reconnectEnabled || (reconnectAttempts > 0 && n >= reconnectAttempts)) {
						log.error("Sending failed, reaching max number of attemps #{}", n);
						throw new WebSocketException(ex);
					}

					reconnect();

					log.info("Trying to resend, attemp #{}", n + 1);
				}
			} while (!sent);
		}
	}

	void trySend(int opcode, byte[] payload) throws IOException {
		final byte[] lengthBytes = encodeLength(payload.length);
		final byte[] mask = new byte[MASK_SIZE];

		// rnd.nextBytes(mask);

		outputStream.write(opcode | 0x80);
		outputStream.write(lengthBytes[0] | 0x80);
		if (lengthBytes.length > 1) {
			outputStream.write(lengthBytes, 1, lengthBytes.length - 1);
		}
		outputStream.write(mask);

		MaskedOutputStream maskedOutputStream = new MaskedOutputStream(outputStream, mask);
		maskedOutputStream.write(payload);
		maskedOutputStream.flush();

		// outputStream.write(payload);
		// outputStream.flush();
	}

	public static byte[] encodeLength(final long length) {
		byte[] bytes;
		if (length <= 125) {
			bytes = new byte[1];
			bytes[0] = (byte) length;
		} else {
			if (length <= 0xFFFF) {
				bytes = new byte[3];
				bytes[0] = 126;
			} else {
				bytes = new byte[9];
				bytes[0] = 127;
			}

			long value = length;
			for (int i = bytes.length - 1; i >= 1; i--) {
				bytes[i] = (byte) (value & 0xFF);
				value >>= 8;
			}
		}
		return bytes;
	}

	public void close() throws WebSocketException {
		synchronized (sync) {
			// Once a WebSocket connection is established, the user agent must
			// use the following steps to *start the WebSocket closing
			// handshake*. These steps must be run asynchronously relative to
			// whatever algorithm invoked this one.

			// 1. If the WebSocket closing handshake has started, then abort
			// these steps.
			if (isClosed()) {
				return;
			}

			try {
				if (keepAliveThread != null) {
					keepAliveThread.interrupt();
					keepAliveThread = null;
				}

				if (isConnected()) {
					try {
						startClosingHandshake();

						// 5. Wait a user-agent-determined length of time, or
						// until the WebSocket connection is closed.
						try {
							readerThread.join(closingHandshakeTimeout);
						} catch (InterruptedException ex) {
							// ignore
						}
					} finally {
						closingHandshakeStarted = false;

						if (readerThread.isAlive()) {
							log.debug("WebSocketReader Thread still alive");
							readerThread.finish();
							readerThread.interrupt();
						}

						readerThread = null;

						// 6. If the WebSocket connection is not already closed,
						// then close the WebSocket connection. (If this
						// happens, then the closing handshake doesn't finish.)
						log.debug("Closing the WebSocket connection");
						Socket s = socket;
						socket = null;
						s.close();
					}
				}
			} catch (IOException ex) {
				throw new WebSocketException(ex);
			} finally {
				// NOTE: The closing handshake finishes once the server returns
				// the 0xFF packet, as described above.
				closed = true;
			}
		}
	}

	/**
	 * Starts The WebSocket closing handshake if not already started
	 * 
	 * @return true if handshake started in this method invocation, false if closing handshake has already started.
	 * @throws IOException
	 */
	boolean startClosingHandshake() throws IOException {
		synchronized (sync) {
			if (closingHandshakeStarted) {
				log.debug("The WebSocket closing handshake has already started");
				return false;
			}
			// 2. Send a 0xFF byte to the server.
			outputStream.write(0xFF);
			// 3. Send a 0x00 byte to the server.
			outputStream.write(0x00);
			outputStream.flush();
			// 4. *The WebSocket closing handshake has started*.
			closingHandshakeStarted = true;
			log.debug("The WebSocket closing handshake has started");

			return true;
		}
	}

	private void handshake() throws IOException {
		OutputStreamWriter writer = new OutputStreamWriter(outputStream, Charset.forName("UTF8"));

		// Once a connection to the server has been established (including a
		// connection via a proxy or over a TLS-encrypted tunnel), the client
		// MUST send an opening handshake to the server. The handshake consists
		// of an HTTP upgrade request, along with a list of required and
		// optional headers. The requirements for this handshake are as
		// follows.

		// 1. The handshake MUST be a valid HTTP request as specified by
		// [RFC2616].

		// 2. The Method of the request MUST be GET and the HTTP version
		// MUST be at least 1.1.

		log.debug("Upgrade request");
		// 3. The request MUST contain a "Request-URI" as part of the GET
		// method. This MUST match the /resource name/ Section 3 (a
		// relative URI), or be an absolute URI that, when parsed, has a
		// matching /resource name/ as well as matching /host/, /port/, and
		// appropriate scheme (ws or wss).
		writer.write("GET ");
		writer.write(resourceName);
		writer.write(" HTTP/1.1\r\n");

		// 4. The request MUST contain a "Host" header whose value is equal to
		// /host/.
		writer.write(HOST_HEADER);
		writer.write(": ");
		writer.write(host);
		writer.write(CRLF);

		// 5. The request MUST contain an "Upgrade" header whose value is
		// equal to "websocket".
		writer.write(UPGRADE_HEADER);
		writer.write(": websocket\r\n");

		// 6. The request MUST contain a "Connection" header whose value MUST
		// include the "Upgrade" token.
		writer.write(CONNECTION_HEADER);
		writer.write(": Upgrade\r\n");

		// 7. The request MUST include a header with the name "Sec-WebSocket-
		// Key". The value of this header MUST be a nonce consisting of a
		// randomly selected 16-byte value that has been base64-encoded
		// [RFC3548]. The nonce MUST be selected randomly for each
		// connection.

		SecWebSocketKey key = generateSecWebSocketKey();

		writer.write(SEC_WEB_SOCKET_KEY_HEADER);
		writer.write(": ");
		writer.write(key.getKey());
		writer.write(CRLF);

		// 8. The request MUST include a header with the name "Sec-WebSocket-
		// Origin" if the request is coming from a browser client. If the
		// connection is from a non-browser client, the request MAY include
		// this header if the semantics of that client match the use-case
		// described here for browser clients. The value of this header
		// MUST be the ASCII serialization of origin of the context in
		// which the code establishing the connection is running, and MUST
		// be lower-case. The value MUST NOT contain letters in the range
		// U+0041 to U+005A (i.e. LATIN CAPITAL LETTER A to LATIN CAPITAL
		// LETTER Z) [I-D.ietf-websec-origin]. The ABNF is as defined in
		// Section 6.1 of [I-D.ietf-websec-origin].
		writer.write(ORIGIN_HEADER);
		writer.write(": ");
		writer.write(origin);
		writer.write(CRLF);

		// 9. The request MUST include a header with the name "Sec-WebSocket-
		// Version". The value of this header MUST be 8.
		writer.write(SEC_WEB_SOCKET_VERSION_HEADER);
		writer.write(": 8\r\n");

		// 10. The request MAY include a header with the name "Sec-WebSocket-
		// Protocol". If present, this value indicates the subprotocol(s)
		// the client wishes to speak, ordered by preference. The elements
		// that comprise this value MUST be non-empty strings with
		// characters in the range U+0021 to U+007E not including separator
		// characters as defined in [RFC2616], and MUST all be unique
		// strings. The ABNF for the value of this header is 1#token,
		// where the definitions of constructs and rules are as given in
		// [RFC2616].
		//
		if (!StringUtils.isBlank(protocol)) {
			writer.write(SEC_WEB_SOCKET_PROTOCOL_HEADER);
			writer.write(": ");
			writer.write(protocol);
			writer.write(CRLF);
		}

		// 11. The request MAY include a header with the name "Sec-WebSocket-
		// Extensions". If present, this value indicates the protocol-
		// level extension(s) the client wishes to speak. The
		// interpretation and format of this header is described in
		// Section 9.1.

		// TODO

		// 12. The request MAY include headers associated with sending cookies,
		// as defined by the appropriate specifications
		// [I-D.ietf-httpstate-cookie]. These headers are referred to as
		// _Headers to Send Appropriate Cookies_.
		//

		// TODO

		// extra headers
		for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
			writer.write(entry.getKey());
			writer.write(": ");
			writer.write(entry.getValue());
			writer.write(CRLF);
		}

		writer.write(CRLF);
		writer.flush();

		// Once the client's opening handshake has been sent, the client MUST
		// wait for a response from the server before sending any further data.

		String code = readStatusCode(inputStream);

		log.debug("Server response code {}", code);
		// The client MUST validate the server's response as follows:

		// 1. If the status code received from the server is not 101, the
		// client handles the response per HTTP procedures. Otherwise,
		// proceed as follows.

		if (!"101".equals(code)) {
			throw new WebSocketException("Invalid handshake response");
		}

		Map<String, List<String>> fieldMap = readFields(inputStream);

		String value;

		// 2. If the response lacks an "Upgrade" header or the "Upgrade" header
		// contains a value that is not an ASCII case-insensitive match for
		// the value "websocket", the client MUST _Fail the WebSocket
		// Connection _.

		value = getFieldValue(fieldMap, "upgrade");
		if (!"WebSocket".equalsIgnoreCase(value)) {
			throw new WebSocketException("Expected WebSocket as Updgrade field value!");
		}

		// 3. If the response lacks a "Connection" header or the "Connection"
		// header contains a value that is not an ASCII case-insensitive
		// match for the value "Upgrade", the client MUST _Fail the
		// WebSocket Connection_.
		value = getFieldValue(fieldMap, "connection");
		if (!UPGRADE_HEADER.equalsIgnoreCase(value)) {
			throw new WebSocketException("Expected Upgrade as Connection field value!");
		}

		// 4. If the response lacks a "Sec-WebSocket-Accept" header or the
		// "Sec-WebSocket-Accept" contains a value other than the base64-
		// encoded SHA-1 of the concatenation of the "Sec-WebSocket-Key" (as
		// a string, not base64-decoded) with the string "258EAFA5-E914-
		// 47DA-95CA-C5AB0DC85B11", the client MUST _Fail the WebSocket
		// Connection_
		value = getFieldValue(fieldMap, "sec-websocket-accept");

		if (!key.isServerKeyValid(value)) {
			throw new WebSocketException("Server key doesn't match an expected response");
		}

		// 5. If the response includes a "Sec-WebSocket-Extensions" header, and
		// this header indicates the use of an extension that was not
		// present in the client' handshake (the server has indicated an
		// extension not requested by the client), the client MUST _Fail the
		// WebSocket Connection_. (The parsing of this header to determine
		// which extensions are requested is discussed in Section 9.1.)

		value = getFieldValue(fieldMap, "sec-websocket-extensions");
		if (!StringUtils.isEmpty(value)) {
			throw new WebSocketException("The server has indicated an extension not request by the client!");
		}

		if (fieldMap.containsKey("")) {
			throw new WebSocketException("Empty field name found in field list!");
		}

		// If the server's response is validated as provided for above, it is
		// said that _The WebSocket Connection is Established_ and that the
		// WebSocket Connection is in the OPEN state. The _Extensions In Use_
		// is defined to be a (possibly empty) string, the value of which is
		// equal to the value of the |Sec-WebSocket-Extensions| header supplied
		// by the server's handshake, or the null value if that header was not
		// present in the server's handshake. The _Subprotocol In Use_ is
		// defined to be the value of the |Sec-WebSocket-Protocol| header in the
		// server's handshake, or the null value if that header was not present
		// in the server's handshake. Additionally, if any headers in the
		// server's handshake indicate that cookies should be set (as defined by
		// [I-D.ietf-httpstate-cookie]), these cookies are referred to as
		// _Cookies Set During the Server's Opening Handshake_.
	}

	InputStream getInputStream() throws WebSocketException {
		return inputStream;
	}

	private String getFieldValue(Map<String, List<String>> fieldMap, String fieldName) throws IOException {
		if (fieldMap.containsKey(fieldName)) {
			List<String> list = fieldMap.get(fieldName);
			if (list.size() > 1) {
				throw new WebSocketException("Expected exacly one field '" + fieldName + "' in field list!");
			} else if (list.size() == 1) {
				return list.get(0);
			}
		}
		return null;
	}

	private String readStatusCode(InputStream is) throws IOException {
		StringBuilder field = new StringBuilder();
		// 28. Read bytes from the server until either the connection closes, or
		// a 0x0A byte is read. Let /field/ be
		// these bytes, including the 0x0A byte.
		int c;
		while ((c = is.read()) != -1) {
			field.append((char) c);
			if (c == '\n')
				break;
		}

		// If /field/ is not at least seven bytes long, or if the last two bytes
		// aren't 0x0D and 0x0A respectively,
		// or if it does not contain at least two 0x20 bytes, then fail the
		// WebSocket connection and abort these
		// steps.
		if (field.length() < 7 || !field.toString().endsWith(CRLF)) {
			throw new WebSocketException("Invalid Status Line:" + field);
		}

		// 4.1.29. Let /code/ be the substring of /field/ that starts from the
		// byte after the first 0x20 byte, and
		// ends with the byte before the second 0x20 byte.
		Pattern p = Pattern.compile("^[^ ]* ([0-9]{3}) ");
		Matcher m = p.matcher(field);

		// 4.1.30. If /code/ is not three bytes long, or if any of the bytes in
		// /code/ are not in the range 0x30 to
		// 0x39, then fail the WebSocket connection and abort these steps.
		if (!m.find()) {
			throw new WebSocketException("No Valid Status Code found: " + field);
		}

		String code = m.group(1);
		return code;
	}

	private Map<String, List<String>> readFields(InputStream is) throws IOException {
		// 4.1 32. Let /fields/ be a list of name-value pairs, initially empty.
		Map<String, List<String>> fields = new LinkedHashMap<String, List<String>>();

		while (true) {
			// 4.1 34. Read /name/
			String name = readFieldName(is);
			if (name.isEmpty())
				break;
			// 4.1 36. Read /value/
			String value = readFieldValue(is);

			// 4.1.38. Append an entry to the /fields/ list that has the name
			// given by the string obtained by
			// interpreting the /name/ byte array as a UTF-8 byte stream and the
			// value given by the string obtained
			// by interpreting the /value/ byte array as a UTF-8 byte stream.
			List<String> list = fields.get(name);
			if (list == null) {
				list = new ArrayList<String>();
				fields.put(name, list);
			}

			list.add(value);
			// 4.1 39. return to the "Field" step above
		}

		// 40. _Fields processing_: Read a byte from the server.

		// If the connection closes before this byte is received, or if the byte
		// is not a 0x0A byte (ASCII LF), then
		// fail the WebSocket connection and abort these steps.

		// NOTE: This skips past the LF byte of the CRLF after the blank line
		// after the fields.
		skipByte(is, 0x0A);

		return fields;
	}

	private String readFieldName(InputStream is) throws IOException {
		// This reads a field name, terminated by a colon, converting upper-case
		// ASCII letters to lowercase, and
		// aborting if a stray CR or LF is found.

		// 4.1.33. Let /name/ be empty byte array.
		ByteArrayOutputStream name = new ByteArrayOutputStream();

		// 4.1.34. Read a byte from the server.
		// If the connection closes before this byte is received, then fail the
		// WebSocket connection and abort these
		// steps.

		// Otherwise, handle the byte as described in the appropriate entry
		// below:

		int b = -1;
		while ((b = is.read()) != -1) {
			if (b == 0x0D) {
				// -> If the byte is 0x0D (ASCII CR)
				// If the /name/ byte array is empty, then jump to the fields
				// processing step. Otherwise, fail the
				// WebSocket connection and abort these steps.
				if (name.size() != 0) {
					throw new WebSocketException("Unexpected CR byte in field name!");
				}
				break;
			} else if (b == 0x0A) {
				// -> If the byte is 0x0A (ASCII LF)
				// Fail the WebSocket connection and abort these steps.
				throw new WebSocketException("Unexpected LF byte in field name!");
			} else if (b == 0x3A) {
				// -> If the byte is 0x3A (ASCII :)
				// Move on to the next step.
				break;
			} else if (b >= 0x41 && b <= 0x5A) {
				// -> If the byte is in the range 0x41 to 0x5A (ASCII A-Z)
				// Append a byte whose value is the byte's value plus 0x20 to
				// the /name/ byte array and redo this
				// step for the next byte.
				name.write(b + 0x20);
			} else {
				// -> Otherwise
				// Append the byte to the /name/ byte array and redo this step
				// for the next byte.
				name.write(b);
			}
		}

		if (b == -1) {
			throw new WebSocketException("Unexpected end of stream!");
		}

		return new String(name.toByteArray(), CharEncoding.UTF_8);
	}

	private String readFieldValue(InputStream is) throws IOException {
		// This reads a field value, terminated by a CRLF, skipping past a
		// single space after the colon if there is
		// one.

		// 4.1 33. Let /value/ be empty byte arrays
		ByteArrayOutputStream value = new ByteArrayOutputStream();

		// 4.1.35. Let /count/ equal 0.
		int count = 0;

		// NOTE: This is used in the next step to skip past a space character
		// after the colon, if necessary.

		// 4.1.36. Read a byte from the server and increment /count/ by 1.

		// If the connection closes before this byte is received, then fail the
		// WebSocket connection and abort these
		// steps.
		int b = -1;
		while ((b = is.read()) != -1) {
			count = +1;
			// Otherwise, handle the byte as described in the appropriate entry
			// below:

			if (b == 0x20 && count == 1) {
				// -> If the byte is 0x20 (ASCII space) and /count/ equals 1
				// Ignore the byte and redo this step for the next byte.
			} else if (b == 0x0D) {
				// -> If the byte is 0x0D (ASCII CR)
				// Move on to the next step.
				break;
			} else if (b == 0x0A) {
				// -> If the byte is 0x0A (ASCII LF)
				// Fail the WebSocket connection and abort these steps.
				throw new WebSocketException("Unexpected LF character in field value!");
			} else {
				// -> Otherwise
				// Append the byte to the /value/ byte array and redo this step
				// for the next byte.
				value.write(b);
			}
		}

		if (b == -1) {
			throw new WebSocketException("Unexpected end of stream!");
		}

		// 4.1.37. Read a byte from the server.
		// If the connection closes before this byte is received, or if the byte
		// is not a 0x0A byte (ASCII LF), then
		// fail the WebSocket connection and abort these steps.

		// NOTE: This skips past the LF byte of the CRLF after the field.
		skipByte(is, 0x0A);

		return new String(value.toByteArray(), CharEncoding.UTF_8);
	}

	private void skipByte(InputStream is, int skip) throws IOException {
		int b = is.read();
		if (b == -1) {
			throw new WebSocketException("Unexpected end of stream!");
		} else if (b != skip) {
			throw new WebSocketException(String.format("Expected %X byte!", skip));
		}
	}

	private Socket createSocket() throws IOException {
		Socket s;
		if (secure) {
			SocketFactory factory = SSLSocketFactory.getDefault();
			s = factory.createSocket(host, port);
		} else {
			s = new Socket(host, port);
		}
		s.setKeepAlive(true);
		s.setSoTimeout(100000);

		return s;
	}

	private SecWebSocketKey generateSecWebSocketKey() throws WebSocketException {
		return new SecWebSocketKey();
	}

	void triggerOnConnect() {
		log.debug("OnConnect");

		synchronized (listeners) {
			for (WebSocketListener listener : listeners) {
				try {
					listener.onConnect();
				} catch (Exception e) {
					// ignore
				}
			}
		}
	}

	void triggerOnMessage(byte[] data) {

		synchronized (listeners) {
			for (WebSocketListener listener : listeners) {
				try {
					listener.onMessage(data);
				} catch (Exception e) {
					// ignore
				}
			}
		}
	}

	void triggerOnMessage(String data) {
		synchronized (listeners) {
			for (WebSocketListener listener : listeners) {
				try {
					listener.onMessage(data);
				} catch (Exception e) {
					// ignore
				}
			}
		}
	}

	private void triggerOnClose(Integer status, String message) {
		synchronized (listeners) {
			for (WebSocketListener listener : listeners) {
				try {
					listener.onClose(status, message);
				} catch (Exception e) {
					// ignore
				}
			}
		}
	}

	void triggerOnError(Exception ex) {
		log.debug("OnError: {}", ex.getMessage(), ex);

		synchronized (listeners) {
			for (WebSocketListener listener : listeners) {
				try {
					listener.onError(ex);
				} catch (Exception e) {
					// ignore
				}
			}
		}
	}

	void onDataFrame(int opcode, byte[] data) throws IOException {
		switch (opcode) {
			case TEXT_OPCODE:
				triggerOnMessage(new String(data, CharEncoding.UTF_8));
				break;
			case BINARY_OPCODE:
				triggerOnMessage(data);
				break;
			case PING_OPCODE:
				trySend((byte) PONG_OPCODE, data);
				break;
			case CLOSE_OPCODE:
				Integer status = null;
				String message = null;

				if (data != null) {
					if (data.length >= 2) {
						status = (data[0] & 0xff) << 8 | (data[1] & 0xff);
					}
					if (data.length > 2) {
						message = new String(data, 2, data.length, Charset.forName(CharEncoding.UTF_8));
					}
				}

				log.debug("CLOSE_OPCODE {}:{}", status, message);

				triggerOnClose(status, message);
				break;
			default:
				// just ignore
				break;
		}
	}

	private boolean isConfigured() {
		return StringUtils.isNotBlank(host);
	}

	private void assertIsConnected() throws WebSocketException {
		if (!isConnected()) {
			throw new WebSocketException("WebSocket is not connected");
		}
	}

	private void assertIsNotConnected() throws WebSocketException {
		if (isConnected()) {
			throw new WebSocketException("WebSocket is already connected");
		}
	}

	private void assertIsConfigured() throws WebSocketException {
		if (!isConfigured()) {
			throw new WebSocketException("WebSocket is closed");
		}
	}

	private void assertClosingHandshakeIsNotStarted() throws WebSocketException {
		if (closingHandshakeStarted) {
			throw new WebSocketException("WebSocket closing handshake is started");
		}
	}
}
