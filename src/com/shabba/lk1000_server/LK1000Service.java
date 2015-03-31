package com.shabba.lk1000_server;
 
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.IBinder;
import ioio.lib.api.DigitalOutput;
import ioio.lib.api.IOIO;
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
 
//adapted from the HelloIOIOService example
 
public class LK1000Service extends IOIOService {
     
    //need to properly handle shutdown order from os and clobber the child thread.
     
    private static final String DEBUG_TAG= "LK4K Server";
    public static Camera cam = null;// has to be static, otherwise onDestroy() destroys it
     
    Thread myCommsThread = null;
    Thread myCamThread = null;
     
    public static final int SERVERPORT = 8082;
    boolean continueLoop = true;
    boolean clientConnected = false;
     
    WifiManager wifiManager = null;
    WifiLock lock = null;
    Integer signalStrength = 0;
     
    Socket s = null;
    ServerSocket ss = null;
     
    String direction = "stop";
    boolean robotEnabled = false;
    Integer servoPanValue = 127;
 
    @Override
    protected IOIOLooper createIOIOLooper() {
        return new BaseIOIOLooper() {
            private DigitalOutput led_;
             
            private PwmOutput ch1PWM_;
            private PwmOutput ch2PWM_;
            private DigitalOutput ch1Dir_;
            private DigitalOutput ch2Dir_;             
            
            @Override
            protected void setup() throws ConnectionLostException,
                    InterruptedException {
                Log.d(DEBUG_TAG, "IOIO Setup function starting");
                led_ = ioio_.openDigitalOutput(IOIO.LED_PIN);
                 
                ch1PWM_ = ioio_.openPwmOutput(1, 1000);
                ch2PWM_ = ioio_.openPwmOutput(2, 1000);
                ch1Dir_ = ioio_.openDigitalOutput(11);
                ch2Dir_ = ioio_.openDigitalOutput(12);

                Log.d(DEBUG_TAG, "IOIO Setup function complete");
                 
                }   //end setup()
             
            public void allStop() throws ConnectionLostException,
            InterruptedException
            {
            	//Log.d(DEBUG_TAG, "allStop invoked");
                ch1PWM_.setDutyCycle(0);
                ch2PWM_.setDutyCycle(0);
                
            }
             
            public void forward(float dc) throws ConnectionLostException,
            InterruptedException
            {
            	Log.d(DEBUG_TAG, "forward invoked");
                ch1PWM_.setDutyCycle(dc);
                ch2PWM_.setDutyCycle(dc);
                ch1Dir_.write(false);
                ch2Dir_.write(false);
            }
             
            public void reverse(float dc) throws ConnectionLostException,
            InterruptedException
            {
            	Log.d(DEBUG_TAG, "reverse invoked");
                ch1PWM_.setDutyCycle(dc);
                ch2PWM_.setDutyCycle(dc);
                ch1Dir_.write(true);
                ch2Dir_.write(true);
            }
             
            public void rotateLeft(float dc) throws ConnectionLostException,
            InterruptedException
            {
            	Log.d(DEBUG_TAG, "rotateLeft invoked");
                ch1PWM_.setDutyCycle(dc);
                ch2PWM_.setDutyCycle(dc);
                ch1Dir_.write(false);
                ch2Dir_.write(true);
            }
             
            public void rotateRight(float dc) throws ConnectionLostException,
            InterruptedException
            {
            	Log.d(DEBUG_TAG, "rotateRight invoked");
                ch1PWM_.setDutyCycle(dc);
                ch2PWM_.setDutyCycle(dc);
                ch1Dir_.write(true);
                ch2Dir_.write(false);
            }
            
            //Torch stuff
            
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
                        Log.e("Camera Error. Failed to Open. Error: ", e.getMessage());
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
            //Torch stuff end
                     
            @Override
            public void loop() throws ConnectionLostException,InterruptedException 
            {   //Main IOIO control loop
                 
                boolean validDirection = false;
                //Log.d(DEBUG_TAG, "IOIO Led Light loop begins");
				led_.write(false);
				Thread.sleep(500);
				led_.write(true);
				Thread.sleep(500);
                //Log.d(DEBUG_TAG, "IOIO Led Light loop ends");
				
                //light LED when robotEnabled
                led_.write(!robotEnabled);
               // Log.d(DEBUG_TAG, "IOIO Robot light enabled");
                         
                float dc = (float) (servoPanValue/100.0);   //duty cycle for PWM - motor speed
                 
                //if either of the below are false, stop evaluating either condition.
                //if the user has told us to disable the motors, or if no client is connected, STOP. :-)
                if (!(robotEnabled && clientConnected))
                	{
                    allStop();
                    //AJR added log output of values of the two booleans robotEnabled and clientConnected
                    //Log.d(DEBUG_TAG + "", "" + robotEnabled + " " + "robotEnabled");
                    //Log.d(DEBUG_TAG + "", "" + clientConnected + " " + "clientConnected");
                	}
                else
                     
                {
                //Old versions of Android do not appear to have had switch statements.
                //We now match the command we got from the client and call functions to set the PWM and direction pins
                //on the IOIO accordingly
                     
                if (direction.equals("stop"))
                    {
                    allStop();
                    validDirection = true;
                    }   
                 
                if (direction.equals("forward"))
                    {
                    forward(dc);
                    validDirection = true;
                    }
                     
                if (direction.equals("rotateRight"))
                    {
                    rotateRight(dc);
                    validDirection = true;
                    }
                 
                if (direction.equals("rotateLeft"))
                    {
                    rotateLeft(dc);
                    validDirection = true;
                    }
                 
                if (direction.equals("reverse"))
                    {
                    reverse(dc);
                    validDirection = true;
                    }
                 
                //we didn't get a valid direction, all stop.
                if (!validDirection)
                    allStop();
                     
                }
                 
                Thread.sleep(100);  
            }   //end loop function
             
        };
    } //end looper thread
 
    @Override
    public void onDestroy() 
    {
        //we can't guarantee that this runs on service death
         
        Log.d(DEBUG_TAG, "Service thread was ordered to shutdown by OS.");
         
    }
     
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) 
    {
        super.onStartCommand(intent, flags, startId);
 
        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        lock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LockTag");
        Log.d(DEBUG_TAG, "AJR: onStart invoked"); 
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
         
        if (intent != null && intent.getAction() != null && intent.getAction().equals("stop"))
        {
            // User clicked the notification. Need to stop the service.
            nm.cancel(0);
             
            //tell comm thread to shut down. 
            this.myCommsThread.interrupt(); //stops outer loop
            continueLoop = false;   //stops inner loop, if it's there
             
             try {  //close the network socket in case the comm thread is blocking in read or accept
                     
                 if (s != null)
                    s.close();
                 
                 if (ss != null)
                    ss.close();
                 }
             catch (IOException e) {Log.d(DEBUG_TAG, "Service thread caught exception in socket teardown.");}
             
            releaseWifiLock();
            Log.d(DEBUG_TAG, "Robot server ending.");
             
            stopSelf();
        } 
         
        else
         
        {
            // Service starting. Create a notification.
            Log.d(DEBUG_TAG, "Robot server starting.");
             
            //loop until wifi is connected
           
            while (!isWifiConnected())
                 
            obtainWifiLock();
             
            //launch network thread
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
             
            //grabImage();
        }
        return START_STICKY;
    }   //end onStart()
 
     
    private void obtainWifiLock()
    {
        //Prevent Android from throttling the wifi back to save batteries
    	Log.d(DEBUG_TAG, "Preventing wifi throttling.");
        if (!lock.isHeld()) 
            lock.acquire(); 
    }
     
    private void releaseWifiLock()
    {
         if (lock != null) 
             if (lock.isHeld()) 
                 lock.release();    
    }
     
 
     
    public void updateSensors()
    {
    //called from the child thread to make sure sensor values are up to date
    //eventually there will be more sensors to update
    updateWifiStats();  
    }
     
    private boolean isWifiConnected()
    {
    	Log.d(DEBUG_TAG, "Checking if isWfifiConnected");
    	ConnectivityManager connManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
 
        if (mWifi.isConnected()) 
            return true;
         
        return false;
         
    }
     
    private void updateWifiStats()
    {
    //currently just updates the WIFI signal level
    //Log.d(DEBUG_TAG, "updates the WIFI signal level");
    Integer numLevels = 6;
         
    WifiInfo currentInfo = null;
     
    currentInfo = wifiManager.getConnectionInfo();
    signalStrength = wifiManager.calculateSignalLevel(currentInfo.getRssi(), numLevels);
         
    }
    

     
    class CommsThread implements Runnable {
         
        //This thread sets up the network server and accepts client connections
         
        public void run() {
           
        //while not interrupted by the parent, listen for client connections
        //parent also closes socket s and sets continueLoop false to break the comm thread from 
        //whatever it is doing
             
        while (!Thread.currentThread().isInterrupted()) 
            //while (continueLoop)
            {
            listen();
            clientConnected = false;
             
            //if we get this far, the client closed the connection, or the connection died.
            //immediately listen for client reconnections
            Log.d(DEBUG_TAG, "cli closed connection, or conn. died"); 
            }
         
        //if we get here the thread should end. The parent has instructed us to exit.
        Log.d(DEBUG_TAG, "if we get here thread should end. parent has instructed to exit.");      
        }
         
        private Integer listen()
        {
            BufferedReader input = null;
            PrintWriter output = null;
            String st = null;
             
            s = null;
            continueLoop = true;
             
            try {
                 
                ss = new ServerSocket(SERVERPORT);
                Log.d(DEBUG_TAG, "Listening for client on > " + SERVERPORT);
                
                s = ss.accept();
                s.setSoTimeout(1000); //if s times out, it should generate an IO exception that will be caught later
                Log.d(DEBUG_TAG, "Client connected.");
                 
                input = new BufferedReader(new InputStreamReader(s.getInputStream()));
                output = new PrintWriter(s.getOutputStream(), true);
                 
                } catch (Exception e) {Log.d(DEBUG_TAG, "Comm thread caught exception in ServerSocket setup.");}
             
            try {
             
            clientConnected = true; 
                 
            while (continueLoop) {
                 
                    updateSensors();
                     
                    //protocol:
                    //enabled or disabled (0,1)
                    //Directions: stop, rotateRight, rotateLeft, forward, reverse
                    //Client sends: robotEnabled,direction,servoPanValue
                    //Server replies: sensor1,sensor2...
                     
                    st = input.readLine();
                    //parse input line from control station
                    String[] separated = st.split(",");
                     
                    //check size to prevent crash if we get a bad string
                     
                    if (separated.length == 3) //TODO: make this defined elsewhere
                        {
                        robotEnabled = Boolean.valueOf(separated[0]);
                        direction = separated[1];
                        servoPanValue = Integer.valueOf(separated[2]);
                        }
                    else
                        {
                        //we got a bad command string. All stop.
                        direction = "stop";
                        robotEnabled = false;
                        Log.d(DEBUG_TAG, "No valid command string from client.");
                        }
                     
                    //send back sensor values - currently just signal strength
                     
                    output.println( signalStrength.toString() );
                    output.flush();
                                         
                    //check for client polite disconnect
                    if (st.equals("quit"))
                        continueLoop = false;
                     
                    //check for client rude disconnect
                    if (st.equals(null))
                        continueLoop = false;
                      
                }
               //end command loop
                     
                } catch (Exception e) {
                    //this happens if the connection times out. Pass on and set up for next listen loop.
                    //Also happens if the service thread dies before the network thread
                    Log.d(DEBUG_TAG, "Robot comm got IOException in main listen() loop.");
                    //e.printStackTrace();
                    } 
         
        try {    
            if (s != null)
                s.close();
         
            if (ss != null)   
                ss.close();
             
            }  catch (IOException e) {
                //Something went wrong cleaning up the sockets
                Log.d(DEBUG_TAG, "Robot comm got IOException in socket cleanup.");
                } 
         
        return 1;   
        }
             
       } //end network server thread
 
     
 
    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }
 
}