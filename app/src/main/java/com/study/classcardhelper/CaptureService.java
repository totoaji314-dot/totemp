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
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptureService extends Service {
    public static final String ACTION_START = "com.study.classcardhelper.START";
    public static final String ACTION_STOP = "com.study.classcardhelper.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL = "screen_analysis";
    private static final int NOTIFICATION_ID = 41;
    private static final long OCR_INTERVAL_MS = 320L;
    private static final long ANSWER_MIN_INTERVAL_MS = 650L;
    private static final int AUTO_TAP_MIN_CONFIDENCE = 78;

    public interface UiListener { void onUpdate(String status, String question, String answer); }
    private static volatile UiListener uiListener;
    public static void setUiListener(UiListener listener) { uiListener = listener; }

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private TextRecognizer recognizer;
    private SecurePrefs prefs;
    private WindowManager windowManager;
    private TextView answerBubble;
    private View highlight;
    private long lastOcrAt = 0L;
    private long lastAnswerAt = 0L;
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
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(NOTIFICATION_ID, n);

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
        captureThread = new HandlerThread("study-lens-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        virtualDisplay = projection.createVirtualDisplay("StudyLens",
                screenWidth, screenHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);
        running = true;
        showBubble("무료 로컬 분석 준비됨");
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
            updateUi("글자 인식 오류", "", "");
        });
    }

    private void handleText(Text result) {
        StringBuilder full = new StringBuilder();
        List<LineBox> lines = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String t = line.getText() == null ? "" : line.getText().trim();
                Rect r = line.getBoundingBox();
                String low = t.toLowerCase(Locale.ROOT);
                if (!t.isEmpty() && r != null && !low.contains("study lens") && !low.contains("무료 로컬 분석")) {
                    lines.add(new LineBox(t, new Rect(r)));
                    full.append(t).append('\n');
                }
            }
        }
        String screenText = full.toString().trim();
        if (screenText.length() < 6) return;
        if (containsSensitive(screenText)) {
            clearHighlight();
            showBubble("민감정보 화면 · 분석 정지");
            updateUi("민감 화면 감지", shortText(screenText), "");
            return;
        }

        learnPairIfPossible(lines, screenText);

        String signature = normalize(screenText);
        if (signature.equals(lastSignature)) return;
        lastSignature = signature;
        long now = System.currentTimeMillis();
        if (now - lastAnswerAt < ANSWER_MIN_INTERVAL_MS) return;
        lastAnswerAt = now;

        List<String> choices = likelyChoices(lines);
        LocalEnglishSolver.Answer answer = LocalEnglishSolver.solve(screenText, choices, prefs.getLearnedPairs());
        updateUi("새 문제 분석 완료", shortText(screenText), answer.text.isEmpty() ? "판단 보류" : answer.text + " (" + answer.confidence + "%)");

        if (answer.text.isEmpty() || answer.confidence < 45) {
            clearHighlight();
            if (prefs.isHighlightEnabled()) showBubble("확신 부족 · 직접 풀어주세요");
            return;
        }

        LineBox best = findBestLine(answer.text, lines);
        boolean assessment = containsAssessment(screenText);
        presentAnswer(answer, best == null ? null : best.rect, assessment);
    }

    private void presentAnswer(LocalEnglishSolver.Answer answer, Rect rect, boolean assessment) {
        if (prefs.isHighlightEnabled()) {
            showBubble("추천: " + answer.text + " · " + answer.confidence + "%");
            if (rect != null) showHighlight(rect);
            else clearHighlight();
        } else {
            clearHighlight();
            removeBubbleOnly();
        }

        if (!prefs.isAutoTapEnabled() || rect == null || answer.confidence < AUTO_TAP_MIN_CONFIDENCE) return;
        if (assessment) {
            if (prefs.isHighlightEnabled()) showBubble("평가/점수 화면 · 자동 터치 OFF");
            updateUi("평가 화면에서 자동 터치 차단", "", answer.text);
            return;
        }
        if (!StudyAccessibilityService.isReady()) {
            if (prefs.isHighlightEnabled()) showBubble("자동 터치 권한을 켜주세요");
            updateUi("자동 터치 권한 필요", "", answer.text);
            return;
        }

        final float x = rect.exactCenterX();
        final float y = rect.exactCenterY();
        new Handler(getMainLooper()).postDelayed(() -> {
            boolean ok = StudyAccessibilityService.tap(x, y);
            if (ok) {
                if (prefs.isHighlightEnabled()) showBubble("연습 자동 선택 ✓  " + answer.text);
                updateUi("연습 자동 터치 완료", "", answer.text);
            } else {
                if (prefs.isHighlightEnabled()) showBubble("ClassCard 화면에서만 자동 터치 가능");
                updateUi("자동 터치 대기", "", answer.text);
            }
        }, 380L);
    }

    private List<String> likelyChoices(List<LineBox> lines) {
        List<String> out = new ArrayList<>();
        int cutoff = (int)(screenHeight * 0.30f);
        for (LineBox l : lines) {
            String t = l.text.trim();
            if (l.rect.centerY() < cutoff) continue;
            if (t.length() > 70 || t.length() < 1) continue;
            String low = t.toLowerCase(Locale.ROOT);
            if (low.contains("classcard") || low.contains("study lens") || low.contains("점수") || low.contains("남은 시간") || low.contains("다음")) continue;
            out.add(t);
        }
        if (out.size() < 2) {
            out.clear();
            int start = Math.max(0, lines.size() - 6);
            for (int i = start; i < lines.size(); i++) out.add(lines.get(i).text);
        }
        return out;
    }

    private void learnPairIfPossible(List<LineBox> lines, String screenText) {
        if (containsAssessment(screenText) || screenText.contains("___") || screenText.contains("____")) return;
        List<String> english = new ArrayList<>();
        List<String> korean = new ArrayList<>();
        for (LineBox l : lines) {
            String t = l.text.trim();
            if (t.length() > 42) continue;
            if (t.matches("[A-Za-z][A-Za-z' -]{1,35}") && t.split("\\s+").length <= 5 && !isUiLine(t)) english.add(t);
            if (t.matches(".*[가-힣].*") && !isUiLine(t) && t.length() <= 32) korean.add(t);
        }
        if (english.size() == 1 && korean.size() == 1) prefs.saveLearnedPair(english.get(0), korean.get(0));
    }

    private boolean isUiLine(String s) {
        String n = s.toLowerCase(Locale.ROOT);
        return n.contains("classcard") || n.contains("정답") || n.contains("다음") || n.contains("학습") || n.contains("점수") || n.contains("study lens");
    }

    private boolean containsAssessment(String s) {
        String n = s.toLowerCase(Locale.ROOT);
        String[] keys = {"점수", "성적", "제출", "평가", "시험", "과제", "숙제", "선생님", "score", "submit", "grade", "result", "ranking", "제한시간"};
        for (String k : keys) if (n.contains(k)) return true;
        return false;
    }

    private void showHighlight(Rect rect) {
        new Handler(getMainLooper()).post(() -> {
            clearHighlightInternal();
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x1819C37D);
            bg.setStroke(dp(4), 0xFF16A765);
            bg.setCornerRadius(dp(16));
            View v = new View(this);
            v.setBackground(bg);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    Math.max(dp(64), rect.width() + dp(22)),
                    Math.max(dp(44), rect.height() + dp(18)),
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                            WindowManager.LayoutParams.FLAG_SECURE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = Math.max(0, rect.left - dp(11));
            lp.y = Math.max(0, rect.top - dp(9));
            try {
                windowManager.addView(v, lp);
                highlight = v;
            } catch (Exception ignored) { }
        });
    }

    private void showBubble(String text) {
        if (!prefs.isHighlightEnabled() && !text.contains("민감정보")) return;
        new Handler(getMainLooper()).post(() -> {
            if (answerBubble == null) {
                TextView tv = new TextView(this);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(13f);
                tv.setPadding(dp(14), dp(9), dp(14), dp(9));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xED172033);
                bg.setCornerRadius(dp(22));
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
                lp.y = dp(50);
                try {
                    windowManager.addView(tv, lp);
                    answerBubble = tv;
                } catch (Exception ignored) { return; }
            }
            answerBubble.setText(text);
        });
    }

    private void removeBubbleOnly() {
        new Handler(getMainLooper()).post(() -> {
            if (answerBubble != null) {
                try { windowManager.removeView(answerBubble); } catch (Exception ignored) { }
                answerBubble = null;
            }
        });
    }

    private void clearHighlight() { new Handler(getMainLooper()).post(this::clearHighlightInternal); }

    private void clearHighlightInternal() {
        if (highlight != null) {
            try { windowManager.removeView(highlight); } catch (Exception ignored) { }
            highlight = null;
        }
    }

    private void removeOverlay() {
        new Handler(getMainLooper()).post(() -> {
            clearHighlightInternal();
            if (answerBubble != null) {
                try { windowManager.removeView(answerBubble); } catch (Exception ignored) { }
                answerBubble = null;
            }
        });
    }

    private LineBox findBestLine(String answer, List<LineBox> lines) {
        String a = normalize(answer);
        LineBox best = null;
        double bestScore = 0.0;
        for (LineBox line : lines) {
            String b = normalize(line.text.replaceFirst("^[A-Da-d][.)]\\s*", ""));
            if (b.isEmpty()) continue;
            double score;
            if (b.equals(a)) score = 1.0;
            else if (b.contains(a) || a.contains(b)) score = 0.93;
            else score = similarity(a, b);
            if (score > bestScore) { bestScore = score; best = line; }
        }
        return bestScore >= 0.52 ? best : null;
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

    private static String normalize(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9가-힣]+", " ").trim(); }

    private static String shortText(String s) {
        String one = s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return one.length() > 180 ? one.substring(0, 180) + "…" : one;
    }

    private void updateUi(String status, String question, String answer) {
        UiListener listener = uiListener;
        if (listener != null) listener.onUpdate(status, question, answer);
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, CaptureService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("ClassCard Study Lens")
                .setContentText("무료 로컬 화면 분석 실행 중")
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_media_pause, "즉시 중지", stopPi)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "실시간 화면 분석", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("영어 학습 화면을 기기에서 분석합니다.");
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
        try { recognizer.close(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Nullable
    @Override public IBinder onBind(Intent intent) { return null; }
}
