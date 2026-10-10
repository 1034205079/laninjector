package com.baozi.laninjector.payload;

import android.content.res.Resources;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared lookup for ordinary strings, array elements and quantity-sensitive labels. */
final class ResourceLabelCatalogue {
    private static final Pattern COUNT = Pattern.compile("(?<![\\d.,-])\\d+(?![\\d.,])");
    private final List<Entry> entries = new ArrayList<>();
    private final java.util.Map<String, List<Entry>> exact = new java.util.HashMap<>();
    private final java.util.Map<String, List<Candidate>> matches = new java.util.LinkedHashMap<String, List<Candidate>>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, List<Candidate>> entry) { return size() > 256; }
    };

    ResourceLabelCatalogue(Resources resources) {
        for (int id : ResourceStringIndex.ids()) {
            try { entries.add(new Entry(id, -1, resources.getText(id).toString())); }
            catch (Resources.NotFoundException ignored) { }
        }
        for (int id : ResourceStringIndex.arrays()) {
            try {
                CharSequence[] values = resources.getTextArray(id);
                for (int i = 0; i < values.length; i++) {
                    if (values[i] != null) entries.add(new Entry(id, i, values[i].toString()));
                }
            } catch (Resources.NotFoundException ignored) { }
        }
        for (int id : ResourceStringIndex.plurals()) {
            for (int count : new int[] {0, 1, 2, 3, 5, 11, 21, 101}) {
                try {
                    entries.add(new Entry(id, -2, count, resources.getQuantityText(id, count).toString()));
                } catch (Resources.NotFoundException ignored) { }
            }
        }
    }

    private void indexEntries() {
        exact.clear();
        for (Entry entry : entries) exact.computeIfAbsent(entry.text, key -> new ArrayList<>()).add(entry);
        matches.clear();
    }

    List<Candidate> match(CharSequence current) {
        List<Candidate> result = new ArrayList<>();
        if (TextUtils.isEmpty(current)) return result;
        String displayed = current.toString();
        if (exact.isEmpty() && !entries.isEmpty()) indexEntries();
        List<Candidate> cached = matches.get(displayed);
        if (cached != null) return cached;
        List<Integer> quantities = new ArrayList<>();
        boolean hasNumber = false;
        for (int i = 0; i < displayed.length(); i++) {
            if (displayed.charAt(i) >= '0' && displayed.charAt(i) <= '9') { hasNumber = true; break; }
        }
        Matcher numbers = COUNT.matcher(displayed);
        while (numbers.find()) {
            try {
                int count = Integer.parseInt(numbers.group());
                if (!quantities.contains(count)) quantities.add(count);
            } catch (NumberFormatException ignored) { }
        }
        List<Entry> identical = exact.get(displayed);
        if (identical != null) for (Entry entry : identical) {
            int count = entry.index == -2 && quantities.size() == 1 ? quantities.get(0) : entry.quantity;
            result.add(new Candidate(entry.id, entry.index, count, null));
        }
        for (Entry entry : entries) {
            if (entry.text.equals(displayed)) continue;
            if (!hasNumber && entry.text.indexOf('%') < 0) continue;
            // Ordinary unmatched strings cannot be templates. Prefix/suffix checks
            // retain the numeric concatenation path without creating regex Matchers.
            if (entry.text.indexOf('%') < 0 && !displayed.startsWith(entry.text) && !displayed.endsWith(entry.text)) continue;
            ResourceTextTemplate.Match match = ResourceTextTemplate.match(entry.text, displayed,
                    entry.index == -2 ? 1 : 4);
            if (match != null) {
                // A single displayed integer provides the real quantity. Otherwise
                // require all matching source forms to agree on the target text.
                int count = entry.index == -2 && quantities.size() == 1 ? quantities.get(0) : entry.quantity;
                result.add(new Candidate(entry.id, entry.index, count, match));
            }
        }
        List<Candidate> immutable = java.util.Collections.unmodifiableList(result);
        matches.put(displayed, immutable);
        return immutable;
    }

    void addOriginalSnapshot(ResourceLabelCatalogue original) {
        if (original == this) return;
        java.util.Set<String> known = new java.util.HashSet<>();
        for (Entry entry : entries) known.add(entry.key());
        boolean changed = false;
        for (Entry entry : original.entries) if (known.add(entry.key())) { entries.add(entry); changed = true; }
        if (changed) indexEntries();
    }

    static final class Candidate {
        final int id;
        final int index;
        final int quantity;
        final ResourceTextTemplate.Match match;

        Candidate(int id, ResourceTextTemplate.Match match) { this(id, -1, 0, match); }

        Candidate(int id, int index, int quantity, ResourceTextTemplate.Match match) {
            this.id = id; this.index = index; this.quantity = quantity; this.match = match;
        }

        CharSequence translate(Resources localized) {
            CharSequence template;
            if (index == -2) template = localized.getQuantityText(id, quantity);
            else if (index >= 0) {
                CharSequence[] items = localized.getTextArray(id);
                if (index >= items.length) return null;
                template = items[index];
            } else template = localized.getText(id);
            if (template == null) return null;
            return match == null ? template : match.renderText(template);
        }
    }

    private static final class Entry {
        final int id;
        final int index;
        final String text;
        final int quantity;
        Entry(int id, int index, String text) { this(id, index, 0, text); }
        Entry(int id, int index, int quantity, String text) {
            this.id = id; this.index = index; this.quantity = quantity; this.text = text;
        }
        String key() { return id + ":" + index + ":" + quantity + ":" + text; }
    }
}
