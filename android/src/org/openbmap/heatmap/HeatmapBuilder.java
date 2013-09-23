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

package org.openbmap.heatmap;

import java.util.ArrayList;

import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.util.MercatorProjection;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader.TileMode;
import android.os.AsyncTask;
import android.util.Log;

/**
 * Builds heat map bitmap
 */
public class HeatmapBuilder extends AsyncTask<Object, Integer, Boolean> {

	private static final String TAG = HeatmapBuilder.class.getSimpleName();

	private Canvas mCanvas;
	private Bitmap backbuffer;
	private int mWidth;
	private int mHeight;
	private float radius;

	private byte zoom;

	/**
	 * Used for callbacks.
	 */
	private HeatmapBuilderListener mListener;

	private BoundingBox	bbox;

	public interface HeatmapBuilderListener {
		void onHeatmapCompleted(Bitmap backbuffer);
		void onHeatmapFailed();
	}

	public HeatmapBuilder(final HeatmapBuilderListener listener, final int width, final int height, final BoundingBox bbox, final byte zoom, final float radius) {
		this.mListener = listener;
		this.bbox = bbox;
		this.backbuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
		this.zoom = zoom;
		this.mCanvas = new Canvas(backbuffer);
		Paint p = new Paint();
		p.setStyle(Paint.Style.FILL);

		p.setColor(Color.TRANSPARENT);

		this.mWidth = width;
		this.mHeight = height;
		this.mCanvas.drawRect(0, 0, width, height, p);

		this.radius = radius;
	}

	/**
	 * Background task.
	 * @return true on success, false if at least one file upload failed
	 */
	@Override
	protected final Boolean doInBackground(final Object... params) {
		//Point out = new Point(1, 1);
		if (params[0] == null) {
			throw new IllegalArgumentException("No heat points provided");
		}

		@SuppressWarnings("unchecked")
		ArrayList<HeatPoint> arrayList = ((ArrayList<HeatPoint>) params[0]);
		for (int i = 0; i < arrayList.size(); i++) {
			HeatPoint p = arrayList.get(i);

			if (p.longitude >= bbox.minLongitude && p.longitude <= bbox.maxLongitude
					&& p.latitude >= bbox.minLatitude && p.latitude <= bbox.maxLatitude) {
				float leftBorder = (float) MercatorProjection.longitudeToPixelX(bbox.minLongitude, zoom);
				float topBorder = (float) MercatorProjection.latitudeToPixelY(bbox.maxLatitude, zoom);
				
				float x = (float) (MercatorProjection.longitudeToPixelX(p.longitude, zoom) - leftBorder);
				float y = (float) (MercatorProjection.latitudeToPixelY(p.latitude, zoom) - topBorder);
				
				// Log.i(TAG, "X:" + x + " Y:" + y);
				addPoint(x, y, p.getIntensity());

				// skip loop if canceled..
				if (isCancelled()) {
					return false;
				}
			}
		}

		colorize(0, 0);

		return !isCancelled();
	}

	@Override
	protected final void onPostExecute(final Boolean success) {
		if (success) {
			if (mListener != null) {
				mListener.onHeatmapCompleted(backbuffer);
			}
			return;
		} else {
			if (mListener != null) {
				Log.e(TAG, "Heat-map error or thread canceled");
				mListener.onHeatmapFailed();
			}
			return;
		}
	}


	private void addPoint(final float x, final float y, final int times) {
		RadialGradient g = new RadialGradient(x, y, radius, Color.argb(Math.max(10 * times, 255), 0, 0, 0), Color.TRANSPARENT, TileMode.CLAMP);

		Paint gp = new Paint();
		gp.setShader(g);

		mCanvas.drawCircle(x, y, radius, gp);
	}

	private void colorize(final float x, final float y) {
		int[] pixels = new int[(int) (this.mWidth * this.mHeight)];

		backbuffer.getPixels(pixels, 0, this.mWidth, 0, 0, this.mWidth, this.mHeight);

		for (int i = 0; i < pixels.length; i++) {
			int r = 0, g = 0, b = 0, tmp = 0;
			int alpha = pixels[i] >>> 24;

					if (alpha == 0) {
						continue;
					}

					if (alpha <= 255 && alpha >= 235) {
						tmp = 255 - alpha;
						r = 255 - tmp;
						g = tmp * 12;
					} else if (alpha <= 234 && alpha >= 200) {

						tmp = 234 - alpha;
						r = 255 - (tmp * 8);
						g = 255;

					} else if (alpha <= 199 && alpha >= 150) {
						tmp = 199 - alpha;
						g = 255;
						b = tmp * 5;

					} else if (alpha <= 149 && alpha >= 100) {
						tmp = 149 - alpha;
						g = 255 - (tmp * 5);
						b = 255;
					} else {
						b = 255;
					}
					pixels[i] = Color.argb((int) alpha / 2, r, g, b);
		}

		backbuffer.setPixels(pixels, 0, this.mWidth, 0, 0, this.mWidth, this.mHeight);
	}


}