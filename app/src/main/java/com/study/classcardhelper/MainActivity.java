package com.study.classcardhelper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class MainActivity extends AppCompatActivity {
    private SecurePrefs prefs;
    private SwitchMaterial highlightSwitch;
    private SwitchMaterial autoTapSwitch;
    private TextView status;
    private TextView lastQuestion;
    private TextView lastAnswer;
    private TextView shizukuStatus;
    private TextView modelStatus;

    private final ActivityResultLauncher<Intent> captureLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent service = new Intent(this, CaptureService.class);
                    service.setAction(CaptureService.ACTION_START);
                    service.putExtra(CaptureService.EXTRA_RESULT_CODE, result.getResultCode());
                    service.putExtra(CaptureService.EXTRA_RESULT_DATA, result.getData());
                    ContextCompat.startForegroundService(this, service);
                    setStatus("분석 중", "영어 단어를 기다리고 있어요");
                    Toast.makeText(this, "ClassCard를 열면 5초 단어 → 4지선다를 자동 추적합니다.", Toast.LENGTH_LONG).show();
                } else {
                    setStatus("정지", "화면 공유가 취소됐어요");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new SecurePrefs(this);
        highlightSwitch = findViewById(R.id.highlightSwitch);
        autoTapSwitch = findViewById(R.id.autoTapSwitch);
        status = findViewById(R.id.status);
        lastQuestion = findViewById(R.id.lastQuestion);
        lastAnswer = findViewById(R.id.lastAnswer);
        shizukuStatus = findViewById(R.id.shizukuStatus);
        modelStatus = findViewById(R.id.modelStatus);

        MaterialButton startBtn = findViewById(R.id.startBtn);
        MaterialButton stopBtn = findViewById(R.id.stopBtn);
        MaterialButton shizukuBtn = findViewById(R.id.shizukuBtn);
        MaterialButton openShizukuBtn = findViewById(R.id.openShizukuBtn);
        MaterialButton modelBtn = findViewById(R.id.modelBtn);

        highlightSwitch.setChecked(prefs.isHighlightEnabled());
        autoTapSwitch.setChecked(prefs.isAutoTapEnabled());

        highlightSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.setHighlightEnabled(isChecked);
            Toast.makeText(this, isChecked ? "정답 표시 ON" : "정답 표시 OFF", Toast.LENGTH_SHORT).show();
        });

        autoTapSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.setAutoTapEnabled(isChecked);
            if (isChecked && !ShizukuTapManager.isReady()) {
                Toast.makeText(this, "자동 터치는 Shizuku 연결 후 작동합니다.", Toast.LENGTH_LONG).show();
            }
        });

        ShizukuTapManager.init(this);
        ShizukuTapManager.setStatusListener(s -> runOnUiThread(() -> shizukuStatus.setText(s)));
        ShizukuTapManager.refreshStatus();

        shizukuBtn.setOnClickListener(v -> ShizukuTapManager.requestPermissionAndBind());
        openShizukuBtn.setOnClickListener(v -> openShizuku());

        modelBtn.setOnClickListener(v -> prepareModel());
        prepareModel();

        startBtn.setOnClickListener(v -> startFlow());
        stopBtn.setOnClickListener(v -> stopServiceFlow());

        CaptureService.setUiListener((s, q, a) -> runOnUiThread(() -> {
            if (s != null && !s.isEmpty()) setStatus(s, "무료 · 기기 내 분석");
            if (q != null && !q.isEmpty()) lastQuestion.setText(q);
            if (a != null && !a.isEmpty()) lastAnswer.setText(a);
        }));

        setStatus("정지", "1. 번역 모델 준비 → 2. Shizuku 연결 → 3. 화면 분석 시작");
    }

    @Override
    protected void onResume() {
        super.onResume();
        ShizukuTapManager.refreshStatus();
    }

    private void prepareModel() {
        modelStatus.setText("무료 번역 모델 확인 중…");
        OfflineTranslator.get(this).ensureModel((ready, message) -> runOnUiThread(() -> {
            modelStatus.setText(message);
            if (ready) Toast.makeText(this, "영어→한국어 오프라인 번역 준비 완료", Toast.LENGTH_SHORT).show();
        }));
    }

    private void openShizuku() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch != null) {
                startActivity(launch);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")));
        } catch (Throwable t) {
            Toast.makeText(this, "Shizuku를 설치해주세요.", Toast.LENGTH_LONG).show();
        }
    }

    private void startFlow() {
        prefs.setHighlightEnabled(highlightSwitch.isChecked());
        prefs.setAutoTapEnabled(autoTapSwitch.isChecked());

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "'다른 앱 위에 표시'를 허용한 뒤 다시 시작해주세요.", Toast.LENGTH_LONG).show();
            return;
        }
        if (autoTapSwitch.isChecked() && !ShizukuTapManager.isReady()) {
            Toast.makeText(this, "Shizuku 자동 터치가 아직 연결되지 않았습니다. 정답 표시는 작동합니다.", Toast.LENGTH_LONG).show();
        }
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        captureLauncher.launch(mgr.createScreenCaptureIntent());
    }

    private void stopServiceFlow() {
        Intent service = new Intent(this, CaptureService.class);
        service.setAction(CaptureService.ACTION_STOP);
        startService(service);
        setStatus("정지", "화면 분석을 멈췄어요");
        lastAnswer.setText("-");
    }

    private void setStatus(String title, String detail) {
        status.setText(title);
        TextView statusDetail = findViewById(R.id.statusDetail);
        statusDetail.setText(detail);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        CaptureService.setUiListener(null);
        ShizukuTapManager.setStatusListener(null);
    }
}
