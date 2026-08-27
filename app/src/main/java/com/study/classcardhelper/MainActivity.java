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
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private SecurePrefs securePrefs;
    private EditText apiKey, modelName;
    private TextView status, lastQuestion, lastAnswer;
    private final ActivityResultLauncher<Intent> captureLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            Intent service = new Intent(this, CaptureService.class);
            service.setAction(CaptureService.ACTION_START);
            service.putExtra(CaptureService.EXTRA_RESULT_CODE, result.getResultCode());
            service.putExtra(CaptureService.EXTRA_RESULT_DATA, result.getData());
            ContextCompat.startForegroundService(this, service);
            status.setText("상태: 실시간 분석 시작됨");
            Toast.makeText(this, "클래스카드를 열면 화면을 읽기 시작합니다.", Toast.LENGTH_LONG).show();
        } else status.setText("상태: 화면 공유 허용 취소");
    });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        securePrefs = new SecurePrefs(this);
        apiKey = findViewById(R.id.apiKey); modelName = findViewById(R.id.modelName);
        status = findViewById(R.id.status); lastQuestion = findViewById(R.id.lastQuestion); lastAnswer = findViewById(R.id.lastAnswer);
        Button saveApi = findViewById(R.id.saveApi), startBtn = findViewById(R.id.startBtn), stopBtn = findViewById(R.id.stopBtn);
        apiKey.setText(securePrefs.getApiKey()); modelName.setText(securePrefs.getModel());
        saveApi.setOnClickListener(v -> { securePrefs.saveApiKey(apiKey.getText().toString().trim()); securePrefs.saveModel(modelName.getText().toString().trim()); Toast.makeText(this, "기기에 저장했습니다.", Toast.LENGTH_SHORT).show(); });
        startBtn.setOnClickListener(v -> startFlow());
        stopBtn.setOnClickListener(v -> { Intent service = new Intent(this, CaptureService.class); service.setAction(CaptureService.ACTION_STOP); startService(service); status.setText("상태: 정지"); lastAnswer.setText("AI 추천: -"); });
        CaptureService.setUiListener((s,q,a) -> runOnUiThread(() -> { if (s != null) status.setText("상태: " + s); if (q != null && !q.isEmpty()) lastQuestion.setText("마지막 인식: " + q); if (a != null && !a.isEmpty()) lastAnswer.setText("AI 추천: " + a); }));
    }

    private void startFlow() {
        securePrefs.saveApiKey(apiKey.getText().toString().trim()); securePrefs.saveModel(modelName.getText().toString().trim());
        if (securePrefs.getApiKey().isEmpty()) { Toast.makeText(this, "먼저 OpenAI API 키를 입력해주세요.", Toast.LENGTH_LONG).show(); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},1001);
        if (!Settings.canDrawOverlays(this)) { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); Toast.makeText(this, "'다른 앱 위에 표시'를 허용한 뒤 다시 시작을 눌러주세요.", Toast.LENGTH_LONG).show(); return; }
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        captureLauncher.launch(mgr.createScreenCaptureIntent());
    }

    @Override protected void onDestroy() { super.onDestroy(); CaptureService.setUiListener(null); }
}
