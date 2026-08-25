/*
 * Copyright (C) 2019 The OmniROM Project
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

package com.realmeparts;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.dreams.IDreamManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class FPSInfoService extends Service {
    private static final String TAG = "FPSInfoService";
    private static final String DUMPSYS = "/system/bin/dumpsys";
    private View mView;
    private Thread mCurFPSThread;
    private String mFps = null;
    private int mPaddingLeft;
    private int mPaddingTop;
    private int mPaddingRight;
    private int mPaddingBottom;
    private IDreamManager mDreamManager;
    private final BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                Log.d(TAG, "ACTION_SCREEN_ON " + isDozeMode());
                if (!isDozeMode()) {
                    startThread();
                    mView.setVisibility(View.VISIBLE);
                }
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                Log.d(TAG, "ACTION_SCREEN_OFF");
                mView.setVisibility(View.GONE);
                stopThread();
            }
        }
    };

    private static List<String> execCommand(String... args) {
        List<String> out = new ArrayList<>();
        BufferedReader br = null;
        try {
            Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
            br = new BufferedReader(new InputStreamReader(p.getInputStream()), 2048);
            String line;
            while ((line = br.readLine()) != null) {
                out.add(line);
            }
            br.close();
            p.waitFor();
        } catch (Exception e) {
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    /**
     * Pick the most relevant updating layer: an activity/window layer
     * (contains a '/') that is not an internal overlay, preferring the
     * topmost SurfaceView or activity entry.
     */
    private static String findTargetLayer(List<String> layers) {
        String candidate = null;
        for (String line : layers) {
            String name = line.trim();
            if (!name.contains("/")) continue;
            if (name.startsWith("RequestedLayerState")
                    || name.contains("Gesture Monitor")
                    || name.startsWith("WindowToken")
                    || name.contains("ScreenDecor")
                    || name.contains("animation-leash")
                    || name.contains("ActivityRecordInputSink")) continue;
            candidate = name;
        }
        return candidate;
    }

    /**
     * Compute instantaneous FPS from SurfaceFlinger frame-present timestamps.
     */
    private static double queryFps() {
        String layer = findTargetLayer(execCommand(DUMPSYS, "SurfaceFlinger", "--list"));
        if (layer == null) {
            return -1;
        }

        List<String> latency = execCommand(DUMPSYS, "SurfaceFlinger", "--latency", layer);
        if (latency.size() < 3) {
            return -1;
        }

        long refreshNs = -1;
        try {
            refreshNs = Long.parseLong(latency.get(0).trim());
        } catch (NumberFormatException ignored) {
        }

        // Column 2 (third value) is the actual present timestamp.
        long first = -1, last = -1;
        int count = 0;
        for (int i = latency.size() - 1; i >= 1 && count < 64; i--) {
            String[] parts = latency.get(i).trim().split("\\s+");
            if (parts.length < 3) continue;
            long ts;
            try {
                ts = Long.parseLong(parts[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (ts == Long.MAX_VALUE || ts <= 0) continue;
            if (last < 0) {
                last = ts;
            }
            first = ts;
            count++;
        }

        if (first <= 0 || last <= 0 || count < 2 || last <= first) {
            return -1;
        }

        double fps = (count - 1) * 1e9 / (double) (last - first);
        if (refreshNs > 0) {
            double maxFps = 1e9 / (double) refreshNs;
            // Ignore stale windows that stopped submitting frames.
            if (fps > maxFps * 1.05) {
                return -1;
            }
        }
        return fps;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mView = new FPSView(this);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.RIGHT | Gravity.TOP;
        params.setTitle("FPS Info");

        startThread();

        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.checkService("dreams"));
        IntentFilter screenStateFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenStateReceiver, screenStateFilter);

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.addView(mView, params);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopThread();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(mView);
        mView = null;
        unregisterReceiver(mScreenStateReceiver);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean isDozeMode() {
        try {
            if (mDreamManager != null && mDreamManager.isDreaming()) {
                return true;
            }
        } catch (RemoteException e) {
            return false;
        }
        return false;
    }

    private void startThread() {
        Log.d(TAG, "started CurFPSThread");
        mCurFPSThread = new CurFPSThread(mView.getHandler());
        mCurFPSThread.start();
    }

    private void stopThread() {
        if (mCurFPSThread != null && mCurFPSThread.isAlive()) {
            Log.d(TAG, "stopping CurFPSThread");
            mCurFPSThread.interrupt();
            try {
                mCurFPSThread.join();
            } catch (InterruptedException e) {
            }
        }
        mCurFPSThread = null;
    }

    private class FPSView extends View {
        private final Paint mOnlinePaint;
        private final float mAscent;
        private final int mFH;
        private final int mMaxWidth;

        private int mNeededWidth;
        private int mNeededHeight;

        private boolean mDataAvail;

        private final Handler mCurFPSHandler = new Handler() {
            public void handleMessage(Message msg) {
                if (msg.obj == null) {
                    return;
                }
                if (msg.what == 1) {
                    String msgData = (String) msg.obj;
                    msgData = msgData.substring(0, Math.min(msgData.length(), 9));
                    mFps = msgData;
                    mDataAvail = true;
                    updateDisplay();
                }
            }
        };

        FPSView(Context c) {
            super(c);
            float density = c.getResources().getDisplayMetrics().density;
            int paddingPx = Math.round(9 * density);
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
            setBackgroundColor(Color.argb(0x00, 0, 0, 0));

            final int textSize = Math.round(16 * density);

            Typeface typeface = Typeface.create("googlesans", Typeface.NORMAL);

            mOnlinePaint = new Paint();
            mOnlinePaint.setTypeface(typeface);
            mOnlinePaint.setAntiAlias(true);
            mOnlinePaint.setTextSize(textSize);
            mOnlinePaint.setColor(Color.WHITE);
            mOnlinePaint.setShadowLayer(5.0f, 0.0f, 0.0f, Color.BLACK);

            mAscent = mOnlinePaint.ascent();
            float descent = mOnlinePaint.descent();
            mFH = (int) (descent - mAscent + .5f);

            final String maxWidthStr = "fps: 120.1";
            mMaxWidth = (int) mOnlinePaint.measureText(maxWidthStr);

            updateDisplay();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            mCurFPSHandler.removeMessages(1);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(resolveSize(mNeededWidth, widthMeasureSpec),
                    resolveSize(mNeededHeight, heightMeasureSpec));
        }

        private String getFPSInfoString() {
            return mFps;
        }

        @Override
        public void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!mDataAvail) {
                return;
            }

            final int W = mNeededWidth;
            final int RIGHT = getWidth() - 1;

            int x = RIGHT - mPaddingLeft;
            int top = mPaddingTop + 2;
            int bottom = mPaddingTop + mFH - 2;

            int y = mPaddingTop - (int) mAscent;

            String s = getFPSInfoString();
            canvas.drawText(s, RIGHT - mPaddingLeft - mMaxWidth,
                    y - 1, mOnlinePaint);
            y += mFH;
        }

        void updateDisplay() {
            if (!mDataAvail) {
                return;
            }

            int neededWidth = mPaddingLeft + mPaddingRight + mMaxWidth;
            int neededHeight = mPaddingTop + mPaddingBottom + 40;
            if (neededWidth != mNeededWidth || neededHeight != mNeededHeight) {
                mNeededWidth = neededWidth;
                mNeededHeight = neededHeight;
                requestLayout();
            } else {
                invalidate();
            }
        }

        public Handler getHandler() {
            return mCurFPSHandler;
        }
    }

    protected class CurFPSThread extends Thread {
        private final Handler mHandler;
        private boolean mInterrupt = false;

        public CurFPSThread(Handler handler) {
            mHandler = handler;
        }

        public void interrupt() {
            mInterrupt = true;
        }

        @Override
        public void run() {
            try {
                while (!mInterrupt) {
                    sleep(1000);
                    double fps = queryFps();
                    String fpsVal = (fps > 0)
                            ? String.format("%.0f", fps)
                            : null;
                    mHandler.sendMessage(mHandler.obtainMessage(1, fpsVal));
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
