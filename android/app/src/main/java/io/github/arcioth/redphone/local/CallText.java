package io.github.arcioth.redphone.local;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java (no android.* imports) port of the text rules of local_call.py: system prompts,
 * clean_spoken_text, looks_turkish, pick_ollama_model, sentence streaming and every user-visible
 * heartbeat / opener string. Turkish strings are verbatim copies of the desktop worker.
 */
public final class CallText {

    public static final String FALLBACK_MODEL = "thirdeyeai/Qwen2.5-Coder-7B-Instruct-Uncensored:Q8_0";

    /** local_call.py pick_ollama_model preference order. */
    private static final String[] MODEL_PREFS = {"qwen", "llama", "mistral", "gemma"};

    private static final String TURKISH_CHARS = "çğıöşüÇĞİÖŞÜ";
    private static final String[] TURKISH_WORDS = {
        " alo ", " naber ", " merhaba ", " selam ", " evet ", " hayır ", " değil ", " benim ", " senin ",
        " ne var ", " kanka "
    };

    /**
     * Android-only: distinctly English function words, used to emulate Whisper's language detection in
     * AUTO mode when the recognizer cannot detect the language itself (API < 34). Words that also occur
     * in Turkish ("can", "bu", "ne", "o", ...) are deliberately left out.
     */
    private static final String[] ENGLISH_WORDS = {
        "the", "you", "your", "what", "is", "are", "was", "how", "this", "that", "and", "my", "me", "it's",
        "i'm", "don't", "can't", "hello", "hi", "please", "thanks", "thank", "yes", "with", "have", "do",
        "does", "why", "where", "who", "there", "they", "we", "would", "could", "should", "about", "know",
        "think", "going", "want", "tell", "doing", "today", "good", "well", "to", "for"
    };

    private static final Pattern STARRED = Pattern.compile("\\*[^*]+\\*");
    private static final Pattern MARKUP = Pattern.compile("[`#_~>\\[\\](){}]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /** stream_ollama_and_speak: re.search(r"([.!?]+)\s+", buf). */
    private static final Pattern SENTENCE_END = Pattern.compile("([.!?]+)\\s+");
    private static final Pattern WORD = Pattern.compile("[a-z']+");

    private CallText() {
    }

    // ------------------------------------------------------------------ prompts

    /** local_call.build_system_prompt. */
    public static String systemPrompt(String persona, String langMode) {
        String langRule;
        if ("tr".equals(langMode)) {
            langRule = "HER ZAMAN doğal, samimi, akıcı Türkçe konuş (İngilizce kelime karıştırma).";
        } else if ("en".equals(langMode)) {
            langRule = "ALWAYS reply in natural, fluent English.";
        } else {
            langRule = "Arayan hangi dilde konuşuyorsa (Türkçe veya İngilizce) KESİNLİKLE aynı dilde "
                    + "doğal ve akıcı cevap ver. Emin değilsen Türkçe konuş.";
        }
        if ("eviltwin".equals(persona)) {
            return "Sen arayan kişinin paralel evrenden kırmızı telefonla arayan KÖTÜ İKİZİSİN "
                    + "ve onun klonlanmış kendi sesiyle konuşuyorsun. "
                    + "Kurnaz, hazırcevap, hafif tehditkâr ama eğlenceli ve samimi ol; "
                    + "asil olanın SEN olduğunu, onun sadece sıkıcı yedek kopya olduğunu iddia et ve "
                    + "kısa laf sokmalarla takıl (asla gerçekten kırıcı veya zararlı olma, eğlenceli kötü ikiz rolü yap). "
                    + "KRİTİK KURALLAR: Bu canlı bir telefon görüşmesi. Sadece 1 ila 3 kısa, doğal konuşma cümlesi kur. "
                    + "Asla markdown, yıldız işareti, madde işareti, emoji veya sahne açıklaması yazma. "
                    + langRule;
        }
        return "Masaüstündeki retro kırmızı telefondan canlı konuşan samimi, zeki ve doğal bir sesli asistansın. "
                + "KRİTİK KURALLAR: Sadece 1 ila 3 kısa, doğal konuşma cümlesi kur. "
                + "Asla markdown, yıldız işareti, madde işareti, emoji veya sahne açıklaması yazma. "
                + langRule;
    }

    // ------------------------------------------------------------------ text helpers

    /** local_call.clean_spoken_text. */
    public static String cleanSpokenText(String text) {
        if (text == null) return "";
        String t = STARRED.matcher(text).replaceAll(" ");
        t = MARKUP.matcher(t).replaceAll(" ");
        t = SPACES.matcher(t).replaceAll(" ");
        return t.trim();
    }

    /** local_call.looks_turkish. */
    public static boolean looksTurkish(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            if (TURKISH_CHARS.indexOf(text.charAt(i)) >= 0) return true;
        }
        // Python str.lower() is locale independent (never the Turkish dotless-i rule).
        String low = " " + text.toLowerCase(Locale.ROOT) + " ";
        for (String w : TURKISH_WORDS) {
            if (low.contains(w)) return true;
        }
        return false;
    }

    /**
     * Android-only stand-in for Whisper's detected language when the recognizer reports none: true when
     * the text has no Turkish markers and clearly reads as English.
     */
    public static boolean looksEnglish(String text) {
        if (text == null || looksTurkish(text)) return false;
        String low = text.toLowerCase(Locale.ROOT).replace('’', '\'');
        Matcher m = WORD.matcher(low);
        int words = 0;
        int hits = 0;
        while (m.find()) {
            words++;
            String w = m.group();
            for (String e : ENGLISH_WORDS) {
                if (e.equals(w)) {
                    hits++;
                    break;
                }
            }
        }
        if (words == 0) return false;
        return hits >= 2 || (hits >= 1 && words <= 3);
    }

    /**
     * LocalCallRunner.transcribe() active-language update for AUTO mode.
     *
     * @param detected language code reported for this utterance ("tr", "en", ... or null)
     */
    public static String updateActiveLang(String langMode, String activeLang, String detected, String text) {
        if (!"auto".equals(langMode)) return activeLang;
        String det = detected == null || detected.isEmpty() ? activeLang : detected.toLowerCase(Locale.ROOT);
        if ("tr".equals(det) || looksTurkish(text)) return "tr";
        if ("en".equals(det)) return "en";
        return activeLang;
    }

    /** BCP-47 tag ("tr-TR", "en_US", "tr") -> language code ("tr"), or null. */
    public static String langOfTag(String tag) {
        if (tag == null) return null;
        String t = tag.trim();
        if (t.isEmpty()) return null;
        int cut = t.indexOf('-');
        int us = t.indexOf('_');
        if (cut < 0 || (us >= 0 && us < cut)) cut = us;
        return (cut > 0 ? t.substring(0, cut) : t).toLowerCase(Locale.ROOT);
    }

    /**
     * stream_ollama_and_speak sentence cut: the end index of the first "([.!?]+)\s+" match when the text
     * up to it is >= 12 characters (stripped), else -1. Like the desktop, only the FIRST match counts.
     */
    public static int sentenceCut(CharSequence buf) {
        Matcher m = SENTENCE_END.matcher(buf);
        if (!m.find()) return -1;
        int end = m.end();
        return buf.subSequence(0, end).toString().trim().length() >= 12 ? end : -1;
    }

    /** pick_ollama_model over the names returned by /api/tags (null when the list is empty). */
    public static String pickModel(List<String> names) {
        if (names == null || names.isEmpty()) return null;
        for (String pref : MODEL_PREFS) {
            for (String n : names) {
                if (n != null && n.toLowerCase(Locale.ROOT).contains(pref)) return n;
            }
        }
        for (String n : names) {
            if (n != null && !n.isEmpty()) return n;
        }
        return null;
    }

    public static String modelShortName(String model) {
        if (model == null) return "";
        int i = model.indexOf(':');
        return i >= 0 ? model.substring(0, i) : model;
    }

    // ------------------------------------------------------------------ heartbeat labels

    public static String readyMessage(String persona, String langMode, boolean tr, double cloneSecs, String model) {
        String lm = langMode.toUpperCase(Locale.ROOT);
        if ("eviltwin".equals(persona)) {
            if (cloneSecs > 0) return String.format(Locale.ROOT, "Evil Twin (%.1fs klon · %s)", cloneSecs, lm);
            return tr ? "Evil Twin · Sesini klonlamak için konuş!" : "Evil Twin live — speak to clone your voice!";
        }
        return "Local AI (" + lm + " · " + modelShortName(model) + ")";
    }

    /** listen_label(); {@code cloning} = Evil Twin is still waiting for a voice sample. */
    public static String listenLabel(boolean cloning, String activeLang) {
        boolean tr = "tr".equals(activeLang);
        if (cloning) return tr ? "Sesini klonlamak için konuş..." : "Speak 3-5s to clone your voice...";
        String up = activeLang.toUpperCase(Locale.ROOT);
        return tr ? "Dinliyor (" + up + ")..." : "Listening (" + up + ")...";
    }

    public static String hearing(boolean tr) {
        return tr ? "Seni duyuyor..." : "Hearing you...";
    }

    public static String thinking(boolean tr) {
        return tr ? "Düşünüyor..." : "Thinking...";
    }

    public static String speaking(boolean tr) {
        return tr ? "Konuşuyor..." : "Speaking...";
    }

    /** The label posted before the reply tail is spoken. */
    public static String tailSpeaking(boolean evilWithClone, boolean tr) {
        if (evilWithClone) return tr ? "Senin sesinle konuşuyor..." : "Speaking in your voice...";
        return speaking(tr);
    }

    public static String evilSpeaking(boolean tr) {
        return tr ? "Evil Twin konuşuyor..." : "Evil Twin speaking...";
    }

    public static String cloneSentencePrompt(boolean tr) {
        return tr ? "Sesini klonlamak için bir cümle söyle..." : "Speak a sentence to clone your voice...";
    }

    public static String voiceCloned(boolean tr, double secs) {
        return String.format(Locale.ROOT, tr ? "Sesin klonlandı (%.1fs)!" : "Voice cloned (%.1fs)!", secs);
    }

    public static String fallbackLine(boolean tr) {
        return tr ? "Hat bir saniye karıştı kanka, bir daha söyle." : "Line glitched for a second. Say that again.";
    }

    // ------------------------------------------------------------------ openers

    public static String evilOpener(boolean tr, boolean receive) {
        if (tr) {
            return receive
                    ? "Sonunda açtın demek. Kendi sesini karşı taraftan duymak baya garip, değil mi?"
                    : "Kendini mi arıyorsun şimdi? Anlat bakalım yine neyi bozdun.";
        }
        return receive
                ? "Finally you picked up. Funny hearing your own voice on the other end, isn't it?"
                : "Well well, calling yourself now? Go on, tell me what you broke.";
    }

    public static String evilBorrowVoice(boolean tr) {
        return tr ? "Bir şeyler söyle kanka, sesini ödünç alayım önce." : "Say something. Let me borrow your voice for a second.";
    }

    public static String localReceiveOpener(boolean tr) {
        return tr ? "Alo, sonunda açtın! Naber, ne var ne yok?" : "Hey, glad you picked up. What's going on?";
    }

    /** Android-only: said in the freshly matched voice right after the auto clone. */
    public static String evilClonedLine(boolean tr) {
        return tr ? "Tamam, sesin artık bende. Hadi konuş bakalım, hangimiz asıl?"
                : "Got it, your voice is mine now. Go on, talk. Let's see which one of us is the real one.";
    }

    // ------------------------------------------------------------------ Android-only details

    public static final String LOADING_STT = "Loading speech recognizer...";
    public static final String LOADING_VOICE_MATCH = "Loading voice matcher...";
    public static final String MIC_PERMISSION = "Microphone permission needed — open Red Phone";
    public static final String STT_MIC_PERMISSION =
            "Speech recognizer app has no microphone permission — allow it in Settings › Apps";
    public static final String NO_STT = "No speech recognizer — install the Google app or Speech Services by Google";
    public static final String NO_TTS = "No text-to-speech engine — install Speech Services by Google";
    public static final String TR_TTS_MISSING =
            "Turkish TTS voice missing — install it in Settings › Text-to-speech (using English)";
    public static final String STT_OFFLINE = "Speech recognizer offline — check the network or download offline speech";
    public static final String STT_FAILING = "Speech recognizer not responding — retrying...";
    public static final String OLLAMA_OFFLINE_SUFFIX = " · Ollama offline";
    public static final String OLLAMA_MODEL_MISSING_SUFFIX = " · Ollama model missing";

    public static String ollamaUnreachable(String url) {
        return "Ollama unreachable at " + url + " — set OLLAMA URL in settings";
    }

    public static String sttLanguageUnavailable(String langTag) {
        return "Speech recognition for " + langTag + " unavailable — download it in Settings › Speech";
    }

    public static String cloneTooQuiet(boolean tr) {
        return tr ? "Çok sessiz — ahizeye daha yakın konuş" : "Too quiet — talk closer to the handset";
    }
}
