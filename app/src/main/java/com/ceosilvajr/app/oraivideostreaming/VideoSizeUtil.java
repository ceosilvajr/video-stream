package com.ceosilvajr.app.oraivideostreaming;

import android.util.Log;
import android.util.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created date 18/06/2018
 *
 * @author ceosilvajr@gmail.com
 **/
public final class VideoSizeUtil {

  private static final String TAG = VideoSizeUtil.class.getName();

  public static Size chooseVideoSize(Size[] choices) {
    for (Size size : choices) {
      if (size.getWidth() == size.getHeight() * 4 / 3 && size.getWidth() <= 1080) {
        return size;
      }
    }
    Log.e(TAG, "Couldn't find any suitable video size");
    return choices[choices.length - 1];
  }

  public static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
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
      return Collections.min(bigEnough, new CameraFragment.CompareSizesByArea());
    } else {
      Log.e(TAG, "Couldn't find any suitable preview size");
      return choices[0];
    }
  }
}
