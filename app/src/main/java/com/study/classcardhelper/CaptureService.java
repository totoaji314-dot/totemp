package com.study.classcardhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptureService extends Service {
    public static final String ACTION_START = "com.study.classcardhelper.START";
    public static final String ACTION_STOP = "com.study.classcardhelper.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL = "study_lens_v3";
    private static final int NOTIFICATION_ID = 43;
    private static final long OCR_INTERVAL_MS = 260L;
    private static final int AUTO_TAP_CONFIDENCE = 82;

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
    private int screenWidth;
    private int screenHeight;
    private volatile boolean running = false;

    private volatile String rememberedEnglish = "";
    private volatile String translatedMeaning = "";
    private volatile String translatedFor = "";
    private volatile String lastTappedWord = "";
    private volatile List<LineBox> cachedChoices = new ArrayList<>();

    private static final class LineBox {
        final String text;
        final Rect rect;
        LineBox(String text, Rect rect) { this.text = text; this.rect = rect; }
    }

    private static final class ChoiceResult {
        final LineBox line;
        final int confidence;
        final String reason;
        ChoiceResult(LineBox line, int confidence, String reason) {
            this.line = line;
            this.confidence = confidence;
            this.reason = reason;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new SecurePrefs(this);
        recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        ShizukuTapManager.init(this);
        OfflineTranslator.get(this).ensureModel((ready, message) -> {
            if (!ready) updateUi("번역 모델 준비 필요", rememberedEnglish, message);
        });
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
                updateUi("화면 공유 종료", rememberedEnglish, "");
                removeOverlay();
            }
        }, new Handler(getMainLooper()));

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        captureThread = new HandlerThread("study-lens-v3-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        virtualDisplay = projection.createVirtualDisplay(
                "StudyLensV3", screenWidth, screenHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);

        running = true;
        showBubble("영어 단어를 기다리는 중…");
        updateUi("실시간 분석 중", "-", "5초 영어 단어를 기다리는 중");
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
            updateUi("글자 인식 오류", rememberedEnglish, "한국어 OCR 오류");
        });
    }

    private void handleText(Text result) {
        List<LineBox> lines = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String t = line.getText() == null ? "" : line.getText().trim();
                Rect r = line.getBoundingBox();
                if (t.isEmpty() || r == null || isOurOverlay(t)) continue;
                lines.add(new LineBox(t, new Rect(r)));
                full.append(t).append('\n');
            }
        }

        String screenText = full.toString().trim();
        if (screenText.length() < 2) return;
        if (containsSensitive(screenText)) {
            clearHighlight();
            showBubble("민감정보 화면 · 분석 정지");
            updateUi("민감 화면 감지", rememberedEnglish, "분석 일시정지");
            return;
        }

        List<LineBox> choices = extractKoreanChoices(lines);
        if (choices.size() >= 3) {
            cachedChoices = copyLines(choices);
            evaluateChoices(cachedChoices);
            return;
        }

        String prompt = detectEnglishPrompt(lines);
        if (!prompt.isEmpty()) rememberPrompt(prompt);
    }

    private void rememberPrompt(String prompt) {
        String normalized = normalizeEnglish(prompt);
        if (normalized.length() < 2) return;
        if (normalized.equals(normalizeEnglish(rememberedEnglish))) {
            showBubble("기억: " + rememberedEnglish);
            return;
        }

        rememberedEnglish = prompt.trim();
        translatedMeaning = "";
        translatedFor = "";
        lastTappedWord = "";
        cachedChoices = new ArrayList<>();
        clearHighlight();

        showBubble("기억: " + rememberedEnglish + " · 뜻 변환 중");
        updateUi("영어 단어 기억 완료", rememberedEnglish, "뜻 변환 중…");

        final String word = rememberedEnglish;
        OfflineTranslator.get(this).translate(word, (translated, error) -> {
            if (!normalizeEnglish(word).equals(normalizeEnglish(rememberedEnglish))) return;
            if (translated == null || translated.trim().isEmpty()) {
                updateUi("뜻 변환 실패", rememberedEnglish, error == null ? "번역 모델 확인 필요" : error);
                showBubble("기억: " + rememberedEnglish + " · 번역 준비 필요");
                return;
            }
            translatedMeaning = translated.trim();
            translatedFor = word;
            showBubble("기억: " + rememberedEnglish + " → " + translatedMeaning);
            updateUi("단어 준비 완료", rememberedEnglish, "뜻: " + translatedMeaning);

            List<LineBox> snapshot = cachedChoices;
            if (snapshot != null && snapshot.size() >= 3) evaluateChoices(snapshot);
        });
    }

    private void evaluateChoices(List<LineBox> choices) {
        if (rememberedEnglish.isEmpty()) {
            clearHighlight();
            showBubble("앞의 영어 단어를 기다리는 중…");
            updateUi("단어 기억 없음", "-", "먼저 5초 영어 화면을 보여주세요");
            return;
        }

        List<String> choiceTexts = new ArrayList<>();
        for (LineBox c : choices) choiceTexts.add(c.text);

        ChoiceResult result = chooseBest(choices, choiceTexts);
        if (result == null || result.line == null || result.confidence < 50) {
            clearHighlight();
            String target = translatedMeaning.isEmpty() ? "뜻 변환 중…" : translatedMeaning;
            showBubble("기억: " + rememberedEnglish + " · " + target);
            updateUi("보기 분석 중", rememberedEnglish, "뜻: " + target);
            return;
        }

        if (prefs.isHighlightEnabled()) {
            showHighlight(result.line.rect);
            showBubble("정답: " + result.line.text + " · " + result.confidence + "%");
        } else {
            clearHighlight();
            removeBubbleOnly();
        }

        updateUi("정답 후보 찾음", rememberedEnglish,
                result.line.text + "  " + result.confidence + "% · " + result.reason);

        if (!prefs.isAutoTapEnabled() || result.confidence < AUTO_TAP_CONFIDENCE) return;
        if (normalizeEnglish(lastTappedWord).equals(normalizeEnglish(rememberedEnglish))) return;

        lastTappedWord = rememberedEnglish;
        final float x = result.line.rect.exactCenterX();
        final float y = result.line.rect.exactCenterY();
        new Handler(getMainLooper()).postDelayed(() -> {
            ShizukuTapManager.tapClassCard(x, y, (success, message) -> {
                if (!success) {
                    lastTappedWord = "";
                    updateUi("자동 터치 대기", rememberedEnglish, message);
                    if (prefs.isHighlightEnabled()) showBubble("정답: " + result.line.text + " · " + message);
                } else {
                    updateUi("자동 터치 완료", rememberedEnglish, result.line.text + " ✓");
                    if (prefs.isHighlightEnabled()) showBubble("자동 선택 ✓  " + result.line.text);
                }
            });
        }, 260L);
    }

    private ChoiceResult chooseBest(List<LineBox> choices, List<String> choiceTexts) {
        String word = rememberedEnglish;
        String translated = translatedMeaning;
        Map<String, String> learned = prefs.getLearnedPairs();
        String learnedMeaning = learned.get(normalizeEnglish(word));

        double best = -1.0;
        double second = -1.0;
        LineBox bestLine = null;
        String reason = "무료 번역";

        for (LineBox line : choices) {
            double score = 0.0;
            if (translated != null && !translated.isEmpty()) score = Math.max(score, koreanSimilarity(translated, line.text));
            if (learnedMeaning != null && !learnedMeaning.isEmpty()) score = Math.max(score, koreanSimilarity(learnedMeaning, line.text));
            if (score > best) {
                second = best;
                best = score;
                bestLine = line;
            } else if (score > second) {
                second = score;
            }
        }

        LocalEnglishSolver.Answer local = LocalEnglishSolver.solve(
                word + "\n한국어 뜻을 고르세요", choiceTexts, learned);
        if (local != null && !local.text.isEmpty()) {
            LineBox localLine = findLine(local.text, choices);
            if (localLine != null) {
                double localScore = Math.max(0.0, Math.min(1.0, local.confidence / 100.0));
                if (bestLine == null || localScore > best) {
                    second = best;
                    best = localScore;
                    bestLine = localLine;
                    reason = local.reason.isEmpty() ? "로컬 단어 사전" : local.reason;
                }
            }
        }

        if (bestLine == null) return null;

        double margin = best - Math.max(0.0, second);
        int confidence;
        if (best >= 0.99) confidence = 99;
        else if (best >= 0.92) confidence = 96;
        else if (best >= 0.82) confidence = 91;
        else if (best >= 0.70) confidence = 85;
        else if (best >= 0.56) confidence = 75;
        else if (best >= 0.42) confidence = 62;
        else confidence = 45;

        if (margin < 0.08 && best < 0.92) confidence = Math.min(confidence, 68);
        if (translated == null || translated.isEmpty()) reason = "로컬 단어 사전";
        else reason = "뜻 " + translated;

        return new ChoiceResult(bestLine, confidence, reason);
    }

    private List<LineBox> extractKoreanChoices(List<LineBox> lines) {
        List<LineBox> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int minY = (int) (screenHeight * 0.25f);
        for (LineBox line : lines) {
            String t = cleanChoice(line.text);
            if (line.rect.centerY() < minY) continue;
            if (!t.matches(".*[가-힣].*")) continue;
            if (t.length() < 1 || t.length() > 55) continue;
            if (isUiKorean(t)) continue;
            String key = normalizeKorean(t);
            if (key.length() < 1 || seen.contains(key)) continue;
            seen.add(key);
            out.add(new LineBox(t, new Rect(line.rect)));
        }

        if (out.size() > 4) {
            List<LineBox> bottom = new ArrayList<>();
            for (int i = Math.max(0, out.size() - 4); i < out.size(); i++) bottom.add(out.get(i));
            return bottom;
        }
        return out;
    }

    private String detectEnglishPrompt(List<LineBox> lines) {
        LineBox best = null;
        double bestScore = -1;
        for (LineBox line : lines) {
            String t = line.text.trim();
            if (!t.matches("[A-Za-z][A-Za-z' -]{1,42}")) continue;
            if (t.split("\\s+").length > 4) continue;
            if (isUiEnglish(t)) continue;
            if (line.rect.centerY() < screenHeight * 0.08f || line.rect.centerY() > screenHeight * 0.82f) continue;

            double centerPenalty = Math.abs(line.rect.centerX() - screenWidth / 2.0) / Math.max(1.0, screenWidth);
            double score = line.rect.height() * 2.2 + line.rect.width() * 0.03 - centerPenalty * 80.0;
            if (score > bestScore) {
                bestScore = score;
                best = line;
            }
        }
        return best == null ? "" : best.text.trim();
    }

    private static List<LineBox> copyLines(List<LineBox> in) {
        List<LineBox> out = new ArrayList<>();
        for (LineBox l : in) out.add(new LineBox(l.text, new Rect(l.rect)));
        return out;
    }

    private static LineBox findLine(String answer, List<LineBox> lines) {
        String a = normalizeKorean(answer);
        for (LineBox l : lines) {
            String b = normalizeKorean(l.text);
            if (a.equals(b) || a.contains(b) || b.contains(a)) return l;
        }
        return null;
    }

    private static double koreanSimilarity(String a, String b) {
        String x = normalizeKorean(a);
        String y = normalizeKorean(b);
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        if (x.equals(y)) return 1.0;
        if (x.contains(y) || y.contains(x)) return 0.95;

        double tokenScore = 0.0;
        String[] xt = x.split(" ");
        for (String token : xt) {
            if (token.length() >= 2 && y.contains(token)) tokenScore = Math.max(tokenScore, 0.78);
        }

        double edit = similarity(x.replace(" ", ""), y.replace(" ", ""));
        return Math.max(tokenScore, edit);
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

    private void showHighlight(Rect rect) {
        new Handler(getMainLooper()).post(() -> {
            clearHighlightInternal();
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x1919C37D);
            bg.setStroke(dp(4), 0xFF16A765);
            bg.setCornerRadius(dp(16));
            View v = new View(this);
            v.setBackground(bg);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    Math.max(dp(70), rect.width() + dp(24)),
                    Math.max(dp(46), rect.height() + dp(20)),
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                            WindowManager.LayoutParams.FLAG_SECURE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = Math.max(0, rect.left - dp(12));
            lp.y = Math.max(0, rect.top - dp(10));
            try {
                windowManager.addView(v, lp);
                highlight = v;
            } catch (Exception ignored) {}
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
                lp.y = dp(48);
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
                try { windowManager.removeView(answerBubble); } catch (Exception ignored) {}
                answerBubble = null;
            }
        });
    }

    private void clearHighlight() { new Handler(getMainLooper()).post(this::clearHighlightInternal); }

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

    private boolean containsSensitive(String s) {
        String n = s.toLowerCase(Locale.ROOT);
        String[] keys = {"password", "비밀번호", "인증번호", "otp", "cvv", "카드번호", "계좌번호", "주민등록", "결제", "보안코드"};
        for (String k : keys) if (n.contains(k)) return true;
        return false;
    }

    private static boolean isOurOverlay(String s) {
        String n = s.toLowerCase(Locale.ROOT);
        return n.contains("study lens") || n.startsWith("기억:") || n.startsWith("정답:") || n.contains("자동 선택") || n.contains("영어 단어를 기다리는 중");
    }

    private static boolean isUiEnglish(String s) {
        String n = s.toLowerCase(Locale.ROOT).trim();
        String[] exact = {"classcard", "next", "skip", "correct", "wrong", "start", "loading", "study lens", "word test", "vocabulary test"};
        for (String e : exact) if (n.equals(e)) return true;
        return n.contains("classcard") || n.contains("study lens");
    }

    private static boolean isUiKorean(String s) {
        String n = s.replace(" ", "");
        String[] keys = {"다음문제", "남은시간", "정답률", "학습종료", "문제수", "점수", "결과보기", "다시하기", "정답표시", "자동터치"};
        for (String k : keys) if (n.contains(k)) return true;
        return false;
    }

    private static String cleanChoice(String s) {
        return s == null ? "" : s.trim().replaceFirst("^[①②③④1-4A-Da-d][.)]?\\s*", "").trim();
    }

    private static String normalizeEnglish(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z' -]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static String normalizeKorean(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT)
                .replaceAll("[^가-힣a-z0-9 ]", " ")
                .replaceAll("\\s+", " ").trim();
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
                .setContentTitle("Study Lens V3")
                .setContentText("5초 단어 → 4지선다 실시간 분석 중")
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_media_pause, "즉시 중지", stopPi)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Study Lens 실시간 분석", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("영어 단어와 한국어 4지선다를 기기에서 분석합니다.");
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
        updateUi("정지", rememberedEnglish, "");
    }

    @Override
    public void onDestroy() {
        stopEverything();
        try { recognizer.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Nullable
    @Override public IBinder onBind(Intent intent) { return null; }
}
