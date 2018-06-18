package com.ceosilvajr.app.oraivideostreaming;

import android.Manifest;

/**
 * Created date 14/06/2018
 *
 * @author ceosilvajr@gmail.com
 **/
public final class AppPermission {

  public static final int REQUEST_VIDEO_PERMISSIONS = 1;

  private AppPermission() {
    super();
  }

  public static final String[] VIDEO_PERMISSIONS = {
      Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
  };
}
