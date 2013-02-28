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

package cn.yo2.aquarium.livevideo;

import java.io.IOException;
import java.net.InetAddress;

import android.media.MediaRecorder;
import android.view.SurfaceHolder;
import cn.yo2.aquarium.livevideo.librtp.AMRNBPacketizer;
import cn.yo2.aquarium.livevideo.librtp.AbstractPacketizer;
import cn.yo2.aquarium.livevideo.librtp.H263Packetizer;
import cn.yo2.aquarium.livevideo.librtp.H264Packetizer;
import cn.yo2.aquarium.logutils.MyLog;

/*
 * 
 * 
 */

public class CameraStreamer {

	private MediaStreamer sound = null, video = null;
	private AbstractPacketizer sstream = null;
	private AbstractPacketizer vstream = null;
	
	public void setup(SurfaceHolder holder, String ip, int audioPort, int videoPort) throws IOException {
		// AUDIO
//		setupAudio(ip, audioPort);
		// VIDEO
		setupVideo(holder, ip, videoPort);
	}
	
	private void setupVideo(SurfaceHolder holder, String ip, int port) throws IOException {
//		setupVideoH264(holder, ip, port);
		
		setupVideoH263(holder, ip, port);
	}

	private void setupVideoH263(SurfaceHolder holder, String ip, int port) throws IOException {
		video = new MediaStreamer();
		
		video.setVideoSource(MediaRecorder.VideoSource.CAMERA);
		video.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
		video.setVideoFrameRate(20);
		video.setVideoSize(176, 144);
		video.setVideoEncoder(MediaRecorder.VideoEncoder.H263);
		video.setPreviewDisplay(holder.getSurface());
		
		try {
			video.prepare();
		} catch (IOException e) {
			throw new IOException("Can't stream video :(");
		}
		
		try {
			vstream = new H263Packetizer(video.getInputStream(), InetAddress.getByName(ip), port);
		} catch (IOException e) {
			MyLog.e("Unknown host");
			throw new IOException("Can't resolve host :(");
		}
	}
	

	private void setupVideoH264(SurfaceHolder holder, String ip, int port) throws IOException {
		video = new MediaStreamer();
		
		video.setVideoSource(MediaRecorder.VideoSource.CAMERA);
		video.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
		video.setVideoFrameRate(15);
		video.setVideoSize(640, 480);
		video.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
		video.setPreviewDisplay(holder.getSurface());
		
		try {
			video.prepare();
		} catch (IOException e) {
			throw new IOException("Can't stream video :(");
		}
		
		try {
			vstream = new H264Packetizer(video.getInputStream(), InetAddress.getByName(ip), port);
		} catch (IOException e) {
			MyLog.e("Unknown host");
			throw new IOException("Can't resolve host :(");
		}
	}

	private void setupAudio(String ip, int port) throws IOException {
		sound = new MediaStreamer();
		
		sound.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
		sound.setOutputFormat(MediaRecorder.OutputFormat.RAW_AMR);
		sound.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
		
		try {
			sound.prepare();
		} catch (IOException e) {
			throw new IOException("Can't stream sound :(");
		}
		
		try {
			sstream = new AMRNBPacketizer(sound.getInputStream(), InetAddress.getByName(ip), port);
		} catch (IOException e) {
			MyLog.e("Unknown host");
			throw new IOException("Can't resolve host :(");
		}
	}
	
	public void start() {
	
		// Start sound streaming
//		startAudio();
		
		// Start video streaming
		startVideo();
		
	}

	private void startVideo() {
		video.start();
		vstream.startStreaming();
	}

	private void startAudio() {
		sound.start();
		sstream.startStreaming();
	}
	
	public void stop() {
	
		// Stop sound streaming
//		stopAudio();
	
		// Stop video streaming
		stopVideo();
		
	}

	private void stopVideo() {
		vstream.stopStreaming();
		video.stop();
	}

	private void stopAudio() {
		sstream.stopStreaming();
		sound.stop();
	}
	
}
