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
package com.creationline.cloudstack.engine;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.JsonToken;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;

import com.creationline.cloudstack.CloudStackAndroidClient;
import com.creationline.cloudstack.engine.db.Errors;
import com.creationline.cloudstack.engine.db.Snapshots;
import com.creationline.cloudstack.engine.db.Transactions;
import com.creationline.cloudstack.engine.db.Vms;
import com.creationline.cloudstack.ui.CsSnapshotListFragment;
import com.creationline.common.engine.RestServiceBase;
import com.creationline.common.utils.ClLog;

public class CsRestService extends RestServiceBase {
	
	public static final String TEST_CALL = "com.creationline.cloudstack.engine.TEST_CALL";
	
	//constants matching params used by the CS API request format
	public static final String COMMAND = "command";
	public static final String CALL_STATUS = "call_status";  //used with CALL_STATUS_VALUES
	public static class CALL_STATUS_VALUES {
		public static final int CALL_FAILURE = 0;
		public static final int CALL_SUCCESS = 1;
	}
	
	//parseAndSaveReply()-use constants
	public static final int INSERT_DATA = 0;
	public static final int UPDATE_DATA_WITH_ID = 1;
	
	//parseReplyBody_queryAsyncJobResult()-use constants
	private static final int ASYNCJOB_STILLINPROGRESS = 0;
	private static final int ASYNCJOB_COMPLETED = 1;
	private static final int ASYNCJOB_FAILEDTOCOMPLETE = 2;
	
	public class JsonNameNodePair {
		private String responseName;
		private JsonNode responseData;
		
		public JsonNameNodePair(String responseName, JsonNode responseData) {
			this.setResponseName(responseName);
			this.setResponseData(responseData);
		}

		public void setResponseName(String responseName) {
			this.responseName = responseName;
		}

		public String getFieldName() {
			return responseName;
		}

		public void setResponseData(JsonNode responseData) {
			this.responseData = responseData;
		}

		public JsonNode getValueNode() {
			return responseData;
		}
	}
	
	public CsRestService() {
		super("CsRestService");
//		time = new Time();  //use default timezone
	}
	
	public static Intent createCsRestServiceIntent(Context context, String action, Bundle apiCmd) {
		
		Bundle payload = new Bundle();
        payload.putString(RestServiceBase.PAYLOAD_FIELDS.ACTION_ID, action);  //action stored under this key
        payload.putBundle(RestServiceBase.PAYLOAD_FIELDS.API_CMD, apiCmd);  //main part of REST request stored under this key
        
        Intent startCsRestServiceIntent = new Intent(context, CsRestService.class);
        startCsRestServiceIntent.putExtras(payload);
        
        return startCsRestServiceIntent;
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		//This is already running on a non-UI thread
		
		Bundle payload = intent.getExtras(); 
		Bundle apiCmd = payload.getBundle(RestServiceBase.PAYLOAD_FIELDS.API_CMD);
		
		performRestRequest(apiCmd);
		
	}
	
	private void performRestRequest(Bundle apiCmd) {
		
		Uri inProgressTransaction = null;
		
		try {
			SharedPreferences preferences = getSharedPreferences(CloudStackAndroidClient.SHARED_PREFERENCES.PREFERENCES_NAME, Context.MODE_PRIVATE);
			final String csHost = preferences.getString(CloudStackAndroidClient.SHARED_PREFERENCES.CLOUDSTACK_HOST_SETTING, null);
			final String apiKey = preferences.getString(CloudStackAndroidClient.SHARED_PREFERENCES.APIKEY_SETTING, null);
			final String secretKey = preferences.getString(CloudStackAndroidClient.SHARED_PREFERENCES.SECRETKEY_SETTING, null);
			
			//create complete url
			String finalUrl = buildFinalUrl(csHost, apiCmd, apiKey, secretKey);

			//save the request to db
			inProgressTransaction = saveRequestToDb(finalUrl, apiCmd);
			inProgressTransactionList.add(inProgressTransaction);

			//send request to cs
			HttpResponse reply = doRestCall(finalUrl, inProgressTransaction);

			//extract body text from response
			StringBuilder replyBody = getReplyBody(reply);

			//save reply to view data db
			saveReplyToDb(inProgressTransaction, reply, replyBody);
			
			//read each field/value from reply data and save to appropriate db
			unpackAndSaveReplyBodyData(inProgressTransaction, reply, replyBody);
			
		} catch (InvalidParameterException e) {
			//buildFinalUrl() throws this exception when it cannot build the url.
			//since we have not saved the request yet, we don't need to do anything.
			//user should not see this type of error in the wild.
			;
		} catch (IllegalArgumentException e) {
			//buildFinalUrl() throws this exception when it cannot build the url.
			//since we have not saved the request yet, we don't need to do anything.
			//user should not see this type of error in the wild.
			;
		} catch (IOException e) {
			addToErrorLog(null, e.getMessage(), inProgressTransaction.toString());
			updateCallAsAbortedOnDb(inProgressTransaction);
			
			Bundle bundle = findTransactionRequestAndCallbackForRow(inProgressTransaction);
			if(bundle!=null) {
				final String originalRequest = bundle.getString(Transactions.REQUEST);
				final String id = extractParamValueFromUriStr(originalRequest, Snapshots.ID);  //using "Snapshots.ID" here, but as this is a common method, what's really needed is just the "id" value
				final String callbackIntentFilter = bundle.getString(Transactions.CALLBACK_INTENT_FILTER);
				if(id!=null) {
					informCallerFragmentOfCallCompletion(callbackIntentFilter, id, CsRestService.CALL_STATUS_VALUES.CALL_FAILURE);
				} else {
					informCallerOfCallCompletion(inProgressTransaction, CsRestService.CALL_STATUS_VALUES.CALL_FAILURE);
				}
			}
		}
		
		inProgressTransactionList.remove(inProgressTransaction);
		inProgressTransaction = null;
		
	}

	public Uri saveRequestToDb(final String request, Bundle apiCmd) {
		final String TAG = "CsRestService.saveRequestToDb()";
		
		if(request==null) {
			return null;
		}
		
		ContentValues contentValues = new ContentValues();
		contentValues.put(Transactions.REQUEST, request);
		contentValues.put(Transactions.STATUS, Transactions.STATUS_VALUES.IN_PROGRESS);
		contentValues.put(Transactions.REQUEST_DATETIME, getCurrentTimeAsString());
		contentValues.put(Transactions.CALLBACK_INTENT_FILTER, apiCmd.getString(Transactions.CALLBACK_INTENT_FILTER));
		
		Uri newUri = getBaseContext().getContentResolver().insert(Transactions.META_DATA.CONTENT_URI, contentValues);
		ClLog.d(TAG, "added new API request to db at " + newUri);
		return newUri;
	}

	/**
	 * Builds the encoded and signed url that can be directly used to make a call to a cs server.
	 * The returned url should work when directly typed into a web browser.
	 * 
	 * @param host if not null, the CS server url to connect to
	 * @param apiCmd command to send to CS server (not including host and user api path)
	 *   
	 * @return finalized URL that can be used to call CS server; null if any step of the building process failed
	 * @throws InvalidParameterException when one or more of the parameters are null/emtpy
	 * @throws IllegalArgumentException when parameters are not null, but error occurred when trying to build url
	 */
	public static String buildFinalUrl(final String specifiedHost, final Bundle apiCmd, final String apiKey, final String secretKey)
																			throws InvalidParameterException, IllegalArgumentException {
		final String TAG = "CsRestService.buildFinalUrl()";
		
		String csHostUrl = "http://fake";  //non-existent CS user api base url
		if (specifiedHost!=null) {csHostUrl = specifiedHost;}  //use any caller-specified host over the default value
		csHostUrl = makeHostIntoApiUrlIfNecessary(csHostUrl);

		try {
			if (apiCmd == null || apiCmd.isEmpty() || apiKey == null || secretKey == null) {
				ClLog.e(TAG, "required parmeter(s) are null, so aborting.  apiCmd="+apiCmd+"  apiKey="+apiKey+"  secretKey="+secretKey);
				throw new InvalidParameterException();
			}
			
			//make sure we GET reply in json 
			apiCmd.putString("response", "json");  //will not check whether apiCmd already has response=json param for speed purposes, but having 2 of these params will cause call to fail

			ClLog.d(TAG, "constructing API call to host='" + csHostUrl + " and apiUrl='" + apiCmd + "' using apiKey='" + apiKey + "' and secretKey='" + secretKey + "'");
			
			//"Step 1: Make sure your APIKey is toLowerCased and URL encoded"
			final String encodedApiKey = URLEncoder.encode(apiKey.toLowerCase(), "UTF-8"); //NOTE: URLEncoder will convert spaces to "+" instead of "%20" like cs prefers
			
			//"Step 2: toLowerCase all the parameters, URL encode each parameter value, and the sort the parameters in alphabetical order"
			List<String> sortedParams = new ArrayList<String>();
			sortedParams.add("apikey="+encodedApiKey);
			Iterator<String> keySetItr = apiCmd.keySet().iterator();
			StringBuilder apiCmdSb = new StringBuilder();
			while(keySetItr.hasNext()) {
				//go through apiCmd bundle and compile the strings we need for building the actual request url
				final String param = keySetItr.next();
				final String value = apiCmd.getString(param);
				
				apiCmdSb.append(param).append("=").append(value).append("&");  //create the un-encoded/un-sorted version of the apiCmd that goes into the final url
				sortedParams.add(param+"="+URLEncoder.encode(value.toLowerCase(), "UTF-8"));  //compile a encoded&sorted string from the apiCmd params/values that is used to sign the request
			}
			Collections.sort(sortedParams);
			ClLog.d(TAG, "sorted parameters= " + sortedParams);
			
			//"Step 3: Construct the sorted URL and sign and URL encode the sorted URL with your secret key"
			StringBuilder sortedParamSb = new StringBuilder(sortedParams.get(0));
			for(int i=1; i<sortedParams.size(); i++) {
				sortedParamSb.append("&").append(sortedParams.get(i));
			}
			ClLog.d(TAG, "sortedParamSb= " + sortedParamSb);
			final String encodedSignature = signRequest(sortedParamSb.toString(), secretKey);
			
			//"Step 4: Construct the final URL we want to send to the CloudStack Management Server"
			final String finalUrl = csHostUrl + "?" + apiCmdSb.toString() + "apiKey=" + apiKey + "&signature=" + encodedSignature;
			ClLog.d(TAG, "finalURL= " + finalUrl);

			return finalUrl;
			
		} catch (Throwable t) {
			ClLog.e(TAG, "error occurred building api call: " + t.toString() + " ["+t.getMessage()+"]");
			throw new IllegalArgumentException("error in trying to build final url", t);
		}
	}
	
	public static String makeHostIntoApiUrlIfNecessary(String csUrl) {
		if(!csUrl.startsWith("http://")) {
			csUrl = "http://"+csUrl;
		}
		if(csUrl.endsWith("/")) {
			csUrl = csUrl.substring(0, csUrl.length()-1);
		}
		if(!csUrl.endsWith("/client/api")) {
			csUrl += "/client/api";
		}
		return csUrl;
	}
	
	/**
	 * Signs a request based on CloudStack API spec:
	 *   1. Signs a string with a secret key using SHA-1
	 *   2. Base64 encode the result
	 *   3. URL encode the final result
	 * 
	 * @param request data to sign
	 * @param key secret key to sign data with
	 * @return requested signed with specified key
	 */
	public static String signRequest(final String request, final String key) {
		final String TAG = "CsRestService.signRequest()";
		final SecretKey secretKey = new SecretKeySpec(key.getBytes(), "HmacSHA1");
		try {
			final Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(secretKey);
			final byte[] signedBytes = mac.doFinal(request.getBytes());
			final String base64EncodedStr = Base64.encodeToString(signedBytes, Base64.DEFAULT);  //NOTE: URLEncoder will convert spaces to "+" instead of "%20" like cs prefers
			final String urlAndBase64EncodedStr = URLEncoder.encode(base64EncodedStr.trim(), "UTF-8");
			
			return urlAndBase64EncodedStr;
			
		} catch (NoSuchAlgorithmException e) {
			ClLog.e(TAG, "Mac.getInstance() failed! [" + e.toString() +"]");
			ClLog.e(TAG, e);
			return null;
		} catch (InvalidKeyException e) {
			ClLog.e(TAG, "mac.init() failed! [" + e.toString() +"]");
			ClLog.e(TAG, e);
			return null;
		} catch (UnsupportedEncodingException e) {
			ClLog.e(TAG, "URLEncoder.encode() failed! [" + e.toString() +"]");
			ClLog.e(TAG, e);
			return null;
		}
	}
	
	/**
	 * Adapted from:
	 *   http://www.devdaily.com/java/jwarehouse/commons-httpclient-4.0.3/httpclient/src/examples/org/apache/http/examples/client/ClientWithResponseHandler.java.shtml
	 *   
	 * @param url url to send an http GET to
	 * @param originatingTransactionUri uri of row in transactions db table that records this call (used to link an error in the errors db table to the specific transaction in the case of a failure)
	 * @throws IOException 
	 */
	private HttpResponse doRestCall(final String url, final Uri originatingTransactionUri) throws IOException {
		final String TAG = "CsRestService.doRestCall()";
		
		if(url==null) {
			return null;
		}
		
        try {
            final HttpGet httpGet = new HttpGet(url);
            //final HttpPost httpPost = new HttpPost(url);
            if(httpclient==null) {
            	httpclient = new DefaultHttpClient();  //lazy init
            }
            
            HttpResponse response =  httpclient.execute(httpGet);
            return response;
            
        } catch (ClientProtocolException e) {
        	ClLog.e(TAG, "got ClientProtocolException! [" + e.toString() +"]");
        	ClLog.e(TAG, e);
        	throw e;
		} catch (IllegalArgumentException e) {
			ClLog.e(TAG, "got IllegalArgumentException! [" + e.toString() +"]");
			ClLog.e(TAG, e);
			//save the error to errors db as well
			//addToErrorLog(null, e.getMessage(), originatingTransactionUri.toString());
			throw e;
		} catch (IOException e) {
			ClLog.e(TAG, "got IOException! [" + e.toString() +"]");
			ClLog.e(TAG, e);
			//save the error to errors db as well
			//addToErrorLog(null, e.getMessage(), originatingTransactionUri.toString());
			throw e;
		} catch (IllegalStateException e) {
			ClLog.e(TAG, "got IllegalStateException! [" + e.toString() +"]");
			ClLog.e(TAG, e);
			//save the error to errors db as well
			//addToErrorLog(null, e.getMessage(), originatingTransactionUri.toString());
			throw e;
		} finally {
			//nothing done here currently
		}
	}

	public Uri addToErrorLog(final String errorCode, final String errorText, final String originatingTransactionUri) {
		ContentValues cv = new ContentValues();
		if(errorCode!=null) { cv.put(Errors.ERRORCODE, errorCode); };
		if(errorText!=null) { cv.put(Errors.ERRORTEXT, errorText); };
		if(originatingTransactionUri!=null) { cv.put(Errors.ORIGINATINGCALL, originatingTransactionUri.toString()); };
		cv.put(Errors.OCCURRED, getCurrentTimeAsString());
		cv.put(Errors.UNREAD, Errors.UNREAD_VALUES.TRUE);
		return getContentResolver().insert(Errors.META_DATA.CONTENT_URI, cv);
	}
	
	public void saveReplyToDb(final Uri uriToUpdate, final HttpResponse reply, final StringBuilder replyBody) {
		final String TAG = "CsRestService.saveReplyToDb()";

		if(uriToUpdate==null || reply==null) {
			ClLog.e(TAG, "required params are null, so aborting.  uriToUpdate="+uriToUpdate+"  reply="+reply);
			
			if(uriToUpdate!=null && reply==null) {
				updateCallAsAbortedOnDb(uriToUpdate);
			}
			return;
		}
		
		//parse the reply for data
		final int statusCode = reply.getStatusLine().getStatusCode();
		final boolean callReturnedOk = statusCode==HttpStatus.SC_OK;
		final String status = (callReturnedOk)? Transactions.STATUS_VALUES.SUCCESS : Transactions.STATUS_VALUES.FAIL;
		
		updateCallWithReplyOnDb(uriToUpdate, status, replyBody);
		
	}
	
	public void unpackAndSaveReplyBodyData(final Uri uriToUpdate, final HttpResponse reply, final StringBuilder replyBody) throws IOException {
		
		if(uriToUpdate==null || reply==null || replyBody==null) {
			return;
		}
		
		final int statusCode = reply.getStatusLine().getStatusCode();
		final boolean callReturnedOk = statusCode==HttpStatus.SC_OK;
		final boolean ranInto404 = statusCode==HttpStatus.SC_NOT_FOUND;
		if(callReturnedOk) {
			processAndSaveJsonReplyData(uriToUpdate, replyBody.toString());
		} else if(ranInto404){
			throw new IOException("CloudStack instance not found at saved URL (404)");
		} else {
			parseErrorAndAddToDb(uriToUpdate, statusCode, replyBody.toString());
		}
	}

	public void parseErrorAndAddToDb(final Uri uriToUpdate, final int statusCode, final String replyBody) {
		final String TAG = "CsRestService.parseErrorAndAddToDb()";

		//extract the error details from the reply, defaulting to unknown if parse fails
		String responseName = null;
		String errorText = "unknown error";
		ObjectMapper om = new ObjectMapper();
		try {
			JsonNode errorObj = om.readTree(replyBody.toString());
			errorText = errorObj.findValue("errortext").asText();
			Iterator<String> fieldNameItr = errorObj.getFieldNames();
			if(fieldNameItr.hasNext()) {
				responseName = fieldNameItr.next();  //we are assuming all responses are contained within a "*response" object, where * is the name of the api call
			}
		} catch (JsonParseException e) {
			ClLog.e(TAG, "expected errorresponse not well-formed! [" + e.toString() +"]");
			ClLog.e(TAG, e);
		} catch (IOException e) {
			ClLog.e(TAG, "got IOException parsing errorresponse! [" + e.toString() +"]");
			ClLog.e(TAG, e);
		}
		
		addToErrorLog(String.valueOf(statusCode), errorText, uriToUpdate.toString());
		informCallerOfFailure(uriToUpdate, responseName);
	}

	public void informCallerOfFailure(final Uri uriToUpdate, String responseName) {
		final String truncatedResponseName = responseName.substring(0, responseName.lastIndexOf("response"));
		if(CsApiConstants.API.deleteSnapshot.equalsIgnoreCase(truncatedResponseName)) {
			informCallerFragmentOfCallFailure(uriToUpdate);
		}
	}

	public void informCallerFragmentOfCallFailure(final Uri uriToUpdate) {
		Bundle bundle = findTransactionRequestAndCallbackForRow(uriToUpdate);
		if(bundle==null) {
			return;
		}
		
		final String originalRequest = bundle.getString(Transactions.REQUEST);
		final String id = extractParamValueFromUriStr(originalRequest, Snapshots.ID);  //using "Snapshots.ID" here, but as this is a common method, what's really needed is just the "id" value
		final String callbackIntentFilter = bundle.getString(Transactions.CALLBACK_INTENT_FILTER);
		
		if(CsSnapshotListFragment.INTENT_ACTION.CALLBACK_DELETESNAPSHOT.equalsIgnoreCase(callbackIntentFilter)) {
			ContentValues cv = new ContentValues();
			cv.putNull(Snapshots.INPROGRESS_STATE);
			final String whereClause = Snapshots.ID+"=?";
			final String[] selectionArgs = new String[] { id };
			getContentResolver().update(Snapshots.META_DATA.CONTENT_URI, cv, whereClause, selectionArgs);
		}

		informCallerFragmentOfCallCompletion(callbackIntentFilter, id, CsRestService.CALL_STATUS_VALUES.CALL_FAILURE);
	}

	public void informCallerFragmentOfCallCompletion(final String callbackIntentFilter, final String id, final int successOrFailure) {
		//inform calling fragment of the result of the call
		if(callbackIntentFilter!=null) {
			Intent broadcastIntent = new Intent(callbackIntentFilter);
			Bundle bundle = new Bundle();
			bundle.putString(Snapshots.ID, id);  //using "Snapshots.ID" here, but as this is a common method, what's really needed is just the "id" value
			bundle.putInt(CsRestService.CALL_STATUS, successOrFailure);
			broadcastIntent.putExtras(bundle);
			sendBroadcast(broadcastIntent);
		}
	}

	public void informCallerOfCallCompletion(final Uri uriToUpdate, final int successOrFailure) {
		Bundle requestAndCallback = findTransactionRequestAndCallbackForRow(uriToUpdate);
		if(requestAndCallback==null) {
			return;
		}
		final String callbackIntentFilter = requestAndCallback.getString(Transactions.CALLBACK_INTENT_FILTER);
		if(callbackIntentFilter==null) {
			return;
		}
		
		Intent broadcastIntent = new Intent(callbackIntentFilter);
		Bundle bundle = new Bundle();
		bundle.putString(RestServiceBase.PAYLOAD_FIELDS.UPDATED_URI, uriToUpdate.toString());
		bundle.putInt(CsRestService.CALL_STATUS, successOrFailure);
		broadcastIntent.putExtras(bundle);
		sendBroadcast(broadcastIntent);
	}
	
	public void updateCallWithReplyOnDb(final Uri uriToUpdate, final String status, final StringBuilder replyBodyText) {
		final String TAG = "CsRestService.updateCallWithReplyOnDb()";

		//save the parsed data to db
		ContentValues contentValues = new ContentValues();
		contentValues.put(Transactions.STATUS, status);
		contentValues.put(Transactions.REPLY, replyBodyText.toString());
		time.setToNow();
		contentValues.put(Transactions.REPLY_DATETIME, time.format3339(false));
		///To change the saved REQUEST_DATETIME str back into a Time object, use the following:
		//    Time readTime = new Time();
		//    readTime.parse3339(timeStr);  //str was saved out using RFC3339 format, so needs to be read in as such
		//    readTime.switchTimezone("Asia/Tokyo");  //parse3339() automatically converts read in times to UTC.  We need to change it back to the default timezone of the handset (JST in this example)
		
		int rowsUpdated = getContentResolver().update(uriToUpdate, contentValues, null, null);
		assert(rowsUpdated==1);  //sanity check: this should only every update a single row
		
		ClLog.d(TAG, "updated request/reply record for " + uriToUpdate);
	}

	public void updateCallAsAbortedOnDb(Uri uriToUpdate) {
		final String TAG = "CsRestService.updateCallAsAborted()";

		//mark transaction as aborted
		ContentValues cvForTransactionsTable = new ContentValues();
		cvForTransactionsTable.put(Transactions.STATUS, Transactions.STATUS_VALUES.ABORTED);
		time.setToNow();
		cvForTransactionsTable.put(Transactions.REPLY_DATETIME, time.format3339(false));
		
		int rowsUpdated = getContentResolver().update(uriToUpdate, cvForTransactionsTable, null, null);
		assert(rowsUpdated==1);  //sanity check: this should only ever update a single row
		
		ClLog.d(TAG, "marked as aborted " + uriToUpdate);
		
//		informCallerFragmentOfCallFailure(uriToUpdate);
	}

	public void processAndSaveJsonReplyData(final Uri uriToUpdate, final String replyBodyText) {
		final String TAG = "CsRestService.processAndSaveJsonReplyData()";
	
		//extract the specific response name (*response, where * is the api name),
		//as well as the actual data object representing the response
		JsonNode rootNode = null;
		try {
			ObjectMapper om = new ObjectMapper();
			rootNode = om.readTree(replyBodyText);
		} catch (JsonParseException e) {
			ClLog.e(TAG, "got Exception parsing json! [" + e.toString() +"]");
			ClLog.e(TAG, e);
		} catch (IOException e) {
			ClLog.e(TAG, "got IOException! [" + e.toString() +"]");
			ClLog.e(TAG, e);
		}
		final JsonNameNodePair responseData = extractFirstFieldValuePair(rootNode);  //we assume the "*response" tag is always the first field of the replyBody
		
		if("listVirtualMachinesResponse".equalsIgnoreCase(responseData.getFieldName())) {
			//parse listVirtualMachine results and save to vms table
			parseReplyBody_listVirtualMachines(uriToUpdate, responseData.getValueNode());
		} else if("startVirtualMachineResponse".equalsIgnoreCase(responseData.getFieldName())) {
			//parse startVirtualMachine results and wait for async results
			parseReplyBody_startOrStopOrRebootVirtualMachine(uriToUpdate, responseData.getValueNode());
		} else if("stopVirtualMachineResponse".equalsIgnoreCase(responseData.getFieldName())) {
			//parse stopVirtualMachine results and wait for async results
			parseReplyBody_startOrStopOrRebootVirtualMachine(uriToUpdate, responseData.getValueNode());
		}  else if("rebootVirtualMachineResponse".equalsIgnoreCase(responseData.getFieldName())) {
			//parse rebootVirtualMachine results and wait for async results
			parseReplyBody_startOrStopOrRebootVirtualMachine(uriToUpdate, responseData.getValueNode());
		} else if("listSnapshotsResponse".equalsIgnoreCase(responseData.getFieldName())) {
			//parse listSnapshots results and save to snapshots table
			parseReplyBody_listSnapshots(uriToUpdate, responseData.getValueNode());
		} else if("deleteSnapshotResponse".equalsIgnoreCase(responseData.getFieldName())) {
			//parse deleteSnapshot results and update snapshots table
			parseReplyBody_startOrStopOrRebootVirtualMachine(uriToUpdate, responseData.getValueNode());
			
			
			
			///////////////////////////////////////////////////////////////////
			//TODO: other API calls to handle will go below here as an else-if
			///////////////////////////////////////////////////////////////////
		} else if("queryAsyncJobResultResponse".equalsIgnoreCase(responseData.getFieldName())) {
			//parse queryAsyncJobResult results and save to appropriate table
			parseReplyBody_queryAsyncJobResult(responseData.getValueNode());
		} else {
			//no such api call!
			ClLog.e(TAG, "No such CloudStack API call/response exists [apiResponseName="+responseData.getFieldName()+"].  No data saved to datastore.");
		}
		
	}
	
	public JsonNameNodePair extractFirstFieldValuePair(JsonNode node) {
		Iterator<String> fieldNameIterator = node.getFieldNames();
		if(fieldNameIterator.hasNext()==false) {
			return null;
		}
		
		final String apiResponseName = fieldNameIterator.next();
		final JsonNode responseDataNode = node.path(apiResponseName);

		return new JsonNameNodePair(apiResponseName, responseDataNode);
	}

	public void parseReplyBody_listVirtualMachines(final Uri uriToUpdate, JsonNode responseDataNode) {
		final String TAG = "CsRestService.parseListVirtualMachinesResult()";

		JsonNode vmNode = responseDataNode.path("virtualmachine");  //extract the virtualmachine list, which contains the actual vm data
		JsonParser vmListParser = vmNode.traverse();

		final int num = getContentResolver().delete(Vms.META_DATA.CONTENT_URI, null, null);
		ClLog.i(TAG, "clearing vms db before adding new data; num of records deleted=" + num);

		if(!vmNode.isMissingNode()) {  //vmNode will be a MissingNode if the server returned 0 results
			parseAndSaveReply(vmListParser, Vms.META_DATA.CONTENT_URI, INSERT_DATA);
		}

		try {
			vmListParser.close();
		} catch (IOException e) {
			ClLog.e(TAG, "got IOException trying to close vm list parser! [" + e.toString() +"]");
			e.printStackTrace();
		}

		informCallerOfCallCompletion(uriToUpdate, CsRestService.CALL_STATUS_VALUES.CALL_SUCCESS);
	}

	private void parseReplyBody_startOrStopOrRebootVirtualMachine(final Uri uriToUpdate, JsonNode responseDataNode) {
		final String TAG = "CsRestService.parseReplyBody_startOrStopOrRebootVirtualMachine()";
		
		final JsonNode jobidNode = responseDataNode.path("jobid");  //extract the jobid object
		final String jobid = jobidNode.asText();

		if(jobid==null) {
			ClLog.e(TAG, "expecting jobid to query, but could not parse replyBodyText; aborting async query request");
			return;
		}
		
		//mark the pending async request with the received jobid
		ContentValues cv = new ContentValues();
		cv.put(Transactions.JOBID, jobid);
		getContentResolver().update(uriToUpdate, cv, null, null);

		startCheckAsyncJobProgress(jobid);
	}

	public void parseReplyBody_listSnapshots(final Uri uriToUpdate, JsonNode responseDataNode) {
		final String TAG = "CsRestService.parseReplyBody_listSnapshots()";

		final JsonNode snapshotNode = responseDataNode.path("snapshot");  //extract the snapshot list, which contains the actual snapshot data
		final JsonParser snapshotListParser = snapshotNode.traverse();

		final int num = getContentResolver().delete(Snapshots.META_DATA.CONTENT_URI, null, null);
		ClLog.i(TAG, "clearing snapshots db before adding new data; num of records deleted=" + num);

		if(!snapshotNode.isMissingNode()) {  //snapshotNode will be a MissingNode if the server returned 0 results
			parseAndSaveReply(snapshotListParser, Snapshots.META_DATA.CONTENT_URI, INSERT_DATA);
		}
		
		try {
			snapshotListParser.close();
		} catch (IOException e) {
			ClLog.e(TAG, "got IOException trying to close snapshot list parser! [" + e.toString() +"]");
			e.printStackTrace();
		}
		
		informCallerOfCallCompletion(uriToUpdate, CsRestService.CALL_STATUS_VALUES.CALL_SUCCESS);
	}
	
	
	private void parseReplyBody_queryAsyncJobResult(JsonNode responseDataNode) {
		final String TAG = "CsRestService.parseReplyBody_queryAsyncJobResult()";

		final String jobid = responseDataNode.path("jobid").asText();
		final String jobstatus = responseDataNode.path("jobstatus").asText();
		
        
		switch(Integer.valueOf(jobstatus)) {
			case ASYNCJOB_STILLINPROGRESS:
				{
					//we basically do nothing while we wait for the async job to finish
					ClLog.i(TAG, "waiting for result of pending async jobid="+jobid);
				}
				break;
			case ASYNCJOB_COMPLETED:
				{
					//read and save jobResult object (how do we tell where it goes?!?)
					ClLog.d(TAG, "async jobid="+jobid+" returned as success");
					
			        endCheckAsyncJobProgress(jobid);

					final JsonNode jobresultObject = responseDataNode.path("jobresult");
					final JsonNameNodePair jobresult = extractFirstFieldValuePair(jobresultObject);
					ClLog.d(TAG, "jobresult.getFieldName()= "+jobresult.getFieldName());
					ClLog.d(TAG, "jobresult.getValueNode()= "+jobresult.getValueNode());
					
					if("virtualmachine".equalsIgnoreCase(jobresult.getFieldName())) {
						//update vms row with returned data
						final JsonNode valueNode = jobresult.getValueNode();
						if(!valueNode.isMissingNode()) { //valueNode will be a MissingNode if the server returned 0 results
							final JsonParser nodeParser = valueNode.traverse();
							parseAndSaveReply(nodeParser, Vms.META_DATA.CONTENT_URI, UPDATE_DATA_WITH_ID);
							
							//no need to notify CsVmListFragment of successes as it will determine the correct action from the change in state
						}
					} else if("success".equalsIgnoreCase(jobresult.getFieldName())) {
						//there are multiple apis that return a jobresult with "success" field:
						//  e.g. "jobresult":{"success":true}}
						//we need to determine which api command produced this result and take appropriate action
						final String successValue = jobresult.getValueNode().asText();
						final boolean jobSucceeded = "true".equalsIgnoreCase(successValue)? true : false;
						handleJobresultBasedOnApi(jobid, jobSucceeded);
					} else {
						ClLog.e(TAG, "got jobresult with unrecognized data.  jobresult fieldname="+jobresult.getFieldName());
					}

				}
				break;
			case ASYNCJOB_FAILEDTOCOMPLETE:
				{
					//read and show error
					ClLog.d(TAG, "async jobid="+jobid+" returned as failure ;_;");
					
			        endCheckAsyncJobProgress(jobid);
			        
					final String jobresultcode = responseDataNode.path("jobresultcode").asText();
					final String errortext = responseDataNode.findPath("errortext").asText();
					ClLog.d(TAG, "jobresultcode= "+jobresultcode);
					ClLog.d(TAG, "jobresult.errortext= "+errortext);
					
					Bundle bundle = findTransactionRequestAndCallbackForJobid(jobid);
					String originatingTransactionUri = bundle.getString(Transactions.REQUEST);
					addToErrorLog(jobresultcode, errortext+" ("+jobresultcode+")", originatingTransactionUri);
					
					if(errortext==null) {
						; //null check
					} else if(errortext.contains("Failed to reboot vm instance")) {
						final String vmid = extractParamValueFromUriStr(originatingTransactionUri, Vms.ID);
						//mark the vm as stopped in the case of a reboot failure
						updateVmState(vmid, Vms.STATE_VALUES.STOPPED);
					}
					//inform calling fragment that the command failed
					handleJobresultBasedOnApi(jobid, false);
				}
				break;
			default:
				ClLog.e(TAG, "got an unrecognized jobstatus="+jobstatus);
		}
	}

	public void handleJobresultBasedOnApi(final String jobid, final boolean jobSucceeded) {
		final String TAG = "CsRestService.handleJobresultBasedOnApi()";

		Bundle bundle = findTransactionRequestAndCallbackForJobid(jobid);
		final String originalRequestUri = bundle.getString(Transactions.REQUEST);
		final String apiCmd = extractParamValueFromUriStr(originalRequestUri, CsRestService.COMMAND);
		final String callbackIntentFilter = bundle.getString(Transactions.CALLBACK_INTENT_FILTER);

		if(CsApiConstants.API.startVirtualMachine.equalsIgnoreCase(apiCmd)
				|| CsApiConstants.API.stopVirtualMachine.equalsIgnoreCase(apiCmd)
						|| CsApiConstants.API.rebootVirtualMachine.equalsIgnoreCase(apiCmd)) {
			final String vmId = extractParamValueFromUriStr(originalRequestUri, Vms.ID);
			final int successOrFailure = (jobSucceeded)? CsRestService.CALL_STATUS_VALUES.CALL_SUCCESS : CsRestService.CALL_STATUS_VALUES.CALL_FAILURE;
			informCallerFragmentOfCallCompletion(callbackIntentFilter, vmId, successOrFailure);
			
		} else if(CsApiConstants.API.deleteSnapshot.equalsIgnoreCase(apiCmd)) {
			final String snapshotId = extractParamValueFromUriStr(originalRequestUri, Snapshots.ID);
			if(jobSucceeded) {
				deleteSnapshotWithId(snapshotId);
				informCallerFragmentOfCallCompletion(callbackIntentFilter, snapshotId, CsRestService.CALL_STATUS_VALUES.CALL_SUCCESS);
			} else {
				informCallerFragmentOfCallCompletion(callbackIntentFilter, snapshotId, CsRestService.CALL_STATUS_VALUES.CALL_FAILURE);
			}
		} else {
			ClLog.e(TAG, "retrieved invalid apiCmd="+apiCmd);
		}
	}
	
	public int updateVmState(final String vmid, final String newState) {
//		Bundle bundle = findTransactionRequestForJobid(jobid);
//		String originatingTransactionUri = bundle.getString(Transactions.REQUEST);
//		final String vmid = extractParamValueFromUriStr(originatingTransactionUri, Vms.ID);
		
		ContentValues cv = new ContentValues();
		cv.put(Vms.STATE, newState);
		final String whereClause = Vms.ID+"=?";
		final String[] selectionArgs = new String[] { vmid };
		return getContentResolver().update(Vms.META_DATA.CONTENT_URI, cv, whereClause, selectionArgs);
	}

	public int deleteSnapshotWithId(final String snapshotId) {
		final String whereClause = Snapshots.ID+"=?";
		final String[] selectionArgs = new String[] { snapshotId };
		return getContentResolver().delete(Snapshots.META_DATA.CONTENT_URI, whereClause, selectionArgs);
	}
	
	/**
	 * Extracts and returns the value portion of the specified parameter from an uri string.
	 * 
	 * @param uriStr uri to parse
	 * @param paramValueToExtract name of param whose value to return
	 * @return value of param as a String if found, null otherwise
	 */
	public static String extractParamValueFromUriStr(final String uriStr, final String paramValueToExtract) {
		if(uriStr==null || paramValueToExtract==null) {
			return null;
		}
		
		final int paramDelimiter = uriStr.indexOf('?');
		String params = uriStr;
		if(paramDelimiter>-1) {
			params = uriStr.substring(paramDelimiter+1);  //remove any host/path info from the uri, leaving only params
		}
		
		StringTokenizer st = new StringTokenizer(params, "&");
		while (st.hasMoreTokens()) {
			final String paramValue = st.nextToken();
			final int indexOfEqualsSign = paramValue.indexOf("=");
			if(indexOfEqualsSign!=-1) {
				final String param = paramValue.substring(0, indexOfEqualsSign);
				if(paramValueToExtract.equalsIgnoreCase(param)) {
					return paramValue.substring(indexOfEqualsSign+1, paramValue.length());
				}
			}
		}
		return null;
	}

	public Bundle findTransactionRequestAndCallbackForJobid(final String jobid) {
		if(jobid==null) {
			return null;
		}
		
		return findTransactionRequestAndCallback(Transactions.META_DATA.CONTENT_URI, jobid);
	}
	
	public Bundle findTransactionRequestAndCallbackForRow(final Uri uriWithRowId) {
		return findTransactionRequestAndCallback(uriWithRowId, null);
	}
	
	public Bundle findTransactionRequestAndCallback(final Uri transactionsUri, final String jobid) {
		if(transactionsUri==null) {
			return null;
		}
		
		final String[] columns = new String[] { Transactions.REQUEST, Transactions.CALLBACK_INTENT_FILTER };
		String whereClause = null;
		String[] selectionArgs =null;
		if(jobid!=null) { 
			whereClause = Transactions.JOBID+"=?";
			selectionArgs = new String[] { jobid };
		};
		Cursor c = getContentResolver().query(transactionsUri, columns, whereClause, selectionArgs, null);
		
		if(c==null || c.getCount()<1) {
			if(c!=null) { c.close(); };
			return null;
		}
		
		c.moveToFirst();
//		final String originatingTransactionUri = c.getString(c.getColumnIndex(Transactions.REQUEST));
		Bundle returnBundle = new Bundle();
		returnBundle.putString(Transactions.REQUEST, c.getString(c.getColumnIndex(Transactions.REQUEST)));
		
		final int callbackIntentFilterColumnIndex = c.getColumnIndex(Transactions.CALLBACK_INTENT_FILTER);
		final boolean hasValidCallbackIntentFilter = callbackIntentFilterColumnIndex!=-1;
		if(hasValidCallbackIntentFilter) {
			returnBundle.putString(Transactions.CALLBACK_INTENT_FILTER, c.getString(callbackIntentFilterColumnIndex));
		}
		
		c.close();
		return returnBundle;
	}
	

	public void startCheckAsyncJobProgress(final String jobid) {
		//set up a task to repeatedly check with cs server whether this jobid has completed
        final long currentTimeInMillis = System.currentTimeMillis();
        final long eightSecIntervalInMillis = 8000;
        
        final PendingIntent checkAsyncProgressPendingItent = createCheckAsyncJobProgressPendingIntent(jobid);
        AlarmManager alarmManager = (AlarmManager) getApplication().getSystemService(Context.ALARM_SERVICE);
        if(alarmManager != null) {
        	alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, currentTimeInMillis, eightSecIntervalInMillis, checkAsyncProgressPendingItent);
        }
	}
	
	public void endCheckAsyncJobProgress(final String jobid) {
		//stop checking up on async job progress, canceling all pending intents
		final PendingIntent checkAsyncProgressPendingItent = createCheckAsyncJobProgressPendingIntent(jobid);
		AlarmManager alarmManager = (AlarmManager) getApplication().getSystemService(Context.ALARM_SERVICE);
		if(alarmManager!=null) {
			alarmManager.cancel(checkAsyncProgressPendingItent);
		}
	}

	public PendingIntent createCheckAsyncJobProgressPendingIntent(final String jobid) {
		Bundle apiCmd = new Bundle();
        apiCmd.putString(CsRestService.COMMAND, "queryAsyncJobResult");
        apiCmd.putString(Transactions.JOBID, jobid);
        
        Intent checkAsyncJobProgressIntent = new Intent(getApplicationContext(), CheckAsyncJobProgress.class);
        checkAsyncJobProgressIntent.putExtras(apiCmd);
        checkAsyncJobProgressIntent.setData(Uri.parse(jobid));  //setting jobid as data here to make AlarmManager spawn an independent alarm for each job (the jobid-as-uri itself means nothing, we just need some piece of unique info that makes this PendingIntent different than the others spawned to keep track of other jobs)
        final PendingIntent checkAsyncJobProgressPendingItent = PendingIntent.getBroadcast(getApplicationContext(), 0, checkAsyncJobProgressIntent, PendingIntent.FLAG_UPDATE_CURRENT);
		return checkAsyncJobProgressPendingItent;
	}
	

	/**
	 * Parses a JasonParser, looking for an object or a list of objects, persisting each parsed object to the specified database.
	 * If INSERT_DATA is specified, all parsed object(s) will be inserted into the database.
	 * If UPDATE_DATA_WITH_ID is specified, the row in the database with an id value equal to the parsed object's id value
	 * will be updated with the parsed data.  If a row with the same id value cannot be found, nothing is done with the
	 * parsed object.
	 * 
	 * @param listObjectParser JsonParser object representing the object/list-of-objects to parse
	 * @param targetDbTable database table to insert/update to
	 * @param insertOrUpdate CsRestService.INSERT_DATA to add data as new row, or CsRestService.UPDATE_DATA_WITH_ID to update existing row with parsed data
	 */
	public void parseAndSaveReply(final JsonParser listObjectParser, final Uri targetDbTable, final int insertOrUpdate) {
		final String TAG = "CsRestService.parseAndSaveReply()";

		try {
			ObjectMapper om = new ObjectMapper();
			JsonToken token = null;
			ContentValues contentValues = new ContentValues();
			for(token = listObjectParser.nextToken(); token!=null && token!=JsonToken.END_ARRAY; token = listObjectParser.nextToken()) {

				if(token==JsonToken.START_ARRAY || token==JsonToken.END_ARRAY) {
					continue;  //ignore any array delimiters
				}

				if(token==JsonToken.START_OBJECT) {
					contentValues.clear();  //if we are at the start of an vm item, prepare a clean contentValues to save parsed data
					continue;
				}

				if(token==JsonToken.END_OBJECT) {
					if(insertOrUpdate==UPDATE_DATA_WITH_ID) {
						//update only the row with the same id as the parsed data
						final String whereClause = Vms.ID+"=?"; //using Vms.ID here, but assumed that all ID fieldnames for all tables are the same, i.e. "id"
						final String[] selectionArgs = new String[] {contentValues.getAsString("id")};
						final int numUpdated = getContentResolver().update(targetDbTable, contentValues, whereClause, selectionArgs);
						ClLog.i(TAG, "updated "+numUpdated+" row(s)");
					} else {
						//insert parsed data into table
						final Uri newUri = getContentResolver().insert(targetDbTable, contentValues);
						if(newUri!=null) {
							ClLog.i(TAG, "successfully inserted "+newUri);
						}
					}
					continue;
				}

				String fieldName = listObjectParser.getCurrentName();
				listObjectParser.nextToken();  //now on value of this field
				String fieldValue = listObjectParser.getText();
				if(listObjectParser.isExpectedStartArrayToken()) {
					//parse & save any object/array values as plain json;
					//the app will have the responsibility of re-creating the obj from the json if it is needed
					JsonNode complexDataNode = om.readTree(listObjectParser);
					fieldValue = om.writeValueAsString(complexDataNode);
				}

				if(fieldName.equals(Vms.META_DATA.CS_ORIGINAL_GROUP_FIELD_NAME)) {
					//this is an unfortunate special check being done to map any "group" fields from cs to
					//a client-specific "groupa" fieldname (arbitrary name).  This is necessary b/c "group"
					//is an sql keyword so we cannot use it as a field name or it cases sql statements to choke.
					fieldName = Vms.GROUPA;
				}

				contentValues.put(fieldName, fieldValue);
			}
		} catch (JsonParseException e) {
			ClLog.e(TAG, "got Exception parsing json! [" + e.toString() +"]");
			ClLog.e(TAG, e);
		} catch (JsonGenerationException e) {
			ClLog.e(TAG, "got Exception generating json! [" + e.toString() +"]");
			ClLog.e(TAG, e);
		} catch (JsonMappingException e) {
			ClLog.e(TAG, "got Exception mapping json! [" + e.toString() +"]");
			ClLog.e(TAG, e);
		} catch (JsonProcessingException e) {
			ClLog.e(TAG, "got Exception processing json! [" + e.toString() +"]");
			ClLog.e(TAG, e);
		} catch (IOException e) {
			ClLog.e(TAG, "got IOException! [" + e.toString() +"]");
			ClLog.e(TAG, e);
		}
		
	}
	

} ///END CsRestService

