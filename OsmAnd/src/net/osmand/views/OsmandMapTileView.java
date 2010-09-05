package net.osmand.views;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.osmand.LogUtil;
import net.osmand.OsmandSettings;
import net.osmand.ResourceManager;
import net.osmand.activities.OsmandApplication;
import net.osmand.data.preparation.MapTileDownloader;
import net.osmand.data.preparation.MapTileDownloader.DownloadRequest;
import net.osmand.data.preparation.MapTileDownloader.IMapDownloaderCallback;
import net.osmand.map.IMapLocationListener;
import net.osmand.map.ITileSource;
import net.osmand.osm.LatLon;
import net.osmand.osm.MapUtils;
import net.osmand.views.AnimateDraggingMapThread.AnimateDraggingCallback;
import net.osmand.views.MultiTouchSupport.MultiTouchZoomListener;

import org.apache.commons.logging.Log;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Paint.Style;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.FloatMath;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.view.GestureDetector.OnDoubleTapListener;
import android.view.GestureDetector.OnGestureListener;
import android.view.SurfaceHolder.Callback;

public class OsmandMapTileView extends SurfaceView implements IMapDownloaderCallback, 
			Callback, AnimateDraggingCallback, OnGestureListener, OnDoubleTapListener, MultiTouchZoomListener {

	public static final int OVERZOOM_IN = 2;
	
	protected final int emptyTileDivisor = 16;
	
	public interface OnTrackBallListener{
		public boolean onTrackBallEvent(MotionEvent e);
	}
	
	public interface OnLongClickListener {
		public boolean onLongPressEvent(PointF point);
	}
	public interface OnClickListener {
		public boolean onPressEvent(PointF point);
	}
	
	
	protected static final Log log = LogUtil.getLog(OsmandMapTileView.class);
	/**
	 * zoom level - could be float to show zoomed tiles
	 */
	private float zoom = 3;
	
	private double longitude = 0d;

	private double latitude = 0d;
	
	private float rotate = 0;
	
	private int mapPosition;
	
	private boolean showMapPosition = true;
	
	// name of source map 
	private ITileSource map = null;
	
	private boolean showMapTiles = true;
	
	private IMapLocationListener locationListener;
	
	private OnLongClickListener onLongClickListener;
	
	private OnClickListener onClickListener;
	
	private OnTrackBallListener trackBallDelegate;
	
	private List<OsmandMapLayer> layers = new ArrayList<OsmandMapLayer>();
	private Map<OsmandMapLayer, Float> zOrders = new HashMap<OsmandMapLayer, Float>();
	
	// UI Part
	// handler to refresh map (in ui thread - not necessary in ui thread, but msg queue is desirable). 
	protected Handler handler = new Handler();
	
	private AnimateDraggingMapThread animatedDraggingThread;
	
	private float initialMultiTouchZoom;
	private PointF initialMultiTouchCenterPoint;
	private LatLon initialMultiTouchLocation;
	
	private GestureDetector gestureDetector;
	
	private MultiTouchSupport multiTouchSupport;
	
	Paint paintGrayFill;
	Paint paintWhiteFill;
	Paint paintCenter;
	Paint paintBitmap;

	private DisplayMetrics dm;

	private final OsmandApplication application;
	
	
	public OsmandMapTileView(Context context, AttributeSet attrs) {
		super(context, attrs);
		initView();
		application = (OsmandApplication) context.getApplicationContext();
	}
	
	public OsmandMapTileView(Context context) {
		super(context);
		initView();
		application = (OsmandApplication) context.getApplicationContext();
	}
	
	/////////////////////////////// INITIALIZING UI PART ///////////////////////////////////
	public void initView(){
		paintGrayFill = new Paint();
		paintGrayFill.setColor(Color.GRAY);
		paintGrayFill.setStyle(Style.FILL);
		// when map rotate
		paintGrayFill.setAntiAlias(true);

		paintWhiteFill = new Paint();
		paintWhiteFill.setColor(Color.WHITE);
		paintWhiteFill.setStyle(Style.FILL);
		// when map rotate
		paintWhiteFill.setAntiAlias(true);
		
		paintCenter = new Paint();
		paintCenter.setStyle(Style.STROKE);
		paintCenter.setColor(Color.rgb(60, 60, 60));
		paintCenter.setStrokeWidth(2);
		paintCenter.setAntiAlias(true);
		
		paintBitmap = new Paint();
		paintBitmap.setFilterBitmap(true);

		setClickable(true);
		setLongClickable(true);
		setFocusable(true);
		
		getHolder().addCallback(this);
		
		animatedDraggingThread = new AnimateDraggingMapThread();
		animatedDraggingThread.setCallback(this);
		gestureDetector = new GestureDetector(getContext(), this);
		multiTouchSupport = new MultiTouchSupport(getContext(), this);
		gestureDetector.setOnDoubleTapListener(this);
		
		WindowManager mgr = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
		dm = new DisplayMetrics();
		mgr.getDefaultDisplay().getMetrics(dm);
	}
	
	
	
	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		refreshMap();
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		refreshMap();
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
	}
	
	
	
	public void addLayer(OsmandMapLayer layer, float zOrder){
		int i=0;
		for(i=0; i<layers.size(); i++){
			if(zOrders.get(layers.get(i)) > zOrder){
				break;
			}
		}
		layer.initLayer(this);
		layers.add(i, layer);
		zOrders.put(layer, zOrder);
	}
	
	public void removeLayer(OsmandMapLayer layer){
		layers.remove(layer);
		zOrders.remove(layer);
		layer.destroyLayer();
	}
	
	public List<OsmandMapLayer> getLayers() {
		return layers;
	}
	
	public OsmandApplication getApplication() {
		return application;
	}

	
	/////////////////////////// NON UI PART (could be extracted in common) /////////////////////////////
	public float getTileSize() {
		float res = map == null ? 256 : map.getTileSize();
		if(zoom != (int) zoom){
			res *= (float) Math.pow(2, zoom - (int) zoom);  
		}
		if(dm != null && dm.density > 1f){
			res *= dm.density;
		}
		return res;
	}
	
	public int getSourceTileSize() {
		return map == null ? 256 : map.getTileSize();
	}
	

	public float getXTile(){
		return (float) MapUtils.getTileNumberX(zoom, longitude);
	}
	
	public float getYTile(){
		return (float) MapUtils.getTileNumberY(zoom, latitude);
	}
	
	
	public void setZoom(float zoom){
		if (map == null || ((map.getMaximumZoomSupported() + OVERZOOM_IN) >= zoom && map.getMinimumZoomSupported() <= zoom)) {
			animatedDraggingThread.stopAnimating();
			this.zoom = zoom;
			refreshMap();
		}
	}
	
	// for internal usage
	@Override
	public void zoomTo(float zoom, boolean notify) {
		if (map == null || ((map.getMaximumZoomSupported() + OVERZOOM_IN) >= zoom && map.getMinimumZoomSupported() <= zoom)) {
			this.zoom = zoom;
			refreshMap();
			if(notify && locationListener != null){
				locationListener.locationChanged(latitude, longitude, this);
			}
		}
	}
	
	public void setRotate(float rotate) {
		float dif = this.rotate - rotate;
		if (dif > 2 || dif < -2) {
			this.rotate = rotate;
			animatedDraggingThread.stopAnimating();
			refreshMap();
		}
	}

	
	public boolean isShowMapPosition() {
		return showMapPosition;
	}
	
	public void setShowMapPosition(boolean showMapPosition) {
		this.showMapPosition = showMapPosition;
	}
	
	public float getRotate() {
		return rotate;
	}
	
	public ITileSource getMap() {
		return map;
	}
	
	public void setMap(ITileSource map) {
		this.map = map;
		if(map.getMaximumZoomSupported() + OVERZOOM_IN < this.zoom){
			zoom = map.getMaximumZoomSupported() + OVERZOOM_IN;
		}
		if(map.getMinimumZoomSupported() > this.zoom){
			zoom = map.getMinimumZoomSupported();
		}
		refreshMap();
	}
	
	public void setLatLon(double latitude, double longitude){
		animatedDraggingThread.stopAnimating();
		this.latitude = latitude;
		this.longitude = longitude;
		refreshMap();
	}
	
	public double getLatitude() {
		return latitude;
	}
	
	public double getLongitude() {
		return longitude;
	}
	
	public int getZoom() {
		return (int) zoom;
	}
	
	public float getFloatZoom(){
		return zoom;
	}
	
	
	public void setMapLocationListener(IMapLocationListener l){
		locationListener = l;
	}

	/**
	 * Adds listener to control when map is dragging 
	 */
	public IMapLocationListener setMapLocationListener(){
		return locationListener;
	}
	
	
	//////////////////////////////// DRAWING MAP PART /////////////////////////////////////////////
	
	protected void drawEmptyTile(Canvas cvs, float x, float y, float ftileSize){
		float tileDiv = (ftileSize / emptyTileDivisor);
		for (int k1 = 0; k1 < emptyTileDivisor; k1++) {
			for (int k2 = 0; k2 < emptyTileDivisor; k2++) {
				float xk = x + tileDiv * k1;
				float yk = y + tileDiv * k2;
				if ((k1 + k2) % 2 == 0) {
					cvs.drawRect(xk, yk, xk + tileDiv, yk + tileDiv, paintGrayFill);
				} else {
					cvs.drawRect(xk, yk, xk + tileDiv, yk + tileDiv, paintWhiteFill);
				}
			}
		}
	}

	public int getCenterPointX(){
		return getWidth() / 2;
	}
	
	public int getCenterPointY(){
		if(mapPosition == OsmandSettings.BOTTOM_CONSTANT){
			return 3 * getHeight() / 4;
		}
		return getHeight() / 2;
	}
	
	public void setMapPosition(int type){
		this.mapPosition = type;
	}
	
	
	private void drawOverMap(Canvas canvas){
		int w = getCenterPointX();
		int h = getCenterPointY();
		canvas.restore();
		// create local copy
		ArrayList<OsmandMapLayer> local = new ArrayList<OsmandMapLayer>(layers);
		for (OsmandMapLayer layer : local) {
			canvas.save();
			if (!layer.drawInScreenPixels()) {
				canvas.rotate(rotate, w, h);
			}
			layer.onDraw(canvas);
			canvas.restore();
		}
		if (showMapPosition) {
			canvas.drawCircle(w, h, 3 * dm.density, paintCenter);
			canvas.drawCircle(w, h, 7 * dm.density, paintCenter);
		}
	}
	
	public void calculateTileRectangle(Rect pixRect, float cx, float cy, float ctilex, float ctiley, RectF tileRect){
		float x1 = calcDiffTileX(pixRect.left - cx, pixRect.top - cy);
		float x2 = calcDiffTileX(pixRect.left - cx, pixRect.bottom - cy);
		float x3 = calcDiffTileX(pixRect.right - cx, pixRect.top - cy);
		float x4 = calcDiffTileX(pixRect.right - cx, pixRect.bottom - cy);
		float y1 = calcDiffTileY(pixRect.left - cx, pixRect.top - cy);
		float y2 = calcDiffTileY(pixRect.left - cx, pixRect.bottom - cy);
		float y3 = calcDiffTileY(pixRect.right - cx, pixRect.top - cy);
		float y4 = calcDiffTileY(pixRect.right - cx, pixRect.bottom - cy);
		float l = Math.min(Math.min(x1, x2), Math.min(x3, x4)) + ctilex;
		float r = Math.max(Math.max(x1, x2), Math.max(x3, x4)) + ctilex;
		float t = Math.min(Math.min(y1, y2), Math.min(y3, y4)) + ctiley;
		float b = Math.max(Math.max(y1, y2), Math.max(y3, y4)) + ctiley;
		tileRect.set(l, t, r, b);
	}
	
	public void calculatePixelRectangle(Rect pixelRect, float cx, float cy, float ctilex, float ctiley, RectF tileRect){
		float x1 = calcDiffPixelX(tileRect.left - ctilex, tileRect.top - ctiley);
		float x2 = calcDiffPixelX(tileRect.left - ctilex, tileRect.bottom - ctiley);
		float x3 = calcDiffPixelX(tileRect.right - ctilex, tileRect.top - ctiley);
		float x4 = calcDiffPixelX(tileRect.right - ctilex, tileRect.bottom - ctiley);
		float y1 = calcDiffPixelY(tileRect.left - ctilex, tileRect.top - ctiley);
		float y2 = calcDiffPixelY(tileRect.left - ctilex, tileRect.bottom - ctiley);
		float y3 = calcDiffPixelY(tileRect.right - ctilex, tileRect.top - ctiley);
		float y4 = calcDiffPixelY(tileRect.right - ctilex, tileRect.bottom - ctiley);
		int l = Math.round(Math.min(Math.min(x1, x2), Math.min(x3, x4)) + cx);
		int r = Math.round(Math.max(Math.max(x1, x2), Math.max(x3, x4)) + cx);
		int t = Math.round(Math.min(Math.min(y1, y2), Math.min(y3, y4)) + cy);
		int b = Math.round(Math.max(Math.max(y1, y2), Math.max(y3, y4)) + cy);
		pixelRect.set(l, t, r, b);
	}
	
	// used only to save space & reuse 
	protected RectF tilesRect = new RectF();
	protected Rect boundsRect = new Rect();
	protected RectF bitmapToDraw = new RectF();
	protected Rect bitmapToZoom = new Rect();
	
	private void refreshMapInternal(){
		if(handler.hasMessages(1)){
			return;
		}
		
		if (OsmandSettings.isUsingInternetToDownloadTiles(getContext())) {
			 MapTileDownloader.getInstance().refuseAllPreviousRequests();
		}
		float ftileSize = getTileSize();
		int tileSize = map == null ? 256 : map.getTileSize();
		float tileX = getXTile();
		float tileY = getYTile();
		float w = getCenterPointX();
		float h = getCenterPointY();
		
		SurfaceHolder holder = getHolder();
		synchronized (holder) {
			int nzoom = (int) zoom;
			Canvas canvas = holder.lockCanvas();
			if (canvas != null) {
				canvas.save();
				boundsRect.set(0, 0, getWidth(), getHeight());
				canvas.drawRect(boundsRect, paintWhiteFill);
				canvas.rotate(rotate, w , h);
				try {
					if (showMapTiles) {
						ResourceManager mgr = getApplication().getResourceManager();
						boolean useInternet = OsmandSettings.isUsingInternetToDownloadTiles(getContext())
								&& map.couldBeDownloadedFromInternet();
						int maxLevel = Math.min(OsmandSettings.getMaximumLevelToDownloadTile(getContext()), map.getMaximumZoomSupported());
						
						calculateTileRectangle(boundsRect, w, h, tileX, tileY, tilesRect);
						int left = (int) FloatMath.floor(tilesRect.left);
						int top = (int) FloatMath.floor(tilesRect.top);
						int width = (int) (FloatMath.ceil(tilesRect.right) - left);
						int height = (int) (FloatMath.ceil(tilesRect.bottom) - top);
						for (int i = 0; i < width; i++) {
							for (int j = 0; j < height; j++) {
								float x1 = (i + left - tileX) * ftileSize + w;
								float y1 = (j + top - tileY) * ftileSize + h;
								String ordImgTile = mgr.calculateTileId(map, left + i, top + j, nzoom);
								// asking tile image async
								boolean imgExist = mgr.tileExistOnFileSystem(ordImgTile, map, left + i, top + j, nzoom);
								Bitmap bmp = null;
								boolean originalBeLoaded = useInternet && nzoom <= maxLevel;
								if (imgExist || originalBeLoaded) {
									bmp = mgr.getTileImageForMapAsync(ordImgTile, map, left + i, top + j, nzoom, useInternet);
								}
								if (bmp == null) {
									int div = 2;
									// asking if there is small version of the map (in cache)
									String imgTile2 = mgr.calculateTileId(map, (left + i) / 2, (top + j) / 2, nzoom - 1);
									String imgTile4 = mgr.calculateTileId(map, (left + i) / 4, (top + j) / 4, nzoom - 2);
									if (originalBeLoaded || imgExist) {
										bmp = mgr.getTileImageFromCache(imgTile2);
										div = 2;
										if (bmp == null) {
											bmp = mgr.getTileImageFromCache(imgTile4);
											div = 4;
										}
									}
									if (!originalBeLoaded && !imgExist) {
										if (mgr.tileExistOnFileSystem(imgTile2, map, (left + i) / 2, (top + j) / 2, nzoom - 1)
												|| (useInternet && nzoom - 1 <= maxLevel)) {
											bmp = mgr.getTileImageForMapAsync(imgTile2, map, (left + i) / 2, (top + j) / 2, nzoom - 1,
													useInternet);
											div = 2;
										} else if (mgr.tileExistOnFileSystem(imgTile4, map, (left + i) / 4, (top + j) / 4, nzoom - 2)
												|| (useInternet && nzoom - 2 <= maxLevel)) {
											bmp = mgr.getTileImageForMapAsync(imgTile4, map, (left + i) / 4, (top + j) / 4, nzoom - 2,
													useInternet);
											div = 4;
										}
									}

									if (bmp == null) {
										drawEmptyTile(canvas, x1, y1, ftileSize);
									} else {
										int xZoom = ((left + i) % div) * tileSize / div;
										int yZoom = ((top + j) % div) * tileSize / div;
										;
										bitmapToZoom.set(xZoom, yZoom, xZoom + tileSize / div, yZoom + tileSize / div);
										bitmapToDraw.set(x1, y1, x1 + ftileSize, y1 + ftileSize);
										canvas.drawBitmap(bmp, bitmapToZoom, bitmapToDraw, paintBitmap);
									}
								} else {
									bitmapToZoom.set(0, 0, map.getTileSize(), map.getTileSize());
									bitmapToDraw.set(x1, y1, x1 + ftileSize, y1 + ftileSize);
									canvas.drawBitmap(bmp, bitmapToZoom, bitmapToDraw, paintBitmap);
								}
							}
						}
					}
					drawOverMap(canvas);
				} finally {
					holder.unlockCanvasAndPost(canvas);
				}
			}
		}
		
	}
	
	public boolean mapIsRefreshing(){
		return handler.hasMessages(1);
	}
	
	public boolean mapIsAnimating(){
		return animatedDraggingThread != null && animatedDraggingThread.isAnimating();
	}
	// this method could be called in non UI thread
	public void refreshMap() {
		if(!handler.hasMessages(1)){
			Message msg = Message.obtain(handler, new Runnable(){
				@Override
				public void run() { 
					refreshMapInternal();
				}
			});
			msg.what = 1;
			handler.sendMessageDelayed(msg, 20);
		}
	}
	
	
	public void tileDownloaded(DownloadRequest request) {
		if (request == null || rotate != 0 ) {
			// if image is rotated call refresh the whole canvas
    		// because we can't find dirty rectangular region but all pixels should be drawn
			
			// we don't know exact images were changed
			refreshMap();
			return;
		}
		if(request.error){
			return;
		}
		if (request.zoom != this.zoom) {
			return;
		}
		float w = getCenterPointX();
		float h = getCenterPointY();
		float tileX = getXTile();
		float tileY = getYTile();

		SurfaceHolder holder = getHolder();
		synchronized (holder) {
			tilesRect.set(request.xTile, request.yTile, request.xTile + 1, request.yTile + 1);
			calculatePixelRectangle(boundsRect, w, h, tileX, tileY, tilesRect);
			
			if(boundsRect.left > getWidth() || boundsRect.right < 0 || boundsRect.bottom < 0 || boundsRect.top > getHeight()){
				return;
			}
			
			Canvas canvas = holder.lockCanvas(boundsRect);
			if (canvas != null) {
				canvas.save();
				canvas.rotate(rotate, w , h);
				
				try {
					if (showMapTiles) {
						ResourceManager mgr = getApplication().getResourceManager();
						Bitmap bmp = mgr.getTileImageForMapSync(null, map, request.xTile, request.yTile, request.zoom, false);
						float x = (request.xTile - tileX) * getTileSize() + w;
						float y = (request.yTile - tileY) * getTileSize() + h;
						float tileSize = getTileSize();
						if (bmp == null) {
							drawEmptyTile(canvas, x, y, tileSize);
						} else {
							bitmapToZoom.set(0, 0, getSourceTileSize(), getSourceTileSize());
							bitmapToDraw.set(x, y, x + tileSize, y + tileSize);
							canvas.drawBitmap(bmp, bitmapToZoom, bitmapToDraw, paintBitmap);
						}
					} else {
						boundsRect.set(0, 0, getWidth(), getHeight());
						canvas.drawRect(boundsRect, paintWhiteFill);
					}
					drawOverMap(canvas);
				} finally {
					holder.unlockCanvasAndPost(canvas);
				}
			}
			
		}
	}
	

	
	/////////////////////////////////// DRAGGING PART ///////////////////////////////////////
	public float calcDiffTileY(float dx, float dy){
		float rad = (float) Math.toRadians(rotate);
		return (-FloatMath.sin(rad) * dx + FloatMath.cos(rad) * dy) / (getTileSize() );
	}
	
	public float calcDiffTileX(float dx, float dy){
		float rad = (float) Math.toRadians(rotate);
		return (FloatMath.cos(rad) * dx + FloatMath.sin(rad) * dy) / (getTileSize() );
	}
	
	public float calcDiffPixelY(float dTileX, float dTileY){
		float rad = (float) Math.toRadians(rotate);
		return (FloatMath.sin(rad) * dTileX + FloatMath.cos(rad) * dTileY) * getTileSize() ;
	}
	
	public float calcDiffPixelX(float dTileX, float dTileY){
		float rad = (float) Math.toRadians(rotate);
		return (FloatMath.cos(rad) * dTileX - FloatMath.sin(rad) * dTileY) * getTileSize() ;
	}
	
	/**
	 * These methods do not consider rotating
	 */
	public int getMapXForPoint(double longitude){
		double tileX = MapUtils.getTileNumberX(zoom, longitude);
		return (int) ((tileX - getXTile()) * getTileSize()+ getCenterPointX());
	}
	public int getMapYForPoint(double latitude){
		double tileY = MapUtils.getTileNumberY(zoom, latitude);
		return (int) ((tileY - getYTile()) * getTileSize()  + getCenterPointY());
	}
	
	public int getRotatedMapXForPoint(double latitude, double longitude){
		int cx = getCenterPointX();
		double xTile = MapUtils.getTileNumberX(zoom, longitude);
		double yTile = MapUtils.getTileNumberY(zoom, latitude);
		return (int) (calcDiffPixelX((float) (xTile - getXTile()), (float) (yTile - getYTile())) + cx);
	}
	public int getRotatedMapYForPoint(double latitude, double longitude){
		int cy = getCenterPointY();
		double xTile = MapUtils.getTileNumberX(zoom, longitude);
		double yTile = MapUtils.getTileNumberY(zoom, latitude);
		return (int) (calcDiffPixelY((float) (xTile - getXTile()), (float) (yTile - getYTile())) + cy);
	}
	
	
	public boolean isPointOnTheRotatedMap(double latitude, double longitude){
		int cx = getCenterPointX();
		int cy = getCenterPointY();
		double xTile = MapUtils.getTileNumberX(zoom, longitude);
		double yTile = MapUtils.getTileNumberY(zoom, latitude);
		int newX = (int) (calcDiffPixelX((float) (xTile - getXTile()), (float) (yTile - getYTile())) + cx);
		int newY = (int) (calcDiffPixelY((float) (xTile - getXTile()), (float) (yTile - getYTile())) + cy);
		if(newX >= 0 && newX <= getWidth() && newY >=0 && newY <= getHeight()){
			return true;
		} 
		return false;
	}
	
	@Override
	public void dragTo(float fromX, float fromY, float toX, float toY, boolean notify){
		float dx = (fromX - toX) ; 
		float dy = (fromY - toY);
		moveTo(dx, dy);
		if(locationListener != null && notify){
			locationListener.locationChanged(latitude, longitude, this);
		}
	}
	
	@Override
	public void setLatLon(double latitude, double longitude, boolean notify) {
		this.latitude = latitude;
		this.longitude = longitude;
		refreshMap();
		if(locationListener != null && notify){
			locationListener.locationChanged(latitude, longitude, this);
		}
		
	}
	
	public void moveTo(float dx, float dy) {
		float fy = calcDiffTileY(dx, dy);
		float fx = calcDiffTileX(dx, dy);
		
		this.latitude = MapUtils.getLatitudeFromTile(zoom, getYTile() + fy);
		this.longitude = MapUtils.getLongitudeFromTile(zoom, getXTile() + fx);
		refreshMap();
		// do not notify here listener
		
	}
	
	
	
	
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if(event.getAction() == MotionEvent.ACTION_DOWN){
			animatedDraggingThread.stopAnimating();
		}
		if (!multiTouchSupport.onTouchEvent(event)) {
			/* return */gestureDetector.onTouchEvent(event);
		}
		return true;
	}
	
	
	@Override
	public boolean onTrackballEvent(MotionEvent event) {
		if(trackBallDelegate != null){
			trackBallDelegate.onTrackBallEvent(event);
		}
		return super.onTrackballEvent(event);
	}
	
	public void setTrackBallDelegate(OnTrackBallListener trackBallDelegate) {
		this.trackBallDelegate = trackBallDelegate;
	}
	
	
	public void setOnLongClickListener(OnLongClickListener l) {
		this.onLongClickListener = l;
	}
	
	public void setOnClickListener(OnClickListener l) {
		this.onClickListener = l;
	}


	@Override
	public boolean onDown(MotionEvent e) {
		// enable double tap animation	
//		animatedDraggingThread.stopAnimating();
		return false;
	}
	
	@Override
	public void onZoomEnded(float distance, float relativeToStart) {
		float dz = (float) (Math.log(relativeToStart) / Math.log(2) * 1.5);
		float calcZoom = initialMultiTouchZoom + dz;
		setZoom(Math.round(calcZoom));
		zoomPositionChanged(getFloatZoom());
	}
	
	public void setShowMapTiles(boolean visible){
		this.showMapTiles = visible;
	}
	
	public boolean isShowMapTiles() {
		return showMapTiles;
	}
	
	 
	@Override
	public void onZoomStarted(float distance, PointF centerPoint) {
		initialMultiTouchCenterPoint = centerPoint;
		initialMultiTouchLocation = getLatLonFromScreenPoint(centerPoint.x, centerPoint.y);
		initialMultiTouchZoom = zoom;
	}
	private void zoomPositionChanged(float calcZoom){
		float dtx = calcDiffTileX(getCenterPointX() - initialMultiTouchCenterPoint.x, getCenterPointY() - initialMultiTouchCenterPoint.y);
		float dty = calcDiffTileY(getCenterPointX() - initialMultiTouchCenterPoint.x, getCenterPointY() - initialMultiTouchCenterPoint.y);
		double tx = MapUtils.getTileNumberX(calcZoom, initialMultiTouchLocation.getLongitude());
		double ty = MapUtils.getTileNumberY(calcZoom, initialMultiTouchLocation.getLatitude());
		double lat = MapUtils.getLatitudeFromTile(calcZoom, ty + dty);
		double lon = MapUtils.getLongitudeFromTile(calcZoom, tx + dtx);
		setLatLon(lat, lon);
	}
	
	
	@Override
	public void onZooming(float distance, float relativeToStart) {
		float dz = (float) (Math.log(relativeToStart) / Math.log(2) * 1.5);
		float calcZoom = initialMultiTouchZoom + dz;
		if(Math.abs(calcZoom - zoom) > 0.05){
			setZoom(calcZoom);
			zoomPositionChanged(calcZoom);
		}
	}

	@Override
	public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
		if(Math.abs(e1.getX() - e2.getX()) + Math.abs(e1.getX() - e2.getX()) > 50 * dm.density){
			animatedDraggingThread.startDragging(Math.abs(velocityX/1000), Math.abs(velocityY/1000), e1.getX(), e1.getY(), e2.getX(), e2.getY());
		} else {
			onScroll(e1, e2, e1.getX() - e2.getX(), e1.getY() - e2.getY());
		}
		return true;
	}
	
	public AnimateDraggingMapThread getAnimatedDraggingThread() {
		return animatedDraggingThread;
	}

	@Override
	public void onLongPress(MotionEvent e) {
		if(multiTouchSupport.isInZoomMode()){
			return;
		}
		if(log.isDebugEnabled()){
			log.debug("On long click event "+  e.getX() + " " + e.getY()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		PointF point = new PointF(e.getX(), e.getY());
		for (int i = layers.size() - 1; i >= 0; i--) {
			if (layers.get(i).onLongPressEvent(point)) {
				return;
			}
		}
		if(onLongClickListener != null && onLongClickListener.onLongPressEvent(point)){
			return;
		}
	}
	
	

	@Override
	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
		dragTo(e2.getX() + distanceX, e2.getY() + distanceY, e2.getX(), e2.getY(), true);
		return true;
	}

	@Override
	public void onShowPress(MotionEvent e) {
	}

	@Override
	public boolean onSingleTapUp(MotionEvent e) {
		PointF point = new PointF(e.getX(), e.getY());
		if(log.isDebugEnabled()){
			log.debug("On click event "+  point.x + " " + point.y); //$NON-NLS-1$ //$NON-NLS-2$
		}
		for (int i = layers.size() - 1; i >= 0; i--) {
			if (layers.get(i).onTouchEvent(point)) {
				return true;
			}
		}
		if(onClickListener != null && onClickListener.onPressEvent(point)){
			return true;
		}
		return false;
	}
	
	
	
	public LatLon getLatLonFromScreenPoint(float x, float y){
		float dx = x - getCenterPointX();
		float dy = y - getCenterPointY();
		float fy = calcDiffTileY(dx, dy);
		float fx = calcDiffTileX(dx, dy);
		double latitude = MapUtils.getLatitudeFromTile(zoom, getYTile() + fy);
		double longitude = MapUtils.getLongitudeFromTile(zoom, getXTile() + fx);
		return new LatLon(latitude, longitude);
	}

	@Override
	public boolean onDoubleTap(MotionEvent e) {
		LatLon l = getLatLonFromScreenPoint(e.getX(), e.getY());
		getAnimatedDraggingThread().startMoving(getLatitude(), getLongitude(), 
				l.getLatitude(), l.getLongitude(), getZoom(), getZoom() + 1, getSourceTileSize(), getRotate(), true);
		return true;
	}

	@Override
	public boolean onDoubleTapEvent(MotionEvent e) {
		return false;
	}

	@Override
	public boolean onSingleTapConfirmed(MotionEvent e) {
		return false;
	}
	

	


}
