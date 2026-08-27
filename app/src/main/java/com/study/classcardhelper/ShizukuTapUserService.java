package com.study.classcardhelper;

import android.content.Context;
import android.system.Os;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ShizukuTapUserService extends IShizukuTapService.Stub {

    public ShizukuTapUserService() {}

    @Keep
    public ShizukuTapUserService(Context context) {}

    @Override
    public boolean tap(int x, int y) {
        if (x < 0 || y < 0) return false;
        try {
            Process process = new ProcessBuilder(
                    "/system/bin/input", "tap",
                    String.valueOf(x), String.valueOf(y))
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isClassCardForeground() {
        try {
            Process process = new ProcessBuilder("/system/bin/dumpsys", "window", "windows")
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("mCurrentFocus") || line.contains("mFocusedApp") || line.contains("topResumedActivity")) {
                        output.append(line).append('\n');
                    }
                }
            }
            process.waitFor();
            return output.toString().contains("classcard.net");
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String status() {
        return "uid=" + Os.getuid() + ", pid=" + Os.getpid();
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
