package com.study.classcardhelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

public class StudyAccessibilityService extends AccessibilityService {
    private static volatile StudyAccessibilityService instance;
    private volatile String foregroundPackage = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getPackageName() != null) {
            foregroundPackage = event.getPackageName().toString();
        }
    }

    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean isReady() { return instance != null; }

    public static boolean isClassCardForeground() {
        StudyAccessibilityService s = instance;
        if (s == null) return false;
        String p = s.foregroundPackage == null ? "" : s.foregroundPackage.toLowerCase();
        return p.contains("classcard");
    }

    public static boolean tap(float x, float y) {
        StudyAccessibilityService s = instance;
        if (s == null || Build.VERSION.SDK_INT < 24 || !isClassCardForeground()) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 80);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return s.dispatchGesture(gesture, null, null);
    }
}
