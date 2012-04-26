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

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Date;

import org.apache.commons.lang.CharEncoding;

public class WebSocketKeepAliveThread extends Thread {
	
	private WebSocketClient wsClient;
	
	public WebSocketKeepAliveThread(WebSocketClient wsClient) {
		super("WebSocket Keep Alive Thread");
		
		setDaemon(true);

		this.wsClient = wsClient;
	}

	@Override
	public void run() {
		try {
			while (true) {
				Thread.sleep(wsClient.getPingInterval());

				// Ping
				wsClient.trySend(WebSocketClient.PING_OPCODE, new Date().toString().getBytes(Charset.forName(CharEncoding.UTF_8)));
			}
		} catch (InterruptedException e) {
			// ignore;
		} catch (IOException e) {
			if (wsClient.isReconnectEnabled()) {
				new WebSocketReconnectionThread(wsClient).start();
			}
		}
	}
}