package com.ceosilvajr.app.oraivideostreaming;

import android.util.Log;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Created date 18/06/2018
 *
 * @author ceosilvajr@gmail.com
 **/
public class AppSocketListener extends WebSocketListener {

  private static final String TAG = AppSocketListener.class.getName();

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
