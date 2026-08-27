package com.study.classcardhelper;

import android.content.Context;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

public final class OfflineTranslator {
    public interface ModelCallback { void onResult(boolean ready, String message); }
    public interface TranslateCallback { void onResult(String translated, String error); }

    private static OfflineTranslator instance;
    private final Translator translator;
    private volatile boolean ready = false;
    private volatile boolean downloading = false;

    private OfflineTranslator(Context context) {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build();
        translator = Translation.getClient(options);
    }

    public static synchronized OfflineTranslator get(Context context) {
        if (instance == null) instance = new OfflineTranslator(context.getApplicationContext());
        return instance;
    }

    public boolean isReady() { return ready; }

    public synchronized void ensureModel(ModelCallback callback) {
        if (ready) {
            if (callback != null) callback.onResult(true, "무료 번역 모델 준비됨");
            return;
        }
        if (downloading) {
            if (callback != null) callback.onResult(false, "무료 번역 모델 다운로드 중");
            return;
        }
        downloading = true;
        DownloadConditions conditions = new DownloadConditions.Builder().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(v -> {
                    ready = true;
                    downloading = false;
                    if (callback != null) callback.onResult(true, "무료 번역 모델 준비됨");
                })
                .addOnFailureListener(e -> {
                    ready = false;
                    downloading = false;
                    String m = e.getMessage();
                    if (m == null || m.trim().isEmpty()) m = "모델 다운로드 실패";
                    if (callback != null) callback.onResult(false, m);
                });
    }

    public void translate(String english, TranslateCallback callback) {
        String input = english == null ? "" : english.trim();
        if (input.isEmpty()) {
            if (callback != null) callback.onResult("", "영어 단어 없음");
            return;
        }
        if (!ready) {
            ensureModel((ok, message) -> {
                if (!ok) {
                    if (callback != null) callback.onResult("", message);
                } else {
                    translateInternal(input, callback);
                }
            });
        } else {
            translateInternal(input, callback);
        }
    }

    private void translateInternal(String input, TranslateCallback callback) {
        translator.translate(input)
                .addOnSuccessListener(result -> {
                    if (callback != null) callback.onResult(result == null ? "" : result.trim(), "");
                })
                .addOnFailureListener(e -> {
                    String m = e.getMessage();
                    if (m == null || m.trim().isEmpty()) m = "번역 실패";
                    if (callback != null) callback.onResult("", m);
                });
    }
}
