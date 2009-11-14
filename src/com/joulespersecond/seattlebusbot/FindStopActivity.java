package com.joulespersecond.seattlebusbot;

import java.util.Iterator;
import java.util.List;

import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.AdapterView.AdapterContextMenuInfo;

import com.google.android.maps.GeoPoint;
import com.joulespersecond.oba.ObaApi;
import com.joulespersecond.oba.ObaResponse;
import com.joulespersecond.oba.ObaStop;

public class FindStopActivity extends ListActivity {
    private static final String TAG = "FindStopActivity";
    
    private StopsDbAdapter mDbAdapter;
    private boolean mShortcutMode = false;
    
    private ObaResponse mResponse;
    private FindStopTask mAsyncTask;
    // This is a bit expensive, so only do it if we need to.
    private boolean mRedoNoFavoritesText = true;
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        
        setContentView(R.layout.find_stop);
        registerForContextMenu(getListView());
        
        Intent myIntent = getIntent();
        if (Intent.ACTION_CREATE_SHORTCUT.equals(myIntent.getAction())) {
            mShortcutMode = true;
        }
        
        setTitle(R.string.find_stop_title);
        
        TextView empty = (TextView)findViewById(android.R.id.empty);
        empty.setMovementMethod(LinkMovementMethod.getInstance());
        setNoFavoriteText();
        
        mDbAdapter = new StopsDbAdapter(this);
        mDbAdapter.open();
                
        Object obj = getLastNonConfigurationInstance();
        if (obj != null) {
            Object[] config = (Object[])obj;
            TextView textView = (TextView)findViewById(R.id.search_text);
            textView.setText((Editable)config[0]);
            setResponse((ObaResponse)config[1]);
        }
        else {
            fillFavorites();
        }
    }
    @Override
    protected void onDestroy() {
        mDbAdapter.close();
        if (mAsyncTask != null) {
            mAsyncTask.cancel(true);
        }
        super.onDestroy();
    }
    @Override
    public Object onRetainNonConfigurationInstance() {
        if (mResponse != null) {
            EditText text = (EditText)findViewById(R.id.search_text);
            return new Object[] { text.getText(), mResponse };
        }
        else {
            return null;
        }
    }
    @Override
    protected void onResume() {
        // This is deferred to onResume because we want it after
        // onRestoreInstanceState.
        
        TextView textView = (TextView)findViewById(R.id.search_text);
        textView.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable s) {
                if (s.length() >= 5) {
                    doSearch(s);
                }
                else if (s.length() == 0) {
                    mRedoNoFavoritesText = true;
                    fillFavorites();
                }
            }
            public void beforeTextChanged(CharSequence s, int start, int count,
                    int after) {      
            }
            public void onTextChanged(CharSequence s, int start, int before,
                    int count) {                
            }
        });  
        super.onResume();
    }
    
    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        String stopId;
        String stopName;
        String stopDir;
        // Get the adapter (this may or may not be a SimpleCursorAdapter)
        ListAdapter adapter = l.getAdapter();
        if (adapter instanceof SimpleCursorAdapter) {
            // Get the cursor and fetch the stop ID from that.
            SimpleCursorAdapter cursorAdapter = (SimpleCursorAdapter)adapter;
            Cursor c = cursorAdapter.getCursor();
            c.moveToPosition(position - l.getHeaderViewsCount());
            stopId = c.getString(StopsDbAdapter.STOP_COL_STOPID);
            stopName = c.getString(StopsDbAdapter.STOP_COL_NAME);
            stopDir = c.getString(StopsDbAdapter.STOP_COL_DIRECTION);
        }
        else if (adapter instanceof SearchResultsListAdapter) {
            ObaStop stop = (ObaStop)adapter.getItem(position - l.getHeaderViewsCount());
            stopId = stop.getId();
            stopName = stop.getName();
            stopDir = stop.getDirection();
        }
        else {
            Log.e(TAG, "Unknown adapter. Giving up!");
            return;
        }

        if (mShortcutMode) {
            makeShortcut(stopId, stopName, stopDir);
        }
        else {
            StopInfoActivity.start(this, stopId, stopName, stopDir);          
        }
    }
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.find_options, menu);
        return true;
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.clear_favorites) {
            StopsDbAdapter.clearFavorites(this);
            ListAdapter adapter = getListView().getAdapter();
            if (adapter instanceof SimpleCursorAdapter) {
                ((SimpleCursorAdapter)adapter).getCursor().requery();
            }
            return true;
        }
        return false;
    }
    public final void onSearch(View v) {
        TextView textView = (TextView)findViewById(R.id.search_text);
        doSearch(textView.getText());         
    }
    
    private static final int CONTEXT_MENU_DEFAULT = 1;
    private static final int CONTEXT_MENU_SHOW_ON_MAP = 2;
    
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        AdapterContextMenuInfo info = (AdapterContextMenuInfo)menuInfo;
        final TextView text = (TextView)info.targetView.findViewById(R.id.name);
        menu.setHeaderTitle(text.getText());
        if (mShortcutMode) {
            menu.add(0, CONTEXT_MENU_DEFAULT, 0, R.string.find_context_create_shortcut);
        }
        else {
            menu.add(0, CONTEXT_MENU_DEFAULT, 0, R.string.find_context_get_stop_info);            
        }
        menu.add(0, CONTEXT_MENU_SHOW_ON_MAP, 0, R.string.find_context_showonmap);
    }
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo)item.getMenuInfo();
        switch (item.getItemId()) {
        case CONTEXT_MENU_DEFAULT:
            // Fake a click
            onListItemClick(getListView(), info.targetView, info.position, info.id);
            return true;
        case CONTEXT_MENU_SHOW_ON_MAP:
            showOnMap(getListView(), info.position);
            return true;
        default:
            return super.onContextItemSelected(item);
        }
    }
    private void showOnMap(ListView l, int position) {
        String stopId;
        double lat, lon;
        // Get the adapter (this may or may not be a SimpleCursorAdapter)
        ListAdapter adapter = l.getAdapter();
        if (adapter instanceof SimpleCursorAdapter) {
            // Get the cursor and fetch the stop ID from that.
            SimpleCursorAdapter cursorAdapter = (SimpleCursorAdapter)adapter;
            Cursor c = cursorAdapter.getCursor();
            c.moveToPosition(position - l.getHeaderViewsCount());
            stopId = c.getString(StopsDbAdapter.STOP_COL_STOPID);
            lat = c.getDouble(StopsDbAdapter.STOP_COL_LATITUDE);
            lon = c.getDouble(StopsDbAdapter.STOP_COL_LONGITUDE);
        }
        else if (adapter instanceof SearchResultsListAdapter) {
            ObaStop stop = (ObaStop)adapter.getItem(position - l.getHeaderViewsCount());
            stopId = stop.getId();
            lat = stop.getLatitude();
            lon = stop.getLongitude();
        }
        else {
            Log.e(TAG, "Unknown adapter. Giving up!");
            return;
        }
        MapViewActivity.start(this, stopId, lat, lon);
    }
    
    private void fillFavorites() {
        // Cancel any current search.
        if (mAsyncTask != null) {
            mAsyncTask.cancel(true);
            mAsyncTask = null;
        }
        
        Cursor c = mDbAdapter.getFavoriteStops();
        startManagingCursor(c);
        
        setNoFavoriteText();    
        
        String[] from = new String[] { 
                DbHelper.KEY_NAME,
                DbHelper.KEY_DIRECTION 
        };
        int[] to = new int[] {
                R.id.name,
                R.id.direction
        };
        SimpleCursorAdapter simpleAdapter = 
            new SimpleCursorAdapter(this, R.layout.find_stop_listitem, c, from, to);
        
        // We need to convert the direction text (N/NW/E/etc)
        // to user level text (North/Northwest/etc..)
        simpleAdapter.setViewBinder(new SimpleCursorAdapter.ViewBinder() {
            public boolean setViewValue(View view, Cursor cursor, int columnIndex) {
                if (columnIndex == StopsDbAdapter.STOP_COL_DIRECTION) {
                    TextView direction = (TextView)view.findViewById(R.id.direction);
                    direction.setText(
                            StopInfoActivity.getStopDirectionText(cursor.getString(columnIndex)));
                    return true;
                } 
                return false;
            }
        });
        setListAdapter(simpleAdapter);
        mResponse = null;
    }
    
    private void makeShortcut(String stopId, String stopName, String direction) {
        // Set up the container intent
        Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, 
                StopInfoActivity.makeIntent(this, stopId, stopName, direction));
        intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, stopName);
        Parcelable iconResource = Intent.ShortcutIconResource.fromContext(
                this,  R.drawable.icon);
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, iconResource);

        // Now, return the result to the launcher
        setResult(RESULT_OK, intent);
        finish();
    }
    
    private final class SearchResultsListAdapter extends Adapters.BaseStopArrayAdapter {       
        public SearchResultsListAdapter(ObaResponse response) {
            super(FindStopActivity.this,
                    response.getData().getStops(),
                    R.layout.find_stop_listitem);
        }
        @Override
        protected void setData(View view, int position) {
            TextView route = (TextView)view.findViewById(R.id.name);
            TextView direction = (TextView)view.findViewById(R.id.direction);

            ObaStop stop = mArray.getStop(position);
            route.setText(stop.getName());
            direction.setText(StopInfoActivity.getStopDirectionText(stop.getDirection()));
        }
    }
    
    private final AsyncTasks.Progress mTitleProgress 
        = new AsyncTasks.ProgressIndeterminateVisibility(this);
    
    private class FindStopTask extends AsyncTasks.StringToResponse {
        public FindStopTask() {
            super(mTitleProgress);
        }
        @Override
        protected ObaResponse doInBackground(String... params) {
            String stopId = params[0];
            return ObaApi.getStopsByLocation(
                    getLocation(FindStopActivity.this), 0, 0, 0, stopId, 0);
        }
        @Override
        protected void doResult(ObaResponse result) {
            setResponse(result);
        }
    }
    
    private void setResponse(ObaResponse response) {
        mResponse = response;
        TextView empty = (TextView) findViewById(android.R.id.empty);
        if (response.getCode() == ObaApi.OBA_OK) {
            empty.setText(R.string.find_hint_noresults);
            setListAdapter(new SearchResultsListAdapter(response));
        }
        else {
            empty.setText(R.string.generic_comm_error);
            mRedoNoFavoritesText = true;
        }        
    }
    
    private void doSearch(CharSequence text) {
        if (text.length() == 0) {
            return;
        }
        if (mAsyncTask != null) {
            // Try to cancel it
            mAsyncTask.cancel(true);
        }
        mAsyncTask = new FindStopTask();
        mAsyncTask.execute(text.toString());
    }
    
    private static final String URL_STOPID = MapViewActivity.HELP_URL + "#finding_stop_ids";
    
    private void setNoFavoriteText() {
        if (!mRedoNoFavoritesText) {
            return;
        }
        final CharSequence first = getText(R.string.find_hint_nofavoritestops);
        final int firstLen = first.length();
        final CharSequence second = getText(R.string.find_hint_nofavoritestops_link);
        
        SpannableStringBuilder builder = new SpannableStringBuilder(first);
        builder.append(second);
        builder.setSpan(new URLSpan(URL_STOPID), firstLen, firstLen+second.length(), 0);
       
        TextView empty = (TextView) findViewById(android.R.id.empty);
        empty.setText(builder, TextView.BufferType.SPANNABLE);
        mRedoNoFavoritesText = false;
    }
    
    // We need to provide the API for a location used to disambiguate
    // stop IDs in case of collision, or to provide multiple results
    // in the case multiple agencies. But we really don't need it to be very accurate.
    public static GeoPoint getLocation(Context cxt) {
        LocationManager mgr = (LocationManager) cxt.getSystemService(Context.LOCATION_SERVICE);
        List<String> providers = mgr.getProviders(true);
        Location last = null;
        for (Iterator<String> i = providers.iterator(); i.hasNext(); ) {
            Location loc = mgr.getLastKnownLocation(i.next());
            // If this provider has a last location, and either:
            // 1. We don't have a last location, 
            // 2. Our last location is older than this location.
            if (loc != null &&
                (last == null || loc.getTime() > last.getTime())) {
                last = loc;
            }
        }
        if (last != null) {
            return ObaApi.makeGeoPoint(last.getLatitude(), last.getLongitude());            
        }
        else {
            // Make up a fake "Seattle" location.
            // ll=47.620975,-122.347355
            return ObaApi.makeGeoPoint(47.620975, -122.347355);
        }
    }
}