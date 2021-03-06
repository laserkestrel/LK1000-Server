package com.shabba.lk1000_server;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.IBinder;
import ioio.lib.api.DigitalOutput;
import ioio.lib.api.DigitalOutput.Spec.Mode;
import ioio.lib.api.IOIO;
import ioio.lib.api.PulseInput;
import ioio.lib.api.PulseInput.PulseMode;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.util.BaseIOIOLooper;
import ioio.lib.util.IOIOLooper;
import ioio.lib.util.android.IOIOService;
import ioio.lib.api.PwmOutput;
import android.util.Log;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
//import java.net.UnknownHostException; 
//import java.net.SocketTimeoutException;



import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.net.wifi.WifiInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;

//import android.hardware.Camera.PictureCallback;
//import android.hardware.Camera.PreviewCallback;
//import android.hardware.Camera.CameraInfo;
//import android.graphics.PixelFormat;
//import android.view.SurfaceView;

//import android.view.SurfaceHolder;

import android.content.Context;

import java.net.URL;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;

import android.os.Environment;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

//adapted from the HelloIOIOService example

public class LK1000Service extends IOIOService {

	// need to properly handle shutdown order from os and clobber the child thread.

	private static final String DEBUG_TAG = "LK4K Server";
	public static Camera cam = null;// has to be static, otherwise onDestroy() destroys it

	Thread myCommsThread = null;
	Thread myCamThread = null;

	public static final int SERVERPORT = 8082;
	boolean continueLoop = true;
	boolean clientConnected = false;

	WifiManager wifiManager = null;
	WifiLock lock = null;
	Integer signalStrength = 0;
	Integer batteryPercentLevel;
	
	// IOIO PINS IN NUMERICAL ORDER
	private final int HBRIDGE_PORT_DIR_PIN = 1 ;
	private final int HBRIDGE_PORT_PWM = 2;
	private final int HBRIDGE_STBD_DIR_PIN = 3 ;
	private final int HBRIDGE_STBD_PWM = 4;
	
	private final int PHONE_TILT_PWM = 10;
	private final int PHONE_PAN_PWM = 11;
	private final int SONIC_ECHO_FORE_PIN = 13;
	private final int SONIC_ECHO_AFT_PIN = 14;
	
	private final int SONIC_TRIG_FORE_PIN = 27;
	private final int SONIC_TRIG_AFT_PIN = 28;
	
	private final int HBRIDGE_PORT_CURRENT = 31;
	private final int HBRIDGE_STBD_CURRENT = 32;
	private final int ANDROID_CHARGE_PIN = 38;
	private final int LASER_SWITCH_PIN = 39;
	private final int BATT1_MONITOR_SWITCH_PIN = 41;
	private final int BATT1_VOLT_PIN = 42;
	private final int BATT1_TEMP_PIN = 43;
	private final int BATT2_MONITOR_SWITCH_PIN = 44;
	private final int BATT2_VOLT_PIN = 45;
	private final int BATT2_TEMP_PIN = 46;
	
	
	//LOOP FREQUENCIES (MILLISEC)
	private final int BATT_MONITORING_FREQ = 60000;
	

	Socket s = null;
	ServerSocket ss = null;

	String direction = "stop";
	boolean robotEnabled = false;
	Integer motorThottleValue = 127;
	/* pan/tilt head */
	static int phonePanValue = 127;
	static int phoneTiltValue = 127;
	/* ultrasonic sensor */
	//private TextView textViewUSDist_;
	private int echoSeconds;
	private int echoDistanceCm;


	@Override
	protected IOIOLooper createIOIOLooper() {
		return new BaseIOIOLooper() {
			private DigitalOutput led_;
			/* ROVER5 chassis */
			private PwmOutput ch1PWM_;
			private PwmOutput ch2PWM_;
			private DigitalOutput ch1Dir_;
			private DigitalOutput ch2Dir_;
			/* pan/tilt head */
			private PwmOutput SvoPanPWM_;
			private PwmOutput SvoTiltPWM_;
			/* 2 channel relay module */
			private DigitalOutput Relay1_;
			private DigitalOutput Relay2_;
			/* ultrasonic sensor (Fore) */
			private DigitalOutput triggerPin_;
			private PulseInput echoPin_;


			@Override
			protected void setup() throws ConnectionLostException,
					InterruptedException {
				Log.d(DEBUG_TAG, "IOIO Setup function starting");
				led_ = ioio_.openDigitalOutput(IOIO.LED_PIN);
				/* Dagu Rover 5 Motor 4 channel motor driver */
				ch1PWM_ = ioio_.openPwmOutput(HBRIDGE_PORT_PWM, 1000);
				ch2PWM_ = ioio_.openPwmOutput(HBRIDGE_STBD_PWM, 1000);
				ch1Dir_ = ioio_.openDigitalOutput(HBRIDGE_PORT_DIR_PIN);
				ch2Dir_ = ioio_.openDigitalOutput(HBRIDGE_STBD_DIR_PIN);
				/* Lynxmotion Pan/Tilt mechanism */
				//SvoPanPWM_ = ioio_.openPwmOutput(PHONE_PAN_PWM, 50); // https://www.servocity.com/html/hs-422_super_sport_.html
				SvoPanPWM_ = ioio_.openPwmOutput(new DigitalOutput.Spec(PHONE_PAN_PWM, Mode.OPEN_DRAIN), 50); // https://www.servocity.com/html/hs-422_super_sport_.html
				//SvoTiltPWM_ = ioio_.openPwmOutput(PHONE_TILT_PWM, 50);
				SvoTiltPWM_ = ioio_.openPwmOutput(new DigitalOutput.Spec(PHONE_TILT_PWM, Mode.OPEN_DRAIN), 50);
				
				/* ultrasonic sensor */
				echoPin_ = ioio_.openPulseInput(SONIC_ECHO_FORE_PIN, PulseMode.POSITIVE);
				triggerPin_ = ioio_.openDigitalOutput(SONIC_TRIG_FORE_PIN);
				
				
				Log.d(DEBUG_TAG, "IOIO Setup function complete");

			} // end setup()

			public void allStop() throws ConnectionLostException,
					InterruptedException {
				// Log.d(DEBUG_TAG, "allStop invoked");
				ch1PWM_.setDutyCycle(0);
				ch2PWM_.setDutyCycle(0);
			}

			public void forward(float dc) throws ConnectionLostException,
					InterruptedException {
				Log.d(DEBUG_TAG, "forward invoked");
				ch1PWM_.setDutyCycle(dc);
				ch2PWM_.setDutyCycle(dc);
				ch1Dir_.write(false);
				ch2Dir_.write(false);
			}

			public void reverse(float dc) throws ConnectionLostException,
					InterruptedException {
				Log.d(DEBUG_TAG, "reverse invoked");
				ch1PWM_.setDutyCycle(dc);
				ch2PWM_.setDutyCycle(dc);
				ch1Dir_.write(true);
				ch2Dir_.write(true);
			}

			public void rotateLeft(float dc) throws ConnectionLostException,
					InterruptedException {
				Log.d(DEBUG_TAG, "rotateLeft invoked");
				ch1PWM_.setDutyCycle(dc);
				ch2PWM_.setDutyCycle(dc);
				ch1Dir_.write(false);
				ch2Dir_.write(true);
			}

			public void rotateRight(float dc) throws ConnectionLostException,
					InterruptedException {
				Log.d(DEBUG_TAG, "rotateRight invoked");
				ch1PWM_.setDutyCycle(dc);
				ch2PWM_.setDutyCycle(dc);
				ch1Dir_.write(true);
				ch2Dir_.write(false);
			}

			public void panCamera(float dc1) throws ConnectionLostException,
			InterruptedException {
			Log.d(DEBUG_TAG, "panCamera invoked with " + dc1);

		//Pulse Width:	500-2400  (MiniServo)
		//Pulse Width:	900-2100  (Hitec HS-303)
		//HITEC dc1 at 1886 seems about max and perfect 90degree right
		// DC1 comes through from client at at 0, which makes the servo go mental...so add 850 to it as the minimum. 
		//AJR - Write my own "direction" so that on pressing the button, the servo spins to the required DC1 PWM value. 
			Log.d(DEBUG_TAG, "panCamera servo set to " + (dc1 + 850));
			SvoPanPWM_.setPulseWidth(dc1 + 850);
			Thread.sleep(50);
		}
			
			
			public void tiltCamera(float dc2) throws ConnectionLostException,
			InterruptedException {
				//Client sending for horizontal <integer name="phoneTiltMax">1531</integer>
				//with addition of 850 servo minimum thats equal to 2381 to reach horiz
				//Minimum needs to be at 428 + 850 which is 1278
				
				if (dc2 < 1300) 
				{
				Log.d(DEBUG_TAG, "tiltCamera has gone below configured minimum of 1300.." + dc2);
				SvoTiltPWM_.setPulseWidth(1300);
				}
				else
				{
				Log.d(DEBUG_TAG, "tiltCamera invoked with " + dc2);
				//Log.d(DEBUG_TAG, "tiltCamera servo set to " + (dc2);
				SvoTiltPWM_.setPulseWidth(dc2);
				Thread.sleep(50);
				}
		}
			
			public void restCamera() throws ConnectionLostException,
			InterruptedException {
				Log.d(DEBUG_TAG, "restCamera invoked");
				SvoPanPWM_.setPulseWidth(1766);
				SvoTiltPWM_.setPulseWidth(1866);
				Thread.sleep(50);
				}			

			// Torch stuff

			private Camera camera;
			private boolean isFlashOn;

			Parameters params;

			// getting camera parameters
			public void getCamera() {
				if (camera == null) {
					try {
						camera = Camera.open();
						params = camera.getParameters();
					} catch (RuntimeException e) {
						Log.e("Camera Error. Failed to Open. Error: ",
								e.getMessage());
					}
				}
			}

			// Turning On flash
			public void turnOnFlash() {
				if (!isFlashOn) {
					if (camera == null || params == null) {
						return;
					}

					params = camera.getParameters();
					params.setFlashMode(Parameters.FLASH_MODE_TORCH);
					camera.setParameters(params);
					camera.startPreview();
					isFlashOn = true;

				}

			}

			// Turning Off flash
			public void turnOffFlash() {
				if (isFlashOn) {
					if (camera == null || params == null) {
						return;
					}

					params = camera.getParameters();
					params.setFlashMode(Parameters.FLASH_MODE_OFF);
					camera.setParameters(params);
					camera.stopPreview();
					isFlashOn = false;

				}
			}

			// Torch stuff end

			@Override
			public void loop() throws ConnectionLostException,
					InterruptedException {
				while (continueLoop) { // Main IOIO control loop

					boolean validDirection = false;

					led_.write(false);
					Thread.sleep(500);
					led_.write(true);
					Thread.sleep(500);

					float dc = (float) (motorThottleValue / 100.0); // duty cycle for PWM motor speed
					float dc1 = (float) (phonePanValue); // duty cycle for Phone PaningPWM motor speed
					float dc2 = (float) (phoneTiltValue); // duty cycle for Phone TiltingPWM motor speed
					
					//Log.d(DEBUG_TAG, "Battery Percent level is at" + batterypercentlevel);
					
					///robot freaks HC-404 sensor code needs adding here. 
					
					
					
					
					
					
					
					
					
					
					
					
					
					// if either of the below are false, stop evaluating either condition.
					// if the user has told us to disable the motors, or if no client is connected, STOP. :-)
					if (!(robotEnabled && clientConnected)) {
						allStop();
					} else

					{
						// Old versions of Android do not appear to have had switch statements.
						// We now match the command we got from the client and call functions to set the PWM and direction pins on the IOIO accordingly

						if (direction.equals("stop")) {
							allStop();
							validDirection = true;
						}

						if (direction.equals("forward")) {
							forward(dc);
							validDirection = true;
						}

						if (direction.equals("rotateRight")) {
							rotateRight(dc);
							validDirection = true;
						}

						if (direction.equals("rotateLeft")) {
							rotateLeft(dc);
							validDirection = true;
						}

						if (direction.equals("reverse")) {
							reverse(dc);
							validDirection = true;
						}

						if (direction.equals("moveCamera")) {
							panCamera(dc1);
							tiltCamera(dc2);
							validDirection = true;
						}

						// we didn't get a valid direction, all stop.
						if (!validDirection)
							allStop();

					}

					Thread.sleep(100);
				} // end loop function
			}
		};

	} // end looper thread

	@Override
	public void onDestroy() {
		// we can't guarantee that this runs on service death
		Log.d(DEBUG_TAG, "Service thread was ordered to shutdown by OS.");
		// AJR do I need to unregisterReceiver(INSERTSOMETHINGHERE); I get exceptions when i stop the service.
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		super.onStartCommand(intent, flags, startId);

		wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		lock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
				"LockTag");
		// lock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL,"LockTag");
		Log.d(DEBUG_TAG, "AJR: onStart invoked");
		NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		this.registerReceiver(this.batteryInfoReceiver,	new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

		if (intent != null && intent.getAction() != null
				&& intent.getAction().equals("stop")) {
			// User clicked the notification. Need to stop the service.
			nm.cancel(0);

			// tell comm thread to shut down.
			this.myCommsThread.interrupt(); // stops outer loop
			continueLoop = false; // stops inner loop, if it's there

			try { // close the network socket in case the comm thread is blocking in read or accept

				if (s != null)
					s.close();

				if (ss != null)
					ss.close();
			} catch (IOException e) {
				Log.d(DEBUG_TAG,
						"Service thread caught exception in socket teardown.");
			}

			releaseWifiLock();
			Log.d(DEBUG_TAG, "Robot server ending.");

			stopSelf();
		}

		else

		{
			// Service starting. Create a notification.
			Log.d(DEBUG_TAG, "Robot server starting.");

			// loop until wifi is connected

			while (!isWifiConnected())

				obtainWifiLock();

			// launch network thread
			this.myCommsThread = new Thread(new CommsThread());
			this.myCommsThread.start();

			Notification notification = new Notification(
					R.drawable.ic_launcher, "Robot service running",
					System.currentTimeMillis());
			notification
					.setLatestEventInfo(this, "Robot Service", "Click to stop",
							PendingIntent.getService(this, 0, new Intent(
									"stop", null, this, this.getClass()), 0));
			notification.flags |= Notification.FLAG_ONGOING_EVENT;
			nm.notify(0, notification);

			// grabImage();
		}
		return START_STICKY;
	} // end onStart()
	
	   private BroadcastReceiver batteryInfoReceiver = new BroadcastReceiver() 
	   {
			@Override
			public void onReceive(Context context, Intent intent) {

				batteryPercentLevel= intent.getIntExtra(BatteryManager.EXTRA_LEVEL,0);
			}
		};

	private void obtainWifiLock() {
		// Prevent Android from throttling the wifi back to save batteries
		Log.d(DEBUG_TAG, "Preventing wifi throttling.");
		if (!lock.isHeld())
			lock.acquire();
	}

	private void releaseWifiLock() {
		if (lock != null)
			if (lock.isHeld())
				lock.release();
	}

	public void updateSensors() {
		// called from the child thread to make sure sensor values are up to
		// date
		// eventually there will be more sensors to update
		updateWifiStats();
	}

	private boolean isWifiConnected() {
		Log.d(DEBUG_TAG, "Checking if isWfifiConnected");
		ConnectivityManager connManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
		NetworkInfo mWifi = connManager
				.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

		if (mWifi.isConnected())
			return true;

		return false;

	}

	private void updateWifiStats() {
		// currently just updates the WIFI signal level
		// Log.d(DEBUG_TAG, "updates the WIFI signal level");
		Integer numLevels = 6;

		WifiInfo currentInfo = null;

		currentInfo = wifiManager.getConnectionInfo();
		signalStrength = wifiManager.calculateSignalLevel(
				currentInfo.getRssi(), numLevels);

	}
	
	class CommsThread implements Runnable {

		// This thread sets up the network server and accepts client connections

		public void run() {

			// while not interrupted by the parent, listen for client connections
			// parent also closes sockets and sets continueLoop false to break the comm thread from whatever it is doing

			while (!Thread.currentThread().isInterrupted())
			// while (continueLoop)
			{
				listen();
				clientConnected = false;

				// if we get this far, the client closed the connection, or the
				// connection died.
				// immediately listen for client reconnections
				Log.d(DEBUG_TAG, "cli closed connection, or conn. died");
			}

			// if we get here the thread should end. The parent has instructed
			// us to exit.
			Log.d(DEBUG_TAG,
					"if we get here thread should end. parent has instructed to exit.");
			// AJR 16-apr-15 Added this below
			continueLoop = false;
		}

		private Integer listen() {
			BufferedReader input = null;
			PrintWriter output = null;
			String st = null;
			String outputString = null;
			s = null;
			continueLoop = true;

			try {

				ss = new ServerSocket(SERVERPORT);
				Log.d(DEBUG_TAG, "Listening for client on > " + SERVERPORT);

				s = ss.accept();
				s.setSoTimeout(2000); // if s times out, it should generate an IO exception that will be caught later
				Log.d(DEBUG_TAG, "Client connected.");

				input = new BufferedReader(new InputStreamReader(s.getInputStream()));
				output = new PrintWriter(s.getOutputStream(), true);

			} catch (Exception e) {
				Log.d(DEBUG_TAG,
						"Comm thread caught exception in ServerSocket setup.");
			}

			try {

				clientConnected = true;

				while (continueLoop) {

					updateSensors();

					// protocol:
					// enabled or disabled (0,1)
					// Directions: stop, rotateRight, rotateLeft, forward, reverse, moveCamera
					// motorThottleValue (0-100)
					// PhonePanValue (min and max servo specific)
					// PhoneTiltValue (min and max servo specific)
					// Client sends: robotEnabled,direction,motorThottleValue
					// Server replies: sensor1,sensor2...

					st = input.readLine();
					// parse input line from control station
					String[] separated = st.split(",");

					// check size to prevent crash if we get a bad string

					if (separated.length == 5) // TODO: make this defined
												// elsewhere
					{
						robotEnabled = Boolean.valueOf(separated[0]);
						direction = separated[1];
						motorThottleValue = Integer.valueOf(separated[2]);
						phonePanValue = Integer.valueOf(separated[3]);
						phoneTiltValue = Integer.valueOf(separated[4]);
						// example st output is "true,rotateRight,47,1000,1125"
					} else {
						// we got a bad command string. All stop.
						direction = "stop";
						robotEnabled = false;
						Log.d(DEBUG_TAG, "WARNING No valid command string from client.");
					}

					// send back sensor values - currently just signal strength

					//output.println(signalStrength.toString());
					//output.flush();
					
					output.println(outputString);
					outputString = signalStrength.toString() + "," + batteryPercentLevel.toString();
					//Log.d(DEBUG_TAG, "Server sends: " + outputString.toString());
					output.flush();
					
					// check for client polite disconnect
					if (st.equals("quit"))
						continueLoop = false;

					// check for client rude disconnect
					if (st.equals(null))
						continueLoop = false;

				}
				// end command loop

			} catch (Exception e) {
				// this happens if the connection times out. Pass on and set up for next listen loop.
				// Also happens if the service thread dies before the network thread
				Log.d(DEBUG_TAG,
						"Robot comm got IOException in main listen() loop.");
				e.printStackTrace();
			}

			try {
				if (s != null)
					s.close();

				if (ss != null)
					ss.close();

			} catch (IOException e) {
				// Something went wrong cleaning up the sockets
				Log.d(DEBUG_TAG,
						"Robot comm got IOException in socket cleanup.");
			}

			return 1;
		}

	} // end network server thread

	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}

}