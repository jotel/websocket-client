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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

class MaskedInputStream extends FilterInputStream {
	private byte[] mask;
	private int idx;

	protected MaskedInputStream(InputStream in, byte[] mask) {
		super(in);

		this.mask = mask;
		this.idx = 0;
	}

	@Override
	public int read() throws IOException {		
		int b = super.read();
		
		if (b >= 0) {
			byte[] arr = new byte[]{(byte)b};

			mask(arr, 0, 1);
			
			b = arr[0];
		}
		
		return b;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		int c = super.read(b, off, len);

		if (c > 0) {
			mask(b, off, c);
		}

		return c;
	}
	
	private void mask(byte[] b, int off, int len) {
		if (mask != null) {
			for (int i = off; i < len; i++) {
				b[i] ^= mask[idx++ % mask.length];
			}
		}
	}
}