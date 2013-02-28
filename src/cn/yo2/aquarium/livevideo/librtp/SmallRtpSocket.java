/*
 * Copyright (C) 2011 GUIGUI Simon, fyhertz@gmail.com
 * 
 * This file is part of Spydroid (http://code.google.com/p/spydroid-ipcamera/)
 * 
 * Spydroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package cn.yo2.aquarium.livevideo.librtp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Random;

import cn.yo2.aquarium.logutils.MyLog;



public class SmallRtpSocket {

	private DatagramSocket usock;
	private DatagramPacket upack;
	
	private byte[] buffer;
	private int seq = 0;
	private boolean upts = false;
	
	public static final int headerLength = 12;
	
	private int payloadLength;
	
	public SmallRtpSocket(InetAddress dest, int dport, byte[] buffer) throws SocketException {
		
		this.buffer = buffer;
		
		/*							     Version(2)  Padding(0)					 					*/
		/*									 ^		  ^			Extension(0)						*/
		/*									 |		  |				^								*/
		/*									 | --------				|								*/
		/*									 | |---------------------								*/
		/*									 | ||  -----------------------> Source Identifier(0)	*/
		/*									 | ||  |												*/
		buffer[0] = (byte) Integer.parseInt("10000000",2);
		
		/* Payload Type */
		buffer[1] = (byte) 96;
		
		/* Byte 2,3        ->  Sequence Number                   */
		/* Byte 4,5,6,7    ->  Timestamp                         */
		
		/* Byte 8,9,10,11  ->  Sync Source Identifier            */
		setLong((new Random()).nextLong(),8,12);
		
		usock = new DatagramSocket();
		upack = new DatagramPacket(buffer,1,dest,dport);

	}
	
	public void close() {
		usock.close();
	}
	
	/* Send RTP packet over the network */
	public void send(int length) {
		updateSequence();
		upack.setLength(length);
		
		updatePayloadLength(length);
		
		MyLog.d("-----> " + packetStr());
		
		try {
			usock.send(upack);
		} catch (IOException e) {
			MyLog.e("Send failed");
		}
		
		if (upts) {
			upts = false;
			buffer[1] -= 0x80;
		}
		
	}
	
	private void updatePayloadLength(int length) {
		payloadLength = length - headerLength;
	}
	
	private void updateSequence() {
		setLong(++seq, 2, 4);
	}
	
	public void updateTimestamp(long timestamp) {
		setLong(timestamp, 4, 8);
	}
	
	public void markNextPacket() {
		upts = true;
		buffer[1] += 0x80; // Mark next packet
	}
	
	// Call this only one time !
	public void markAllPackets() {
		buffer[1] += 0x80;
	}
	
	private void setLong(long n, int begin, int end) {
		for (end--; end >= begin; end--) {
			buffer[end] = (byte) (n % 256);
			n >>= 8;
		}
	}	
	
	private String packetStr() {
		return String.format("PT=%d, SSRC=0x%08x, Seq=%d, len=%d, ts=%d %s", 
				getPayloadType(), 
				getSscr(), 
				getSequenceNumber(), 
				getPayloadLength(), 
				getTimestamp(), 
				hasMarker() ? "Mark" : "");
	}
	
	// version (V): 2 bits
	// padding (P): 1 bit
	// extension (X): 1 bit
	// CSRC count (CC): 4 bits
	// marker (M): 1 bit
	// payload type (PT): 7 bits
	// sequence number: 16 bits
	// timestamp: 32 bits
	// SSRC: 32 bits
	// CSRC list: 0 to 15 items, 32 bits each

	/** Gets the version (V) */
	public int getVersion() {
		return (buffer[0] >> 6 & 0x03);
	}

	/** Sets the version (V) */
	public void setVersion(int v) {
		buffer[0] = (byte) ((buffer[0] & 0x3F) | ((v & 0x03) << 6));
	}

	/** Whether has padding (P) */
	public boolean hasPadding() {
		return getBit(buffer[0], 5);
	}

	/** Set padding (P) */
	public void setPadding(boolean p) {
		buffer[0] = setBit(p, buffer[0], 5);
	}

	/** Whether has extension (X) */
	public boolean hasExtension() {
		return getBit(buffer[0], 4);
	}

	/** Set extension (X) */
	public void setExtension(boolean x) {
		buffer[0] = setBit(x, buffer[0], 4);
	}

	/** Gets the CSCR count (CC) */
	public int getCscrCount() {
		return (buffer[0] & 0x0F);
	}

	/** Whether has marker (M) */
	public boolean hasMarker() {
		return getBit(buffer[1], 7);
	}

	/** Set marker (M) */
	public void setMarker(boolean m) {
		buffer[1] = setBit(m, buffer[1], 7);
	}
	
	public int getPayloadLength() {
		return payloadLength;
	}

	/** Gets the payload type (PT) */
	public int getPayloadType() {
		return (buffer[1] & 0x7F);
	}

	/** Sets the payload type (PT) */
	public void setPayloadType(int pt) {
		buffer[1] = (byte) ((buffer[1] & 0x80) | (pt & 0x7F));
	}

	/** Gets the sequence number */
	public int getSequenceNumber() {
		return getInt(buffer, 2, 4);
	}

	/** Sets the sequence number */
	public void setSequenceNumber(int sn) {
		setInt(sn, buffer, 2, 4);
	}

	/** Gets the timestamp */
	public long getTimestamp() {
		return getLong(buffer, 4, 8);
	}

	/** Sets the timestamp */
	public void setTimestamp(long timestamp) {
		setLong(timestamp, buffer, 4, 8);
	}

	/** Gets the SSCR */
	public long getSscr() {
		return getLong(buffer, 8, 12);
	}

	/** Sets the SSCR */
	public void setSscr(long ssrc) {
		setLong(ssrc, buffer, 8, 12);
	}

	/** Gets the CSCR list */
	public long[] getCscrList() {
		int cc = getCscrCount();
		long[] cscr = new long[cc];
		for (int i = 0; i < cc; i++)
			cscr[i] = getLong(buffer, 12 + 4 * i, 16 + 4 * i);
		return cscr;
	}

	/** Sets the CSCR list */
	public void setCscrList(long[] cscr) {
		int cc = cscr.length;
		if (cc > 15)
			cc = 15;
		buffer[0] = (byte) (((buffer[0] >> 4) << 4) + cc);
		cscr = new long[cc];
		for (int i = 0; i < cc; i++)
			setLong(cscr[i], buffer, 12 + 4 * i, 16 + 4 * i);
		// header_len=12+4*cc;
	}
	
	// *********************** Private and Static ***********************

	/** Gets int value */
	private static int getInt(byte b) {
		return ((int) b + 256) % 256;
	}

	/** Gets long value */
	private static long getLong(byte[] data, int begin, int end) {
		long n = 0;
		for (; begin < end; begin++) {
			n <<= 8;
			n += data[begin] & 0xFF;
		}
		return n;
	}

	/** Sets long value */
	private static void setLong(long n, byte[] data, int begin, int end) {
		for (end--; end >= begin; end--) {
			data[end] = (byte) (n % 256);
			n >>= 8;
		}
	}

	/** Gets Int value */
	private static int getInt(byte[] data, int begin, int end) {
		return (int) getLong(data, begin, end);
	}

	/** Sets Int value */
	private static void setInt(int n, byte[] data, int begin, int end) {
		setLong(n, data, begin, end);
	}

	/** Gets bit value */
	private static boolean getBit(byte b, int bit) {
		return ((b >> bit) & 0x01) == 1;
	}

	/** Sets bit value */
	private static byte setBit(boolean value, byte b, int bit) {
		if (value)
			return (byte) (b | (1 << bit));
		else
			return (byte) ((b | (1 << bit)) ^ (1 << bit));
	}
}
