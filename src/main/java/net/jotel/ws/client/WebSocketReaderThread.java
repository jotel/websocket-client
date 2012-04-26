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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.io.input.BoundedInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketReaderThread extends Thread {
	private final static Logger log = LoggerFactory.getLogger(WebSocketReaderThread.class);

	private WebSocketClient wsClient;
	private BufferedInputStream inputStream;
	private AtomicBoolean active = new AtomicBoolean(true);

	public WebSocketReaderThread(WebSocketClient wsClient) throws WebSocketException {
		super("WebSocket Reader Thread");

		setDaemon(true);
		
		this.wsClient = wsClient;
		inputStream = new BufferedInputStream(wsClient.getInputStream());
	}

	public boolean isActive() {
		return active.get();
	}

	public void finish() {
		active.set(false);
	}

	@Override
	public void run() {
		try {
			int inFrameType = 0;

			while (active.get() && !interrupted()) {
				int b = read(inputStream);

				boolean finalFragment = (b & 0x80) == 0x80;
				int opcode = b & 0xF;

				if (!finalFragment) {
					if (inFrameType == 0) {
						inFrameType = opcode;
					}
				} else {
					inFrameType = 0;
				}

				b = read(inputStream);

				final boolean masked = (b & 0x80) == 0x80;
				// FIXME in the spec length might be 64 bit.. but this implementation won't handle so big amount of data
				int length = b & 0x7f;

				if (length > 125) {
					int bytesToRead = (length == 126) ? 2 : 8;
					length = 0;
					for (int i = 0; i < bytesToRead; i++) {
						b = read(inputStream);
						length = length << (i << 3) | (b & 0xFF); // (i * 8) == (i << 3)
					}
				}

				byte[] mask = null;

				if (masked) {
					mask = new byte[WebSocketClient.MASK_SIZE];
					read(inputStream, mask);
				}

				byte[] payload = new byte[length];

				MaskedInputStream maskedInputStream = new MaskedInputStream(new BoundedInputStream(inputStream, length), mask);

				read(maskedInputStream, payload);

				wsClient.onDataFrame(opcode, payload);
			}
		} catch (Exception ex) {
			if (active.compareAndSet(true, false)) {
				wsClient.triggerOnError(ex);
				log.error(ex.getMessage(), ex);

				if (wsClient.isReconnectEnabled()) {					
					new WebSocketReconnectionThread(wsClient).start();
				}
			}
		}
	}

	private int read(BufferedInputStream is) throws IOException, WebSocketException {
		int b = is.read();
		if (b == -1) {
			throw new WebSocketException("Unexpected EOF");
		}
		return b;
	}

	private void read(InputStream is, byte[] buffer) throws IOException, WebSocketException {
		int off = 0;
		int bytesRead = 0;
		int totalBytesRead = 0;
		int bytesToRead = buffer.length;

		do {
			bytesRead = is.read(buffer, off, bytesToRead);
			if (bytesRead > 0) {
				totalBytesRead += bytesRead;
				bytesToRead -= bytesRead;
				off += bytesRead;
			}

		} while (bytesToRead > 0 && bytesRead != -1);

		if (totalBytesRead != buffer.length) {
			throw new WebSocketException("Unexpected end of stream!");
		}
	}
}