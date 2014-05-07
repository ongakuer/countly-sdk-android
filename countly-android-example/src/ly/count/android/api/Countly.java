package ly.count.android.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.OpenUDID.OpenUDID_manager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

public class Countly {

    private volatile static Countly sInstance;

    private ScheduledExecutorService mTimer;

    private ConnectionQueue mQueue;

    private EventQueue mEventQueue;

    private boolean mIsVisible;

    private double mUnsentSessionLength;

    private double mLastTime;

    private int mActivityCount;

    private CountlyStore mCountlyStore;

    protected static final int SESSION_DURATION_WHEN_TIME_ADJUSTED = 15;

    protected static final int EVENT_COUNT_REQUEST = 20;

    public static Countly getInstance() {
        if (sInstance == null) {
            synchronized (Countly.class) {
                if (sInstance == null) {
                    sInstance = new Countly();
                }
            }
        }
        return sInstance;
    }

    private Countly() {
        mQueue = new ConnectionQueue();
        mTimer = Executors.newScheduledThreadPool(1);
        mTimer.scheduleWithFixedDelay(new Runnable() {

            @Override
            public void run() {
                onTimer();
            }
        }, 1, 1, TimeUnit.MINUTES);

        mIsVisible = false;
        mUnsentSessionLength = 0;
        mActivityCount = 0;
    }

    public void init(Context context, String serverURL, String appKey) {
        OpenUDID_manager.sync(context);
        mCountlyStore = new CountlyStore(context);

        mQueue.setContext(context);
        mQueue.setServerURL(serverURL);
        mQueue.setAppKey(appKey);
        mQueue.setCountlyStore(mCountlyStore);

        mEventQueue = new EventQueue(mCountlyStore);
    }

    public void onStart() {
        mActivityCount++;
        if (mActivityCount == 1) onStartHelper();
    }

    public void onStop() {
        mActivityCount--;
        if (mActivityCount == 0) onStopHelper();
    }

    public void onStartHelper() {
        mLastTime = System.currentTimeMillis() / 1000.0;

        mQueue.beginSession();

        mIsVisible = true;
    }

    public void onStopHelper() {
        if (mEventQueue.size() > 0) mQueue.recordEvents(mEventQueue.events());

        double currTime = System.currentTimeMillis() / 1000.0;
        mUnsentSessionLength += currTime - mLastTime;

        int duration = (int) mUnsentSessionLength;
        mQueue.endSession(duration);
        mUnsentSessionLength -= duration;

        mIsVisible = false;
    }

    public void recordEvent(String key) {
        mEventQueue.recordEvent(key);

        if (mEventQueue.size() >= EVENT_COUNT_REQUEST) mQueue.recordEvents(mEventQueue.events());
    }

    public void recordEvent(String key, int count) {
        mEventQueue.recordEvent(key, count);

        if (mEventQueue.size() >= EVENT_COUNT_REQUEST) mQueue.recordEvents(mEventQueue.events());
    }

    public void recordEvent(String key, int count, double sum) {
        mEventQueue.recordEvent(key, count, sum);

        if (mEventQueue.size() >= EVENT_COUNT_REQUEST) mQueue.recordEvents(mEventQueue.events());
    }

    public void recordEvent(String key, Map<String, String> segmentation, int count) {
        mEventQueue.recordEvent(key, segmentation, count);

        if (mEventQueue.size() >= EVENT_COUNT_REQUEST) mQueue.recordEvents(mEventQueue.events());
    }

    public void recordEvent(String key, Map<String, String> segmentation, int count, double sum) {
        mEventQueue.recordEvent(key, segmentation, count, sum);

        if (mEventQueue.size() >= EVENT_COUNT_REQUEST) mQueue.recordEvents(mEventQueue.events());
    }

    private void onTimer() {
        if (mIsVisible == false) {
            return;
        }

        Log.d("Countly", "onTimer");
        double currTime = System.currentTimeMillis() / 1000.0;
        mUnsentSessionLength += currTime - mLastTime;
        mLastTime = currTime;

        int duration = (int) mUnsentSessionLength;
        mQueue.updateSession(duration);
        mUnsentSessionLength -= duration;

        if (mEventQueue.size() > 0) mQueue.recordEvents(mEventQueue.events());
    }
}

class ConnectionQueue {

    private CountlyStore mStore;

    private ScheduledExecutorService mScheduler;

    private String mAppKey;

    private Context mContext;

    private String mServerURL;

    public static final int CONNECTION_COUNT_REQUEST = 5;

    public void setAppKey(String appKey) {
        mAppKey = appKey;
    }

    public void setContext(Context context) {
        mContext = context;
    }

    public void setServerURL(String serverURL) {
        mServerURL = serverURL;
    }

    public void setCountlyStore(CountlyStore countlyStore) {
        mStore = countlyStore;
    }

    public int size() {
        synchronized (this) {
            return mStore.connections().length;
        }
    }

    public void beginSession() {
        String data;
        data = "app_key=" + mAppKey;
        data += "&" + "device_id=" + DeviceInfo.getUDID();
        data += "&" + "timestamp=" + (long) (System.currentTimeMillis() / 1000.0);
        data += "&" + "sdk_version=" + "2.0";
        data += "&" + "begin_session=" + "1";
        data += "&" + "metrics=" + DeviceInfo.getMetrics(mContext);

        mStore.addConnection(data);

        // TODO
        //        if (size() < CONNECTION_COUNT_REQUEST) {
        //            tick();
        //        }
    }

    public void updateSession(int duration) {
        String data;
        data = "app_key=" + mAppKey;
        data += "&" + "device_id=" + DeviceInfo.getUDID();
        data += "&" + "timestamp=" + (long) (System.currentTimeMillis() / 1000.0);
        data += "&" + "session_duration="
                + (duration > 0 ? duration : Countly.SESSION_DURATION_WHEN_TIME_ADJUSTED);

        mStore.addConnection(data);

        tick(); // TODO
    }

    public void endSession(int duration) {
        String data;
        data = "app_key=" + mAppKey;
        data += "&" + "device_id=" + DeviceInfo.getUDID();
        data += "&" + "timestamp=" + (long) (System.currentTimeMillis() / 1000.0);
        data += "&" + "end_session=" + "1";
        data += "&" + "session_duration="
                + (duration > 0 ? duration : Countly.SESSION_DURATION_WHEN_TIME_ADJUSTED);

        mStore.addConnection(data);

        // TODO
        //        if (size() < CONNECTION_COUNT_REQUEST) {
        //            tick(); 
        //        }
    }

    public void recordEvents(String events) {
        String data;
        data = "app_key=" + mAppKey;
        data += "&" + "device_id=" + DeviceInfo.getUDID();
        data += "&" + "timestamp=" + (long) (System.currentTimeMillis() / 1000.0);
        data += "&" + "events=" + events;

        mStore.addConnection(data);

        tick();
    }

    private void tick() {

        if (mScheduler != null && !mScheduler.isShutdown()) {
            return;
        }

        if (mStore.isEmptyConnections()) {
            return;
        }

        mScheduler = Executors.newScheduledThreadPool(1);
        mScheduler.scheduleWithFixedDelay(new Runnable() {

            @Override
            public void run() {

                String[] sessions = mStore.connections();

                if (sessions.length <= 0) {
                    Log.d("Countly", "mScheduler shutdown");
                    mScheduler.shutdown();
                    return;
                }

                String data = sessions[0];

                int index = data.indexOf("REPLACE_UDID");
                if (index != -1) {

                    if (OpenUDID_manager.isInitialized() == false) {
                        return;
                    };

                    data = data.replaceFirst("REPLACE_UDID", OpenUDID_manager.getOpenUDID());
                }

                HttpURLConnection httpURLConnection = null;
                InputStream input = null;
                try {
                    httpURLConnection = (HttpURLConnection) new URL(mServerURL + "/i?" + data)
                            .openConnection();
                    httpURLConnection.setRequestMethod("GET");
                    httpURLConnection.connect();

                    int responseCode = httpURLConnection.getResponseCode();
                    if (responseCode == 200) {
                        input = httpURLConnection.getInputStream();
                        while (input.read() != -1);
                    }

                    Log.d("Countly", "ok ->" + data);

                    mStore.removeConnection(data);
                } catch (Exception e) {
                    Log.d("Countly", e.toString());
                    Log.d("Countly", "error ->" + data);
                } finally {
                    if (httpURLConnection != null) {
                        httpURLConnection.disconnect();
                    }

                    if (input != null) {
                        try {
                            input.close();
                            input = null;
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }, 0, 200, TimeUnit.MILLISECONDS);
    }
}

class DeviceInfo {

    public static String getUDID() {
        return OpenUDID_manager.isInitialized() == false ? "REPLACE_UDID" : OpenUDID_manager
                .getOpenUDID();
    }

    public static String getOS() {
        return "Android";
    }

    public static String getOSVersion() {
        return android.os.Build.VERSION.RELEASE;
    }

    public static String getDevice() {
        return android.os.Build.MODEL;
    }

    public static String getResolution(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        Display display = wm.getDefaultDisplay();

        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);

        return metrics.widthPixels + "x" + metrics.heightPixels;
    }

    public static String getDensity(Context context) {
        int density = context.getResources().getDisplayMetrics().densityDpi;

        switch (density) {
            case DisplayMetrics.DENSITY_LOW:
                return "LDPI";
            case DisplayMetrics.DENSITY_MEDIUM:
                return "MDPI";
            case DisplayMetrics.DENSITY_TV:
                return "TVDPI";
            case DisplayMetrics.DENSITY_HIGH:
                return "HDPI";
            case DisplayMetrics.DENSITY_XHIGH:
                return "XHDPI";
            case DisplayMetrics.DENSITY_XXHIGH:
                return "XXHDPI";
            case DisplayMetrics.DENSITY_XXXHIGH:
                return "XXXHDPI";
            default:
                return "";
        }
    }

    public static String getCarrier(Context context) {
        try {
            TelephonyManager manager = (TelephonyManager) context
                    .getSystemService(Context.TELEPHONY_SERVICE);
            return manager.getNetworkOperatorName();
        } catch (NullPointerException npe) {
            npe.printStackTrace();
            Log.e("Countly", "No carrier found");
        }
        return "";
    }

    public static String getLocale() {
        Locale locale = Locale.getDefault();
        return locale.getLanguage() + "_" + locale.getCountry();
    }

    public static String appVersion(Context context) {
        String result = "1.0";
        try {
            result = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (NameNotFoundException e) {}

        return result;
    }

    public static String getMetrics(Context context) {
        String result = "";
        JSONObject json = new JSONObject();

        try {
            json.put("_device", getDevice());
            json.put("_os", getOS());
            json.put("_os_version", getOSVersion());
            json.put("_carrier", getCarrier(context));
            json.put("_resolution", getResolution(context));
            json.put("_density", getDensity(context));
            json.put("_locale", getLocale());
            json.put("_app_version", appVersion(context));
        } catch (JSONException e) {
            e.printStackTrace();
        }

        result = json.toString();

        try {
            result = java.net.URLEncoder.encode(result, "UTF-8");
        } catch (UnsupportedEncodingException e) {

        }

        return result;
    }
}

class Event {

    public String key = null;

    public Map<String, String> segmentation = null;

    public int count = 0;

    public double sum = 0;

    public int timestamp = 0;

    public boolean equals(Object o) {
        if (o == null || !(o instanceof Event)) return false;

        Event e = (Event) o;

        return (key == null ? e.key == null : key.equals(e.key))
                && timestamp == e.timestamp
                && (segmentation == null ? e.segmentation == null : segmentation
                        .equals(e.segmentation));
    }
}

class EventQueue {

    private CountlyStore countlyStore_;

    public EventQueue(CountlyStore countlyStore) {
        countlyStore_ = countlyStore;
    }

    public int size() {
        synchronized (this) {
            return countlyStore_.events().length;
        }
    }

    public String events() {
        String result = "";

        synchronized (this) {
            List<Event> events = countlyStore_.eventsList();

            JSONArray eventArray = new JSONArray();
            for (Event e : events)
                eventArray.put(CountlyStore.eventToJSON(e));

            result = eventArray.toString();

            countlyStore_.removeEvents(events);
        }

        try {
            result = java.net.URLEncoder.encode(result, "UTF-8");
        } catch (UnsupportedEncodingException e) {

        }

        return result;
    }

    public void recordEvent(String key) {
        recordEvent(key, null, 1, 0);
    }

    public void recordEvent(String key, int count) {
        recordEvent(key, null, count, 0);
    }

    public void recordEvent(String key, int count, double sum) {
        recordEvent(key, null, count, sum);
    }

    public void recordEvent(String key, Map<String, String> segmentation, int count) {
        recordEvent(key, segmentation, count, 0);
    }

    public void recordEvent(String key, Map<String, String> segmentation, int count, double sum) {
        synchronized (this) {
            countlyStore_.addEvent(key, segmentation, count, sum);
        }
    }
}

class CountlyStore {

    private static final String TAG = "COUNTLY_STORE";

    private static final String PREFERENCES = "COUNTLY_STORE";

    private static final String DELIMITER = "===";

    private static final String CONNECTIONS_PREFERENCE = "CONNECTIONS";

    private static final String EVENTS_PREFERENCE = "EVENTS";

    private SharedPreferences preferences;

    protected CountlyStore(Context ctx) {
        preferences = ctx.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public String[] connections() {
        String array = preferences.getString(CONNECTIONS_PREFERENCE, null);
        return array == null || "".equals(array) ? new String[0] : array.split(DELIMITER);
    }

    public String[] events() {
        String array = preferences.getString(EVENTS_PREFERENCE, null);
        return array == null || "".equals(array) ? new String[0] : array.split(DELIMITER);
    }

    public List<Event> eventsList() {
        String[] array = events();
        if (array.length == 0) return new ArrayList<Event>();
        else {
            List<Event> events = new ArrayList<Event>();
            for (String s : array) {
                try {
                    events.add(jsonToEvent(new JSONObject(s)));
                } catch (JSONException e) {
                    Log.e(TAG, "Cannot parse Event json", e);
                }
            }

            Collections.sort(events, new Comparator<Event>() {

                @Override
                public int compare(Event e1, Event e2) {
                    return e2.timestamp - e1.timestamp;
                }
            });

            return events;
        }
    }

    public boolean isEmptyConnections() {
        return connections().length == 0;
    }

    public boolean isEmptyEvents() {
        return events().length == 0;
    }

    public void addConnection(String str) {
        List<String> connections = new ArrayList<String>(Arrays.asList(connections()));
        connections.add(str);
        preferences.edit().putString(CONNECTIONS_PREFERENCE, join(connections, DELIMITER)).commit();
    }

    public void removeConnection(String str) {
        List<String> connections = new ArrayList<String>(Arrays.asList(connections()));
        connections.remove(str);
        preferences.edit().putString(CONNECTIONS_PREFERENCE, join(connections, DELIMITER)).commit();
    }

    public void addEvent(Event event) {
        List<Event> events = eventsList();
        if (!events.contains(event)) events.add(event);
        preferences.edit().putString(EVENTS_PREFERENCE, joinEvents(events, DELIMITER)).commit();
    }

    public void addEvent(String key, Map<String, String> segmentation, int count, double sum) {
        List<Event> events = eventsList();
        Event event = null;
        for (Event e : events)
            if (e.key != null && e.key.equals(key)) event = e;

        if (event == null) {
            event = new Event();
            event.key = key;
            event.segmentation = segmentation;
            event.count = 0;
            event.sum = 0;
            event.timestamp = (int) (System.currentTimeMillis() / 1000);
        } else {
            removeEvent(event);
            event.timestamp = Math
                    .round((event.timestamp + (System.currentTimeMillis() / 1000)) / 2);
        }

        event.count += count;
        event.sum += sum;

        addEvent(event);
    }

    public void removeEvent(Event event) {
        List<Event> events = eventsList();
        events.remove(event);
        preferences.edit().putString(EVENTS_PREFERENCE, joinEvents(events, DELIMITER)).commit();
    }

    public void removeEvents(Collection<Event> eventsToRemove) {
        List<Event> events = eventsList();
        for (Event e : eventsToRemove) {
            events.remove(e);
        }
        preferences.edit().putString(EVENTS_PREFERENCE, joinEvents(events, DELIMITER)).commit();
    }

    protected static JSONObject eventToJSON(Event event) {
        JSONObject json = new JSONObject();

        try {
            json.put("key", event.key);
            json.put("count", event.count);
            json.put("sum", event.sum);
            json.put("timestamp", event.timestamp);

            if (event.segmentation != null) {
                json.put("segmentation", new JSONObject(event.segmentation));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return json;
    }

    protected static Event jsonToEvent(JSONObject json) {
        Event event = new Event();

        try {
            event.key = json.get("key").toString();
            event.count = Integer.valueOf(json.get("count").toString());
            event.sum = Double.valueOf(json.get("sum").toString());
            event.timestamp = Integer.valueOf(json.get("timestamp").toString());

            if (json.has("segmentation")) {
                JSONObject segm = json.getJSONObject("segmentation");
                HashMap<String, String> segmentation = new HashMap<String, String>();
                Iterator nameItr = segm.keys();

                while (nameItr.hasNext()) {
                    Object obj = nameItr.next();
                    if (obj instanceof String) {
                        segmentation.put((String) obj,
                                ((JSONObject) json.get("segmentation")).getString((String) obj));
                    }
                }

                event.segmentation = segmentation;
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return event;
    }

    private static String joinEvents(Collection<Event> collection, String delimiter) {
        List<String> strings = new ArrayList<String>();
        for (Event e : collection) {
            strings.add(eventToJSON(e).toString());
        }

        return join(strings, delimiter);
    }

    private static String join(Collection<String> collection, String delimiter) {
        StringBuilder builder = new StringBuilder();

        int i = 0;
        for (String s : collection) {
            builder.append(s);
            if (++i < collection.size()) builder.append(delimiter);
        }

        return builder.toString();
    }

}
