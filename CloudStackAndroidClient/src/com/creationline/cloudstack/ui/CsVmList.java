package com.creationline.cloudstack.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.FragmentActivity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.creationline.cloudstack.R;
import com.creationline.cloudstack.engine.CsRestService;
import com.creationline.cloudstack.engine.db.Errors;
import com.creationline.cloudstack.engine.db.Transactions;

public class CsVmList extends FragmentActivity {
	
	private BroadcastReceiver broadcastReceiver = null;
	
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.csvmlist);
        
        // Create the list fragment and add it as our sole content.
//        if (getSupportFragmentManager().findFragmentById(R.id.listfragment) == null) {
//            CsVmListFragment list = new CsVmListFragment();
//            getSupportFragmentManager().beginTransaction().add(R.id.listfragment, list).commit();
//        }
        
        final String action = CsRestService.TEST_CALL;

        
        broadcastReceiver = new BroadcastReceiver(){
        	//This handles intents broadcasted by CsRestService
        	@Override
        	public void onReceive(Context arg0, Intent arg1) {
        		String responseString = arg1.getStringExtra(CsRestService.RESPONSE);
        		Toast.makeText(getBaseContext(), "CsRestService: request "+responseString+" initiated...", Toast.LENGTH_SHORT).show();
        		unregisterReceiver(this); //unregister self once the broadcast has been received
        	}
        };
        registerReceiver(broadcastReceiver, new IntentFilter(action));  //activity will now get intents broadcast by CsRestService (filtered by action str)
        
        
        registerForErrorsDbUpdate();
        registerForVmsDbUpdate();
//        registerForDbUpdate(Errors.META_DATA.CONTENT_URI);
//        registerForDbUpdate(Vms.META_DATA.CONTENT_URI);
        
        
//        Intent csRestServiceIntent = CsRestService.createCsRestServiceIntent(this, action, "http://192.168.3.11:8096/?command=listVirtualMachines&account=thsu-account&domainid=2&response=json");  //admin api
//        Intent csRestServiceIntent = CsRestService.createCsRestServiceIntent(this, action, "command=listVirtualMachines&account=thsu-account&domainid=2");  //user api
//        Intent csRestServiceIntent = CsRestService.createCsRestServiceIntent(this, action, "command=listVirtualMachines&account=iizuka1");  //user api
        Bundle apiCmd = new Bundle();
        apiCmd.putString(CsRestService.COMMAND, "listVirtualMachines");
//        apiCmd.putString("account", "iizuka1");
        apiCmd.putString("account", "rickson");
        Intent csRestServiceIntent = CsRestService.createCsRestServiceIntent(this, action, apiCmd);  //user api
        startService(csRestServiceIntent);
        
        
    }

    
    private void registerForErrorsDbUpdate() {
    	final Runnable updatedUiWithResults = new Runnable() {
    		//This handles notifs from CsRestContentProvider upon changes in db
    		public void run() {
//    			Toast.makeText(getBaseContext(), "Got a notif from errors!!!!!!", Toast.LENGTH_LONG).show();
    			final String columns[] = new String[] {
    					Errors._ID,
    					Errors.ERRORTEXT
    			};
    			Cursor errorLog = getContentResolver().query(Errors.META_DATA.CONTENT_URI, columns, null, null, "_ID DESC");
    			errorLog.moveToFirst();
    			final int latestErrorMsgId = errorLog.getInt(errorLog.getColumnIndex(Errors._ID));
    			final String latestErrorMsg = errorLog.getString(errorLog.getColumnIndex(Errors.ERRORTEXT));
    			
    			TextView errorLogTextView = (TextView)findViewById(R.id.errorLogTextView);
    			errorLogTextView.setText(latestErrorMsgId+": "+latestErrorMsg);
    		}
    	};
    	
    	registerForDbUpdate(Errors.META_DATA.CONTENT_URI, updatedUiWithResults);
    }
    
    private void registerForVmsDbUpdate() {
    	final Runnable updatedUiWithResults = new Runnable() {
    		//This handles notifs from CsRestContentProvider upon changes in db
    		public void run() {
    			Toast.makeText(getBaseContext(), "Got a notif from vms!!!!!!", Toast.LENGTH_LONG).show();
    		}
    	};
    	
    	registerForDbUpdate(Transactions.META_DATA.CONTENT_URI, updatedUiWithResults);
    }
    
    private void registerForDbUpdate(final Uri contentUriToObserve, final Runnable updatedUiWithResults) {
    	final Handler handler = new Handler();
    	ContentObserver contentObserver = new ContentObserver(null) {
    		@Override
    		public void onChange(boolean selfChange) {
    			handler.post(updatedUiWithResults);  //off-loading work to runnable b/c this bg thread can't update ui directly
    		}
    	};
    	getContentResolver().registerContentObserver(contentUriToObserve, true, contentObserver);  //activity will now get updated when vms db is changed
    }

    protected void onclick_sideMenu(View view) {
    	//TODO: do something with me or remove!!
    	Toast.makeText(getBaseContext(), "-> got clicked!", Toast.LENGTH_SHORT).show();
    }

    
	@Override
	protected void onPause() {
		Intent csRestServiceIntent = new Intent(this, CsRestService.class);
        stopService(csRestServiceIntent);
        
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		
		if(broadcastReceiver!=null) {
			//catch-all here as a safeguard against cases where the activity is exited before BroadcastReceiver.onReceive() has been called-back
			try {
				unregisterReceiver(broadcastReceiver);
			} catch (IllegalArgumentException e) {
				//will get this exception if broadcastReceiver has already been unregistered (or was never registered); will just ignore here
				;
			}
		}
		super.onDestroy();
	}


    
}
