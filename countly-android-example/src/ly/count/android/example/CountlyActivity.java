package ly.count.android.example;

import android.app.Activity;
import android.os.Bundle;
import ly.count.android.api.Countly;

public class CountlyActivity extends Activity {
	
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

    /** You should use cloud.count.ly instead of YOUR_SERVER for the line below if you are using Countly Cloud service */
        Countly.getInstance().init(this, "https://YOUR_SERVER", "YOUR_APP_KEY");
    }
    
    @Override
    public void onStart()
    {
    	super.onStart();
        Countly.getInstance().onStart();
    }

    @Override
    public void onStop()
    {
        Countly.getInstance().onStop();
    	super.onStop();
    }
}
