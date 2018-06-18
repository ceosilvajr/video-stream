package com.ceosilvajr.app.oraivideostreaming;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
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
import android.util.Log;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Created date 18/06/2018
 *
 * @author ceosilvajr@gmail.com
 **/
public class CameraFragment extends Fragment implements TextureView.SurfaceTextureListener {

  private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
  private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;
  private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
  private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

  private static final String TAG = "Camera2VideoFragment";
  private static final int REQUEST_VIDEO_PERMISSIONS = 1;
  private static final String FRAGMENT_DIALOG = "dialog";

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

  private Size mPreviewSize;
  private Size mVideoSize;
  private MediaRecorder mMediaRecorder;
  private boolean mIsRecordingVideo;
  private HandlerThread mBackgroundThread;
  private Handler mBackgroundHandler;
  private Semaphore mCameraOpenCloseLock = new Semaphore(1);

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
      Activity activity = getActivity();
      if (null != activity) {
        activity.finish();
      }
    }
  };
  private Integer mSensorOrientation;
  private CaptureRequest.Builder mPreviewBuilder;

  public static CameraFragment newInstance() {
    return new CameraFragment();
  }

  private static Size chooseVideoSize(Size[] choices) {
    for (Size size : choices) {
      if (size.getWidth() == size.getHeight() * 4 / 3 && size.getWidth() <= 1080) {
        return size;
      }
    }
    Log.e(TAG, "Couldn't find any suitable video size");
    return choices[choices.length - 1];
  }

  private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
    List<Size> bigEnough = new ArrayList<>();
    int w = aspectRatio.getWidth();
    int h = aspectRatio.getHeight();
    for (Size option : choices) {
      if (option.getHeight() == option.getWidth() * h / w
          && option.getWidth() >= width
          && option.getHeight() >= height) {
        bigEnough.add(option);
      }
    }
    if (bigEnough.size() > 0) {
      return Collections.min(bigEnough, new CompareSizesByArea());
    } else {
      Log.e(TAG, "Couldn't find any suitable preview size");
      return choices[0];
    }
  }

  @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.fragment_camera_video, container, false);
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

  private void startBackgroundThread() {
    mBackgroundThread = new HandlerThread("CameraBackground");
    mBackgroundThread.start();
    mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
  }

  private void stopBackgroundThread() {
    mBackgroundThread.quitSafely();
    try {
      mBackgroundThread.join();
      mBackgroundThread = null;
      mBackgroundHandler = null;
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  private boolean shouldShowRequestPermissionRationale(String[] permissions) {
    for (String permission : permissions) {
      if (shouldShowRequestPermissionRationale(permission)) {
        return true;
      }
    }
    return false;
  }

  private void requestVideoPermissions() {
    if (shouldShowRequestPermissionRationale(AppPermission.VIDEO_PERMISSIONS)) {
      new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
    } else {
      requestPermissions(AppPermission.VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS);
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    if (requestCode == REQUEST_VIDEO_PERMISSIONS) {
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

  private boolean hasPermissionsGranted(String[] permissions) {
    for (String permission : permissions) {
      if (ActivityCompat.checkSelfPermission(getActivity(), permission) != PackageManager.PERMISSION_GRANTED) {
        return false;
      }
    }
    return true;
  }

  /**
   * Tries to open a {@link CameraDevice}. The result is listened by `mStateCallback`.
   */
  @SuppressWarnings("MissingPermission") private void openCamera(int width, int height) {
    if (!hasPermissionsGranted(AppPermission.VIDEO_PERMISSIONS)) {
      requestVideoPermissions();
      return;
    }
    final Activity activity = getActivity();
    if (null == activity || activity.isFinishing()) {
      return;
    }
    CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    try {
      if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
        throw new RuntimeException("Time out waiting to lock camera opening.");
      }
      String cameraId = manager.getCameraIdList()[0];

      // Choose the sizes for camera preview and video recording
      CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
      StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
      if (map == null) {
        throw new RuntimeException("Cannot get available preview/video sizes");
      }
      mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
      mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height, mVideoSize);

      int orientation = getResources().getConfiguration().orientation;
      if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
        mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
      } else {
        mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
      }
      configureTransform(width, height);
      mMediaRecorder = new MediaRecorder();
      manager.openCamera(cameraId, mStateCallback, null);
    } catch (CameraAccessException e) {
      Toast.makeText(activity, "Cannot access the camera.", Toast.LENGTH_SHORT).show();
      activity.finish();
    } catch (NullPointerException e) {
      // Currently an NPE is thrown when the Camera2API is used but not supported on the device.
      ErrorDialog.newInstance(getString(R.string.camera_error)).show(getChildFragmentManager(), FRAGMENT_DIALOG);
    } catch (InterruptedException e) {
      throw new RuntimeException("Interrupted while trying to lock camera opening.");
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
    } catch (InterruptedException e) {
      throw new RuntimeException("Interrupted while trying to lock camera closing.");
    } finally {
      mCameraOpenCloseLock.release();
    }
  }

  private void startPreview() {
    if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize) {
      return;
    }
    try {
      closePreviewSession();
      SurfaceTexture texture = mTextureView.getSurfaceTexture();
      assert texture != null;
      texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
      mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

      Surface previewSurface = new Surface(texture);
      mPreviewBuilder.addTarget(previewSurface);

      mCameraDevice.createCaptureSession(Collections.singletonList(previewSurface),
          new CameraCaptureSession.StateCallback() {

            @Override public void onConfigured(@NonNull CameraCaptureSession session) {
              mPreviewSession = session;
              updatePreview();
            }

            @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
              Activity activity = getActivity();
              if (null != activity) {
                Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
              }
            }
          }, mBackgroundHandler);
    } catch (CameraAccessException e) {
      e.printStackTrace();
    }
  }

  private void updatePreview() {
    if (null == mCameraDevice) {
      return;
    }
    try {
      setUpCaptureRequestBuilder(mPreviewBuilder);
      HandlerThread thread = new HandlerThread("CameraPreview");
      thread.start();
      mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
    } catch (CameraAccessException e) {
      e.printStackTrace();
    }
  }

  private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
  }

  private void configureTransform(int viewWidth, int viewHeight) {
    Activity activity = getActivity();
    if (null == mTextureView || null == mPreviewSize || null == activity) {
      return;
    }
    int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
    Matrix matrix = new Matrix();
    RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
    RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
    float centerX = viewRect.centerX();
    float centerY = viewRect.centerY();
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
    final Activity activity = getActivity();
    if (null == activity) {
      return;
    }
    mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
    mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
    mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
    // mMediaRecorder.setOutputFile(getVideoFilePath()); TODO (ceosilvajr) make sure this works.
    mMediaRecorder.setVideoEncodingBitRate(10000000);
    mMediaRecorder.setVideoFrameRate(30);
    mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
    mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
    mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
    int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
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
    final Request request = new Request.Builder().url("ws://192.168.112.44:8000/stream").build();
    final WebSocket ws = client.newWebSocket(request, new EchoWebSocketListener());
    final ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(client.socketFactory().createSocket());
    return pfd.getFileDescriptor();
  }

  private void startRecordingVideo() {
    if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize) {
      return;
    }
    try {
      closePreviewSession();
      setUpMediaRecorder();
      SurfaceTexture texture = mTextureView.getSurfaceTexture();
      assert texture != null;
      texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
      mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
      List<Surface> surfaces = new ArrayList<>();

      // Set up Surface for the camera preview
      Surface previewSurface = new Surface(texture);
      surfaces.add(previewSurface);
      mPreviewBuilder.addTarget(previewSurface);

      // Set up Surface for the MediaRecorder
      Surface recorderSurface = mMediaRecorder.getSurface();
      surfaces.add(recorderSurface);
      mPreviewBuilder.addTarget(recorderSurface);

      // Start a capture session
      // Once the session starts, we can update the UI and start recording
      mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {

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
    } catch (CameraAccessException | IOException e) {
      e.printStackTrace();
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

    startPreview();
  }

  /**
   * Compares two {@code Size}s based on their areas.
   */
  static class CompareSizesByArea implements Comparator<Size> {

    @Override public int compare(Size lhs, Size rhs) {
      // We cast here to ensure the multiplications won't overflow
      return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
    }
  }

  public static class ErrorDialog extends DialogFragment {

    private static final String ARG_MESSAGE = "message";

    public static ErrorDialog newInstance(String message) {
      ErrorDialog dialog = new ErrorDialog();
      Bundle args = new Bundle();
      args.putString(ARG_MESSAGE, message);
      dialog.setArguments(args);
      return dialog;
    }

    @Override public Dialog onCreateDialog(Bundle savedInstanceState) {
      final Activity activity = getActivity();
      return new AlertDialog.Builder(activity).setMessage(getArguments().getString(ARG_MESSAGE))
          .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialogInterface, int i) {
              activity.finish();
            }
          })
          .create();
    }
  }

  public static class ConfirmationDialog extends DialogFragment {

    @Override public Dialog onCreateDialog(Bundle savedInstanceState) {
      final Fragment parent = getParentFragment();
      return new AlertDialog.Builder(getActivity()).setMessage(R.string.permission_request)
          .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
              requestPermissions(AppPermission.VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS);
            }
          })
          .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
              parent.getActivity().finish();
            }
          })
          .create();
    }
  }

  private final class EchoWebSocketListener extends WebSocketListener {

    private static final int NORMAL_CLOSURE_STATUS = 1000;

    @Override public void onOpen(WebSocket webSocket, Response response) {
      webSocket.send("Hello socket.");
    }

    @Override public void onMessage(WebSocket webSocket, String text) {
      Log.d(TAG, "Receiving : " + text);
    }

    @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
      Log.d(TAG, "Receiving bytes : " + bytes.hex());
    }

    @Override public void onClosing(WebSocket webSocket, int code, String reason) {
      webSocket.close(NORMAL_CLOSURE_STATUS, null);
      Log.d(TAG, "Closing : " + code + " / " + reason);
    }

    @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
      Log.d(TAG, "Error : " + t.getMessage());
    }
  }
}
