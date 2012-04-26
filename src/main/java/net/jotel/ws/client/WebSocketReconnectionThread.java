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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketReconnectionThread extends Thread {
	private final static Logger log = LoggerFactory.getLogger(WebSocketReconnectionThread.class);
	
	private WebSocketClient wsClient;
	
    public WebSocketReconnectionThread(WebSocketClient wsClient) {
	    super("WebSocket Reconnection thread");
	    
	    setDaemon(true);
	    
	    this.wsClient = wsClient;
    }

    @Override
    public void run() {
    	try {
    		wsClient.reconnect();
    	} catch (WebSocketException ex) {
    		wsClient.triggerOnError(ex);
    		log.error(ex.getMessage(), ex);
    	}
    }
}