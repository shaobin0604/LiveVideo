package cn.yo2.aquarium.livevideo;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

import android.os.Environment;
import android.view.SurfaceHolder;
import cn.yo2.aquarium.livevideo.librtp.RtpPacket;
import cn.yo2.aquarium.logutils.MyLog;

public class RtpVideoPlayer {
	
	private AndroidVideoWindowImpl mVideoWindow;
	
	private int mPort;
	private VideoReceiver mVideoReceiver;
	
	public void setPort(int port) {
		MyLog.d("setPort");
		mPort = port;
	}
	
	public void setDisplay(SurfaceHolder sh, AndroidVideoWindowImpl.VideoWindowListener listener) {
		MyLog.d("setDisplay");
		mVideoWindow = new AndroidVideoWindowImpl(sh);
		mVideoWindow.setListener(listener);
	}
	
	public void start() {
		MyLog.d("start");
		mVideoReceiver = new VideoReceiver(mVideoWindow, mPort);
		mVideoReceiver.startReceive();
	}
	
	public void stop() {
		MyLog.d("stop");
		if (mVideoReceiver != null) {
			mVideoReceiver.quit();
			mVideoReceiver = null;
		}
	}
	
	private static class VideoReceiver extends Thread {

		private static final boolean DUMP_RECEIVE_RAW_H263 = false;
		private static final String DUMP_RECEIVE_RAW_H263_FILE = "raw_j.h263";
		private static final int PT_H263_1998 = 103;
		private boolean mRunning;
		private AndroidVideoWindowImpl mVideoWindow;
		private int mVideoPort;

		public void quit() {
			MyLog.i("quit");
			if (mRunning) {
				mRunning = false;
				interrupt();
				mVideoWindow.stopPlayThread();
			}
		}

		public VideoReceiver(AndroidVideoWindowImpl window, int videoPort) {
			MyLog.i("VideoReceiver constructor");
			mVideoWindow = window;
			mVideoPort = videoPort;
		}

		private void startReceive() {
			MyLog.d("startReceive");
			mVideoWindow.startPlayThread();
			start();
		}

		private static void writeRawH263(OutputStream os, byte[] packet, int offset, int len) {
			// Logger.i("Write offset[" + offset + "], len[" + len + "]");
			try {
				os.write(packet, offset, len);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		@Override
		public void run() {
			int frame_size = 1400;
			byte[] buffer = new byte[frame_size + 14];
			int rtpPayloadLen; // h263 header len(2) + h263 stream len
			int h263PayloadLen; // h263 stream len
			int rtpPayloadType;
			final byte[] psc = { 0x00, 0x00 };
			final int pscLen = psc.length;
			boolean hasPsc = false;

			// int startcode, vrc, pictureHeader;

			// gather h263 frame, when gather complete one, send to jni
			final int framebufferLen = 1024 * 16;
			byte[] framebuffer = new byte[framebufferLen];
			int offset = 0;

			FileOutputStream fos = null;

			if (DUMP_RECEIVE_RAW_H263) {
				File output = new File(Environment.getExternalStorageDirectory(), DUMP_RECEIVE_RAW_H263_FILE);
				MyLog.i("Open file: " + output + " to dump raw h263 stream");
				try {
					fos = new FileOutputStream(output);
				} catch (FileNotFoundException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();

					MyLog.e("Cannot open output file " + output + ", exit videoReceiverThread");
				}
			}

			mRunning = true;
			DatagramSocket socket = null;

			try {
				socket = new DatagramSocket(mVideoPort);
				DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
				while (mRunning) {
					socket.receive(packet);

					RtpPacket rtpPacket = new RtpPacket(buffer, packet.getLength());

					MyLog.d("<----- " + packet.getAddress() + ", " + rtpPacket.toString());

					rtpPayloadType = rtpPacket.getPayloadType();

					if (rtpPayloadType != PT_H263_1998) {
						MyLog.w("Not h263 payload, drop this one");
						continue;
					}

					rtpPayloadLen = rtpPacket.getPayloadLength();

					if (rtpPayloadLen < 2) {
						MyLog.w("Malformed RTP packet, drop this one");
						continue;
					}

					hasPsc = (buffer[12] & 0x04) > 0;

					h263PayloadLen = rtpPayloadLen - 2;

					if (hasPsc) {
						// MyLog.i("***** Start a new frame *****");

						// MyLog.d("before copy start codes");
						System.arraycopy(psc, 0, framebuffer, 0, pscLen);
						offset = pscLen;
						// MyLog.d("after copy start codes, offset = " +
						// offset);

						if (DUMP_RECEIVE_RAW_H263) {
							writeRawH263(fos, psc, 0, pscLen);
						}
					}

					if (DUMP_RECEIVE_RAW_H263) {
						writeRawH263(fos, buffer, 14, h263PayloadLen);
					}

					if (offset > 0) {
						if (offset + h263PayloadLen < framebufferLen) {
							// MyLog.d("before copy h263 payload, offset = [" +
							// offset + "], payload len = [" + h263PayloadLen +
							// "]");
							System.arraycopy(buffer, 14, framebuffer, offset, h263PayloadLen);
							offset += h263PayloadLen;
							// MyLog.d("after copy h263 payload, offset = [" +
							// offset + "]");
						} else {
							MyLog.w("buffer overflow 8k, drop, reset offset");
							offset = 0;
						}
					}

					if (offset > 0 && rtpPacket.hasMarker()) {
						// MyLog.d("frame end, write h263 frame to jni, offset = ["
						// + offset + "]");
						mVideoWindow.writeH263Frame(framebuffer, offset);
					}

				}
			} catch (IOException e) {
				MyLog.e("Error when receive rtp packet quit", e);
			} finally {
				if (socket != null) {
					socket.close();
				}
				
				if (fos != null) {
					try {
						fos.close();
					} catch (IOException e) {
					}
				}
			}
		}
	}
}
