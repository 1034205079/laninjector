package com.baozi.laninjector.payload;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LocaleUtils {

    private static final Map<String, String> FALLBACK_NAMES = new HashMap<>();

    static {
        FALLBACK_NAMES.put("af", "Afrikaans");
        FALLBACK_NAMES.put("am", "\u12A0\u121B\u122D\u129B");
        FALLBACK_NAMES.put("ar", "\u0627\u0644\u0639\u0631\u0628\u064A\u0629");
        FALLBACK_NAMES.put("az", "Az\u0259rbaycan");
        FALLBACK_NAMES.put("be", "\u0411\u0435\u043B\u0430\u0440\u0443\u0441\u043A\u0430\u044F");
        FALLBACK_NAMES.put("bg", "\u0411\u044A\u043B\u0433\u0430\u0440\u0441\u043A\u0438");
        FALLBACK_NAMES.put("bn", "\u09AC\u09BE\u0982\u09B2\u09BE");
        FALLBACK_NAMES.put("bs", "Bosanski");
        FALLBACK_NAMES.put("ca", "Catal\u00E0");
        FALLBACK_NAMES.put("cs", "\u010Ce\u0161tina");
        FALLBACK_NAMES.put("da", "Dansk");
        FALLBACK_NAMES.put("de", "Deutsch");
        FALLBACK_NAMES.put("el", "\u0395\u03BB\u03BB\u03B7\u03BD\u03B9\u03BA\u03AC");
        FALLBACK_NAMES.put("en", "English");
        FALLBACK_NAMES.put("es", "Espa\u00F1ol");
        FALLBACK_NAMES.put("et", "Eesti");
        FALLBACK_NAMES.put("eu", "Euskara");
        FALLBACK_NAMES.put("fa", "\u0641\u0627\u0631\u0633\u06CC");
        FALLBACK_NAMES.put("fi", "Suomi");
        FALLBACK_NAMES.put("fil", "Filipino");
        FALLBACK_NAMES.put("fr", "Fran\u00E7ais");
        FALLBACK_NAMES.put("gl", "Galego");
        FALLBACK_NAMES.put("gu", "\u0A97\u0AC1\u0A9C\u0AB0\u0ABE\u0AA4\u0AC0");
        FALLBACK_NAMES.put("he", "\u05E2\u05D1\u05E8\u05D9\u05EA");
        FALLBACK_NAMES.put("hi", "\u0939\u093F\u0928\u094D\u0926\u0940");
        FALLBACK_NAMES.put("hr", "Hrvatski");
        FALLBACK_NAMES.put("hu", "Magyar");
        FALLBACK_NAMES.put("hy", "\u0540\u0561\u0575\u0565\u0580\u0565\u0576");
        FALLBACK_NAMES.put("id", "Indonesia");
        FALLBACK_NAMES.put("is", "\u00CDslenska");
        FALLBACK_NAMES.put("it", "Italiano");
        FALLBACK_NAMES.put("iw", "\u05E2\u05D1\u05E8\u05D9\u05EA");
        FALLBACK_NAMES.put("ja", "\u65E5\u672C\u8A9E");
        FALLBACK_NAMES.put("ka", "\u10E5\u10D0\u10E0\u10D7\u10E3\u10DA\u10D8");
        FALLBACK_NAMES.put("kk", "\u049A\u0430\u0437\u0430\u049B");
        FALLBACK_NAMES.put("km", "\u1781\u17D2\u1798\u17C2\u179A");
        FALLBACK_NAMES.put("kn", "\u0C95\u0CA8\u0CCD\u0CA8\u0CA1");
        FALLBACK_NAMES.put("ko", "\uD55C\uAD6D\uC5B4");
        FALLBACK_NAMES.put("lo", "\u0EA5\u0EB2\u0EA7");
        FALLBACK_NAMES.put("lt", "Lietuvi\u0173");
        FALLBACK_NAMES.put("lv", "Latvie\u0161u");
        FALLBACK_NAMES.put("mk", "\u041C\u0430\u043A\u0435\u0434\u043E\u043D\u0441\u043A\u0438");
        FALLBACK_NAMES.put("ml", "\u0D2E\u0D32\u0D2F\u0D3E\u0D33\u0D02");
        FALLBACK_NAMES.put("mn", "\u041C\u043E\u043D\u0433\u043E\u043B");
        FALLBACK_NAMES.put("mr", "\u092E\u0930\u093E\u0920\u0940");
        FALLBACK_NAMES.put("ms", "Melayu");
        FALLBACK_NAMES.put("my", "\u1019\u103C\u1014\u103A\u1019\u102C");
        FALLBACK_NAMES.put("nb", "Norsk bokm\u00E5l");
        FALLBACK_NAMES.put("ne", "\u0928\u0947\u092A\u093E\u0932\u0940");
        FALLBACK_NAMES.put("nl", "Nederlands");
        FALLBACK_NAMES.put("no", "Norsk");
        FALLBACK_NAMES.put("pa", "\u0A2A\u0A70\u0A1C\u0A3E\u0A2C\u0A40");
        FALLBACK_NAMES.put("pl", "Polski");
        FALLBACK_NAMES.put("pt", "Portugu\u00EAs");
        FALLBACK_NAMES.put("ro", "Rom\u00E2n\u0103");
        FALLBACK_NAMES.put("ru", "\u0420\u0443\u0441\u0441\u043A\u0438\u0439");
        FALLBACK_NAMES.put("si", "\u0DC3\u0DD2\u0D82\u0DC4\u0DBD");
        FALLBACK_NAMES.put("sk", "Sloven\u010Dina");
        FALLBACK_NAMES.put("sl", "Sloven\u0161\u010Dina");
        FALLBACK_NAMES.put("sq", "Shqip");
        FALLBACK_NAMES.put("sr", "\u0421\u0440\u043F\u0441\u043A\u0438");
        FALLBACK_NAMES.put("sv", "Svenska");
        FALLBACK_NAMES.put("sw", "Kiswahili");
        FALLBACK_NAMES.put("ta", "\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD");
        FALLBACK_NAMES.put("te", "\u0C24\u0C46\u0C32\u0C41\u0C17\u0C41");
        FALLBACK_NAMES.put("th", "\u0E44\u0E17\u0E22");
        FALLBACK_NAMES.put("tl", "Filipino");
        FALLBACK_NAMES.put("tr", "T\u00FCrk\u00E7e");
        FALLBACK_NAMES.put("uk", "\u0423\u043A\u0440\u0430\u0457\u043D\u0441\u044C\u043A\u0430");
        FALLBACK_NAMES.put("ur", "\u0627\u0631\u062F\u0648");
        FALLBACK_NAMES.put("uz", "O\u02BBzbek");
        FALLBACK_NAMES.put("vi", "Ti\u1EBFng Vi\u1EC7t");
        FALLBACK_NAMES.put("zh", "\u4E2D\u6587");
        FALLBACK_NAMES.put("zu", "IsiZulu");
    }

    /**
     * Parse a locale qualifier string like "ja", "zh-rCN", "pt-rBR" into a Locale.
     * "fil" is normalized to use Locale.forLanguageTag for correct BCP47 handling.
     */
    public static Locale parseLocaleQualifier(String qualifier) {
        if (qualifier.contains("-r")) {
            String[] parts = qualifier.split("-r");
            return new Locale(parts[0], parts[1]);
        }
        // Use forLanguageTag for 3-letter BCP47 codes like "fil" so the OS
        // correctly resolves them (Android 5+ maps "fil" ↔ "tl" internally).
        if (qualifier.length() == 3 && qualifier.matches("[a-z]{3}")) {
            try {
                Locale l = Locale.forLanguageTag(qualifier);
                if (!l.getLanguage().isEmpty()) return l;
            } catch (Exception ignored) {}
        }
        return new Locale(qualifier);
    }

    /**
     * Get display name for a locale in its own language.
     * Example: "ja" -> "日本語", "fr" -> "Français"
     */
    public static String getDisplayName(String localeCode) {
        Locale locale = parseLocaleQualifier(localeCode);
        String name = locale.getDisplayLanguage(locale);

        // If the system returned just the code, use fallback
        if (name.equals(localeCode) || name.equals(locale.getLanguage())) {
            String fallback = FALLBACK_NAMES.get(locale.getLanguage());
            if (fallback != null) {
                name = fallback;
            }
        }

        // Add country if present
        String country = locale.getCountry();
        if (country != null && !country.isEmpty()) {
            String displayCountry = locale.getDisplayCountry(locale);
            if (!displayCountry.isEmpty() && !displayCountry.equals(country)) {
                name = name + " (" + displayCountry + ")";
            } else {
                name = name + " (" + country + ")";
            }
        }

        return name;
    }

    /**
     * Format for display: "ja - 日本語"
     */
    public static String formatForDisplay(String localeCode) {
        return localeCode + " - " + getDisplayName(localeCode);
    }
}
