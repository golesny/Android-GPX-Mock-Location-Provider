/*
 * Copyright (c) 2011 2linessoftware.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twolinessoftware.android;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

import android.Manifest;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.twolinessoftware.android.framework.util.Logger;

public class MainActivity extends Activity implements GpsPlaybackListener {

	private static final int REQUEST_FILE = 1;

	private static final String LOG = MainActivity.class.getSimpleName();
	private static final String LOGNAME = "SimulatedGPSProvider.MainActivity";

	private ServiceConnection connection;
	private IPlaybackService service;
	private EditText mEditText;

	private EditText mEditTextDelay;

	private String filepath;

	private String delayTimeOnReplay = "";

	private GpsPlaybackBroadcastReceiver receiver;

	private int state;

	private ProgressDialog progressDialog;

	private static final String APP_DATA_CACHE_FILENAME = "gpx_app_data_cache";
	private static final String DEFAULT_PATH_TO_GPX_FILE = "/";

	// Storage Permissions
	private static final int REQUEST_EXTERNAL_STORAGE = 1;
	private static String[] PERMISSIONS_STORAGE = {
			Manifest.permission.READ_EXTERNAL_STORAGE,
			Manifest.permission.WRITE_EXTERNAL_STORAGE
	};

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		mEditText = (EditText) findViewById(R.id.file_path);

		TextView mLabelEditText = (TextView) findViewById(R.id.label_edit_text_delay);
		mLabelEditText.setText("Playback Delay (ms): ");
		mLabelEditText.setTextSize(17);
		mLabelEditText.setTextColor(Color.RED);


		mEditTextDelay = (EditText) findViewById(R.id.editTextDelay);

		mEditTextDelay.setOnFocusChangeListener(new View.OnFocusChangeListener() {

			public void onFocusChange(View v, boolean hasFocus) {
				if (!hasFocus) {
					delayTimeOnReplay = mEditTextDelay.getText().toString();
				}
			}
		});
	}

	@Override
	protected void onStart() {
		bindStatusListener();
		connectToService();
		super.onStart();
	}

	@Override
	protected void onStop() {
		if (receiver != null)
			unregisterReceiver(receiver);

		try {
			unbindService(connection);
		} catch (Exception ie) {
		}

		super.onStop();

	}

	private void hideProgressDialog() {
		if (progressDialog != null)
			progressDialog.cancel();
	}

	private void showProgressDialog() {
		// Display progress dialog

		progressDialog = ProgressDialog.show(this,
				getString(R.string.please_wait),
				getString(R.string.loading_file), true);
		progressDialog.setCancelable(true);
		progressDialog.setOnCancelListener(new OnCancelListener() {
			@Override
			public void onCancel(DialogInterface dialog) {
				dialog.dismiss();
			}
		});
	}

	private void bindStatusListener() {
		receiver = new GpsPlaybackBroadcastReceiver(this);
		IntentFilter filter = new IntentFilter();
		filter.addAction(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
		registerReceiver(receiver, filter);
	}

	private void connectToService() {
		Intent i = new Intent(getApplicationContext(), PlaybackService.class);
		connection = new PlaybackServiceConnection();
		bindService(i, connection, Context.BIND_AUTO_CREATE);
	}

	public void onClickOpenFile(View view) {
		openFile();
	}

	public void onClickStart(View view) {
		startPlaybackService();
	}

	public void onClickStop(View view) {
		stopPlaybackService();
	}



	/**
	 * Checks if the app has permission to write to device storage
	 *
	 * If the app does not has permission then the user will be prompted to grant permissions
	 *
	 * @param activity
	 */
	public static void verifyStoragePermissions(Activity activity) {
		Log.d(LOG, "verifyStoragePermission start");
		// Check if we have write permission
		int permission = ActivityCompat
				.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE);
		Log.d(LOG, "verifyStoragePermission done");
		if (permission != PackageManager.PERMISSION_GRANTED) {
			// We don't have permission so prompt the user
			ActivityCompat.requestPermissions(
					activity,
					PERMISSIONS_STORAGE,
					REQUEST_EXTERNAL_STORAGE
			);
		}
	}

	/**
	 * Opens the file manager to select a file to open.
	 */
	public void openFile() {

		verifyStoragePermissions(this);
        String fileName = getGpxFilePath();

		Intent intent = new Intent(FileManagerIntents.ACTION_PICK_FILE);

		// Construct URI from file name.
		File file = new File(fileName);
		intent.setData(Uri.fromFile(file));

		// Set fancy title and button (optional)
		intent.putExtra(FileManagerIntents.EXTRA_TITLE,
				getString(R.string.open_title));
		intent.putExtra(FileManagerIntents.EXTRA_BUTTON_TEXT,
				getString(R.string.open_button));

		try {
			startActivityForResult(intent, REQUEST_FILE);

		} catch (ActivityNotFoundException e) {
			// No compatible file manager was found.
			Toast.makeText(this, R.string.no_filemanager_installed,
					Toast.LENGTH_SHORT).show();
		}
	}

	/*
	 * "Start" button clicked
	 */
	public void startPlaybackService() {

		if (filepath == null) {
			Toast.makeText(this, "No File Loaded", Toast.LENGTH_SHORT).show();
			return;
		}

		if (delayTimeOnReplay == null) {
			Toast.makeText(this, "No delay time specified", Toast.LENGTH_SHORT).show();
			return;
		}


		try {
			if (service != null) {
				service.startService(filepath);
			}

		} catch (RemoteException e) {
			Log.d(LOG, "Remote Exception");
		}

		Intent i = new Intent(getApplicationContext(), PlaybackService.class);
		i.putExtra("delayTimeOnReplay", delayTimeOnReplay);
		startService(i);
	}

	public void stopPlaybackService() {

		try {
			if (service != null) {
				saveGpxFilePath(filepath);
				mEditText.setText(filepath);

				service.stopService();
			}


		} catch (RemoteException e) {
		}
	}

	private void updateUi() {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				Button start = (Button) findViewById(R.id.start);
				Button stop = (Button) findViewById(R.id.stop);

				switch (state) {
					case PlaybackService.RUNNING:
						start.setEnabled(false);
						stop.setEnabled(true);
						break;
					case PlaybackService.STOPPED:
						start.setEnabled(true);
						stop.setEnabled(false);
						break;
				}

			}

		});

	}

	class PlaybackServiceConnection implements ServiceConnection {

		public void onServiceConnected(ComponentName name, IBinder boundService) {
			service = IPlaybackService.Stub.asInterface(boundService);
			try {
				state = service.getState();
			} catch (RemoteException e) {
				Logger.e(LOGNAME, "Unable to access state:" + e.getMessage());
			}
			updateUi();
		}

		public void onServiceDisconnected(ComponentName name) {
			service = null;
		}

	}

	/**
	 * This is called after the file manager finished.
	 */
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		switch (requestCode) {
		case REQUEST_FILE:
			if (resultCode == RESULT_OK && data != null) {
				// obtain the filename
				Uri fileUri = data.getData();
				if (fileUri != null) {
					String filePath = fileUri.getPath();
					if (filePath != null) {
						mEditText.setText(filePath);
						this.filepath = filePath;
					}
				}
			}
			break;
		}
	}

	@Override
	public void onFileLoadStarted() {
		Logger.d(LOGNAME, "File loading started");
		showProgressDialog();
	}

	@Override
	public void onFileLoadFinished() {
		Logger.d(LOGNAME, "File loading finished");
		hideProgressDialog();
	}

	@Override
	public void onStatusChange(int newStatus) {
		state = newStatus;
		updateUi();
	}

	@Override
	public void onFileError(String message) {
		hideProgressDialog();
	}

	/**
	 * Saves filepath to private application data saved on disk.
	 * @param filepath
	 */
	public void saveGpxFilePath(String filepath) {

		try {
			FileOutputStream fos = openFileOutput(APP_DATA_CACHE_FILENAME, Context.MODE_PRIVATE);
			if (filepath != null) {
				fos.write(filepath.getBytes());
			} else {
				fos.write(DEFAULT_PATH_TO_GPX_FILE.getBytes());
			}
			fos.close();

		} catch (java.lang.Exception e) {
			Logger.d(LOGNAME, "saveGpxFilePath exception: " + e.getMessage());
		}
	}

	/**
	 * Method gets the path to last GPX file loaded based on value saved in private application
	 * data saved on disk. Defaults to "/".
	 *
	 * @return path to gpx file
	 */
	public String getGpxFilePath() {

		String filepath = DEFAULT_PATH_TO_GPX_FILE;

		try {
			FileInputStream fis = openFileInput(APP_DATA_CACHE_FILENAME);
			filepath = convertStreamToString(fis);
			if (filepath == null || filepath.equalsIgnoreCase("")) {
				filepath = DEFAULT_PATH_TO_GPX_FILE;
			}
			fis.close();

		} catch (java.lang.Exception e) {
			Logger.d(LOGNAME, "getGpxFilePath - no cache file detected - default path being used e.g /");
		}
		return filepath;
	}

	/**
	 * Method reads inputstream to string.
	 *
	 * @param is
	 * @return file contents
	 * @throws Exception
	 */
	public static String convertStreamToString(InputStream is) throws Exception {
		BufferedReader reader = new BufferedReader(new InputStreamReader(is));
		StringBuilder sb = new StringBuilder();
		String line = null;
		while ((line = reader.readLine()) != null) {
			sb.append(line).append("\n");
		}
		reader.close();
		return sb.toString();
	}

}