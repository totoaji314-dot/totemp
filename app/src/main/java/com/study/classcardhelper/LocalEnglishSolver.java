package com.study.classcardhelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LocalEnglishSolver {
    public static final class Answer {
        public final String text;
        public final int confidence;
        public final String reason;
        Answer(String text, int confidence, String reason) {
            this.text = text == null ? "" : text.trim();
            this.confidence = Math.max(0, Math.min(100, confidence));
            this.reason = reason == null ? "" : reason;
        }
    }

    private static final Map<String, String> BASE = new LinkedHashMap<>();
    private static final Map<String, String> PAST = new HashMap<>();
    private static final Map<String, String> PARTICIPLE = new HashMap<>();

    static {
        put("apple","사과"); put("banana","바나나"); put("book","책"); put("school","학교");
        put("teacher","선생님"); put("student","학생"); put("friend","친구"); put("family","가족");
        put("mother","어머니"); put("father","아버지"); put("brother","형제"); put("sister","자매");
        put("house","집"); put("home","집"); put("room","방"); put("door","문"); put("window","창문");
        put("water","물"); put("food","음식"); put("breakfast","아침식사"); put("lunch","점심"); put("dinner","저녁식사");
        put("happy","행복한"); put("sad","슬픈"); put("angry","화난"); put("tired","피곤한"); put("hungry","배고픈");
        put("big","큰"); put("small","작은"); put("long","긴"); put("short","짧은"); put("fast","빠른"); put("slow","느린");
        put("good","좋은"); put("bad","나쁜"); put("new","새로운"); put("old","오래된"); put("young","어린");
        put("day","날"); put("night","밤"); put("morning","아침"); put("afternoon","오후"); put("evening","저녁");
        put("today","오늘"); put("tomorrow","내일"); put("yesterday","어제"); put("week","주"); put("month","달"); put("year","년");
        put("go","가다"); put("come","오다"); put("run","달리다"); put("walk","걷다"); put("sit","앉다"); put("stand","서다");
        put("eat","먹다"); put("drink","마시다"); put("sleep","자다"); put("read","읽다"); put("write","쓰다"); put("speak","말하다");
        put("listen","듣다"); put("look","보다"); put("see","보다"); put("watch","보다"); put("make","만들다"); put("do","하다");
        put("have","가지다"); put("get","얻다"); put("give","주다"); put("take","가져가다"); put("buy","사다"); put("sell","팔다");
        put("know","알다"); put("think","생각하다"); put("want","원하다"); put("need","필요하다"); put("like","좋아하다"); put("love","사랑하다");
        put("help","돕다"); put("play","놀다"); put("study","공부하다"); put("learn","배우다"); put("teach","가르치다");
        put("beautiful","아름다운"); put("important","중요한"); put("different","다른"); put("same","같은"); put("easy","쉬운"); put("difficult","어려운");
        put("always","항상"); put("usually","보통"); put("often","자주"); put("sometimes","때때로"); put("never","결코 ~않다");

        irregular("go", "went", "gone"); irregular("come", "came", "come"); irregular("see", "saw", "seen");
        irregular("eat", "ate", "eaten"); irregular("drink", "drank", "drunk"); irregular("write", "wrote", "written");
        irregular("read", "read", "read"); irregular("take", "took", "taken"); irregular("give", "gave", "given");
        irregular("get", "got", "gotten"); irregular("make", "made", "made"); irregular("do", "did", "done");
        irregular("have", "had", "had"); irregular("buy", "bought", "bought"); irregular("think", "thought", "thought");
        irregular("know", "knew", "known"); irregular("run", "ran", "run"); irregular("sit", "sat", "sat");
        irregular("stand", "stood", "stood"); irregular("speak", "spoke", "spoken"); irregular("sleep", "slept", "slept");
        irregular("teach", "taught", "taught");
    }

    private LocalEnglishSolver() { }

    public static Answer solve(String screenText, List<String> visibleChoices, Map<String, String> learned) {
        if (screenText == null) return none();
        String raw = screenText.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        List<String> choices = cleanChoices(visibleChoices);
        Map<String, String> dictionary = new LinkedHashMap<>(BASE);
        if (learned != null) dictionary.putAll(learned);

        Answer a = vocabularySolve(raw, lower, choices, dictionary);
        if (!a.text.isEmpty()) return a;
        a = grammarSolve(raw, lower, choices);
        if (!a.text.isEmpty()) return a;
        a = simpleDefinitionSolve(lower, choices, dictionary);
        if (!a.text.isEmpty()) return a;
        return none();
    }

    private static Answer vocabularySolve(String raw, String lower, List<String> choices, Map<String,String> dict) {
        String questionEnglish = findProminentEnglish(raw);
        String questionKorean = findProminentKorean(raw);
        boolean asksMeaning = lower.contains("뜻") || lower.contains("의미") || lower.contains("meaning") || lower.contains("한국어");
        boolean asksEnglish = lower.contains("영어로") || lower.contains("영어 단어") || lower.contains("english word");
        boolean koreanChoices = countKorean(choices) >= 2;
        boolean englishChoices = countEnglish(choices) >= 2;

        if (!questionEnglish.isEmpty() && (asksMeaning || koreanChoices)) {
            String meaning = dict.get(normEnglish(questionEnglish));
            if (meaning != null) {
                String exact = bestContains(choices, meaning);
                if (!exact.isEmpty()) return new Answer(exact, 96, "단어 뜻");
            }
        }

        if (!questionKorean.isEmpty() && (asksEnglish || englishChoices)) {
            for (Map.Entry<String,String> e : dict.entrySet()) {
                if (koreanSimilar(questionKorean, e.getValue())) {
                    String exact = bestEnglishChoice(choices, e.getKey());
                    if (!exact.isEmpty()) return new Answer(exact, 94, "영단어");
                }
            }
        }
        return none();
    }

    private static Answer simpleDefinitionSolve(String lower, List<String> choices, Map<String,String> dict) {
        if (choices.size() < 2) return none();
        for (Map.Entry<String,String> e : dict.entrySet()) {
            if (lower.matches("(?s).*\\b" + java.util.regex.Pattern.quote(e.getKey()) + "\\b.*") && countKorean(choices) >= 2) {
                String exact = bestContains(choices, e.getValue());
                if (!exact.isEmpty()) return new Answer(exact, 84, "기본 단어 사전");
            }
        }
        return none();
    }

    private static Answer grammarSolve(String raw, String lower, List<String> choices) {
        List<String> words = englishSingleWordChoices(choices);
        if (words.size() < 2) return none();

        if (lower.contains("every day") || lower.contains("usually") || lower.contains("often") || lower.contains("always") || lower.contains("sometimes")) {
            boolean third = lower.matches("(?s).*(\\bhe\\b|\\bshe\\b|\\bit\\b).*\\b(every day|usually|often|always|sometimes)\\b.*");
            String c = third ? chooseThirdPerson(words) : chooseBase(words);
            if (!c.isEmpty()) return new Answer(c, third ? 88 : 82, third ? "현재시제 3인칭 단수" : "현재시제");
        }

        if (lower.contains("yesterday") || lower.contains(" last ") || lower.contains(" ago") || lower.startsWith("last ")) {
            String c = choosePast(words);
            if (!c.isEmpty()) return new Answer(c, 90, "과거시제");
        }

        if (lower.matches("(?s).*(\\bcan\\b|\\bmust\\b|\\bshould\\b|\\bwill\\b|\\bmay\\b|\\bmight\\b|\\bdid\\b|\\bdoes\\b|\\bdo\\b)\\s+[_—-]{2,}.*")) {
            String c = chooseBase(words);
            if (!c.isEmpty()) return new Answer(c, 94, "조동사 뒤 동사원형");
        }

        if (lower.matches("(?s).*\\bto\\s+[_—-]{2,}.*")) {
            String c = chooseBase(words);
            if (!c.isEmpty()) return new Answer(c, 90, "to 뒤 동사원형");
        }

        if (lower.matches("(?s).*(\\bhave\\b|\\bhas\\b)\\s+[_—-]{2,}.*")) {
            String c = chooseParticiple(words);
            if (!c.isEmpty()) return new Answer(c, 92, "현재완료 과거분사");
        }

        if (lower.matches("(?s).*(\\bam\\b|\\bis\\b|\\bare\\b|\\bwas\\b|\\bwere\\b)\\s+[_—-]{2,}.*")) {
            for (String c : words) if (normEnglish(c).endsWith("ing")) return new Answer(c, 83, "be동사 + -ing");
        }

        if (lower.contains(" than ")) {
            for (String c : choices) {
                String n = normEnglish(c);
                if (n.endsWith("er") || n.startsWith("more ")) return new Answer(c, 80, "비교급");
            }
        }
        return none();
    }

    private static String chooseThirdPerson(List<String> choices) {
        for (String c : choices) {
            String n = normEnglish(c);
            if (n.endsWith("ies") || n.endsWith("es") || (n.endsWith("s") && !n.endsWith("ss"))) return c;
        }
        return "";
    }

    private static String chooseBase(List<String> choices) {
        for (String c : choices) {
            String n = normEnglish(c);
            if (n.isEmpty()) continue;
            if (!n.endsWith("ing") && !n.endsWith("ed") && !n.endsWith("ies") && !n.endsWith("es") && !PAST.containsValue(n)) return c;
        }
        return "";
    }

    private static String choosePast(List<String> choices) {
        for (String c : choices) {
            String n = normEnglish(c);
            if (PAST.containsValue(n) || n.endsWith("ed")) return c;
        }
        return "";
    }

    private static String chooseParticiple(List<String> choices) {
        for (String c : choices) {
            String n = normEnglish(c);
            if (PARTICIPLE.containsValue(n) || n.endsWith("ed") || n.endsWith("en")) return c;
        }
        return "";
    }

    private static List<String> cleanChoices(List<String> input) {
        if (input == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String s : input) {
            if (s == null) continue;
            String t = s.trim().replaceAll("^[A-Da-d][.)]\\s*", "");
            String l = t.toLowerCase(Locale.ROOT);
            if (t.length() < 1 || t.length() > 65) continue;
            if (l.contains("classcard") || l.contains("study lens") || l.contains("정답 표시") || l.contains("자동 터치") || l.contains("다음 문제")) continue;
            if (t.matches("^[0-9%:/ .-]+$")) continue;
            out.add(t);
        }
        return out;
    }

    private static List<String> englishSingleWordChoices(List<String> choices) {
        List<String> out = new ArrayList<>();
        for (String c : choices) {
            String n = normEnglish(c);
            if (n.matches("[a-z']{1,22}")) out.add(c);
        }
        return out;
    }

    private static int countKorean(List<String> list) { int c=0; for (String s:list) if (s.matches(".*[가-힣].*")) c++; return c; }
    private static int countEnglish(List<String> list) { int c=0; for (String s:list) if (s.matches(".*[A-Za-z].*")) c++; return c; }

    private static String findProminentEnglish(String raw) {
        for (String line : raw.split("\\r?\\n")) {
            String t = line.trim();
            if (t.matches("[A-Za-z][A-Za-z' -]{1,35}") && t.split("\\s+").length <= 5 && !isUiEnglish(t)) return t;
        }
        return "";
    }

    private static String findProminentKorean(String raw) {
        for (String line : raw.split("\\r?\\n")) {
            String t = line.trim();
            if (t.matches(".*[가-힣].*") && t.length() <= 35 && !isUiKorean(t)) return t;
        }
        return "";
    }

    private static boolean isUiEnglish(String s) {
        String n = s.toLowerCase(Locale.ROOT);
        return n.contains("classcard") || n.contains("study lens") || n.equals("next") || n.equals("skip") || n.equals("correct");
    }
    private static boolean isUiKorean(String s) {
        return s.contains("정답") || s.contains("문제") || s.contains("다음") || s.contains("학습") || s.contains("자동") || s.contains("점수");
    }

    private static String bestContains(List<String> choices, String meaning) {
        String m = normalizeKorean(meaning); String best=""; int bestScore=0;
        for (String c:choices) {
            String n=normalizeKorean(c); int score=0;
            if (n.equals(m)) score=100; else if (n.contains(m)||m.contains(n)) score=92;
            else for (String token:m.split(" ")) if (token.length()>=2 && n.contains(token)) score+=20;
            if (score>bestScore) { bestScore=score; best=c; }
        }
        return bestScore>=40 ? best : "";
    }

    private static String bestEnglishChoice(List<String> choices, String english) {
        String e=normEnglish(english);
        for (String c:choices) if (normEnglish(c).equals(e)) return c;
        for (String c:choices) if (normEnglish(c).contains(e) || e.contains(normEnglish(c))) return c;
        return "";
    }

    private static boolean koreanSimilar(String q, String meaning) {
        String a=normalizeKorean(q), b=normalizeKorean(meaning);
        if (a.equals(b)||a.contains(b)||b.contains(a)) return true;
        for (String t:b.split(" ")) if (t.length()>=2 && a.contains(t)) return true;
        return false;
    }

    private static String normEnglish(String s) { return s==null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z' ]"," ").replaceAll("\\s+"," ").trim(); }
    private static String normalizeKorean(String s) { return s==null ? "" : s.replaceAll("[^가-힣 ]"," ").replaceAll("\\s+"," ").trim(); }
    private static void put(String e,String k) { BASE.put(e,k); }
    private static void irregular(String base,String past,String pp) { PAST.put(base,past); PARTICIPLE.put(base,pp); }
    private static Answer none() { return new Answer("",0,""); }
}
