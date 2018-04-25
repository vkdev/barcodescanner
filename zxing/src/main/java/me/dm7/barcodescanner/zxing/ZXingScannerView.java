package me.dm7.barcodescanner.zxing;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;

import me.dm7.barcodescanner.core.BarcodeScannerView;
import me.dm7.barcodescanner.core.DisplayUtils;

public class ZXingScannerView extends BarcodeScannerView {
    private static final String TAG = "ZXingScannerView";

    public interface ResultHandler {
        void handleResult(Result rawResult);
    }

    private class ScanResult {
        byte[] data;
        int width;
        int height;
    }

    private static final int QUEUE_SIZE = 4;
    private static final long SCHEDULER_DELAY = 1000 / 30;//30 fps; xxx update FPS from parameters

    private MultiFormatReader mMultiFormatReader;
    public static final List<BarcodeFormat> ALL_FORMATS = new ArrayList<>();
    private List<BarcodeFormat> mFormats;
    private ResultHandler mResultHandler;
    private Handler mainHandler;
    private final Handler decoderHandler;
    private final Map<ScanResult, AsyncTask> decodeTasks;
    private final Queue<ScanResult> queue;
    private boolean isDecodeScheduled;

    static {
        ALL_FORMATS.add(BarcodeFormat.AZTEC);
        ALL_FORMATS.add(BarcodeFormat.CODABAR);
        ALL_FORMATS.add(BarcodeFormat.CODE_39);
        ALL_FORMATS.add(BarcodeFormat.CODE_93);
        ALL_FORMATS.add(BarcodeFormat.CODE_128);
        ALL_FORMATS.add(BarcodeFormat.DATA_MATRIX);
        ALL_FORMATS.add(BarcodeFormat.EAN_8);
        ALL_FORMATS.add(BarcodeFormat.EAN_13);
        ALL_FORMATS.add(BarcodeFormat.ITF);
        ALL_FORMATS.add(BarcodeFormat.MAXICODE);
        ALL_FORMATS.add(BarcodeFormat.PDF_417);
        ALL_FORMATS.add(BarcodeFormat.QR_CODE);
        ALL_FORMATS.add(BarcodeFormat.RSS_14);
        ALL_FORMATS.add(BarcodeFormat.RSS_EXPANDED);
        ALL_FORMATS.add(BarcodeFormat.UPC_A);
        ALL_FORMATS.add(BarcodeFormat.UPC_E);
        ALL_FORMATS.add(BarcodeFormat.UPC_EAN_EXTENSION);
    }

    public ZXingScannerView(Context context) {
        super(context);
        queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
        decodeTasks = new HashMap<>();
        decoderHandler = new Handler(context.getMainLooper());

        mainHandler = new Handler(context.getMainLooper());

        initMultiFormatReader();
    }

    public ZXingScannerView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
        decodeTasks = new HashMap<>();
        decoderHandler = new Handler(context.getMainLooper());

        mainHandler = new Handler(context.getMainLooper());

        initMultiFormatReader();
    }

    public void setFormats(List<BarcodeFormat> formats) {
        mFormats = formats;
        initMultiFormatReader();
    }

    public void setResultHandler(ResultHandler resultHandler) {
        mResultHandler = resultHandler;
    }

    public Collection<BarcodeFormat> getFormats() {
        if (mFormats == null) {
            return ALL_FORMATS;
        }
        return mFormats;
    }

    private void initMultiFormatReader() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, getFormats());
        mMultiFormatReader = new MultiFormatReader();
        mMultiFormatReader.setHints(hints);
    }


    private synchronized void scheduleDecoder(boolean force) {
        if (force || !isDecodeScheduled) {
            isDecodeScheduled = true;
            decoderHandler.postDelayed(new Runnable() {
                @Override
                public void run() {

                    synchronized (queue) {
                        if (decodeTasks.size() <= QUEUE_SIZE) {
                            ScanResult sr = queue.poll();
                            if (sr != null) {
                                DecodingTask task = new DecodingTask();
                                decodeTasks.put(sr, task);
                                task.execute(sr);
                            }
                        }
                    }

                    scheduleDecoder(true);
                }
            }, SCHEDULER_DELAY);
        }
    }

    @Override
    public void stopCamera() {
        stop();
        super.stopCamera();
    }

    @Override
    public void stopCameraPreview() {
        stop();
        super.stopCameraPreview();
    }

    private void stop() {
        synchronized (decoderHandler) {
            decoderHandler.removeCallbacksAndMessages(null);
            isDecodeScheduled = false;
        }

        synchronized (decodeTasks) {
            for (AsyncTask task : decodeTasks.values()) {
                task.cancel(true);
            }
            decodeTasks.clear();
        }
    }

    @Override
    public synchronized void onPreviewFrame(byte[] data, Camera camera) {
        if (mResultHandler == null) {
            Log.e(TAG, "Handler is null! onPreviewFrame");
            return;
        }

        scheduleDecoder(false);

        int width;
        int height;

        try {
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size size = parameters.getPreviewSize();
            width = size.width;
            height = size.height;
        } catch (Exception e) {
            Log.e(TAG, "Camera Error", e);
            return;
        }

        ScanResult scanResult = new ScanResult();
        scanResult.data = data;
        scanResult.width = width;
        scanResult.height = height;

        synchronized (queue) {
            if (queue.size() >= QUEUE_SIZE) {
                queue.poll();
            }

            queue.add(scanResult);
        }

        try {
            camera.setPreviewCallback(this);
        } catch (Exception e) {
            //camera already released
        }
    }

    public void resumeCameraPreview(ResultHandler resultHandler) {
        mResultHandler = resultHandler;
        super.resumeCameraPreview();
    }

    public PlanarYUVLuminanceSource buildLuminanceSource(byte[] data, int width, int height) {
        Rect rect = getFramingRectInPreview(width, height);
        if (rect == null) {
            return null;
        }
        // Go ahead and assume it's YUV rather than die.
        PlanarYUVLuminanceSource source = null;

        try {
            source = new PlanarYUVLuminanceSource(data, width, height, rect.left, rect.top,
                    rect.width(), rect.height(), false);
        } catch (Exception e) {
            Log.e(TAG, "PlanarYUVLuminanceSource error", e);
        }

        return source;
    }

    private void handleResult(final Result result) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                stop();
                stopCameraPreview();
                mResultHandler.handleResult(result);
            }
        });
    }

    private class DecodingTask extends AsyncTask<ScanResult, Void, Result> {

        private ScanResult sr;

        @Override
        protected Result doInBackground(ScanResult... scanResults) {
            if (scanResults == null || scanResults.length == 0) {
                return null;
            }

            sr = scanResults[0];

            try {
                if (DisplayUtils.getScreenOrientation(getContext()) == Configuration.ORIENTATION_PORTRAIT) {

                    int originalWidth = sr.width;
                    int originalHeight = sr.height;

                    int rotationCount = getRotationCount();
                    if (rotationCount == 1 || rotationCount == 3) {
                        int tmp = sr.width;
                        sr.width = sr.height;
                        sr.height = tmp;
                    }
                    sr.data = getRotatedData(sr.data, originalWidth, originalHeight);
                }

                Result rawResult = null;
                PlanarYUVLuminanceSource source = buildLuminanceSource(sr.data, sr.width, sr.height);

                if (source != null) {
                    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                    try {
                        rawResult = mMultiFormatReader.decodeWithState(bitmap);
                    } catch (ReaderException re) {
                        //Log.e(TAG, "onPreviewFrame", re);
                        // continue
                    } catch (NullPointerException npe) {
                        //Log.e(TAG, "onPreviewFrame", npe);
                        // This is terrible
                    } catch (ArrayIndexOutOfBoundsException aoe) {
                        //Log.e(TAG, "onPreviewFrame", aoe);
                    } finally {
                        mMultiFormatReader.reset();
                    }

                    if (rawResult == null) {
                        LuminanceSource invertedSource = source.invert();
                        bitmap = new BinaryBitmap(new HybridBinarizer(invertedSource));
                        try {
                            //Log.e(TAG, "BITMAP w = " + bitmap.getWidth() + "  h = " + bitmap.getHeight());
                            rawResult = mMultiFormatReader.decodeWithState(bitmap);
                        } catch (NotFoundException e) {
                            Log.e(TAG, "onPreviewFrame", e);
                            // continue
                        } finally {
                            mMultiFormatReader.reset();
                        }
                    }
                }

                return rawResult;
            } catch (RuntimeException e) {
                // TODO: Terrible hack. It is possible that this method is invoked after camera is released.
                Log.e(TAG, e.toString(), e);
            }

            return null;
        }

        @Override
        protected void onPostExecute(final Result result) {
            if (result != null && !isCancelled()) {
                handleResult(result);
            }

            synchronized (decodeTasks) {
                decodeTasks.remove(sr);
            }
        }
    }
}
