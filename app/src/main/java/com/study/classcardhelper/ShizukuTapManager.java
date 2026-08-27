package com.study.classcardhelper;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public final class ShizukuTapManager {
    public interface StatusListener { void onStatus(String status); }
    public interface TapCallback { void onResult(boolean success, String message); }

    private static final int REQUEST_CODE = 7712;
    private static Context appContext;
    private static volatile IShizukuTapService remote;
    private static volatile boolean binding = false;
    private static volatile boolean initialized = false;
    private static volatile StatusListener statusListener;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static Shizuku.UserServiceArgs userServiceArgs;

    private static final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            remote = IShizukuTapService.Stub.asInterface(service);
            binding = false;
            notifyStatus("Shizuku 자동 터치 연결됨");
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null;
            binding = false;
            notifyStatus("Shizuku 자동 터치 연결 끊김");
        }
    };

    private static final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        notifyStatus("Shizuku 실행 중");
        if (hasPermission()) bindUserService();
    };

    private static final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        remote = null;
        binding = false;
        notifyStatus("Shizuku가 중지됨");
    };

    private static final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        if (requestCode != REQUEST_CODE) return;
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            notifyStatus("Shizuku 권한 허용됨");
            bindUserService();
        } else {
            notifyStatus("Shizuku 권한이 거부됨");
        }
    };

    private ShizukuTapManager() {}

    public static synchronized void init(Context context) {
        if (initialized) return;
        appContext = context.getApplicationContext();
        userServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(appContext.getPackageName(), ShizukuTapUserService.class.getName()))
                .daemon(false)
                .processNameSuffix("tap")
                .tag("study_lens_tap")
                .version(2);

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        initialized = true;
    }

    public static void setStatusListener(StatusListener listener) {
        statusListener = listener;
        refreshStatus();
    }

    public static boolean isShizukuRunning() {
        try { return Shizuku.pingBinder(); }
        catch (Throwable t) { return false; }
    }

    public static boolean hasPermission() {
        try {
            return isShizukuRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isReady() {
        try { return remote != null && remote.asBinder().pingBinder(); }
        catch (Throwable t) { remote = null; return false; }
    }

    public static void requestPermissionAndBind() {
        if (!initialized || appContext == null) return;
        if (!isShizukuRunning()) {
            notifyStatus("Shizuku를 먼저 실행해주세요");
            return;
        }
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindUserService();
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                notifyStatus("Shizuku 앱에서 Study Lens 권한을 허용해주세요");
            } else {
                Shizuku.requestPermission(REQUEST_CODE);
                notifyStatus("Shizuku 권한 요청 중");
            }
        } catch (Throwable t) {
            notifyStatus("Shizuku 연결 오류");
        }
    }

    private static synchronized void bindUserService() {
        if (isReady() || binding || userServiceArgs == null) return;
        if (!hasPermission()) return;
        binding = true;
        notifyStatus("자동 터치 엔진 연결 중");
        try {
            Shizuku.bindUserService(userServiceArgs, connection);
        } catch (Throwable t) {
            binding = false;
            notifyStatus("자동 터치 엔진 연결 실패");
        }
    }

    public static void tapClassCard(float x, float y, TapCallback callback) {
        if (!isReady()) {
            if (callback != null) callback.onResult(false, "Shizuku 연결 필요");
            return;
        }
        final int tx = Math.max(0, Math.round(x));
        final int ty = Math.max(0, Math.round(y));
        worker.execute(() -> {
            try {
                IShizukuTapService service = remote;
                if (service == null || !service.asBinder().pingBinder()) {
                    remote = null;
                    if (callback != null) callback.onResult(false, "Shizuku 연결 끊김");
                    return;
                }
                if (!service.isClassCardForeground()) {
                    if (callback != null) callback.onResult(false, "ClassCard가 앞에 있을 때만 터치합니다");
                    return;
                }
                boolean ok = service.tap(tx, ty);
                if (callback != null) callback.onResult(ok, ok ? "자동 터치 완료" : "터치 실행 실패");
            } catch (Throwable t) {
                remote = null;
                if (callback != null) callback.onResult(false, "Shizuku 실행 오류");
            }
        });
    }

    public static void refreshStatus() {
        if (!isShizukuRunning()) notifyStatus("Shizuku 미실행");
        else if (!hasPermission()) notifyStatus("Shizuku 권한 필요");
        else if (isReady()) notifyStatus("Shizuku 자동 터치 연결됨");
        else notifyStatus("Shizuku 권한 있음 · 연결 대기");
    }

    private static void notifyStatus(String status) {
        StatusListener listener = statusListener;
        if (listener != null) listener.onStatus(status);
    }
}
