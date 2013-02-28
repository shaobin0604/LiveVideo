package cn.yo2.aquarium.livevideo;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Bitmap.Config;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceHolder.Callback;
import cn.yo2.aquarium.logutils.MyLog;

public class AndroidVideoWindowImpl {
	private Surface mSurface;
	private Bitmap mBitmap;
	private VideoWindowListener mListener;
	
	static {
		System.loadLibrary("ffmpeg");
		System.loadLibrary("rtp-player");
	}

	public static interface VideoWindowListener {
		void onSurfaceReady(AndroidVideoWindowImpl v);

		void onSurfaceDestroyed(AndroidVideoWindowImpl v);
	}
	
	public void setListener(VideoWindowListener l) {
		mListener = l;
	}

	public AndroidVideoWindowImpl(SurfaceHolder sh) {
		mBitmap = null;
		mSurface = null;
		mListener = null;

		sh.addCallback(new Callback() {
			public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
				MyLog.i("Surface is being changed.");

				synchronized (AndroidVideoWindowImpl.this) {
					mBitmap = Bitmap.createBitmap(width, height, Config.RGB_565);
					mSurface = holder.getSurface();
				}
				AndroidVideoWindowImpl.this.setViewWindowId(AndroidVideoWindowImpl.this);

				if (mListener != null) {
					mListener.onSurfaceReady(AndroidVideoWindowImpl.this);
				}
				MyLog.w("Video display surface changed");
			}

			public void surfaceCreated(SurfaceHolder holder) {
				MyLog.w("Video display surface created");
			}

			public void surfaceDestroyed(SurfaceHolder holder) {

				synchronized (AndroidVideoWindowImpl.this) {
					mSurface = null;
					mBitmap = null;
				}
				AndroidVideoWindowImpl.this.setViewWindowId(null);

				if (mListener != null) {
					mListener.onSurfaceDestroyed(AndroidVideoWindowImpl.this);
				}
				MyLog.d("Video display surface destroyed");
			}
		});
	}

	static final int LANDSCAPE = 0;
	static final int PORTRAIT = 1;

	public void requestOrientation(int orientation) {
		// Surface.setOrientation(0, orientation==LANDSCAPE ? 1 : 0);
		// Log.d("Orientation changed.");
	}

	public Bitmap getBitmap() {
		MyLog.d("getBitmap enter...");
		return mBitmap;
	}

	// Called by the native code to update SurfaceView
	public synchronized void update() {
		MyLog.d("update enter...");
		if (mSurface != null) {
			try {
				Canvas canvas = mSurface.lockCanvas(null);
				canvas.drawBitmap(mBitmap, 0, 0, null);
				mSurface.unlockCanvasAndPost(canvas);

			} catch (IllegalArgumentException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (OutOfResourcesException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	public native void setViewWindowId(Object oid);
	
	public native void startPlayThread();
	
	public native void stopPlayThread();
	
	public native int writeH263Frame(byte[] frame, int len);
	
	public native int writeRtpPacket(byte[] packet, int offset, int len);
}
