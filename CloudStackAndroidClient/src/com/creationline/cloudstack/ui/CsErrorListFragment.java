/*******************************************************************************
 * Copyright 2011 Creationline,Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.creationline.cloudstack.ui;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.CursorAdapter;
import android.support.v4.widget.ResourceCursorAdapter;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SlidingDrawer;
import android.widget.TextSwitcher;
import android.widget.TextView;
import android.widget.ViewSwitcher;

import com.creationline.cloudstack.CloudStackAndroidClient;
import com.creationline.cloudstack.R;
import com.creationline.cloudstack.engine.db.Errors;
import com.creationline.cloudstack.utils.DateTimeParser;
import com.creationline.common.utils.ClLog;

public class CsErrorListFragment extends CsListFragmentBase implements LoaderManager.LoaderCallbacks<Cursor>, ViewSwitcher.ViewFactory {
	public static String ONDISPLAY_ERROR = "com.creationline.cloudstack.ui.CurrentPageListener.ONDISPLAY_ERROR";
	
	private static final int CSERROR_LIST_LOADER = 0x03;
    private ResourceCursorAdapter adapter = null;  //backer for this list
	private ContentObserver errorsContentObserver = null;  //used to receive notifs from CsRestContentProvider upon updates to db
    private boolean isProvisioned = false;  //whether we currently have api/secret key or not

    
    private class CsErrorListAdapter extends ResourceCursorAdapter {
    	//This adaptor used strictly for use with the CsErrorListFragment class/layout, and expects specific data to fill its contents.
    	
		public CsErrorListAdapter(Context context, int layout, Cursor c, int flags) {
			super(context, layout, c, flags);
		}

		@Override
    	public void bindView(View view, Context context, Cursor cursor) {
			setTextViewWithString(view, R.id.dbentryid, cursor, Errors._ID);
			setTextViewWithString(view, R.id.errorcode, cursor, Errors.ERRORCODE);
			setTextViewWithString(view, R.id.errortext, cursor, Errors.ERRORTEXT);
			setTextViewWithString(view, R.id.occurred, cursor, Errors.OCCURRED);
			
    	}

		/**
		 * Looks for a TextView with textViewId in view and sets its text value to the String value from cursor under columnName.
		 * @param view view that contains TextView to update
		 * @param textViewId id of TextView to update
		 * @param cursor cursor with String data to use as updated text
		 * @param columnName name of column in cursor that contains the String data to use as updated text
		 */
		public void setTextViewWithString(View view, int textViewId, Cursor cursor, String columnName) {
			TextView tv = (TextView) view.findViewById(textViewId);
			final String text = cursor.getString(cursor.getColumnIndex(columnName));
			
			if(textViewId==R.id.occurred) {
				TextView occurredtime = (TextView)view.findViewById(R.id.occurredtime);
				if(text!=null) { DateTimeParser.setParsedDateTime3999(tv, occurredtime, text); }
			} else if(textViewId==R.id.dbentryid) {
				tv.setText(text);
				setDeleteErrorButtonOnClickHandler(view, text);
			} else if(textViewId==R.id.errortext) {
				tv.setText(text);
				setErrorTextAppearance(cursor, tv);
			} else {
				//for non-special cases, just output text as is
				tv.setText(text);
			}
		}

		public void setDeleteErrorButtonOnClickHandler(View view, final String text) {
			Button deleteerrorbutton = (Button)view.findViewById(R.id.deleteerrorbutton);
			deleteerrorbutton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					deleteError(text);
				}
			});
		}

		public void setErrorTextAppearance(Cursor cursor, TextView tv) {
			final String unread = cursor.getString(cursor.getColumnIndex(Errors.UNREAD));
			if(Errors.UNREAD_VALUES.TRUE.equalsIgnoreCase(unread)) {
				tv.setTypeface(null, Typeface.BOLD);
				tv.setTextColor(getResources().getColor(R.color.error));
			} else {
				tv.setTypeface(null, Typeface.NORMAL);
				tv.setTextColor(getResources().getColor(R.color.grey_a5));
			}
		}

		@Override
		public void notifyDataSetChanged() {
			TextView footererrornum = (TextView)getActivity().findViewById(R.id.footererrornum);
			TextView footerunreaderrornum = (TextView)getActivity().findViewById(R.id.footerunreaderrornum);
			if(footererrornum!=null && footerunreaderrornum!=null) {
				//update the current #-of-errors count
				final int count = getCursor().getCount();
				footererrornum.setText(String.valueOf(count));
				
				//update the current unread-#-of-errors count
				final String[] columns = new String[] { Errors._ID };
				final String whereClause = Errors.UNREAD+"=?";
				final String[] selectionArgs = new String[] { Errors.UNREAD_VALUES.TRUE }; 
				Cursor unreadErrors = getActivity().getContentResolver().query(Errors.META_DATA.CONTENT_URI, columns, whereClause, selectionArgs, null);
				int unreadErrorCount = 0;
				if(unreadErrors!=null) {
					unreadErrorCount = unreadErrors.getCount();
					unreadErrors.close();
				}
				footerunreaderrornum.setText(String.valueOf(unreadErrorCount));
			}
			
			super.notifyDataSetChanged();
		}
		
    }
    

    public CsErrorListFragment() {
    	//empty constructor is needed by Android for automatically creating fragments from XML declarations
    }
	
	@Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        isProvisioned = isProvisioned();  //this needs to be done first as the isProvisioned member var is used at various places
        registerForErrorsDbUpdate(errorsContentObserver);
        
    }
	
	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		adapter = setupListAdapter(CSERROR_LIST_LOADER);
	}
	
	@Override
	public void onActivityCreated(Bundle savedInstanceState) {

		//add the summary footer to the list
		addAndInitFooter(savedInstanceState, R.layout.cserrorlistsummaryfooter, R.id.cserrorlistsummaryfooterviewswitcher);
		
		adapter = setupListAdapter(CSERROR_LIST_LOADER);
        
        //make the list not visually respond (i.e. highlight) to any clicks on individual items
        getListView().setSelector(android.R.color.transparent);
        
        //set animation for logdrawer
        Activity activity = getActivity();
        final SlidingDrawer logdrawer = (SlidingDrawer)activity.findViewById(R.id.logdrawer);
        final Animation slide_bottomtotop = AnimationUtils.loadAnimation(activity, R.anim.slide_bottomtotop);
        logdrawer.setAnimation(slide_bottomtotop);
        //set bottom half of drawer "cloud" to act as the drawer handle like the top half
        setTextViewAsSecondSlidingDrawerHandle(logdrawer, R.id.logdrawercontenttitle);
        logdrawer.setOnDrawerCloseListener(new SlidingDrawer.OnDrawerCloseListener() {
			@Override
			public void onDrawerClosed() {
				removeErrorLogIconAndText();
				markErrorsAsRead();
			}
		});
        
        //prevent touch events from falling through the drawer (comes into play when we have no errors and no list is shown)
        FrameLayout logdrawercontentbg = (FrameLayout)activity.findViewById(R.id.logdrawercontentbg);
        logdrawercontentbg.setOnTouchListener(new View.OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				return true;  //eat all events
			}
		});
        
        //set-up error log view to update with animation
        TextSwitcher ts = (TextSwitcher)activity.findViewById(R.id.logdrawertextswitcher);
        ts.setFactory(this);
        Animation fade_in = AnimationUtils.loadAnimation(activity,  android.R.anim.fade_in);
        Animation fade_out = AnimationUtils.loadAnimation(activity, android.R.anim.fade_out);
        ts.setInAnimation(fade_in);
        ts.setOutAnimation(fade_out);
        
        super.onActivityCreated(savedInstanceState);
	}

	public void deleteError(final String _id) {
		final String whereClause = Errors._ID+"=?";
		final String[] selectionArgs = new String[] { _id };
		getActivity().getContentResolver().delete(Errors.META_DATA.CONTENT_URI, whereClause, selectionArgs);
	}
	
	
	@Override
	public void onResume() {
		//set any last-error that was displayed when we paused/closed the app before; or reset the display otherwise
		removeErrorLogIconAndText();
		SharedPreferences preferences = getActivity().getSharedPreferences(CloudStackAndroidClient.SHARED_PREFERENCES.PREFERENCES_NAME, Context.MODE_PRIVATE);
		final String onDisplayError = preferences.getString(CloudStackAndroidClient.SHARED_PREFERENCES.ERRORLOG_ONDISPLAYERROR, null);
		if(!TextUtils.isEmpty(onDisplayError)) {
			setErrorLogIconAndText(onDisplayError);
		}
        
		super.onResume();
	}
	
	@Override
	public void onPause() {
		//save any currently on-display last-error so we can show it again when the error log is re-created in the next screen
		SharedPreferences preferences = getActivity().getSharedPreferences(CloudStackAndroidClient.SHARED_PREFERENCES.PREFERENCES_NAME, Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = preferences.edit();
		final String onDisplayError = getErrorLogText();
		if(onDisplayError!=null) {
			editor.putString(CloudStackAndroidClient.SHARED_PREFERENCES.ERRORLOG_ONDISPLAYERROR, onDisplayError);
			editor.commit();
		} else {
			editor.remove(CloudStackAndroidClient.SHARED_PREFERENCES.ERRORLOG_ONDISPLAYERROR);
		}
		
		super.onPause();
	}
	
	@Override
	public void onDestroy() {
		//we will not keep the on-display last-error between app restarts
		SharedPreferences preferences = getActivity().getSharedPreferences(CloudStackAndroidClient.SHARED_PREFERENCES.PREFERENCES_NAME, Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = preferences.edit();
		editor.remove(CloudStackAndroidClient.SHARED_PREFERENCES.ERRORLOG_ONDISPLAYERROR);
		editor.commit();
		
		unregisterFromDbUpdate(errorsContentObserver);
		
		releaseListAdapter();
		
		super.onDestroy();
	}

	public void releaseListAdapter() {
		//zero-out list adapter-related references so gc can work
		getLoaderManager().destroyLoader(CSERROR_LIST_LOADER);
		setListAdapter(null);
		adapter = null;
	}
	
	public ResourceCursorAdapter setupListAdapter(final int listLoaderId) {
		//if we have previous, existing adapter (say, from orientation change), just use it instead of creating new one to prevent mem leak
		ResourceCursorAdapter listAdapter = (ResourceCursorAdapter)getListAdapter();
		if(listAdapter==null) {
			//set-up the loader & adapter for populating this list
			getLoaderManager().initLoader(listLoaderId, null, this);
			listAdapter = new CsErrorListAdapter(getActivity().getApplicationContext(), R.layout.cserrorlistitem, null, CursorAdapter.FLAG_REGISTER_CONTENT_OBSERVER);
			setListAdapter(listAdapter);
		}
		return listAdapter;
	}
	
    private void registerForErrorsDbUpdate(ContentObserver contentObserver) {
    	final Runnable updatedUiWithResults = new Runnable() {
    		//This handles notifs from CsRestContentProvider upon changes in db
    		public void run() {
    			String TAG = "CsErrorListFragment.registerForErrorsDbUpdate(): errors content observer";
    			Activity activity = getActivity();
				if(activity==null) {
    				ClLog.i(TAG, "activity was null. Probably a result of unread->read updates when closing drawer (OK to ignore if so)");
    				return;
    			}
    			ContentResolver contentResolver = activity.getContentResolver();
    			if(contentResolver==null) {
    				ClLog.e(TAG, "contentResolver was null");
    				return;
    			}

    			final String columns[] = new String[] {
    					Errors._ID,
    					Errors.ERRORTEXT,
    					Errors.UNREAD,
    			};
    			Cursor errorLog = getActivity().getContentResolver().query(Errors.META_DATA.CONTENT_URI, columns, null, null, Errors._ID+" DESC");
    			if(errorLog==null || errorLog.getCount()<=0) {
    				if(errorLog!=null) { errorLog.close(); };
    				ClLog.i(TAG, "Returned errorLog was null or 0 results.");
    				return;
    			}
    			
    			errorLog.moveToFirst();
    			final String unread = errorLog.getString(errorLog.getColumnIndex(Errors.UNREAD));
    			if(Errors.UNREAD_VALUES.TRUE.equalsIgnoreCase(unread)) {
    				final String latestErrorMsg = errorLog.getString(errorLog.getColumnIndex(Errors.ERRORTEXT));
    				setErrorLogIconAndText(latestErrorMsg);
    			}
    			errorLog.close();
    		}

    	};
    	registerForDbUpdate(contentObserver, Errors.META_DATA.CONTENT_URI, updatedUiWithResults);
    }
    
    private void registerForDbUpdate(ContentObserver contentObserver, final Uri contentUriToObserve, final Runnable updatedUiWithResults) {
    	final Handler handler = new Handler();
    	contentObserver = new ContentObserver(null) {
    		@Override
    		public void onChange(boolean selfChange) {
    			handler.post(updatedUiWithResults);  //off-loading work to runnable b/c this bg thread can't update ui directly
    		}
    	};
    	getActivity().getContentResolver().registerContentObserver(contentUriToObserve, true, contentObserver);  //activity will now GET updated when db is changed
    }
    
	public void unregisterFromDbUpdate(ContentObserver contentObserver) {
		if(contentObserver!=null) {
			getActivity().getContentResolver().unregisterContentObserver(contentObserver);
		}
	}
    
	public void setTextViewAsSecondSlidingDrawerHandle(final SlidingDrawer slidingDrawer, final int contentTopId) {
		TextView logdrawercontenttop = (TextView)getActivity().findViewById(contentTopId);
		logdrawercontenttop.setOnTouchListener(new View.OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				//make contentdrawertop also act as a handle for the logdrawer
				return slidingDrawer.dispatchTouchEvent(event);
			}
		});
	}
    
	public void setErrorLogIconAndText(final String latestErrorMsg) {
		Activity activity = getActivity();
		ImageView logdrawericon = (ImageView)activity.findViewById(R.id.logdrawericon);
		final Animation shake = AnimationUtils.loadAnimation(activity, R.anim.shake);
		logdrawericon.startAnimation(shake);
		logdrawericon.setVisibility(View.VISIBLE);

		TextSwitcher logdrawertextswitcher = (TextSwitcher)activity.findViewById(R.id.logdrawertextswitcher);
		logdrawertextswitcher.setText(latestErrorMsg);
	}

	public String getErrorLogText() {
		TextSwitcher logdrawertextswitcher = (TextSwitcher)getActivity().findViewById(R.id.logdrawertextswitcher);
		TextView currentView = (TextView)logdrawertextswitcher.getCurrentView();
		final String onDisplayError = currentView.getText().toString();
		return onDisplayError;
	}

	public void removeErrorLogIconAndText() {
		Activity activity = getActivity();
		ImageView logdrawericon = (ImageView)activity.findViewById(R.id.logdrawericon);
		if(logdrawericon.getVisibility()==View.VISIBLE) {
			Animation fade_out = AnimationUtils.loadAnimation(activity, android.R.anim.fade_out);
			logdrawericon.startAnimation(fade_out);
			logdrawericon.setVisibility(View.INVISIBLE);
		}

		TextSwitcher logdrawertextswitcher = (TextSwitcher)activity.findViewById(R.id.logdrawertextswitcher);
		logdrawertextswitcher.setText("");
	}
	
	public void markErrorsAsRead() {
		ContentValues values = new ContentValues();
		values.put(Errors.UNREAD, Errors.UNREAD_VALUES.FALSE);
		getActivity().getContentResolver().update(Errors.META_DATA.CONTENT_URI, values, null, null);
	}
	
	
	@Override
	public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        String[] columns = new String[] {
        		Errors._ID,
        		Errors.ERRORCODE,
        		Errors.ERRORTEXT,
        		Errors.ORIGINATINGCALL,
        		Errors.OCCURRED,
        		Errors.UNREAD,
        };
        CursorLoader cl = new CursorLoader(getActivity(), Errors.META_DATA.CONTENT_URI, columns, null, null, Errors._ID+" DESC");
		return cl;
	}

	@Override
	public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
		if(adapter!=null) {
			adapter.swapCursor(cursor);
		}
	}

	@Override
	public void onLoaderReset(Loader<Cursor> loader) {
		if(adapter!=null) {
			adapter.swapCursor(null);
		}
	}
	
	@Override
	public View makeView() {
		TextView t = new TextView(getActivity());
		t.setTextSize(15);
		t.setTextColor(getResources().getColor(R.color.error));
		t.setSingleLine();
		t.setHorizontalFadingEdgeEnabled(true);
		return t;
	}
	
}
