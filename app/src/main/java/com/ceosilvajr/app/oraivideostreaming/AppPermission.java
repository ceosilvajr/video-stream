package com.ceosilvajr.app.oraivideostreaming;

import android.Manifest;

/**
 * Created date 14/06/2018
 *
 * @author ceosilvajr@gmail.com
 **/
public final class AppPermission {

  private AppPermission() {
    super();
  }

  public static final String[] VIDEO_PERMISSIONS = {
      Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
  };
}
