/*
	Radiobeacon - Openbmap wifi and cell logger
    Copyright (C) 2013  wish7

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.openbmap.activities;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import org.openbmap.Preferences;
import org.openbmap.R;
import org.openbmap.utils.FileUtils;
import org.openbmap.utils.LegacyDownloader;
import org.openbmap.utils.LegacyDownloader.LegacyDownloadListener;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Preferences activity.
 */
public class SettingsActivity extends PreferenceActivity implements LegacyDownloadListener {

	private static final String TAG = SettingsActivity.class.getSimpleName();

	private DownloadManager mDownloadManager;

    /*
     * Id of the active catalog download or -1 if no active download
     */
	private long currentCatalogDownloadId = -1;

    /*
     * Id of the active map download or -1 if no active download
     */
	private long currentMapDownloadId = -1;

	private BroadcastReceiver mReceiver = null; 

	@SuppressLint("NewApi")
	@Override
	protected final void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		addPreferencesFromResource(R.xml.preferences);

		// with versions >= GINGERBREAD use download manager
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
			initDownloadManager();
		}

		initWifiCatalogDownloadControl();
		initActiveWifiCatalogControl(PreferenceManager.getDefaultSharedPreferences(this).getString(Preferences.KEY_WIFI_CATALOG_FOLDER,
				this.getExternalFilesDir(null).getAbsolutePath() + Preferences.WIFI_CATALOG_SUBDIR));

		initMapDownloadControl();
		initActiveMapControl(PreferenceManager.getDefaultSharedPreferences(this).getString(Preferences.KEY_MAP_FOLDER,
				this.getExternalFilesDir(null).getAbsolutePath() + Preferences.MAPS_SUBDIR));


		initGpsLogIntervalControl();

		initAdvancedSettingsButton();
	}

	/**
	 * Initialises download manager for GINGERBREAD and newer
	 */
	@SuppressLint("NewApi")
	private void initDownloadManager() {

		mDownloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);

		mReceiver = new BroadcastReceiver() {
			@SuppressLint("NewApi")
			@Override
			public void onReceive(final Context context, final Intent intent) {
				final String action = intent.getAction();
				if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
					final long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0);
					final Query query = new Query();
					query.setFilterById(downloadId);
					final Cursor c = mDownloadManager.query(query);
					if (c.moveToFirst()) {
						final int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
						if (DownloadManager.STATUS_SUCCESSFUL == c.getInt(columnIndex)) {
							// we're not checking download id here, that is done in handleDownloads
							final String uriString = c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
							handleDownloads(uriString);
						}
					}
				}
			}
		};

		registerReceiver(mReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
	}

	@Override
	protected final void onDestroy() {
		try {
			if (mReceiver != null) {
				Log.i(TAG, "Unregistering broadcast receivers");
				unregisterReceiver(mReceiver);
			}
		} catch (final IllegalArgumentException e) {
			// do nothing here {@see http://stackoverflow.com/questions/2682043/how-to-check-if-receiver-is-registered-in-android}
			super.onDestroy();
			return;
		}

		super.onDestroy();
	}

	/**
	 * Starts advanced setting activity
	 */
	private void initAdvancedSettingsButton() {
		final Preference pref = findPreference(Preferences.KEY_ADVANCED_SETTINGS);
		pref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(final Preference preference) {
                startActivity(new Intent(pref.getContext(), AdvancedSettingsActivity.class));
                return true;
            }
        });
	}

	/**
	 * Initializes gps logging interval.
	 */
	private void initGpsLogIntervalControl() {
		// Update GPS logging interval summary to the current value
		final Preference pref = findPreference(org.openbmap.Preferences.KEY_GPS_LOGGING_INTERVAL);
		pref.setSummary(
                PreferenceManager.getDefaultSharedPreferences(this).getString(org.openbmap.Preferences.KEY_GPS_LOGGING_INTERVAL, org.openbmap.Preferences.VAL_GPS_LOGGING_INTERVAL)
                        + " " + getResources().getString(R.string.prefs_gps_logging_interval_seconds)
                        + ". " + getResources().getString(R.string.prefs_gps_logging_interval_summary));
		pref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(final Preference preference, final Object newValue) {
                // Set summary with the interval and "seconds"
                preference.setSummary(newValue
                        + " " + getResources().getString(R.string.prefs_gps_logging_interval_seconds)
                        + ". " + getResources().getString(R.string.prefs_gps_logging_interval_summary));
                return true;
            }
        });
	}

	/**
	 * Populates the download list with links to mapsforge downloads.
	 */
	private void initMapDownloadControl() {
		String[] entries;
		String[] values;

		// No map found, populate values with just the default entry.
		entries = getResources().getStringArray(R.array.listDisplayWord);
		values = getResources().getStringArray(R.array.listReturnValue);

		final ListPreference lf = (ListPreference) findPreference(Preferences.KEY_DOWNLOAD_MAP);
		lf.setEntries(entries);
		lf.setEntryValues(values);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
			// use download manager for versions >= GINGERBREAD
			lf.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
				@SuppressLint("NewApi")
				public boolean onPreferenceChange(final Preference preference, final Object newValue) {
                    if (currentMapDownloadId > -1) {
                        Toast.makeText(SettingsActivity.this, getString(R.string.other_download_active), Toast.LENGTH_LONG).show();
                        return true;
                    }

					// try to create directory
					final File folder = new File(PreferenceManager.getDefaultSharedPreferences(SettingsActivity.this).getString(
							Preferences.KEY_MAP_FOLDER,
							SettingsActivity.this.getExternalFilesDir(null).getAbsolutePath() + File.separator + Preferences.MAPS_SUBDIR));

					boolean folderAccessible = false;
					if (folder.exists() && folder.canWrite()) {
						folderAccessible = true;
					}

					if (!folder.exists()) {
						folderAccessible = folder.mkdirs();
					}
					if (folderAccessible) {
						final String filename = newValue.toString().substring(newValue.toString().lastIndexOf('/') + 1);

						final File target = new File(folder.getAbsolutePath() + File.separator + filename);
						if (target.exists()) {
							Log.i(TAG, "Map file " + filename + " already exists. Overwriting..");
							target.delete();
						}

						try {
							// try to download to target. If target isn't below Environment.getExternalStorageDirectory(),
							// e.g. on second SD card a security exception is thrown
							final Request request = new Request(Uri.parse(newValue.toString()));
							request.setDestinationUri(Uri.fromFile(target));
							currentMapDownloadId = mDownloadManager.enqueue(request);
						} catch (final SecurityException sec) {
							// download to temp dir and try to move to target later
							Log.w(TAG, "Security exception, can't write to " + target + ", using " + SettingsActivity.this.getExternalCacheDir());
							final File tempFile = new File(SettingsActivity.this.getExternalCacheDir() + File.separator + filename);

							final Request request = new Request(Uri.parse(newValue.toString()));
							request.setDestinationUri(Uri.fromFile(tempFile));
							currentMapDownloadId = mDownloadManager.enqueue(request);
						}
					} else {
						Toast.makeText(preference.getContext(), R.string.error_save_file_failed, Toast.LENGTH_SHORT).show();
					}

					return false;
				}
			});
		} else {
			// use home-brew download manager for version < GINGERBREAD
			lf.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
				public boolean onPreferenceChange(final Preference preference, final Object newValue) {

					try {
						// try to create directory
						final File folder = new File(PreferenceManager.getDefaultSharedPreferences(SettingsActivity.this).getString(
								Preferences.KEY_MAP_FOLDER,
								SettingsActivity.this.getExternalFilesDir(null).getAbsolutePath() + File.separator + Preferences.MAPS_SUBDIR)
								);

						boolean folderAccessible = false;
						if (folder.exists() && folder.canWrite()) {
							folderAccessible = true;
						}

						if (!folder.exists()) {
							folderAccessible = folder.mkdirs();
						}
						if (folderAccessible) {
							final URL url = new URL(newValue.toString());
							final String filename = newValue.toString().substring(newValue.toString().lastIndexOf('/') + 1);

							Log.d(TAG, "Saving " + url + " at " + folder.getAbsolutePath() + '/' + filename);
							final LegacyDownloader task = (LegacyDownloader) new LegacyDownloader(preference.getContext()).execute(
									url, folder.getAbsolutePath() + File.separator + filename);

							// Callback to refresh maps preference on completion
							task.setListener((SettingsActivity) preference.getContext());

						} else {
							Toast.makeText(preference.getContext(), R.string.error_save_file_failed, Toast.LENGTH_SHORT).show();
						}	
					} catch (final MalformedURLException e) {
						Log.e(TAG, "Malformed download url: " + newValue.toString());
					}
					return false;
				}
			});
		}
	}

	/**
	 * Populates the active map list preference by scanning available map files.
	 * @param mapFolder Folder for maps
	 */
	private void initActiveMapControl(final String mapFolder) {
		String[] entries;
		String[] values;

		Log.d(TAG, "Scanning for map files");

		// Check for presence of maps directory
		final File mapsDir = new File(mapFolder);

		// List each map file
		if (mapsDir.exists() && mapsDir.canRead()) {
			final String[] mapFiles = mapsDir.list(new FilenameFilter() {
				@Override
				public boolean accept(final File dir, final String filename) {
					return filename.endsWith(org.openbmap.Preferences.MAP_FILE_EXTENSION);
				}
			});

			// Create array of values for each map file + one for not selected
			entries = new String[mapFiles.length + 1];
			values = new String[mapFiles.length + 1];

			// Create default / none entry
			entries[0] = getResources().getString(R.string.prefs_map_none);
			values[0] = org.openbmap.Preferences.VAL_MAP_NONE;

			for (int i = 0; i < mapFiles.length; i++) {
				entries[i + 1] = mapFiles[i].substring(0, mapFiles[i].length() - org.openbmap.Preferences.MAP_FILE_EXTENSION.length());
				values[i + 1] = mapFiles[i];
			}
		} else {
			// No map found, populate values with just the default entry.
			entries = new String[] {getResources().getString(R.string.prefs_map_none)};
			values = new String[] {org.openbmap.Preferences.VAL_MAP_NONE};
		}

		final ListPreference lf = (ListPreference) findPreference(Preferences.KEY_MAP_FILE);
		lf.setEntries(entries);
		lf.setEntryValues(values);
	}

	/**
	 * Changes map preference item to given filename.
	 * Helper method to activate map after successful download
	 * @param absoluteFile absolute filename (including path)
	 */
	private void activateMap(final String absoluteFile) {
		final ListPreference lf = (ListPreference) findPreference(org.openbmap.Preferences.KEY_MAP_FILE);

		// get filename
		final String[] filenameArray = absoluteFile.split("\\/");
		final String file = filenameArray[filenameArray.length - 1];

		final CharSequence[] values = lf.getEntryValues();
		for (int i = 0; i < values.length; i++) {
			if (file.equals(values[i].toString())) {
				lf.setValueIndex(i);
			}
		}
	}

	/**
	 * Initializes wifi catalog source preference
	 */
	@SuppressLint("NewApi")
	private void initWifiCatalogDownloadControl() {
		final Preference pref = findPreference(Preferences.KEY_DOWNLOAD_WIFI_CATALOG);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
			// use download manager for versions >= GINGERBREAD
			pref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
				@Override
				public boolean onPreferenceClick(final Preference preference) {
                    if (currentCatalogDownloadId > -1) {
                        Toast.makeText(SettingsActivity.this, getString(R.string.other_download_active), Toast.LENGTH_LONG).show();
                        return true;
                    }
					// try to create directory		
					final File folder = new File(PreferenceManager.getDefaultSharedPreferences(SettingsActivity.this).getString(
							Preferences.KEY_WIFI_CATALOG_FOLDER,
							SettingsActivity.this.getExternalFilesDir(null).getAbsolutePath() + Preferences.WIFI_CATALOG_SUBDIR));

					boolean folderAccessible = false;
					if (folder.exists() && folder.canWrite()) {
						folderAccessible = true;
					}

					if (!folder.exists()) {
						folderAccessible = folder.mkdirs();
					}
					if (folderAccessible) {
						final File target = new File(folder.getAbsolutePath() + File.separator + Preferences.WIFI_CATALOG_FILE);
						if (target.exists()) {
							Log.i(TAG, "Catalog file already exists. Overwriting..");
							target.delete();
						}

						try {
							// try to download to target. If target isn't below Environment.getExternalStorageDirectory(),
							// e.g. on second SD card a security exception is thrown
							final Request request = new Request(
									Uri.parse(Preferences.WIFI_CATALOG_DOWNLOAD_URL));
							request.setDestinationUri(Uri.fromFile(target));
							currentCatalogDownloadId = mDownloadManager.enqueue(request);
						} catch (final SecurityException sec) {
							// download to temp dir and try to move to target later
							Log.w(TAG, "Security exception, can't write to " + target + ", using " + SettingsActivity.this.getExternalCacheDir() 
									+ File.separator + Preferences.WIFI_CATALOG_FILE);
							final File tempFile = new File(SettingsActivity.this.getExternalCacheDir() + File.separator + Preferences.WIFI_CATALOG_FILE);
							final Request request = new Request(
									Uri.parse(Preferences.WIFI_CATALOG_DOWNLOAD_URL));
							request.setDestinationUri(Uri.fromFile(tempFile));
							mDownloadManager.enqueue(request);
						}
					} else {
						Toast.makeText(preference.getContext(), R.string.error_save_file_failed, Toast.LENGTH_SHORT).show();
					}
					return true;
				}
			});
		} else {
			// use home-brew download manager for version < GINGERBREAD
			pref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
				@Override
				public boolean onPreferenceClick(final Preference preference) {
					try {
						// try to create directory
						final File folder = new File(
								PreferenceManager.getDefaultSharedPreferences(preference.getContext()).getString(
										Preferences.KEY_WIFI_CATALOG_FOLDER,
										SettingsActivity.this.getExternalFilesDir(null).getAbsolutePath() + Preferences.WIFI_CATALOG_SUBDIR
										));

						boolean folderAccessible = false;
						if (folder.exists() && folder.canWrite()) {
							folderAccessible = true;
						}

						if (!folder.exists()) {
							folderAccessible = folder.mkdirs();
						}
						if (folderAccessible) {
							final URL url = new URL(Preferences.WIFI_CATALOG_DOWNLOAD_URL);
							final String filename = Preferences.WIFI_CATALOG_FILE;

							final LegacyDownloader task = (LegacyDownloader) new LegacyDownloader(preference.getContext()).execute(
									url, folder.getAbsolutePath() + File.separator + filename);

							// Callback to refresh maps preference on completion
							task.setListener((SettingsActivity) preference.getContext());

						} else {
							Toast.makeText(preference.getContext(), R.string.error_save_file_failed, Toast.LENGTH_SHORT).show();
						}	
					} catch (final MalformedURLException e) {
						Log.e(TAG, "Malformed download url: " + Preferences.WIFI_CATALOG_DOWNLOAD_URL);
					}
					return true;
				}
			});
		}
	}

	/**
	 * Populates the wifi catalog list preference by scanning catalog folder.
	 * @param catalogFolder Root folder for WIFI_CATALOG_SUBDIR
	 */
	private void initActiveWifiCatalogControl(final String catalogFolder) {

		String[] entries;
		String[] values;

		// Check for presence of database directory
		final File folder = new File(catalogFolder);

		if (folder.exists() && folder.canRead()) {
			// List each map file
			final String[] dbFiles = folder.list(new FilenameFilter() {
				@Override
				public boolean accept(final File dir, final String filename) {
					return filename.endsWith(
							org.openbmap.Preferences.WIFI_CATALOG_FILE_EXTENSION);
				}
			});

			// Create array of values for each map file + one for not selected
			entries = new String[dbFiles.length + 1];
			values = new String[dbFiles.length + 1];

			// Create default / none entry
			entries[0] = getResources().getString(R.string.prefs_map_none);
			values[0] = org.openbmap.Preferences.VAL_WIFI_CATALOG_NONE;

			for (int i = 0; i < dbFiles.length; i++) {
				entries[i + 1] = dbFiles[i].substring(0, dbFiles[i].length() - org.openbmap.Preferences.WIFI_CATALOG_FILE_EXTENSION.length());
				values[i + 1] = dbFiles[i];
			}
		} else {
			// No wifi catalog found, populate values with just the default entry.
			entries = new String[] {getResources().getString(R.string.prefs_map_none)};
			values = new String[] {org.openbmap.Preferences.VAL_WIFI_CATALOG_NONE};
		}

		final ListPreference lf = (ListPreference) findPreference(org.openbmap.Preferences.KEY_WIFI_CATALOG_FILE);
		lf.setEntries(entries);
		lf.setEntryValues(values);
	}

	/**
	 * Changes catalog preference item to given filename.
	 * Helper method to activate wifi catalog following successful download
	 * @param absoluteFile absolute filename (including path)
	 */
	private void activateWifiCatalog(final String absoluteFile) {
		final ListPreference lf = (ListPreference) findPreference(org.openbmap.Preferences.KEY_WIFI_CATALOG_FILE);

		// get filename
		final String[] filenameArray = absoluteFile.split("\\/");
		final String file = filenameArray[filenameArray.length - 1];

		final CharSequence[] values = lf.getEntryValues();
		for (int i = 0; i < values.length; i++) {
			if (file.equals(values[i].toString())) {
				lf.setValueIndex(i);
			}
		}
	}

	/**
	 * Selects downloaded file either as wifi catalog / active map (based on file extension).
	 * @param file
	 */
	public final void handleDownloads(String file) {
		// get current file extension
		final String[] filenameArray = file.split("\\.");
		final String extension = "." + filenameArray[filenameArray.length - 1];

		// TODO verify on newer Android versions (>4.2)
		// replace prefix file:// in filename string
		file = file.replace("file://", "");

		if (extension.equals(org.openbmap.Preferences.MAP_FILE_EXTENSION)) {
			currentMapDownloadId = -1;
			final String mapFolder = PreferenceManager.getDefaultSharedPreferences(this).getString(Preferences.KEY_MAP_FOLDER,
					SettingsActivity.this.getExternalFilesDir(null).getAbsolutePath() + Preferences.MAPS_SUBDIR);
			if (file.indexOf(SettingsActivity.this.getExternalCacheDir().getPath()) > - 1 ) {
				// file has been downloaded to cache folder, so move..
				file = moveToFolder(file, mapFolder); 
			}

			initActiveMapControl(mapFolder);
			// handling map files
			activateMap(file);
		} else if (extension.equals(org.openbmap.Preferences.WIFI_CATALOG_FILE_EXTENSION)) {
			currentCatalogDownloadId = -1;
			final String catalogFolder = PreferenceManager.getDefaultSharedPreferences(this).getString(
					Preferences.KEY_WIFI_CATALOG_FOLDER,
					SettingsActivity.this.getExternalFilesDir(null).getAbsolutePath() + Preferences.WIFI_CATALOG_SUBDIR);
			if (file.indexOf(SettingsActivity.this.getExternalCacheDir().getPath()) > - 1 ) {
				// file has been downloaded to cache folder, so move..
				file = moveToFolder(file, catalogFolder); 
			}

			initActiveWifiCatalogControl(catalogFolder);
			// handling wifi catalog files
			activateWifiCatalog(file);
		}
	}

	/**
	 * Moves file to specified folder
	 * @param file
	 * @param folder
	 * @return new file name
	 */
	private String moveToFolder(final String file, final String folder) {
		// file path contains external cache dir, so we have to move..
		final File source = new File(file);
		final File destination = new File(folder + File.separator + source.getName());
		Log.i(TAG, file + " stored in temp folder. Moving to " + destination.getAbsolutePath());

		try {
			FileUtils.moveFile(source, destination);
		}
		catch (final IOException e) {
			Log.e(TAG, "I/O error while moving file");
		}
		return  destination.getAbsolutePath();
	}

	// [start] Android 2.2 compatiblity: work-around for the missing download manager

	/* (non-Javadoc)
	 * @see org.openbmap.utils.LegacyDownloader.LegacyDownloadListener#onDownloadCompleted(java.lang.String)
	 */
	@Override
	public void onDownloadCompleted(final String filename) {
		handleDownloads(filename);
	}

	/* (non-Javadoc)
	 * @see org.openbmap.utils.LegacyDownloader.LegacyDownloadListener#onDownloadFailed(java.lang.String)
	 */
	@Override
	public void onDownloadFailed(final String filename) {
		Log.e(TAG, "Download (compatiblity mode) failed " + filename);
		Toast.makeText(this, getResources().getString(R.string.download_failed) + " " + filename , Toast.LENGTH_LONG).show();

	}
	// [end]

}
