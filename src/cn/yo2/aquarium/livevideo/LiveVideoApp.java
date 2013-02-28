package cn.yo2.aquarium.livevideo;

import cn.yo2.aquarium.logutils.MyLog;
import android.app.Application;

public class LiveVideoApp extends Application {
	private static final String TAG = LiveVideoApp.class.getSimpleName();
	@Override
	public void onCreate() {
		super.onCreate();
		MyLog.initLog(TAG, true, false);
	}
}
