package com.study.classcardhelper;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class SecurePrefs {
    private static final String PREF = "study_lens_settings_v2";
    private static final String KEY_HIGHLIGHT = "highlight_enabled";
    private static final String KEY_AUTOTAP = "auto_tap_enabled";
    private static final String LEARN_PREFIX = "learn_";

    private final SharedPreferences prefs;

    public SecurePrefs(Context context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public boolean isHighlightEnabled() {
        return prefs.getBoolean(KEY_HIGHLIGHT, true);
    }

    public void setHighlightEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_HIGHLIGHT, enabled).apply();
    }

    public boolean isAutoTapEnabled() {
        return prefs.getBoolean(KEY_AUTOTAP, false);
    }

    public void setAutoTapEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTOTAP, enabled).apply();
    }

    public void saveLearnedPair(String english, String korean) {
        String key = normalizeEnglish(english);
        String value = korean == null ? "" : korean.trim();
        if (key.length() < 2 || value.length() < 1) return;
        prefs.edit().putString(LEARN_PREFIX + key, value).apply();
    }

    public Map<String, String> getLearnedPairs() {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(LEARN_PREFIX) || !(entry.getValue() instanceof String)) continue;
            String k = entry.getKey().substring(LEARN_PREFIX.length());
            String v = (String) entry.getValue();
            if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
        }
        return out;
    }

    private static String normalizeEnglish(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z' -]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
