package com.baozi.laninjector.payload;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recover displayed arguments without changing money, account numbers or user input. */
final class ResourceTextTemplate {
    private static final Map<String, Pattern> compiled = new java.util.LinkedHashMap<String, Pattern>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Pattern> entry) { return size() > 256; }
    };

    private static synchronized Pattern compiledPattern(String regex) {
        Pattern pattern = compiled.get(regex);
        if (pattern == null) { pattern = Pattern.compile(regex, Pattern.DOTALL); compiled.put(regex, pattern); }
        return pattern;
    }
    private static final Pattern TOKEN = Pattern.compile("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([sSdDfFeEgG])|%%");
    private static final Pattern AMOUNT = Pattern.compile("[\\s\\p{Sc}:+-]*[0-9][0-9.,\\s\\p{Sc}%+-]*");

    static Match match(String template, String displayed) {
        return match(template, displayed, 4);
    }

    static Match match(String template, String displayed, int minimumLiteralLength) {
        if (template.equals(displayed)) return new Match(null, null, null);
        if (!template.contains("%")) return matchAmount(template, displayed);
        Matcher tokens = TOKEN.matcher(template);
        if (!tokens.find() || !displayed.startsWith(template.substring(0, tokens.start()))) return null;
        tokens.reset();
        StringBuilder regex = new StringBuilder("\\A");
        Map<Integer, Integer> positions = new HashMap<>();
        int offset = 0;
        int implicit = 1;
        int group = 0;
        int literals = 0;
        while (tokens.find()) {
            String literal = template.substring(offset, tokens.start());
            regex.append(Pattern.quote(literal));
            literals += literal.trim().length();
            if (tokens.group().equals("%%")) {
                regex.append('%');
            } else {
                // Adjacent placeholders do not provide a reliable argument boundary.
                if (group > 0 && literal.isEmpty()) return null;
                int argument;
                try {
                    argument = tokens.group(1) == null ? implicit++ : Integer.parseInt(tokens.group(1));
                } catch (NumberFormatException invalid) { return null; }
                if (positions.containsKey(argument)) {
                    regex.append('\\').append(positions.get(argument));
                } else {
                    positions.put(argument, ++group);
                    char type = Character.toLowerCase(tokens.group(2).charAt(0));
                    regex.append(type == 's' ? "(.+?)" : "([-+0-9.,\\s]+?)");
                }
            }
            offset = tokens.end();
        }
        String remaining = template.substring(offset);
        literals += remaining.trim().length();
        regex.append(Pattern.quote(remaining)).append("\\z");
        if (group > 0 && literals >= minimumLiteralLength) {
            Matcher values = compiledPattern(regex.toString()).matcher(displayed);
            if (values.matches()) {
                Map<Integer, String> arguments = new HashMap<>();
                for (Map.Entry<Integer, Integer> entry : positions.entrySet()) {
                    arguments.put(entry.getKey(), values.group(entry.getValue()));
                }
                return new Match(arguments, null, null);
            }
        }
        // Some apps append a monetary value instead of using a printf resource.
        if (group == 0) return matchAmount(template, displayed);
        return null;
    }

    private static Match matchAmount(String template, String displayed) {
        if (template.trim().length() >= 4) {
            if (displayed.startsWith(template)) {
                String suffix = displayed.substring(template.length());
                if (AMOUNT.matcher(suffix).matches()) return new Match(null, "", suffix);
            }
            if (displayed.endsWith(template)) {
                String prefix = displayed.substring(0, displayed.length() - template.length());
                if (AMOUNT.matcher(prefix).matches()) return new Match(null, prefix, "");
            }
        }
        return null;
    }

    static final class Match {
        private final Map<Integer, String> arguments;
        private final String prefix;
        private final String suffix;

        Match(Map<Integer, String> arguments, String prefix, String suffix) {
            this.arguments = arguments;
            this.prefix = prefix;
            this.suffix = suffix;
        }

        String render(String translated) {
            if (prefix != null) return prefix + translated + suffix;
            if (arguments == null) return translated;
            Matcher tokens = TOKEN.matcher(translated);
            StringBuffer result = new StringBuffer();
            int implicit = 1;
            java.util.Set<Integer> used = new java.util.HashSet<>();
            while (tokens.find()) {
                String value;
                if (tokens.group().equals("%%")) value = "%";
                else {
                    int id;
                    try {
                        id = tokens.group(1) == null ? implicit++ : Integer.parseInt(tokens.group(1));
                    } catch (NumberFormatException invalid) { return null; }
                    value = arguments.get(id);
                    if (value == null) return null;
                    used.add(id);
                }
                tokens.appendReplacement(result, Matcher.quoteReplacement(value));
            }
            tokens.appendTail(result);
            return used.equals(arguments.keySet()) ? result.toString() : null;
        }

        CharSequence renderText(CharSequence translated) {
            if (arguments == null && prefix == null) return translated;
            if (render(translated.toString()) == null) return null;
            android.text.SpannableStringBuilder result = new android.text.SpannableStringBuilder();
            if (prefix != null) return result.append(prefix).append(translated).append(suffix);
            result = new android.text.SpannableStringBuilder(translated);
            Matcher tokens = TOKEN.matcher(translated);
            java.util.List<int[]> ranges = new java.util.ArrayList<>();
            java.util.List<String> replacements = new java.util.ArrayList<>();
            int implicit = 1;
            while (tokens.find()) {
                ranges.add(new int[] {tokens.start(), tokens.end()});
                if (tokens.group().equals("%%")) replacements.add("%");
                else {
                    int id = tokens.group(1) == null ? implicit++ : Integer.parseInt(tokens.group(1));
                    replacements.add(arguments.get(id));
                }
            }
            for (int i = ranges.size() - 1; i >= 0; i--) {
                int[] range = ranges.get(i);
                result.replace(range[0], range[1], replacements.get(i));
            }
            return result;
        }
    }
}
