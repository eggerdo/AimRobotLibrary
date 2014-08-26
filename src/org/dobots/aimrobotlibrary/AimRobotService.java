package org.dobots.aimrobotlibrary;

import java.util.HashMap;

import org.dobots.aim.AimProtocol;
import org.dobots.aim.AimService;
import org.dobots.comm.msg.RoboCommands;
import org.dobots.comm.msg.RoboCommands.BaseCommand;
import org.dobots.comm.msg.RoboCommands.ControlCommand;
import org.dobots.utilities.ThreadMessenger;
import org.dobots.utilities.log.AndroidLogger;
import org.dobots.utilities.log.Logger;
import org.dobots.zmq.ZmqHandler;
import org.dobots.zmq.video.FpsCounter;
import org.dobots.zmq.video.IFpsListener;
import org.dobots.zmq.video.IRawVideoListener;
import org.dobots.zmq.video.VideoThrottle;
import org.dobots.zmq.video.ZmqVideoReceiver;
import org.zeromq.ZMQ;

import robots.remote.RobotServiceBinder;
import robots.remote.RobotServiceBinder.RobotBinder;
import android.content.Intent;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

public abstract class AimRobotService extends AimService implements IRawVideoListener {
	
	private static final String TAG = "RobotService";
	
	protected RobotServiceBinder mRobot;
	
	// a ThreadMessenger is a thread with a messenger. messages are
	// handled by that thread
	// a normal Messenger uses the thread that creates the messenger
	// to handle messages (which in this case would be the main (UI)
	// thread
	private ThreadMessenger mPortCmdInReceiver = new ThreadMessenger("PortCmdInMessenger") {
		
		@Override
		public boolean handleIncomingMessage(Message msg) {
			switch (msg.what) {
			case AimProtocol.MSG_PORT_DATA:
				// do we need to check datatype to make sure it is string?
				String data = msg.getData().getString("data");
				BaseCommand cmd = RoboCommands.decodeCommand(data);
				handleData(cmd);
				break;
			default:
				return false;
			}
			return true;
		}
	};
	
	protected void handleData(BaseCommand cmd) {
		try {
			if ((cmd instanceof ControlCommand) && ((ControlCommand)cmd).mCommand.equals("setFrameRate")) {
				setFrameRate((Double)((ControlCommand)cmd).getParameter(0));
			} else {
				mRobot.handleCommand(cmd);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	protected VideoThrottle mVideoThrottle;

	private ZmqVideoReceiver mVideoReceiver;

	private FpsCounter mCounter;
	
	@Override
	public String getTag() {
		return TAG;
	}
	
	public void defineInMessenger(HashMap<String, Messenger> list) {
		list.put("cmd", mPortCmdInReceiver.getMessenger());
	}

	public void defineOutMessenger(HashMap<String, Messenger> list) {
		list.put("video", null);
	}

	@Override
	public IBinder onBind(Intent intent) {
		Log.i(TAG, "onBind: " + intent.toString());
		return mRobot.getBinder();
	}
	
	public void onCreate() {
		super.onCreate();
		
		ZmqHandler.initialize(this);
		Logger.setLogger(new AndroidLogger());
		
		mVideoThrottle = new VideoThrottle("videoThrottle");
		mVideoThrottle.setRawVideoListener(this);
		mVideoThrottle.setFrameRate(20.0);

		mCounter = new FpsCounter(new IFpsListener() {
		
			@Override
			public void onFPS(double i_nFPS) {
				Log.d("debug", "fps: %1f" + i_nFPS);
			}
		});
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		
		if (mRobot != null) {
			mRobot.destroy();
		}
		
		mPortCmdInReceiver.destroy();
		
		mVideoThrottle.destroy();
		mVideoReceiver.destroy();

		ZmqHandler.destroyInstance();
	}

	@Override
	public void onFrame(byte[] rgb, int rotation) {
		if (getOutMessenger("video") != null) {
			String base64 = android.util.Base64.encodeToString(rgb, android.util.Base64.NO_WRAP);
			mAimConnectionHelper.sendData(getOutMessenger("video"), base64);
			mCounter.tick();
		}
	}

	protected void setRobot(RobotServiceBinder robotServiceBinder) {
		mRobot = robotServiceBinder;

		ZMQ.Socket oVideoRecvSocket = ZmqHandler.getInstance().obtainVideoRecvSocket();
		oVideoRecvSocket.subscribe(((RobotBinder)mRobot.getBinder()).getRobot().getID().getBytes());

		mVideoReceiver = new ZmqVideoReceiver(oVideoRecvSocket);
		mVideoReceiver.setRawVideoListener(mVideoThrottle);
		mVideoReceiver.start();
	}

	protected RobotServiceBinder getRobot() {
		return mRobot;
	}
	
	public void setFrameRate(double rate) {
		mVideoThrottle.setFrameRate(rate);
	}

}
