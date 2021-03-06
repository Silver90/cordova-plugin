package com.ialocation.plugin;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.indooratlas.android.sdk.IALocation;
import com.indooratlas.android.sdk.IALocationManager;
import com.indooratlas.android.sdk.IALocationRequest;
import com.indooratlas.android.sdk.IARegion;
import com.indooratlas.android.sdk.resources.IAFloorPlan;
import com.indooratlas.android.sdk.resources.IALatLng;
import com.indooratlas.android.sdk.resources.IAResourceManager;
import com.indooratlas.android.sdk.resources.IAResult;
import com.indooratlas.android.sdk.resources.IAResultCallback;
import com.indooratlas.android.sdk.resources.IATask;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Cordova Plugin which implements IndoorAtlas positioning service.
 * IndoorAtlas.initialize method should always be called before starting positioning session.
 */
public class IALocationPlugin extends CordovaPlugin{
    private static final String TAG ="IALocationPlugin";
    private static final int PERMISSION_REQUEST = 101;

    private IALocationManager mLocationManager;
    private IAResourceManager mResourceManager;
    private IATask<IAFloorPlan> mFetchFloorplanTask;
    private String[] permissions = new String[]{
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.INTERNET
    };
    private CallbackContext mCbContext;
    private IndoorLocationListener mListener;
    private boolean mLocationServiceRunning=false;
    private Timer mTimer;
    private String mApiKey, mApiSecret;

    /**
     * Called by the WebView implementation to check for geolocation permissions, can be used
     * by other Java methods in the event that a plugin is using this as a dependency.
     * @return Returns true if the plugin has all the permissions it needs to operate.
     */
    @Override
    public boolean hasPermisssion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        for (String p : permissions) {
            if (PackageManager.PERMISSION_DENIED == cordova.getActivity().checkSelfPermission(p)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Called by the Plugin Manager when we need to actually request permissions
     * @param requestCode   Passed to the activity to track the request
     *
     */
    @Override
    public void requestPermissions(int requestCode) {
        cordova.requestPermissions(this,requestCode,permissions);
    }

    /**
     * Called by the system when the user grants permissions
     * @param requestCode
     * @param permissions
     * @param grantResults
     * @throws JSONException
     */
    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        PluginResult result;
        for (int grantResult : grantResults) {
            if (grantResult == PackageManager.PERMISSION_DENIED) {
                result = new PluginResult(PluginResult.Status.ERROR, PositionError.getErrorObject(PositionError.PERMISSION_DENIED));
                mCbContext.sendPluginResult(result);
                return;
            }
        }
        if (PERMISSION_REQUEST == requestCode) {
            result = new PluginResult(PluginResult.Status.OK);
            mCbContext.sendPluginResult(result);
        }
        mCbContext = null;
    }

    /**
     * Executes the request.
     * This method is called from the WebView thread. To do a non-trivial amount of work, use:
     *     cordova.getThreadPool().execute(runnable);
     *     To run on the UI thread, use:
     *     cordova.getActivity().runOnUiThread(runnable);
     * @param action          The action to execute.
     * @param args            The exec() arguments.
     * @param callbackContext The callback context used when calling back into JavaScript.
     * @return
     * @throws JSONException
     */
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        try{
            if ("initializeIndoorAtlas".equals(action)){
                if (validateIAKeys(args)){
                    String apiKey = args.getString(0);
                    String apiSecret = args.getString(1);
                    initializeIndoorAtlas(apiKey,apiSecret);
                    callbackContext.success();
                }
                else{
                    callbackContext.error(PositionError.getErrorObject(PositionError.INVALID_ACCESS_TOKEN));
                }
            }else if ("addWatch".equals(action)){
                String watchId = args.getString(0);
                addWatch(watchId,callbackContext);
                if (!mLocationServiceRunning){
                    startPositioning(callbackContext);
                }
            }else if ("clearWatch".equals(action)){
                String watchId = args.getString(0);
                clearWatch(watchId);
                callbackContext.success();

            } else if ("getLocation".equals(action)){
                if (mLocationServiceRunning){ //get last known location if service has started.
                    getLastKnownLocation(callbackContext);
                }
                else{//Start service
                    getListener(this).addCallback(callbackContext);
                    startPositioning(callbackContext);
                }
            } else if ("getPermissions".equals(action)){
                if (hasPermisssion()){
                    callbackContext.success();
                    return true;
                }
                else{
                    mCbContext = callbackContext;
                    requestPermissions(PERMISSION_REQUEST);
                }
            } else if ("setPosition".equals(action)){
                setPosition(args,callbackContext);
            } else if ("addRegionWatch".equals(action)){
                String watchId = args.getString(0);
                if (!mLocationServiceRunning){
                    startPositioning(callbackContext);
                }
                addRegionWatch(watchId,callbackContext);
            }else if ("clearRegionWatch".equals(action)){
                String watchId = args.getString(0);
                clearRegionWatch(watchId);
                callbackContext.success();
            }else if("fetchFloorplan".equals(action)){
                String floorplanId = args.getString(0);
                fetchFloorplan(floorplanId,callbackContext);
            }

        }
        catch(Exception ex){
            Log.e(TAG,ex.toString());
            callbackContext.error(PositionError.getErrorObject(PositionError.UNSPECIFIED_ERROR,ex.toString()));
            return false;
        }
        return true;
    }

    /**
     * The final call you receive before your activity is destroyed.
     */
    @Override
    public void onDestroy() {
        if (mLocationManager!=null){
            mLocationManager.destroy();
        }
        super.onDestroy();
    }

    /**
     * Gets last known saved position from IndoorLocationListener
     * @param callbackContext
     */
    private void getLastKnownLocation(CallbackContext callbackContext)throws JSONException{
        JSONObject locationData;
        if (mListener!=null){
            locationData = mListener.getLastKnownLocation();
            if (locationData!=null){
                callbackContext.success(locationData);
            }
            else{
                callbackContext.error(PositionError.getErrorObject(PositionError.POSITION_UNAVAILABLE));
            }
        }
        else{
            callbackContext.error(PositionError.getErrorObject(PositionError.POSITION_UNAVAILABLE));
        }
    }

    /**
     * Initialized location manger with given key and secret
     * @param apiKey
     * @param apiSecret
     */
    private void initializeIndoorAtlas(final String apiKey, final String apiSecret){
        if (mLocationManager==null){
            cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Bundle bundle = new Bundle(2);
                    bundle.putString(IALocationManager.EXTRA_API_KEY,apiKey);
                    bundle.putString(IALocationManager.EXTRA_API_SECRET,apiSecret);
                    mLocationManager = IALocationManager.create(cordova.getActivity().getApplicationContext(),bundle);
                    mResourceManager = IAResourceManager.create(cordova.getActivity().getApplicationContext(),bundle);
                    mApiKey = apiKey;
                    mApiSecret = apiSecret;
                }
            });
        }
    }

    /**
     * Starts tasks to fetch floorplan from IA
     * @param floorplanId
     * @param callbackContext
     */
    private void fetchFloorplan(String floorplanId, CallbackContext callbackContext){
        if (mResourceManager!=null){
            cancelPendingNetworkCalls();
            mCbContext = callbackContext;
            mFetchFloorplanTask = mResourceManager.fetchFloorPlanWithId(floorplanId);
            mFetchFloorplanTask.setCallback(new IAResultCallback<IAFloorPlan>() {
                @Override
                public void onResult(IAResult<IAFloorPlan> iaResult) {
                    IAFloorPlan floorPlan;
                    JSONObject floorplanInfo;
                    JSONArray latlngArray;
                    floorPlan = iaResult.getResult();
                    IALatLng iaLatLng;
                    try{
                        if (floorPlan!=null){
                            floorplanInfo = new JSONObject();
                            floorplanInfo.put("id",floorPlan.getId());
                            floorplanInfo.put("name",floorPlan.getName());
                            floorplanInfo.put("url",floorPlan.getUrl());
                            floorplanInfo.put("floorLevel",floorPlan.getFloorLevel());
                            floorplanInfo.put("bearing",floorPlan.getBearing());
                            floorplanInfo.put("bitmapHeight",floorPlan.getBitmapHeight());
                            floorplanInfo.put("bitmapWidth",floorPlan.getBitmapWidth());
                            floorplanInfo.put("heightMeters",floorPlan.getHeightMeters());
                            floorplanInfo.put("widthMeters",floorPlan.getWidthMeters());
                            floorplanInfo.put("metersToPixels",floorPlan.getMetersToPixels());
                            floorplanInfo.put("pixelsToMeters",floorPlan.getPixelsToMeters());

                            latlngArray = new JSONArray();
                            iaLatLng = floorPlan.getBottomLeft();
                            latlngArray.put(iaLatLng.longitude);
                            latlngArray.put(iaLatLng.latitude);
                            floorplanInfo.put("bottomLeft",latlngArray);

                            latlngArray = new JSONArray();
                            iaLatLng = floorPlan.getCenter();
                            latlngArray.put(iaLatLng.longitude);
                            latlngArray.put(iaLatLng.latitude);
                            floorplanInfo.put("center",latlngArray);

                            latlngArray = new JSONArray();
                            iaLatLng = floorPlan.getTopLeft();
                            latlngArray.put(iaLatLng.longitude);
                            latlngArray.put(iaLatLng.latitude);
                            floorplanInfo.put("topLeft",latlngArray);

                            latlngArray = new JSONArray();
                            iaLatLng = floorPlan.getTopRight();
                            latlngArray.put(iaLatLng.longitude);
                            latlngArray.put(iaLatLng.latitude);
                            floorplanInfo.put("topRight",latlngArray);
                            mCbContext.success(floorplanInfo);
                        }
                        else{
                            mCbContext.error(PositionError.getErrorObject(PositionError.FLOOR_PLAN_UNAVAILABLE));
                        }
                    }
                    catch(JSONException ex){
                        Log.e(TAG, ex.toString());
                        throw new IllegalStateException(ex.getMessage());
                    }
                }
            }, Looper.getMainLooper());
        }
        else{
            callbackContext.error(PositionError.getErrorObject(PositionError.INITIALIZATION_ERROR));
        }
    }

    /**
     * Helper method to cancel current task if any.
     */
    private void cancelPendingNetworkCalls() {
        if (mFetchFloorplanTask!=null){
            if (!mFetchFloorplanTask.isCancelled()){
                mFetchFloorplanTask.cancel();
                mCbContext.sendPluginResult(new PluginResult(PluginResult.Status.NO_RESULT));
            }
        }
    }

    /**
     * Adds a new callback to the IndoorAtlas location listener
     * @param watchId
     * @param callbackContext
     */
    private void addWatch(String watchId, CallbackContext callbackContext){
        getListener(this).addWatch(watchId,callbackContext);
    }

    /**
     * Adds a new callback to the IndoorAtlas IARegion.Listener
     */
    private void addRegionWatch(String watchId, CallbackContext callbackContext){
        getListener(this).addRegionWatch(watchId,callbackContext);
    }
    /**
     * Removes callback from IndoorAtlas location listener
     * @param watchId
     */
    private void clearWatch(String watchId){
        getListener(this).clearWatch(watchId);
    }

    /**
     * Removes callback from IndoorAtlas IARegion.Listener
     * @param watchId
     */
    private void clearRegionWatch(String watchId){
        getListener(this).clearRegionWatch(watchId);
    }

    /**
     * Resets IndoorAtlas positioning session (NOT BEING USED)
     */
    public void resetIndoorAtlas(){
        stopPositioning();
        initializeIndoorAtlas(mApiKey, mApiSecret);
        startPositioning();
    }

    /**
     * Validates parameters passed by user before initializing IALocationManager
     * @param args
     * @return
     * @throws JSONException
     */
    private boolean validateIAKeys(JSONArray args) throws JSONException{
        String apiKey = args.getString(0);
        String apiSecret = args.getString(1);
        if (apiKey.trim().equalsIgnoreCase("")){
            return false;
        }
        if (apiSecret.trim().equalsIgnoreCase("")){
            return false;
        }
        return true;
    }

    /**
     * Validates parameters passed by user before calling IALocationManager.setLocation
     * @param args
     * @return
     * @throws JSONException
     */
    private boolean validatePositionArguments(JSONArray args) throws JSONException{
        if (!args.getString(0).trim().equalsIgnoreCase("")){
            return true;
        }
        if (args.getJSONArray(1).length() == 2){
            return true;
        }
        return false;
    }

    /**
     * Set explicit position of IALocationManager using either a region or geo-coordinates
     * @param args
     * @param callbackContext
     * @throws Exception
     */
    private void setPosition(final JSONArray args,final CallbackContext callbackContext) throws Exception{
        final IALocation.Builder builder;
        if (mLocationManager!=null){
            if (validatePositionArguments(args)){
                builder = new IALocation.Builder();
                if (!args.getString(0).trim().equalsIgnoreCase("")){
                    builder.withRegion(IARegion.floorPlan(args.getString(0).trim()));
                }
                if (args.getJSONArray(1).length() == 2){
                    builder.withLatitude(args.getJSONArray(1).getDouble(0));
                    builder.withLongitude(args.getJSONArray(1).getDouble(1));
                }
                cordova.getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        IALocation iaLocation;
                        iaLocation = builder.build();
                        mLocationManager.setLocation(iaLocation);
                        callbackContext.success();
                    }
                });
            }
        }
        else{
            callbackContext.error(PositionError.getErrorObject(PositionError.INITIALIZATION_ERROR));
        }
    }

    /**
     * Starts IndoorAtlas positioning session
     * @param callbackContext
     */
    protected void startPositioning(CallbackContext callbackContext){
        if (mLocationManager!=null){
            startPositioning();
        }
        else{
            callbackContext.error(PositionError.getErrorObject(PositionError.INITIALIZATION_ERROR));
        }
    }

    /**
     * Starts IndoorAtlas positioning session
     */
    protected void startPositioning(){
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mLocationManager.requestLocationUpdates(IALocationRequest.create(), getListener(IALocationPlugin.this));
                mLocationManager.registerRegionListener(getListener(IALocationPlugin.this));
                mLocationServiceRunning=true;
            }
        });
    }

    /**
     * Stops IndoorAtlas positioning session
     */
    protected void stopPositioning(){
        if (mLocationManager!=null){
            cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mLocationManager.unregisterRegionListener(getListener(IALocationPlugin.this));
                    mLocationManager.removeLocationUpdates(getListener(IALocationPlugin.this));
                    mLocationServiceRunning=false;
                }
            });
        }
    }

    /**
     * Checks if application manifest contains IndoorAtlas key and secret(NOT BEING USED).
     * @return
     * @throws PackageManager.NameNotFoundException
     */
    private boolean isValidManifest()throws PackageManager.NameNotFoundException{
        Bundle bundle;
        bundle = cordova.getActivity().getPackageManager().getApplicationInfo(cordova.getActivity().getPackageName(), PackageManager.GET_META_DATA).metaData;
        if (!bundle.containsKey("com.indooratlas.android.sdk.API_KEY")){
            return false;
        }
        if (!bundle.containsKey("com.indooratlas.android.sdk.API_SECRET")){
            return false;
        }
        return true;
    }

    /**
     * Returns IndoorLocationListener class object
     * @param plugin
     * @return
     */
    private IndoorLocationListener getListener(IALocationPlugin plugin){
        if (mListener == null){
            mListener = new IndoorLocationListener(plugin);
        }
        return mListener;
    }

    /**
     * Cancels the timeout timer used in getCurrentPosition and addWatch methods.
     */
    public void cancelTimer() {
        if (mTimer != null) {
            mTimer.cancel();
            mTimer.purge();
            mTimer = null;
        }
    }

    /**
     * Creates timeout timer used in getCurrentPosition and addWatch methods.
     * @param callbackContext
     * @param timeout
     */
    private void scheduleTimer(CallbackContext callbackContext,int timeout){
        if (mTimer==null){
            mTimer = new Timer();
        }
        mTimer.schedule(new TimeoutTask(callbackContext, getListener(this)),timeout);
    }
    /**
     * TimerTask which implements timeout logic when fetching position.
     */
    private class TimeoutTask extends TimerTask{
        private CallbackContext mCallbackContext = null;
        private IndoorLocationListener mListener = null;

        public TimeoutTask(CallbackContext callbackContext, IndoorLocationListener listener){
            mCallbackContext = callbackContext;
            mListener = listener;
        }
        @Override
        public void run() {
            PluginResult pluginResult;
            JSONObject errorObject;
            for (CallbackContext callbackContext : mListener.getCallbacks()) {
                if (mCallbackContext == callbackContext) {
                    callbackContext.error(PositionError.getErrorObject(PositionError.TIMEOUT));
                    mListener.getCallbacks().remove(callbackContext);
                    break;
                }
            }
            Set<String> keys= mListener.getWatches().keySet();
            for(String key: keys){
                if (mCallbackContext == mListener.getWatches().get(key)){
                    mListener.getWatches().get(key).error(PositionError.getErrorObject(PositionError.TIMEOUT));
                    mListener.getWatches().remove(key);
                }
            }
            if (mListener.size()==0){
                stopPositioning();
            }
        }
    }

}
