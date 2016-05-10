package com.android.browser;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Created by gateworks on 4/26/16.
 */
public class DeviceAdmin extends DeviceAdminReceiver {
    public DeviceAdmin() {
        super();
    }
    @Override
    public void onEnabled(Context context, Intent intent) {
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
    }
}
