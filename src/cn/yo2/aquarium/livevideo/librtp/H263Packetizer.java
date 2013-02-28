package cn.yo2.aquarium.livevideo.librtp;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketException;

import android.os.SystemClock;
import cn.yo2.aquarium.logutils.MyLog;

public class H263Packetizer extends AbstractPacketizer {
	private final int h263hl = 2; 
	private final int packetSize = 1400;
	
	public H263Packetizer(InputStream fis, InetAddress dest, int port) throws SocketException {
		super(fis, dest, port);
		
		rsock.setPayloadType(103);
	}
	
	@Override
	public void run() {
		// num: bytes read a time
		// number: total h263 stream length in a packet
		int fps, num, number = 0, src, dest, len = 0, head = 0, lasthead = 0, lasthead2 = 0, cnt = 0, stable = 0;

		long now, lasttime = 0;
		double avgrate = 24000;
		double avglen = avgrate / 20;
		
        
		try {
			// Skip all atoms preceding mdat atom
			while (true) {
				fis.read(buffer,rtphl,8);	// box length: 4 bytes, box type: 4 bytes 
				if (buffer[rtphl+4] == 'm' && buffer[rtphl+5] == 'd' && buffer[rtphl+6] == 'a' && buffer[rtphl+7] == 't') { 
					MyLog.w("find mdat, break");
					break;
				}
				len = (buffer[rtphl+3]&0xFF) + (buffer[rtphl+2]&0xFF)*256 + (buffer[rtphl+1]&0xFF)*65536;	// the box length
				if (0 == len) {
					MyLog.w("box length is zero, break");
					break;
				}
				MyLog.w("Atom skipped: "+printBuffer(rtphl+4,rtphl+8)+" size: "+len);
				fis.read(buffer,rtphl,len-8);
			} 
			
			// Some phones do not set length correctly when stream is not seekable
			if (0 == len) {
				MyLog.d("box length is zero");
				while (true) {
					while (fis.read() != 'm');
					fis.read(buffer,rtphl,3);
					if (buffer[rtphl] == 'd' && buffer[rtphl+1] == 'a' && buffer[rtphl+2] == 't') {
						MyLog.w("find mdat, break");
						break;
					}
				}
			}
		} catch (IOException e)  {
			MyLog.e("Error when finding box mdat", e);
			return;
		}
		
		MyLog.i("all atoms preceding mdat atom have been skipped");
		
		while (running) { 
			num = -1;
			try {
				num = fis.read(buffer, rtphl + h263hl + number, packetSize - number);
				
				if (len < 0) {
					MyLog.w("stream not available, wait 20ms for next try...");
					try {
						sleep(20);
					} catch (InterruptedException e) {
						// wakeup 
					}
					continue;
				}
				
				number += num;
				head += num;

				now = SystemClock.elapsedRealtime();
				if (lasthead != head + fis.available() && ++stable >= 5 && now - lasttime > 700) {
					if (cnt != 0 && len != 0)
						avglen = len / cnt;
					if (lasttime != 0) {
						fps = (int) ((double) cnt * 1000 / (now - lasttime));
						avgrate = (double) ((head + fis.available()) - lasthead2) * 1000 / (now - lasttime);
					}
					lasttime = now;
					lasthead = head + fis.available();
					lasthead2 = head;
					len = cnt = stable = 0;
				}
				
				// find h263 PSC is a word of 22 bits. Its value is 0000 0000 0000 0000 1 00000
				// see http://www.cmlab.csie.ntu.edu.tw/cml/dsp/training/coding/h263/format_p.html
				for (num = rtphl + h263hl; num <= rtphl + h263hl + number - 3; num++) {
					if (buffer[num] == 0x00 && buffer[num + 1] == 0x00 && (buffer[num + 2] & 0xFC) == 0x80) {
						break;
					}
				}
				
				if (num > 14 + number - 3) {
					MyLog.d("frame not complete");
					// frame not complete
					num = 0;
					rsock.setMarker(false);
					
				} else {
					MyLog.d("frame compelte, set marker bit");
					// frame complete, set marker bit
					num = 14 + number - num;
					rsock.setMarker(true);
				}
				
				int framelen = number - num;
				rsock.send(rtphl + h263hl + framelen);
				len += framelen; // len: total h263 frame lens
				
				// copy the left bytes to playload start position
				if (num > 0) {
					num -= 2;
					dest = 14; // h263 payload start postion
					src = 14 + number - num; // bypass 2 bytes h263 PSC

					// TODO: why skip the first zero byte ??
					if (num > 0 && buffer[src] == 0) {
						src++;
						num--;
					}

					number = num;
					while (num-- > 0)
						buffer[dest++] = buffer[src++];

					// first byte of H.263+ payload_header(2 bytes): RR(5)=0, P(1)=0, V(1)=0
					buffer[rtphl] = 0x04; 

					cnt++;
					try {
						MyLog.d("avgrate = " + avgrate);
						if (avgrate != 0) {
							int sleepTime = (int) (avglen / avgrate * 1000);
							MyLog.d("sleepTime = " + sleepTime);
							Thread.sleep(sleepTime);
						}
					} catch (Exception e) {
						break;
					}
					rsock.updateTimestamp(SystemClock.elapsedRealtime() * 90);
				} else {
					number = 0;
					// first byte of H.263+ payload_header(2 bytes): RR(5)=0, P(1)=0, V(1)=0
					buffer[rtphl] = 0x00; 
				}
				
			} catch (IOException e) {
				MyLog.e("Error: read bytes from local socket", e);
				break;
			}
		}
		
		try {
			while (fis.read(buffer, 0, packetSize) > 0)
				;
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
