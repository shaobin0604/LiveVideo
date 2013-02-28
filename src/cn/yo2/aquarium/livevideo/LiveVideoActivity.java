package cn.yo2.aquarium.livevideo;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.widget.EditText;
import android.widget.ToggleButton;
import cn.yo2.aquarium.livevideo.AndroidVideoWindowImpl.VideoWindowListener;
import cn.yo2.aquarium.livevideo.librtp.RtpPacket;
import cn.yo2.aquarium.logutils.MyLog;

public class LiveVideoActivity extends Activity implements SurfaceHolder.Callback, OnClickListener, VideoWindowListener {

	private static final int AUDIO_PORT = 50600;
	private static final int VIDEO_PORT = 50601;
	
	private CameraStreamer mCameraStreamer;
	private RtpVideoPlayer mRtpVideoPlayer;

	private EditText mPeerIP;

	private ToggleButton mSendVideo;
	private ToggleButton mPlayVideo;

	private SurfaceView mLocalSurface;
	private SurfaceView mRemoteSurface;


	private SharedPreferences mSharedPreferences;


	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

		// setup local video
		mLocalSurface = (SurfaceView) findViewById(R.id.local_video);
		SurfaceHolder sh = mLocalSurface.getHolder();
		sh.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		sh.addCallback(this);
		
		mCameraStreamer = new CameraStreamer();

		// setup remote video
		mRemoteSurface = (SurfaceView) findViewById(R.id.remote_video);
		
		mRtpVideoPlayer = new RtpVideoPlayer();
		mRtpVideoPlayer.setPort(VIDEO_PORT);
		mRtpVideoPlayer.setDisplay(mRemoteSurface.getHolder(), this);

		mPeerIP = (EditText) findViewById(R.id.peer_ip);
		String ip = mSharedPreferences.getString(getString(R.string.prefs_key_target_ip), "127.0.0.1");
		mPeerIP.setText(ip);

		mSendVideo = (ToggleButton) findViewById(R.id.send_video);
		mSendVideo.setOnClickListener(this);
		mPlayVideo = (ToggleButton) findViewById(R.id.play_video);
		mPlayVideo.setOnClickListener(this);
		
	}
	
	@Override
	protected void onStart() {
		super.onStart();
		
		setScreenOnFlag();
	}
	
	private void setScreenOnFlag() {
		Window w = getWindow();
		final int keepScreenOnFlag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
		if ((w.getAttributes().flags & keepScreenOnFlag) == 0) {
			w.addFlags(keepScreenOnFlag);
		}
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		// TODO Auto-generated method stub

	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		MyLog.d("local surfaceCreated");

		String ip = mPeerIP.getText().toString();

		Editor editor = mSharedPreferences.edit();
		editor.putString(getString(R.string.prefs_key_target_ip), ip);
		editor.commit();

		MyLog.d("peer ip = " + ip);

		try {
			mCameraStreamer.setup(holder, ip, AUDIO_PORT, VIDEO_PORT);
		} catch (IOException e) {
			// Catch error if any and display message
			MyLog.e("Setup CameraStreamer... Error", e);
		}
		mCameraStreamer.start();
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		MyLog.d("local surfaceDestroyed");
		mCameraStreamer.stop();
	}
	
	

	@Override
	public void onSurfaceDestroyed(AndroidVideoWindowImpl v) {
		MyLog.d("remote surfaceDestroyed");
		mRtpVideoPlayer.stop();
	}

	@Override
	public void onSurfaceReady(AndroidVideoWindowImpl v) {
		MyLog.d("remote surfaceReady");
		mRtpVideoPlayer.start();
	}

	@Override
	public void onClick(View v) {
		if (v == mSendVideo) {
			if (mSendVideo.isChecked()) {
				startLocalVideo();
			} else {
				stopLocalVideo();
			}
		} else if (v == mPlayVideo) {
			if (mPlayVideo.isChecked()) {
				startRemoteVideo();
			} else {
				stopRemoteVideo();
			}
		}
	}

	private void startLocalVideo() {
		mLocalSurface.setVisibility(View.VISIBLE);
	}

	private void stopLocalVideo() {
		mLocalSurface.setVisibility(View.GONE);
	}

	private void startRemoteVideo() {
		mRemoteSurface.setVisibility(View.VISIBLE);
	}

	private void stopRemoteVideo() {
		mRemoteSurface.setVisibility(View.GONE);
	}
}