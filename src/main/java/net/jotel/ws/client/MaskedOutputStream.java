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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

class MaskedOutputStream extends FilterOutputStream {	
	private byte[] mask;
	private int idx;
	
	public MaskedOutputStream(OutputStream out, byte[] mask) {
	    super(out);
	    
	    this.idx = 0;
	    this.mask = mask;
	}
	
	@Override
    public void write(int b) throws IOException {
	    write(new byte[]{(byte)b}, 0, 1);
    }

	@Override
    public void write(byte[] b) throws IOException {
	    write(b, 0, b.length);
    }

	@Override
    public void write(byte[] b, int off, int len) throws IOException {
		byte[] masked = mask(b, off, len);

		out.write(masked);
    }

	private byte[] mask(byte[] b, int off, int len) {
		byte[] masked = new byte[len];
		
		System.arraycopy(b, off, masked, 0, len);
		
		if (mask != null) {
			for (int i = 0; i < len; i++) {
				masked[i] ^= mask[idx++ % mask.length];
			}
		}
		
		return masked;
	}
}