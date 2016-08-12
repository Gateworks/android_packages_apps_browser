/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.browser;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.provider.Settings.System;
import android.view.WindowManager;

import com.android.browser.stub.NullController;
import com.google.common.annotations.VisibleForTesting;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static java.util.concurrent.TimeUnit.*;

public class BrowserActivity extends Activity {

    public static final String ACTION_SHOW_BOOKMARKS = "show_bookmarks";
    public static final String ACTION_SHOW_BROWSER = "show_browser";
    public static final String ACTION_RESTART = "--restart--";
    private static final String EXTRA_STATE = "state";
    public static final String EXTRA_DISABLE_URL_OVERRIDE = "disable_url_override";

    private final static String LOGTAG = "browser";

    private final static boolean LOGV_ENABLED = Browser.LOGV_ENABLED;

    private ActivityController mController = NullController.INSTANCE;

    @Override
    public void onCreate(Bundle icicle) {
        if (LOGV_ENABLED) {
            Log.v(LOGTAG, this + " onStart, has state: "
                    + (icicle == null ? "false" : "true"));
        }
        super.onCreate(icicle);

        if (shouldIgnoreIntents()) {
            finish();
            return;
        }

        // If this was a web search request, pass it on to the default web
        // search provider and finish this activity.
        if (IntentHandler.handleWebSearchIntent(this, null, getIntent())) {
            finish();
            return;
        }

        mController = createController();

        Intent intent = (icicle == null) ? getIntent() : null;
        mController.start(intent);

        if (propReader("ro.boot.kioskdisable") != "PROPERTY_NOT_FOUND") {
            Log.d(LOGTAG, "ro.boot.kioskdisable was set, unlocking");
        }
        else {
            startKioskMode();
        }
        setBrightness(255);

        getActionBar().hide();

        int refreshTime;
        try {
            refreshTime = Integer.parseInt(propReader("ro.boot.refreshtime"));
        } catch (NumberFormatException e) {
            Log.d(LOGTAG, "onCreate: found invalid or missing refreshtime param for kiosk mode, " +
                    "defaulting to 60 minutes");
            refreshTime = 60 * 60;
        }
        KioskRefreshScheduler krs =
                new KioskRefreshScheduler(propReader("ro.boot.url"), propReader("ro.boot.fallback"),
                        refreshTime);
        krs.begin();
    }

    private void startKioskMode() {
        DevicePolicyManager DPM = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName mDeviceAdminSample = new ComponentName(this, DeviceAdmin.class);
        Intent intent;

        /* Check to see if application has already been authorized
         * for Lock Task Mode.
         */
        if (DPM.isLockTaskPermitted(this.getPackageName())) {
            startLockTask();
        }
        else if (!DPM.isDeviceOwner(getPackageName()) || !DPM.isAdminActive(mDeviceAdminSample)) {
            intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, mDeviceAdminSample);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enabling force lock admin");
            startActivityForResult(intent, 99);
        }
        else {
            String[] packages = {getPackageName()};
            DPM.setLockTaskPackages(mDeviceAdminSample, packages);
            startLockTask();
        }

        return;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            Intent closeDialog = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            sendBroadcast(closeDialog);
        }
    }

    private void setBrightness(int brightness) {
        ContentResolver content = getContentResolver();
        Window window = getWindow();

        if (brightness < 20)
            brightness = 20;
        else if (brightness > 255)
            brightness = 255;

        //Set the system brightness using the brightness variable value
        System.putInt(content, System.SCREEN_BRIGHTNESS, brightness);
        //Get the current window attributes
        WindowManager.LayoutParams layoutpars = window.getAttributes();
        //Set the brightness of this window
        layoutpars.screenBrightness = brightness / (float)255;
        //Apply attribute changes to this window
        window.setAttributes(layoutpars);
    }

    public static boolean isTablet(Context context) {
        return context.getResources().getBoolean(R.bool.isTablet);
    }

    private Controller createController() {
        Controller controller = new Controller(this);
        boolean xlarge = isTablet(this);
        UI ui = null;
        if (xlarge) {
            ui = new XLargeUi(this, controller);
        } else {
            ui = new PhoneUi(this, controller);
        }
        controller.setUi(ui);
        return controller;
    }

    @VisibleForTesting
    Controller getController() {
        return (Controller) mController;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (shouldIgnoreIntents()) return;
        if (ACTION_RESTART.equals(intent.getAction())) {
            Bundle outState = new Bundle();
            mController.onSaveInstanceState(outState);
            finish();
            getApplicationContext().startActivity(
                    new Intent(getApplicationContext(), BrowserActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_STATE, outState));
            return;
        }
        mController.handleNewIntent(intent);
    }

    private KeyguardManager mKeyguardManager;
    private PowerManager mPowerManager;
    private boolean shouldIgnoreIntents() {
        // Only process intents if the screen is on and the device is unlocked
        // aka, if we will be user-visible
        if (mKeyguardManager == null) {
            mKeyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        }
        if (mPowerManager == null) {
            mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        }
        boolean ignore = !mPowerManager.isScreenOn();
        ignore |= mKeyguardManager.inKeyguardRestrictedInputMode();
        if (LOGV_ENABLED) {
            Log.v(LOGTAG, "ignore intents: " + ignore);
        }
        return ignore;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (LOGV_ENABLED) {
            Log.v(LOGTAG, "BrowserActivity.onResume: this=" + this);
        }
        mController.onResume();
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        if (Window.FEATURE_OPTIONS_PANEL == featureId) {
            mController.onMenuOpened(featureId, menu);
        }
        return true;
    }

    @Override
    public void onOptionsMenuClosed(Menu menu) {
        mController.onOptionsMenuClosed(menu);
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        mController.onContextMenuClosed(menu);
    }

    /**
     *  onSaveInstanceState(Bundle map)
     *  onSaveInstanceState is called right before onStop(). The map contains
     *  the saved state.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (LOGV_ENABLED) {
            Log.v(LOGTAG, "BrowserActivity.onSaveInstanceState: this=" + this);
        }
        mController.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        mController.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (LOGV_ENABLED) {
            Log.v(LOGTAG, "BrowserActivity.onDestroy: this=" + this);
        }
        super.onDestroy();
        mController.onDestroy();
        mController = NullController.INSTANCE;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mController.onConfgurationChanged(newConfig);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mController.onLowMemory();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        return mController.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        return mController.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (!mController.onOptionsItemSelected(item)) {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenuInfo menuInfo) {
        mController.onCreateContextMenu(menu, v, menuInfo);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        return mController.onContextItemSelected(item);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return mController.onKeyDown(keyCode, event) ||
            super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return mController.onKeyUp(keyCode, event) ||
            super.onKeyUp(keyCode, event);
    }

    @Override
    public void onActionModeStarted(ActionMode mode) {
        super.onActionModeStarted(mode);
        mController.onActionModeStarted(mode);
    }

    @Override
    public void onActionModeFinished(ActionMode mode) {
        super.onActionModeFinished(mode);
        mController.onActionModeFinished(mode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
            Intent intent) {
        if (requestCode == 99) {
            if(resultCode == Activity.RESULT_OK){
                DevicePolicyManager DPM = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                ComponentName mDeviceAdminSample = new ComponentName(this, DeviceAdmin.class);
                String[] packages = {getPackageName()};
                DPM.setLockTaskPackages(mDeviceAdminSample, packages);
            }
            if (resultCode == Activity.RESULT_CANCELED) {
                Log.d(LOGTAG, getPackageName() + " was denied device admin.");
            }
            startLockTask();
        }
        mController.onActivityResult(requestCode, resultCode, intent);
    }

    @Override
    public boolean onSearchRequested() {
        return mController.onSearchRequested();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return mController.dispatchKeyEvent(event)
                || super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        return mController.dispatchKeyShortcutEvent(event)
                || super.dispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        return mController.dispatchTouchEvent(ev)
                || super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent ev) {
        return mController.dispatchTrackballEvent(ev)
                || super.dispatchTrackballEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        return mController.dispatchGenericMotionEvent(ev) ||
                super.dispatchGenericMotionEvent(ev);
    }

    // Constructs returns result of a getprop call
    public static String propReader(String propName) {
        Process proc = null;
        String line;

        try {
            proc = Runtime.getRuntime().exec("/system/bin/getprop " + propName);
        } catch (IOException e) {
            e.printStackTrace();
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));

        try {
            if ((line = br.readLine()) != null && !line.isEmpty())
                return line.trim();

        } catch (IOException e) {
            e.printStackTrace();
        }

        return "PROPERTY_NOT_FOUND";
    }

    public boolean isOnline() {
        ConnectivityManager connMgr = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        return (networkInfo != null && networkInfo.isConnected());
    }

    class KioskRefreshScheduler {
        private final ScheduledExecutorService scheduler =
                Executors.newScheduledThreadPool(1);
        private String targetURL, fallbackURL;
        private int refreshTime;
        private ScheduledFuture<?> refresherHandle = null;
        private Runnable changeToTargetURL, changeToFallbackURL;
        private final BrowserSettings bs = BrowserSettings.getInstance();
        private final Tab currentTab = ((UiController) mController).getCurrentTab();

        public KioskRefreshScheduler(final String tURL,final String fURL, int refresh) {
            if (tURL.equals("PROPERTY_NOT_FOUND")) {
                Log.d(LOGTAG, "KioskRefreshScheduler: Kiosk browser target URL not found, " +
                   "make sure to set androidboot.url correctly in the bootloader 'extra' variable");
                bs.setHomePage("http://www.gateworks.com");
            }
            if (fURL.equals("PROPERTY_NOT_FOUND")) {
                Log.d(LOGTAG, "KioskRefreshScheduler: Kiosk browser fallback URL not found, " +
                    "make sure to set androidboot.fallback correctly in the bootloader 'extra'" +
                    " variable. (file URLs should be of the form 'file:///data/picture.png'");
            }
            refreshTime = refresh;
            fallbackURL = fURL;
            changeToFallbackURL = new Runnable() {
                @Override
                public void run() {
                    if (!fURL.equals("PROPERTY_NOT_FOUND")) bs.setHomePage(fURL);
                    ((UiController)mController).loadUrl(currentTab, bs.getHomePage());
                }
            };
            changeToTargetURL = new Runnable() {
                @Override
                public void run() {
                    if (!tURL.equals("PROPERTY_NOT_FOUND")) bs.setHomePage(tURL);
                    Log.d(LOGTAG, "standardmode just refreshed the page to: " + targetURL);
                    ((UiController)mController).loadUrl(currentTab, bs.getHomePage());

                    //Also clear cache to prevent filling up disk
                    bs.clearCache();
                }
            };
        }

        public void begin() {
            if (isOnline())
                startStandardMode();
            else
                startFailureMode();
        }

        public void startFailureMode() {
            Log.d("BROWSERTESTING", "failuremode: checking if network is online before continuing."
                    + " Setting URL to this fallback in the meantime: " + fallbackURL);
            if (fallbackURL != null && !fallbackURL.equals("PROPERTY_NOT_FOUND"))
                runOnUiThread(changeToFallbackURL);

            final Runnable refresher = new Runnable() {
                public void run() {
                    if (isOnline()) switchToStandardMode();
                }
            };
            refresherHandle = scheduler.scheduleAtFixedRate(refresher, 0, 5, SECONDS);
        }

        public void startStandardMode() {
            final Runnable refresher = new Runnable() {
                public void run() {
                    if (!isOnline()) {
                        Log.d(LOGTAG, "standardmode just encountered loss of connection while refreshing, switching to failuremode");
                        switchToFailureMode();
                    }
                    else {
                        runOnUiThread(changeToTargetURL);
                    }

                }
            };
            refresherHandle = scheduler.scheduleAtFixedRate(refresher, 0, refreshTime, SECONDS);
        }

        private void switchToFailureMode() {
            refresherHandle.cancel(true);
            startFailureMode();
        }

        private void switchToStandardMode() {
            Log.d("browser", "onCreate: network is online, going to standardMode");
            refresherHandle.cancel(true);
            startStandardMode();
        }
    }
}
