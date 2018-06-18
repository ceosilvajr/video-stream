package com.ceosilvajr.app.oraivideostreaming;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.Unbinder;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;

/**
 * Created date 18/06/2018
 *
 * @author ceosilvajr@gmail.com
 **/
public class CameraFragment extends Fragment implements TextureView.SurfaceTextureListener {

  private static final String FRAGMENT_DIALOG = "dialog";
  private static final String CAMERA_PREVIEW_HANDLER_NAME = "CameraPreview";
  private static final String CAMERA_BACKGROUND_HANDLER_NAME = "CameraBackground";

  private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
  private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;

  private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
  private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

  static {
    DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90);
    DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0);
    DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
    DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);
  }

  static {
    INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
    INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
    INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
    INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
  }

  @BindView(R.id.texture) AutoFitTextureView mTextureView;
  @BindView(R.id.video) Button mButtonVideo;

  private Unbinder unbinder;

  private CameraDevice mCameraDevice;
  private CameraCaptureSession mPreviewSession;

  private Size mPreviewSize;
  private Size mVideoSize;
  private MediaRecorder mMediaRecorder;
  private boolean mIsRecordingVideo;
  private HandlerThread mBackgroundThread;
  private Handler mBackgroundHandler;
  private Semaphore mCameraOpenCloseLock = new Semaphore(1);

  private Integer mSensorOrientation;
  private CaptureRequest.Builder mPreviewBuilder;

  public static CameraFragment newInstance() {
    return new CameraFragment();
  }

  @Override public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
    openCamera(width, height);
  }

  @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
    configureTransform(width, height);
  }

  @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
    return true;
  }

  @Override public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
  }

  private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

    @Override public void onOpened(@NonNull CameraDevice cameraDevice) {
      mCameraDevice = cameraDevice;
      startPreview();
      mCameraOpenCloseLock.release();
      if (null != mTextureView) {
        configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
      }
    }

    @Override public void onDisconnected(@NonNull CameraDevice cameraDevice) {
      mCameraOpenCloseLock.release();
      cameraDevice.close();
      mCameraDevice = null;
    }

    @Override public void onError(@NonNull CameraDevice cameraDevice, int error) {
      mCameraOpenCloseLock.release();
      cameraDevice.close();
      mCameraDevice = null;
    }
  };

  @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    final View view = inflater.inflate(R.layout.fragment_camera_video, container, false);
    this.unbinder = ButterKnife.bind(this, view);
    return view;
  }

  @Override public void onResume() {
    super.onResume();
    startBackgroundThread();
    if (mTextureView.isAvailable()) {
      openCamera(mTextureView.getWidth(), mTextureView.getHeight());
    } else {
      mTextureView.setSurfaceTextureListener(this);
    }
  }

  @Override public void onPause() {
    closeCamera();
    stopBackgroundThread();
    super.onPause();
  }

  @Override public void onDestroyView() {
    super.onDestroyView();
    this.unbinder.unbind();
  }

  @OnClick(R.id.video) public void onVideoButtonClicked() {
    if (mIsRecordingVideo) {
      stopRecordingVideo();
    } else {
      startRecordingVideo();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    if (requestCode == AppPermission.REQUEST_VIDEO_PERMISSIONS) {
      if (grantResults.length == AppPermission.VIDEO_PERMISSIONS.length) {
        for (int result : grantResults) {
          if (result != PackageManager.PERMISSION_GRANTED) {
            ErrorDialog.newInstance(getString(R.string.permission_request))
                .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            break;
          }
        }
      } else {
        ErrorDialog.newInstance(getString(R.string.permission_request))
            .show(getChildFragmentManager(), FRAGMENT_DIALOG);
      }
    } else {
      super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
  }

  private void startBackgroundThread() {
    mBackgroundThread = new HandlerThread(CAMERA_BACKGROUND_HANDLER_NAME);
    mBackgroundThread.start();
    mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
  }

  private void stopBackgroundThread() {
    mBackgroundThread.quitSafely();
    try {
      mBackgroundThread.join();
      mBackgroundThread = null;
      mBackgroundHandler = null;
    } catch (final InterruptedException e) {
      e.printStackTrace();
    }
  }

  private void requestVideoPermissions() {
    if (shouldShowRequestPermissionRationale()) {
      new ConfirmPermissionDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
    } else {
      requestPermissions(AppPermission.VIDEO_PERMISSIONS, AppPermission.REQUEST_VIDEO_PERMISSIONS);
    }
  }

  private boolean shouldShowRequestPermissionRationale() {
    for (final String permission : AppPermission.VIDEO_PERMISSIONS) {
      if (shouldShowRequestPermissionRationale(permission)) {
        return true;
      }
    }
    return false;
  }

  private boolean hasPermissionsGranted() {
    for (final String permission : AppPermission.VIDEO_PERMISSIONS) {
      if (ActivityCompat.checkSelfPermission(getActivity(), permission) != PackageManager.PERMISSION_GRANTED) {
        return false;
      }
    }
    return true;
  }

  @SuppressWarnings("MissingPermission") private void openCamera(final int width, final int height) {
    if (!hasPermissionsGranted()) {
      requestVideoPermissions();
      return;
    }
    final CameraManager manager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
    try {
      if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
        throw new RuntimeException(getString(R.string.time_out_waitng_camera));
      }
      final String cameraId = manager.getCameraIdList()[0];
      // Choose the sizes for camera preview and video recording
      final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
      final StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
      if (map == null) {
        throw new RuntimeException(getString(R.string.error_on_preview_sizes));
      }
      mVideoSize = VideoSizeUtil.chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
      mPreviewSize =
          VideoSizeUtil.chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height, mVideoSize);

      final int orientation = getResources().getConfiguration().orientation;
      if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
        mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
      } else {
        mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
      }
      configureTransform(width, height);
      mMediaRecorder = new MediaRecorder();
      manager.openCamera(cameraId, mStateCallback, null);
    } catch (final CameraAccessException e) {
      ErrorDialog.newInstance(getString(R.string.cannot_access_the_camera))
          .show(getChildFragmentManager(), FRAGMENT_DIALOG);
    } catch (final NullPointerException e) {
      ErrorDialog.newInstance(getString(R.string.camera_error)).show(getChildFragmentManager(), FRAGMENT_DIALOG);
    } catch (InterruptedException e) {
      throw new RuntimeException(getString(R.string.failed_to_open_camera_message));
    }
  }

  private void closeCamera() {
    try {
      mCameraOpenCloseLock.acquire();
      closePreviewSession();
      if (null != mCameraDevice) {
        mCameraDevice.close();
        mCameraDevice = null;
      }
      if (null != mMediaRecorder) {
        mMediaRecorder.release();
        mMediaRecorder = null;
      }
    } catch (final InterruptedException e) {
      throw new RuntimeException(getString(R.string.failed_to_close_camera));
    } finally {
      mCameraOpenCloseLock.release();
    }
  }

  private void startPreview() {
    if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize) {
      // Do nothing.
      return;
    }
    try {
      closePreviewSession();
      final SurfaceTexture texture = mTextureView.getSurfaceTexture();
      texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
      mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
      final Surface previewSurface = new Surface(texture);
      mPreviewBuilder.addTarget(previewSurface);
      mCameraDevice.createCaptureSession(Collections.singletonList(previewSurface),
          new CameraCaptureSession.StateCallback() {

            @Override public void onConfigured(@NonNull CameraCaptureSession session) {
              mPreviewSession = session;
              updatePreview();
            }

            @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
              Toast.makeText(getActivity(), R.string.failed, Toast.LENGTH_SHORT).show();
            }
          }, mBackgroundHandler);
    } catch (final CameraAccessException e) {
      ErrorDialog.newInstance(getString(R.string.cannot_access_the_camera))
          .show(getChildFragmentManager(), FRAGMENT_DIALOG);
    }
  }

  private void updatePreview() {
    if (null == mCameraDevice) {
      // do nothing
      return;
    }
    try {
      mPreviewBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
      final HandlerThread thread = new HandlerThread(CAMERA_PREVIEW_HANDLER_NAME);
      thread.start();
      mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
    } catch (final CameraAccessException e) {
      ErrorDialog.newInstance(getString(R.string.cannot_access_the_camera))
          .show(getChildFragmentManager(), FRAGMENT_DIALOG);
    }
  }

  private void configureTransform(final int viewWidth, final int viewHeight) {
    final int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
    final Matrix matrix = new Matrix();
    final RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
    final RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
    final float centerX = viewRect.centerX();
    final float centerY = viewRect.centerY();
    if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
      bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
      matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
      float scale =
          Math.max((float) viewHeight / mPreviewSize.getHeight(), (float) viewWidth / mPreviewSize.getWidth());
      matrix.postScale(scale, scale, centerX, centerY);
      matrix.postRotate(90 * (rotation - 2), centerX, centerY);
    }
    mTextureView.setTransform(matrix);
  }

  private void setUpMediaRecorder() throws IOException {
    mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
    mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
    mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
    // mMediaRecorder.setOutputFile(getVideoFilePath()); TODO (ceosilvajr) make sure this works.
    mMediaRecorder.setVideoEncodingBitRate(10000000);
    mMediaRecorder.setVideoFrameRate(30);
    mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
    mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
    mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
    int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
    switch (mSensorOrientation) {
      case SENSOR_ORIENTATION_DEFAULT_DEGREES:
        mMediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation));
        break;
      case SENSOR_ORIENTATION_INVERSE_DEGREES:
        mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
        break;
    }
    mMediaRecorder.prepare();
  }

  private FileDescriptor getVideoFilePath() throws IOException {
    final OkHttpClient client = new OkHttpClient();
    final Request request = new Request.Builder().url(getString(R.string.websocket_url)).build();
    final WebSocket ws = client.newWebSocket(request, new AppSocketListener());
    final ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(client.socketFactory().createSocket());
    return pfd.getFileDescriptor();
  }

  private void startRecordingVideo() {
    if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize) {
      // do nothing
      return;
    }
    try {
      closePreviewSession();
      setUpMediaRecorder();

      final SurfaceTexture texture = mTextureView.getSurfaceTexture();
      texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
      mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

      // Set up Surface for the camera preview
      final Surface previewSurface = new Surface(texture);
      mPreviewBuilder.addTarget(previewSurface);

      // Set up Surface for the MediaRecorder
      final Surface recorderSurface = mMediaRecorder.getSurface();
      mPreviewBuilder.addTarget(recorderSurface);

      // Start a capture session
      // Once the session starts, we can update the UI and start recording
      mCameraDevice.createCaptureSession(Arrays.asList(previewSurface, recorderSurface),
          new CameraCaptureSession.StateCallback() {

            @Override public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
              mPreviewSession = cameraCaptureSession;
              updatePreview();
              getActivity().runOnUiThread(new Runnable() {
                @Override public void run() {
                  // UI
                  mButtonVideo.setText(R.string.stop);
                  mIsRecordingVideo = true;

                  // Start recording
                  mMediaRecorder.start();
                }
              });
            }

            @Override public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
              Activity activity = getActivity();
              if (null != activity) {
                Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
              }
            }
          }, mBackgroundHandler);
    } catch (final CameraAccessException | IOException e) {
      ErrorDialog.newInstance(getString(R.string.cannot_access_the_camera))
          .show(getChildFragmentManager(), FRAGMENT_DIALOG);
    }
  }

  private void closePreviewSession() {
    if (mPreviewSession != null) {
      mPreviewSession.close();
      mPreviewSession = null;
    }
  }

  private void stopRecordingVideo() {
    // UI
    mIsRecordingVideo = false;
    mButtonVideo.setText(R.string.record);
    // Stop recording
    mMediaRecorder.stop();
    mMediaRecorder.reset();
    // Start preview
    startPreview();
  }
}
