/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.google.android.cameraview;

import android.annotation.SuppressLint;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.view.SurfaceHolder;

import androidx.collection.SparseArrayCompat;

import com.facebook.react.bridge.ReadableMap;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.atomic.AtomicBoolean;


@SuppressWarnings("deprecation")
public class Camera1 extends CameraViewImpl implements MediaRecorder.OnInfoListener,
        MediaRecorder.OnErrorListener, Camera.PreviewCallback {

    private static final int INVALID_CAMERA_ID = -1;

    private static final SparseArrayCompat<String> FLASH_MODES = new SparseArrayCompat<>();

    static {
        FLASH_MODES.put(Constants.FLASH_OFF, Camera.Parameters.FLASH_MODE_OFF);
        FLASH_MODES.put(Constants.FLASH_ON, Camera.Parameters.FLASH_MODE_ON);
        FLASH_MODES.put(Constants.FLASH_TORCH, Camera.Parameters.FLASH_MODE_TORCH);
        FLASH_MODES.put(Constants.FLASH_AUTO, Camera.Parameters.FLASH_MODE_AUTO);
        FLASH_MODES.put(Constants.FLASH_RED_EYE, Camera.Parameters.FLASH_MODE_RED_EYE);
    }

    private static final SparseArrayCompat<String> WB_MODES = new SparseArrayCompat<>();

    static {
        WB_MODES.put(Constants.WB_AUTO, Camera.Parameters.WHITE_BALANCE_AUTO);
        WB_MODES.put(Constants.WB_CLOUDY, Camera.Parameters.WHITE_BALANCE_CLOUDY_DAYLIGHT);
        WB_MODES.put(Constants.WB_SUNNY, Camera.Parameters.WHITE_BALANCE_DAYLIGHT);
        WB_MODES.put(Constants.WB_SHADOW, Camera.Parameters.WHITE_BALANCE_SHADE);
        WB_MODES.put(Constants.WB_FLUORESCENT, Camera.Parameters.WHITE_BALANCE_FLUORESCENT);
        WB_MODES.put(Constants.WB_INCANDESCENT, Camera.Parameters.WHITE_BALANCE_INCANDESCENT);
    }

    private int mCameraId;

    private final AtomicBoolean isPictureCaptureInProgress = new AtomicBoolean(false);

    public static Camera mCamera;

    private Camera.Parameters mCameraParameters;

    private final Camera.CameraInfo mCameraInfo = new Camera.CameraInfo();

    private MediaRecorder mMediaRecorder;

    private String mVideoPath;

    private boolean mIsRecording;

    private final SizeMap mPreviewSizes = new SizeMap();

    private boolean mIsPreviewActive = false;

    private final SizeMap mPictureSizes = new SizeMap();

    private Size mPictureSize;

    private AspectRatio mAspectRatio;

    private boolean mShowingPreview;

    private boolean mAutoFocus;

    private int mFacing;

    private int mFlash;

    private int mDisplayOrientation;

    private int mDeviceOrientation;

    private int mOrientation = Constants.ORIENTATION_AUTO;

    private float mZoom;

    private int mWhiteBalance;

    private boolean mIsScanning;

    private SurfaceTexture mPreviewTexture;

    Camera1(Callback callback, PreviewImpl preview) {
        super(callback, preview);
        preview.setCallback(new PreviewImpl.Callback() {
            @Override
            public void onSurfaceChanged() {
                if (mCamera != null) {
                    setUpPreview();
                    mIsPreviewActive = false;
                    adjustCameraParameters();
                }
            }

            @Override
            public void onSurfaceDestroyed() {
                stop();
            }
        });
    }

    @Override
    boolean start() {
        chooseCamera();
        if (!openCamera()) {
            mCallback.onMountError();
            // returning false will result in invoking this method again
            return true;
        }
        if (mPreview.isReady()) {
            setUpPreview();
        }
        mShowingPreview = true;
        startCameraPreview();
        return true;
    }

    @Override
    void stop() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallback(null);
        }
        mShowingPreview = false;
        if (mMediaRecorder != null) {
            mMediaRecorder.stop();
            mMediaRecorder.release();
            mMediaRecorder = null;

            if (mIsRecording) {
                int deviceOrientation = displayOrientationToOrientationEnum(mDeviceOrientation);
                mCallback.onVideoRecorded(mVideoPath, mOrientation != Constants.ORIENTATION_AUTO ? mOrientation : deviceOrientation, deviceOrientation);
                mIsRecording = false;
            }
        }
        releaseCamera();
    }

    // Suppresses Camera#setPreviewTexture
    @SuppressLint("NewApi")
    void setUpPreview() {
        try {
            if (mPreviewTexture != null) {
                mCamera.setPreviewTexture(mPreviewTexture);
            } else if (mPreview.getOutputClass() == SurfaceHolder.class) {
                final boolean needsToStopPreview = mShowingPreview && Build.VERSION.SDK_INT < 14;
                if (needsToStopPreview) {
                    mCamera.stopPreview();
                    mIsPreviewActive = false;
                }
                mCamera.setPreviewDisplay(mPreview.getSurfaceHolder());
                if (needsToStopPreview) {
                    startCameraPreview();
                }
            } else {
                mCamera.setPreviewTexture((SurfaceTexture) mPreview.getSurfaceTexture());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void startCameraPreview() {
        mCamera.startPreview();
        mIsPreviewActive = true;
        if (mIsScanning) {
            mCamera.setPreviewCallback(this);
        }
    }

    @Override
    public void resumePreview() {
        startCameraPreview();
    }

    @Override
    public void pausePreview() {
        mCamera.stopPreview();
        mIsPreviewActive = false;
    }

    @Override
    boolean isCameraOpened() {
        return mCamera != null;
    }

    @Override
    void setFacing(int facing) {
        if (mFacing == facing) {
            return;
        }
        mFacing = facing;
        if (isCameraOpened()) {
            stop();
            start();
        }
    }

    @Override
    int getFacing() {
        return mFacing;
    }

    @Override
    Set<AspectRatio> getSupportedAspectRatios() {
        SizeMap idealAspectRatios = mPreviewSizes;
        for (AspectRatio aspectRatio : idealAspectRatios.ratios()) {
            if (mPictureSizes.sizes(aspectRatio) == null) {
                idealAspectRatios.remove(aspectRatio);
            }
        }
        return idealAspectRatios.ratios();
    }

    @Override
    SortedSet<Size> getAvailablePictureSizes(AspectRatio ratio) {
        return mPictureSizes.sizes(ratio);
    }

    @Override
    void setPictureSize(Size size) {
        if (size == null) {
            if (mAspectRatio == null) {
                return;
            }
            SortedSet<Size> sizes = mPictureSizes.sizes(mAspectRatio);
            if(sizes != null && !sizes.isEmpty())
            {
                mPictureSize = sizes.last();
            }
        } else {
            mPictureSize = size;
        }
        if (mCameraParameters != null && mCamera != null) {
            mCameraParameters.setPictureSize(mPictureSize.getWidth(), mPictureSize.getHeight());
            mCamera.setParameters(mCameraParameters);
        }
    }

    @Override
    Size getPictureSize() {
        return mPictureSize;
    }

    @Override
    boolean setAspectRatio(AspectRatio ratio) {
        if (mAspectRatio == null || !isCameraOpened()) {
            // Handle this later when camera is opened
            mAspectRatio = ratio;
            return true;
        } else if (!mAspectRatio.equals(ratio)) {
            final Set<Size> sizes = mPreviewSizes.sizes(ratio);
            if (sizes == null) {
                throw new UnsupportedOperationException(ratio + " is not supported");
            } else {
                mAspectRatio = ratio;
                adjustCameraParameters();
                return true;
            }
        }
        return false;
    }

    @Override
    AspectRatio getAspectRatio() {
        return mAspectRatio;
    }

    @Override
    void setAutoFocus(boolean autoFocus) {
        if (mAutoFocus == autoFocus) {
            return;
        }
        if (setAutoFocusInternal(autoFocus)) {
            mCamera.setParameters(mCameraParameters);
        }
    }

    @Override
    boolean getAutoFocus() {
        if (!isCameraOpened()) {
            return mAutoFocus;
        }
        String focusMode = mCameraParameters.getFocusMode();
        return focusMode != null && focusMode.contains("continuous");
    }

    @Override
    void setFlash(int flash) {
        if (flash == mFlash) {
            return;
        }
        if (setFlashInternal(flash)) {
            mCamera.setParameters(mCameraParameters);
        }
    }

    @Override
    int getFlash() {
        return mFlash;
    }

    @Override
    public void setFocusDepth(float value) {
        // not supported for Camera1
    }

    @Override
    float getFocusDepth() {
        return 0;
    }

    @Override
    void setZoom(float zoom) {
        if (zoom == mZoom) {
            return;
        }
        if (setZoomInternal(zoom)) {
            mCamera.setParameters(mCameraParameters);
        }
    }

    @Override
    float getZoom() {
        return mZoom;
    }

    @Override
    public void setWhiteBalance(int whiteBalance) {
        if (whiteBalance == mWhiteBalance) {
            return;
        }
        if (setWhiteBalanceInternal(whiteBalance)) {
            mCamera.setParameters(mCameraParameters);
        }
    }

    @Override
    public int getWhiteBalance() {
        return mWhiteBalance;
    }

    @Override
    void setScanning(boolean isScanning) {
        if (isScanning == mIsScanning) {
            return;
        }
        setScanningInternal(isScanning);
    }

    @Override
    boolean getScanning() {
        return mIsScanning;
    }

    @Override
    void takePicture(final ReadableMap options) {
        if (!isCameraOpened()) {
            throw new IllegalStateException(
                    "Camera is not ready. Call start() before takePicture().");
        }
        if (!mIsPreviewActive) {
            throw new IllegalStateException("Preview is paused - resume it before taking a picture.");
        }
        if (getAutoFocus()) {
            mCamera.cancelAutoFocus();
            mCamera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean success, Camera camera) {
                    takePictureInternal(options);
                }
            });
        } else {
            takePictureInternal(options);
        }
    }

    int orientationEnumToRotation(int orientation) {
        switch(orientation) {
            case Constants.ORIENTATION_UP:
                return 0;
            case Constants.ORIENTATION_DOWN:
                return 180;
            case Constants.ORIENTATION_LEFT:
                return 270;
            case Constants.ORIENTATION_RIGHT:
                return 90;
            default:
                return Constants.ORIENTATION_UP;
        }
    }

    int displayOrientationToOrientationEnum(int rotation) {
        switch (rotation) {
            case 0:
                return Constants.ORIENTATION_UP;
            case 90:
                return Constants.ORIENTATION_RIGHT;
            case 180:
                return Constants.ORIENTATION_DOWN;
            case 270:
                return Constants.ORIENTATION_LEFT;
            default:
                return 1;
        }
    }

    void takePictureInternal(final ReadableMap options) {
        if (!isPictureCaptureInProgress.getAndSet(true)) {

            if (options.hasKey("orientation") && options.getInt("orientation") != Constants.ORIENTATION_AUTO) {
                mOrientation = options.getInt("orientation");
                int rotation = orientationEnumToRotation(mOrientation);
                mCameraParameters.setRotation(calcCameraRotation(rotation));
                mCamera.setParameters(mCameraParameters);
            }

            mCamera.takePicture(null, null, null, new Camera.PictureCallback() {
                @Override
                public void onPictureTaken(byte[] data, Camera camera) {
                    isPictureCaptureInProgress.set(false);
                    camera.cancelAutoFocus();
                    if (options.hasKey("pauseAfterCapture") && !options.getBoolean("pauseAfterCapture")) {
                        camera.startPreview();
                        mIsPreviewActive = true;
                        if (mIsScanning) {
                            camera.setPreviewCallback(Camera1.this);
                        }
                    } else {
                        camera.stopPreview();
                        mIsPreviewActive = false;
                        camera.setPreviewCallback(null);
                    }

                    mOrientation = Constants.ORIENTATION_AUTO;
                    mCallback.onPictureTaken(data, displayOrientationToOrientationEnum(mDeviceOrientation));
                }
            });
        }
    }

    @Override
    boolean record(String path, int maxDuration, int maxFileSize, boolean recordAudio, CamcorderProfile profile, int orientation) {
        if (!mIsRecording) {
            if (orientation != Constants.ORIENTATION_AUTO) {
                mOrientation = orientation;
            }
            setUpMediaRecorder(path, maxDuration, maxFileSize, recordAudio, profile);
            try {
                mMediaRecorder.prepare();
                mMediaRecorder.start();
                mIsRecording = true;
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        return false;
    }

    @Override
    void stopRecording() {
        if (mIsRecording) {
            stopMediaRecorder();
            if (mCamera != null) {
                mCamera.lock();
            }
        }
    }

    @Override
    int getCameraOrientation() {
        return mCameraInfo.orientation;
    }

    @Override
    void setDisplayOrientation(int displayOrientation) {
        if (mDisplayOrientation == displayOrientation) {
            return;
        }
        mDisplayOrientation = displayOrientation;
        if (isCameraOpened()) {
            final boolean needsToStopPreview = mShowingPreview && Build.VERSION.SDK_INT < 14;
            if (needsToStopPreview) {
                mCamera.stopPreview();
                mIsPreviewActive = false;
            }
            mCamera.setDisplayOrientation(calcDisplayOrientation(displayOrientation));
            if (needsToStopPreview) {
                startCameraPreview();
            }
        }
    }

    @Override
    void setDeviceOrientation(int deviceOrientation) {
        if (mDeviceOrientation == deviceOrientation) {
            return;
        }
        mDeviceOrientation = deviceOrientation;
        if (isCameraOpened() && mOrientation == Constants.ORIENTATION_AUTO) {
            mCameraParameters.setRotation(calcCameraRotation(deviceOrientation));
            mCamera.setParameters(mCameraParameters);
        }
    }

    @Override
    public void setPreviewTexture(SurfaceTexture surfaceTexture) {
        try {
            if (mCamera == null) {
                mPreviewTexture = surfaceTexture;
                return;
            }

            mCamera.stopPreview();
            mIsPreviewActive = false;

            if (surfaceTexture == null) {
                mCamera.setPreviewTexture((SurfaceTexture) mPreview.getSurfaceTexture());
            } else {
                mCamera.setPreviewTexture(surfaceTexture);
            }

            mPreviewTexture = surfaceTexture;
            startCameraPreview();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Size getPreviewSize() {
        Camera.Size cameraSize = mCameraParameters.getPreviewSize();
        return new Size(cameraSize.width, cameraSize.height);
    }

    /**
     * This rewrites {@link #mCameraId} and {@link #mCameraInfo}.
     */
    private void chooseCamera() {
        for (int i = 0, count = Camera.getNumberOfCameras(); i < count; i++) {
            Camera.getCameraInfo(i, mCameraInfo);
            if (mCameraInfo.facing == mFacing) {
                mCameraId = i;
                return;
            }
        }
        mCameraId = INVALID_CAMERA_ID;
    }

    private boolean openCamera() {
        if (mCamera != null) {
            releaseCamera();
        }
        try {
            mCamera = Camera.open(mCameraId);
            mCameraParameters = mCamera.getParameters();
            // Supported preview sizes
            mPreviewSizes.clear();
            for (Camera.Size size : mCameraParameters.getSupportedPreviewSizes()) {
                mPreviewSizes.add(new Size(size.width, size.height));
            }
            // Supported picture sizes;
            mPictureSizes.clear();
            for (Camera.Size size : mCameraParameters.getSupportedPictureSizes()) {
                mPictureSizes.add(new Size(size.width, size.height));
            }
            // AspectRatio
            if (mAspectRatio == null) {
                mAspectRatio = Constants.DEFAULT_ASPECT_RATIO;
            }
            adjustCameraParameters();
            mCamera.setDisplayOrientation(calcDisplayOrientation(mDisplayOrientation));
            mCallback.onCameraOpened();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private AspectRatio chooseAspectRatio() {
        AspectRatio r = null;
        for (AspectRatio ratio : mPreviewSizes.ratios()) {
            r = ratio;
            if (ratio.equals(Constants.DEFAULT_ASPECT_RATIO)) {
                return ratio;
            }
        }
        return r;
    }

    void adjustCameraParameters() {
        SortedSet<Size> sizes = mPreviewSizes.sizes(mAspectRatio);
        if (sizes == null) { // Not supported
            mAspectRatio = chooseAspectRatio();
            sizes = mPreviewSizes.sizes(mAspectRatio);
        }
        Size size = chooseOptimalSize(sizes);

        // Always re-apply camera parameters
        if (mPictureSize == null) {
            mPictureSize = mPictureSizes.sizes(mAspectRatio).last();
        }
        if (mShowingPreview) {
            mCamera.stopPreview();
            mIsPreviewActive = false;
        }
        mCameraParameters.setPreviewSize(size.getWidth(), size.getHeight());
        mCameraParameters.setPictureSize(mPictureSize.getWidth(), mPictureSize.getHeight());
        if (mOrientation != Constants.ORIENTATION_AUTO) {
            mCameraParameters.setRotation(calcCameraRotation(orientationEnumToRotation(mOrientation)));
        } else {
            mCameraParameters.setRotation(calcCameraRotation(mDeviceOrientation));
        }

        setAutoFocusInternal(mAutoFocus);
        setFlashInternal(mFlash);
        setAspectRatio(mAspectRatio);
        setZoomInternal(mZoom);
        setWhiteBalanceInternal(mWhiteBalance);
        setScanningInternal(mIsScanning);
        mCamera.setParameters(mCameraParameters);
        if (mShowingPreview) {
            startCameraPreview();
        }
    }

    @SuppressWarnings("SuspiciousNameCombination")
    private Size chooseOptimalSize(SortedSet<Size> sizes) {
        if (!mPreview.isReady()) { // Not yet laid out
            return sizes.first(); // Return the smallest size
        }
        int desiredWidth;
        int desiredHeight;
        final int surfaceWidth = mPreview.getWidth();
        final int surfaceHeight = mPreview.getHeight();
        if (isLandscape(mDisplayOrientation)) {
            desiredWidth = surfaceHeight;
            desiredHeight = surfaceWidth;
        } else {
            desiredWidth = surfaceWidth;
            desiredHeight = surfaceHeight;
        }
        Size result = null;
        for (Size size : sizes) { // Iterate from small to large
            if (desiredWidth <= size.getWidth() && desiredHeight <= size.getHeight()) {
                return size;

            }
            result = size;
        }
        return result;
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.release();
            mCamera = null;
            mPictureSize = null;
            mCallback.onCameraClosed();
        }
    }

    /**
     * Calculate display orientation
     * https://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
     *
     * This calculation is used for orienting the preview
     *
     * Note: This is not the same calculation as the camera rotation
     *
     * @param screenOrientationDegrees Screen orientation in degrees
     * @return Number of degrees required to rotate preview
     */
    private int calcDisplayOrientation(int screenOrientationDegrees) {
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - (mCameraInfo.orientation + screenOrientationDegrees) % 360) % 360;
        } else {  // back-facing
            return (mCameraInfo.orientation - screenOrientationDegrees + 360) % 360;
        }
    }

    /**
     * Calculate camera rotation
     *
     * This calculation is applied to the output JPEG either via Exif Orientation tag
     * or by actually transforming the bitmap. (Determined by vendor camera API implementation)
     *
     * Note: This is not the same calculation as the display orientation
     *
     * @param screenOrientationDegrees Screen orientation in degrees
     * @return Number of degrees to rotate image in order for it to view correctly.
     */
    private int calcCameraRotation(int screenOrientationDegrees) {
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
            return (mCameraInfo.orientation + screenOrientationDegrees) % 360;
        }
        // back-facing
        final int landscapeFlip = isLandscape(screenOrientationDegrees) ? 180 : 0;
        return (mCameraInfo.orientation + screenOrientationDegrees + landscapeFlip) % 360;
    }

    /**
     * Test if the supplied orientation is in landscape.
     *
     * @param orientationDegrees Orientation in degrees (0,90,180,270)
     * @return True if in landscape, false if portrait
     */
    private boolean isLandscape(int orientationDegrees) {
        return (orientationDegrees == Constants.LANDSCAPE_90 ||
                orientationDegrees == Constants.LANDSCAPE_270);
    }

    /**
     * @return {@code true} if {@link #mCameraParameters} was modified.
     */
    private boolean setAutoFocusInternal(boolean autoFocus) {
        mAutoFocus = autoFocus;
        if (isCameraOpened()) {
            final List<String> modes = mCameraParameters.getSupportedFocusModes();
            if (autoFocus && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (modes.contains(Camera.Parameters.FOCUS_MODE_FIXED)) {
                mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
            } else if (modes.contains(Camera.Parameters.FOCUS_MODE_INFINITY)) {
                mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
            } else {
                mCameraParameters.setFocusMode(modes.get(0));
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * @return {@code true} if {@link #mCameraParameters} was modified.
     */
    private boolean setFlashInternal(int flash) {
        if (isCameraOpened()) {
            List<String> modes = mCameraParameters.getSupportedFlashModes();
            String mode = FLASH_MODES.get(flash);
            if(modes == null) {
                return false;
            }
            if (modes.contains(mode)) {
                mCameraParameters.setFlashMode(mode);
                mFlash = flash;
                return true;
            }
            String currentMode = FLASH_MODES.get(mFlash);
            if (!modes.contains(currentMode)) {
                mCameraParameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                return true;
            }
            return false;
        } else {
            mFlash = flash;
            return false;
        }
    }

    /**
     * @return {@code true} if {@link #mCameraParameters} was modified.
     */
    private boolean setZoomInternal(float zoom) {
        if (isCameraOpened() && mCameraParameters.isZoomSupported()) {
            int maxZoom = mCameraParameters.getMaxZoom();
            int scaledValue = (int) (zoom * maxZoom);
            mCameraParameters.setZoom(scaledValue);
            mZoom = zoom;
            return true;
        } else {
            mZoom = zoom;
            return false;
        }
    }

    /**
     * @return {@code true} if {@link #mCameraParameters} was modified.
     */
    private boolean setWhiteBalanceInternal(int whiteBalance) {
        mWhiteBalance = whiteBalance;
        if (isCameraOpened()) {
            final List<String> modes = mCameraParameters.getSupportedWhiteBalance();
            String mode = WB_MODES.get(whiteBalance);
            if (modes != null && modes.contains(mode)) {
                mCameraParameters.setWhiteBalance(mode);
                return true;
            }
            String currentMode = WB_MODES.get(mWhiteBalance);
            if (modes == null || !modes.contains(currentMode)) {
                mCameraParameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
                return true;
            }
            return false;
        } else {
            return false;
        }
    }

    private void setScanningInternal(boolean isScanning) {
        mIsScanning = isScanning;
        if (isCameraOpened()) {
            if (mIsScanning) {
                mCamera.setPreviewCallback(this);
            } else {
                mCamera.setPreviewCallback(null);
            }
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        Camera.Size previewSize = mCameraParameters.getPreviewSize();
        mCallback.onFramePreview(data, previewSize.width, previewSize.height, mDeviceOrientation);
    }

    private void setUpMediaRecorder(String path, int maxDuration, int maxFileSize, boolean recordAudio, CamcorderProfile profile) {
        mMediaRecorder = new MediaRecorder();
        mCamera.unlock();

        mMediaRecorder.setCamera(mCamera);

        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        if (recordAudio) {
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        }

        mMediaRecorder.setOutputFile(path);
        mVideoPath = path;

        CamcorderProfile camProfile;
        if (CamcorderProfile.hasProfile(mCameraId, profile.quality)) {
            camProfile = CamcorderProfile.get(mCameraId, profile.quality);
        } else {
            camProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_HIGH);
        }
        camProfile.videoBitRate = profile.videoBitRate;
        setCamcorderProfile(camProfile, recordAudio);

        mMediaRecorder.setOrientationHint(calcCameraRotation(mOrientation != Constants.ORIENTATION_AUTO ? orientationEnumToRotation(mOrientation) : mDeviceOrientation));

        if (maxDuration != -1) {
            mMediaRecorder.setMaxDuration(maxDuration);
        }
        if (maxFileSize != -1) {
            mMediaRecorder.setMaxFileSize(maxFileSize);
        }

        mMediaRecorder.setOnInfoListener(this);
        mMediaRecorder.setOnErrorListener(this);
    }

    private void stopMediaRecorder() {
        mIsRecording = false;
        if (mMediaRecorder != null) {
            try {
                mMediaRecorder.stop();
            } catch (RuntimeException ex) {
                ex.printStackTrace();
            }
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;
        }

        int deviceOrientation = displayOrientationToOrientationEnum(mDeviceOrientation);
        if (mVideoPath == null || !new File(mVideoPath).exists()) {
            mCallback.onVideoRecorded(null, mOrientation != Constants.ORIENTATION_AUTO ? mOrientation : deviceOrientation, deviceOrientation);
            return;
        }

        mCallback.onVideoRecorded(mVideoPath, mOrientation != Constants.ORIENTATION_AUTO ? mOrientation : deviceOrientation, deviceOrientation);
        mVideoPath = null;
    }

    private void setCamcorderProfile(CamcorderProfile profile, boolean recordAudio) {
        mMediaRecorder.setOutputFormat(profile.fileFormat);
        mMediaRecorder.setVideoFrameRate(profile.videoFrameRate);
        mMediaRecorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        mMediaRecorder.setVideoEncodingBitRate(profile.videoBitRate);
        mMediaRecorder.setVideoEncoder(profile.videoCodec);
        if (recordAudio) {
            mMediaRecorder.setAudioEncodingBitRate(profile.audioBitRate);
            mMediaRecorder.setAudioChannels(profile.audioChannels);
            mMediaRecorder.setAudioSamplingRate(profile.audioSampleRate);
            mMediaRecorder.setAudioEncoder(profile.audioCodec);
        }
    }

    @Override
    public void onInfo(MediaRecorder mr, int what, int extra) {
        if ( what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
            stopRecording();
        }
    }


    @Override
    public void onError(MediaRecorder mr, int what, int extra) {
        stopRecording();
    }
}
