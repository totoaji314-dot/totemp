package com.study.classcardhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptureService extends Service {
    public static final String ACTION_START = "com.study.classcardhelper.START";
    public static final String ACTION_STOP = "com.study.classcardhelper.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL = "screen_analysis";
    private static final int NOTIFICATION_ID = 41;
    private static final long OCR_INTERVAL_MS = 350L;
    private static final long AI_MIN_INTERVAL_MS = 900L;

    public interface UiListener { void onUpdate(String status, String question, String answer); }
    private static volatile UiListener uiListener;
    public static void setUiListener(UiListener listener) { uiListener = listener; }

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private final AtomicBoolean aiBusy = new AtomicBoolean(false);
    private TextRecognizer recognizer;
    private SecurePrefs prefs;
    private WindowManager windowManager;
    private TextView answerBubble;
    private View highlight;
    private long lastOcrAt = 0L;
    private long lastAiAt = 0L;
    private String lastSignature = "";
    private int screenWidth;
    private int screenHeight;
    private volatile boolean running = false;

    private static final class LineBox {
        final String text;
        final Rect rect;
        LineBox(String text, Rect rect) { this.text = text; this.rect = rect; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new SecurePrefs(this);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopEverything();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
            Intent data;
            if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
            else data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (data == null) {
                updateUi("화면 공유 데이터 없음", "", "");
                stopSelf();
                return START_NOT_STICKY;
            }
            startCapture(resultCode, data);
        }
        return START_NOT_STICKY;
    }

    private void startCapture(int resultCode, Intent data) {
        if (running) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        int density = dm.densityDpi;

        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mgr.getMediaProjection(resultCode, data);
        if (projection == null) {
            updateUi("화면 공유 시작 실패", "", "");
            stopSelf();
            return;
        }

        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                running = false;
                updateUi("화면 공유 종료", "", "");
                removeOverlay();
            }
        }, new Handler(getMainLooper()));

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        captureThread = new HandlerThread("screen-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        virtualDisplay = projection.createVirtualDisplay("ClassCardAIHelper",
                screenWidth, screenHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);
        running = true;
        showBubble("AI 분석 준비됨");
        updateUi("실시간 화면 분석 중", "", "");
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        long now = System.currentTimeMillis();
        if (!running || now - lastOcrAt < OCR_INTERVAL_MS || !ocrBusy.compareAndSet(false, true)) {
            image.close();
            return;
        }
        lastOcrAt = now;

        Bitmap bitmap = null;
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;
            int paddedWidth = screenWidth + rowPadding / pixelStride;
            Bitmap padded = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            bitmap = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight);
            padded.recycle();
        } catch (Throwable t) {
            ocrBusy.set(false);
        } finally {
            image.close();
        }
        if (bitmap == null) return;

        final Bitmap frame = bitmap;
        InputImage input = InputImage.fromBitmap(frame, 0);
        Task<Text> task = recognizer.process(input);
        task.addOnSuccessListener(result -> {
            try { handleText(result); }
            finally { frame.recycle(); ocrBusy.set(false); }
        }).addOnFailureListener(e -> {
            frame.recycle();
            ocrBusy.set(false);
            updateUi("OCR 오류", "", "");
        });
    }

    private void handleText(Text result) {
        StringBuilder full = new StringBuilder();
        List<LineBox> lines = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String t = line.getText() == null ? "" : line.getText().trim();
                Rect r = line.getBoundingBox();
                if (!t.isEmpty() && r != null && !t.toLowerCase(Locale.ROOT).contains("ai 추천")) {
                    lines.add(new LineBox(t, new Rect(r)));
                    full.append(t).append('\n');
                }
            }
        }
        String screenText = full.toString().trim();
        if (screenText.length() < 8) return;
        if (containsSensitive(screenText)) {
            clearHighlight();
            showBubble("민감정보 화면 · 분석 일시정지");
            updateUi("민감 화면 감지", shortText(screenText), "");
            return;
        }

        String signature = normalize(screenText);
        if (signature.equals(lastSignature)) return;
        lastSignature = signature;
        updateUi("새 문제 감지 · AI 판단 중", shortText(screenText), "");
        maybeAskAi(screenText, lines);
    }

    private void maybeAskAi(String text, List<LineBox> lines) {
        long now = System.currentTimeMillis();
        if (now - lastAiAt < AI_MIN_INTERVAL_MS || !aiBusy.compareAndSet(false, true)) return;
        lastAiAt = now;
        String apiKey = prefs.getApiKey();
        String model = prefs.getModel();
        if (apiKey.isEmpty()) {
            aiBusy.set(false);
            showBubble("API 키 필요");
            updateUi("API 키 필요", shortText(text), "");
            return;
        }

        aiExecutor.execute(() -> {
            try {
                OpenAiClient.Answer answer = OpenAiClient.solve(apiKey, model, text);
                if (answer.text.isEmpty() || answer.confidence < 45) {
                    clearHighlight();
                    showBubble("확신 부족 · 직접 풀어주세요");
                    updateUi("AI 확신 부족", shortText(text), "");
                } else {
                    LineBox best = findBestLine(answer.text, lines);
                    if (best != null) showAnswer(answer, best.rect);
                    else showAnswer(answer, null);
                    updateUi("추천 완료", shortText(text), answer.text + "  (" + answer.confidence + "%)");
                }
            } catch (Exception e) {
                clearHighlight();
                showBubble("AI 연결 오류");
                updateUi("AI 오류: " + safeMessage(e), shortText(text), "");
            } finally {
                aiBusy.set(false);
            }
        });
    }

    private void showAnswer(OpenAiClient.Answer answer, Rect rect) {
        showBubble("AI 추천: " + answer.text + " · " + answer.confidence + "%");
        if (rect == null) {
            clearHighlight();
            return;
        }
        new Handler(getMainLooper()).post(() -> {
            clearHighlightInternal();
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x1818A558);
            bg.setStroke(dp(4), 0xFF18A558);
            bg.setCornerRadius(dp(12));
            View v = new View(this);
            v.setBackground(bg);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    Math.max(dp(60), rect.width() + dp(20)),
                    Math.max(dp(40), rect.height() + dp(16)),
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                            WindowManager.LayoutParams.FLAG_SECURE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = Math.max(0, rect.left - dp(10));
            lp.y = Math.max(0, rect.top - dp(8));
            try {
                windowManager.addView(v, lp);
                highlight = v;
            } catch (Exception ignored) {}
        });
    }

    private void showBubble(String text) {
        new Handler(getMainLooper()).post(() -> {
            if (answerBubble == null) {
                TextView tv = new TextView(this);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(14f);
                tv.setPadding(dp(12), dp(8), dp(12), dp(8));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xE817202A);
                bg.setCornerRadius(dp(18));
                tv.setBackground(bg);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                                WindowManager.LayoutParams.FLAG_SECURE,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = dp(54);
                try {
                    windowManager.addView(tv, lp);
                    answerBubble = tv;
                } catch (Exception ignored) { return; }
            }
            answerBubble.setText(text);
        });
    }

    private void clearHighlight() {
        new Handler(getMainLooper()).post(this::clearHighlightInternal);
    }

    private void clearHighlightInternal() {
        if (highlight != null) {
            try { windowManager.removeView(highlight); } catch (Exception ignored) {}
            highlight = null;
        }
    }

    private void removeOverlay() {
        new Handler(getMainLooper()).post(() -> {
            clearHighlightInternal();
            if (answerBubble != null) {
                try { windowManager.removeView(answerBubble); } catch (Exception ignored) {}
                answerBubble = null;
            }
        });
    }

    private LineBox findBestLine(String answer, List<LineBox> lines) {
        String a = normalize(answer);
        LineBox best = null;
        double bestScore = 0.0;
        for (LineBox line : lines) {
            String b = normalize(line.text);
            if (b.isEmpty()) continue;
            double score;
            if (b.equals(a)) score = 1.0;
            else if (b.contains(a) || a.contains(b)) score = 0.92;
            else score = similarity(a, b);
            if (score > bestScore) { bestScore = score; best = line; }
        }
        return bestScore >= 0.50 ? best : null;
    }

    private static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int[][] dp = new int[a.length()+1][b.length()+1];
        for (int i=0;i<=a.length();i++) dp[i][0]=i;
        for (int j=0;j<=b.length();j++) dp[0][j]=j;
        for (int i=1;i<=a.length();i++) {
            for (int j=1;j<=b.length();j++) {
                int cost = a.charAt(i-1)==b.charAt(j-1)?0:1;
                dp[i][j]=Math.min(Math.min(dp[i-1][j]+1,dp[i][j-1]+1),dp[i-1][j-1]+cost);
            }
        }
        int max = Math.max(a.length(), b.length());
        return 1.0 - ((double)dp[a.length()][b.length()] / max);
    }

    private boolean containsSensitive(String s) {
        String n = s.toLowerCase(Locale.ROOT);
        String[] keys = {"password", "비밀번호", "인증번호", "otp", "cvv", "카드번호", "계좌번호", "주민등록", "결제", "보안코드"};
        for (String k : keys) if (n.contains(k)) return true;
        return false;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9가-힣]+", " ").trim();
    }

    private static String shortText(String s) {
        String one = s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return one.length() > 180 ? one.substring(0, 180) + "…" : one;
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) return t.getClass().getSimpleName();
        return m.length() > 100 ? m.substring(0, 100) : m;
    }

    private void updateUi(String status, String question, String answer) {
        UiListener listener = uiListener;
        if (listener != null) listener.onUpdate(status, question, answer);
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, CaptureService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 3, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("ClassCard AI Helper")
                .setContentText("화면의 영어 연습 문제를 분석 중")
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_media_pause, "즉시 중지", stopPi)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "실시간 화면 분석", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("화면 공유 기반 영어 학습 분석이 실행 중임을 표시합니다.");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private void stopEverything() {
        running = false;
        removeOverlay();
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (projection != null) { projection.stop(); projection = null; }
        if (captureThread != null) { captureThread.quitSafely(); captureThread = null; }
        stopForeground(STOP_FOREGROUND_REMOVE);
        updateUi("정지", "", "");
    }

    @Override
    public void onDestroy() {
        stopEverything();
        try { recognizer.close(); } catch (Exception ignored) {}
        aiExecutor.shutdownNow();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Nullable
    @Override public IBinder onBind(Intent intent) { return null; }
}
