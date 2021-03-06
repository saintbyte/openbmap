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

package org.openbmap.soapclient;

import java.io.File;
import java.io.IOException;

import org.openbmap.R;
import org.openbmap.utils.MediaScanner;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;

/**
 * Manages export gpx process
 */
public class ExportGpxTask extends AsyncTask<Void, Object, Boolean> {

	private static final String TAG = ExportGpxTask.class.getSimpleName();

	/**
	 * Session Id to export
	 */
	private final int mSession;

	/**
	 * Message in case of an error
	 */
	private final String errorMsg = null;

	/**
	 * Used for callbacks.
	 */
	private final ExportGpxTaskListener mListener;

	private Context mAppContext;

	/**
	 * Folder where GPX file is created
	 */
	private final String mPath;

	/**
	 * GPX filename
	 */
	private final String mFilename;
	
	public interface ExportGpxTaskListener {
		void onExportGpxProgressUpdate(Object[] values);
		void onExportGpxCompleted(final int id);
		void onExportGpxFailed(final int id, final String error);
	}

	//http://stackoverflow.com/questions/9573855/second-instance-of-activity-after-orientation-change
	/**
	 * 
	 * @param context
	 * @param listener
	 * @param session
	 * @param targetPath
	 */
	public ExportGpxTask(final Context context, final ExportGpxTaskListener listener, final int session,
			final String path, final String filename) {
		this.mAppContext = context.getApplicationContext();
		this.mSession = session;
		this.mPath = path;
		this.mFilename = filename;
		this.mListener = listener;
	}

	/**
	 * Builds cell xml files and saves/uploads them
	 */
	@SuppressLint("NewApi")
	@Override
	protected final Boolean doInBackground(final Void... params) {
		Boolean success = false;
		
		publishProgress(mAppContext.getResources().getString(R.string.please_stay_patient), mAppContext.getResources().getString(R.string.exporting_gpx), 0);
		final GpxExporter gpx = new GpxExporter(mAppContext, mSession);
		final File target = new File(mPath, mFilename);
		try {
			gpx.doExport(mFilename, target);
			success = true;
		} catch (final IOException e) {
			Log.e(TAG, "Can't write gpx file " + mPath + File.separator + mFilename);
		}
		
		return success;
	}

	/**
	 * Updates progress bar.
	 * @param values[0] contains title (as string)
	 * @param values[1] contains message (as string)
	 * @param values[1] contains progress (as int)
	 */
	@Override
	protected final void onProgressUpdate(final Object... values) {
		if (mListener != null) {
			mListener.onExportGpxProgressUpdate(values);
		}
	}

	@SuppressLint("NewApi")
	@Override
	protected final void onPostExecute(final Boolean success) {

		// rescan SD card on honeycomb devices
		// Otherwise files may not be visible when connected to desktop pc (MTP cache problem)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			Log.i(TAG, "Re-indexing SD card temp folder");
			new MediaScanner(mAppContext, new File(mPath));
		}

		if (success) {
			if (mListener != null) {
				mListener.onExportGpxCompleted(mSession);
			}
			return;
		} else {
			if (mListener != null) {
				mListener.onExportGpxFailed(mSession, errorMsg);
			}
			return;
		}
	}

	/**
	 * @param sessionActivity
	 */
	public void setContext(final Context context) {
		mAppContext = context;
	}

}
