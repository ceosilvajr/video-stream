package com.ceosilvajr.app.oraivideostreaming;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.DialogInterface;
import android.os.Bundle;

/**
 * Created date 18/06/2018
 *
 * @author ceosilvajr@gmail.com
 **/
public class ConfirmPermissionDialog extends DialogFragment {
  @Override public Dialog onCreateDialog(Bundle savedInstanceState) {
    final Fragment parent = getParentFragment();
    return new AlertDialog.Builder(getActivity()).setMessage(R.string.permission_request)
        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface dialog, int which) {
            requestPermissions(AppPermission.VIDEO_PERMISSIONS, AppPermission.REQUEST_VIDEO_PERMISSIONS);
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
