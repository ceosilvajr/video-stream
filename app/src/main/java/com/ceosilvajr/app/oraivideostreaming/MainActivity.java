package com.ceosilvajr.app.oraivideostreaming;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    if (null == savedInstanceState) {
      getFragmentManager().beginTransaction().replace(R.id.container, CameraFragment.newInstance()).commit();
    }
  }
}
