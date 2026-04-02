package com.baozi.laninjector.payload;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class LanguagePanelView extends LinearLayout {

    public interface OnStartCallback {
        void onStart(List<String> selectedLocales);
    }

    public interface OnCloseCallback {
        void onClose();
    }

    private final List<CheckBox> checkBoxes = new ArrayList<>();
    private final List<String> localeCodes = new ArrayList<>();
    private final List<String> displayNames = new ArrayList<>();
    private OnStartCallback startCallback;
    private OnCloseCallback closeCallback;
    private CheckBox selectAllBox;
    private TextView countLabel;

    public LanguagePanelView(Context context, String[] locales) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(0xF0FFFFFF);
        int pad = dp(16);
        setPadding(pad, pad, pad, pad);
        setElevation(dp(8));
        buildUI(locales);
    }

    public void setStartCallback(OnStartCallback callback) {
        this.startCallback = callback;
    }

    public void setCloseCallback(OnCloseCallback callback) {
        this.closeCallback = callback;
    }

    private void buildUI(String[] locales) {
        // Title row: title + count + close button
        LinearLayout titleRow = new LinearLayout(getContext());
        titleRow.setOrientation(HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, 0, 0, dp(8));

        TextView title = new TextView(getContext());
        title.setText("Select Languages");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF212121);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(title, titleLp);

        countLabel = new TextView(getContext());
        countLabel.setText("0/" + locales.length);
        countLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        countLabel.setTextColor(0xFF757575);
        countLabel.setPadding(0, 0, dp(12), 0);
        titleRow.addView(countLabel);

        TextView closeBtn = new TextView(getContext());
        closeBtn.setText("\u2715");
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        closeBtn.setTextColor(0xFF757575);
        closeBtn.setOnClickListener(v -> {
            if (closeCallback != null) closeCallback.onClose();
        });
        titleRow.addView(closeBtn);

        addView(titleRow);

        // Search box with embedded clear button
        FrameLayout searchFrame = new FrameLayout(getContext());
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(0xFFF5F5F5);
        searchBg.setCornerRadius(dp(4));
        searchFrame.setBackground(searchBg);

        EditText searchBox = new EditText(getContext());
        searchBox.setHint("Search...");
        searchBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        searchBox.setSingleLine(true);
        searchBox.setBackground(null);
        searchBox.setPadding(dp(10), dp(8), dp(32), dp(8));
        searchFrame.addView(searchBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView clearBtn = new TextView(getContext());
        clearBtn.setText("\u2715");
        clearBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        clearBtn.setTextColor(0xFF9E9E9E);
        clearBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        clearBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams clearLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clearLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        clearBtn.setOnClickListener(v -> searchBox.setText(""));
        searchFrame.addView(clearBtn, clearLp);

        LinearLayout.LayoutParams searchFrameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchFrameLp.setMargins(0, 0, 0, dp(8));
        addView(searchFrame, searchFrameLp);

        // Select All checkbox
        selectAllBox = new CheckBox(getContext());
        selectAllBox.setText("Select All");
        selectAllBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        selectAllBox.setTextColor(0xFF424242);
        selectAllBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                for (CheckBox cb : checkBoxes) {
                    cb.setChecked(isChecked);
                }
            }
        });
        addView(selectAllBox);

        // Divider
        View divider = new View(getContext());
        divider.setBackgroundColor(0xFFE0E0E0);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divLp.setMargins(0, dp(4), 0, dp(4));
        addView(divider, divLp);

        // Scrollable language list
        ScrollView scrollView = new ScrollView(getContext());
        LinearLayout listContainer = new LinearLayout(getContext());
        listContainer.setOrientation(VERTICAL);

        for (String locale : locales) {
            localeCodes.add(locale);
            String name = LocaleUtils.formatForDisplay(locale);
            displayNames.add(name.toLowerCase());

            CheckBox cb = new CheckBox(getContext());
            cb.setText(name);
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            cb.setTextColor(0xFF616161);
            cb.setPadding(dp(4), dp(2), dp(4), dp(2));
            cb.setOnCheckedChangeListener((buttonView, isChecked) -> updateSelectAllState());
            checkBoxes.add(cb);
            listContainer.addView(cb);
        }

        scrollView.addView(listContainer);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        addView(scrollView, scrollLp);

        // Search filter + clear button visibility
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String query = s.toString().toLowerCase().trim();
                clearBtn.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                for (int i = 0; i < checkBoxes.size(); i++) {
                    boolean match = query.isEmpty()
                            || localeCodes.get(i).toLowerCase().contains(query)
                            || displayNames.get(i).contains(query);
                    checkBoxes.get(i).setVisibility(match ? View.VISIBLE : View.GONE);
                }
            }
        });

        // File picker button (above Start)
        Button loadBtn = new Button(getContext());
        loadBtn.setText("选择文件来勾选语言");
        loadBtn.setBackgroundColor(0xFFE8EAF6);
        loadBtn.setTextColor(0xFF3949AB);
        loadBtn.setAllCaps(false);
        loadBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams loadBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        loadBtnLp.setMargins(0, dp(8), 0, dp(4));
        loadBtn.setOnClickListener(v -> importFromFile());
        addView(loadBtn, loadBtnLp);

        // Start button
        Button startBtn = new Button(getContext());
        startBtn.setText("Start");
        startBtn.setBackgroundColor(0xFF6200EE);
        startBtn.setTextColor(Color.WHITE);
        startBtn.setAllCaps(false);
        startBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.setMargins(0, dp(8), 0, 0);
        startBtn.setOnClickListener(v -> {
            List<String> selected = getSelectedLocales();
            if (!selected.isEmpty() && startCallback != null) {
                startCallback.onStart(selected);
            }
        });
        addView(startBtn, btnLp);
    }

    private void updateSelectAllState() {
        boolean allChecked = true;
        int checkedCount = 0;
        for (CheckBox cb : checkBoxes) {
            if (cb.isChecked()) {
                checkedCount++;
            } else {
                allChecked = false;
            }
        }

        countLabel.setText(checkedCount + "/" + checkBoxes.size());
        countLabel.setTextColor(checkedCount > 0 ? 0xFF6200EE : 0xFF757575);

        selectAllBox.setOnCheckedChangeListener(null);
        selectAllBox.setChecked(allChecked);
        selectAllBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                for (CheckBox cb : checkBoxes) {
                    cb.setChecked(isChecked);
                }
            }
        });
    }

    private List<String> getSelectedLocales() {
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selected.add(localeCodes.get(i));
            }
        }
        return selected;
    }

    /** Open system file picker to let the user choose a txt file. */
    private void importFromFile() {
        Context ctx = getContext();
        Activity activity = null;
        while (ctx instanceof android.content.ContextWrapper) {
            if (ctx instanceof Activity) { activity = (Activity) ctx; break; }
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        if (activity == null) {
            Toast.makeText(getContext(), "Cannot open file picker", Toast.LENGTH_SHORT).show();
            return;
        }
        final Activity act = activity;
        FilePickerFragment.attach(act, uri -> {
            try {
                List<String> codes = new ArrayList<>();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(act.getContentResolver().openInputStream(uri)));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim().toLowerCase();
                    if (!line.isEmpty()) codes.add(line);
                }
                reader.close();
                if (codes.isEmpty()) {
                    Toast.makeText(getContext(), "File is empty", Toast.LENGTH_SHORT).show();
                    return;
                }
                applyLocaleCodes(codes);
            } catch (Exception e) {
                Toast.makeText(getContext(), "Failed to read file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }).launch();
    }

    /** Headless fragment used to start ACTION_GET_CONTENT and receive the result. */
    @SuppressWarnings("deprecation")
    public static class FilePickerFragment extends android.app.Fragment {
        private static final int REQUEST_CODE = 0x4C49; // "LI"
        private static final String TAG = "_laninjector_picker";

        interface Callback { void onPicked(Uri uri); }
        private Callback callback;

        static FilePickerFragment attach(Activity activity, Callback cb) {
            android.app.FragmentManager fm = activity.getFragmentManager();
            FilePickerFragment f = (FilePickerFragment) fm.findFragmentByTag(TAG);
            if (f == null) {
                f = new FilePickerFragment();
                fm.beginTransaction().add(f, TAG).commitAllowingStateLoss();
                fm.executePendingTransactions();
            }
            f.callback = cb;
            return f;
        }

        void launch() {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("text/plain");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "Select locale file"), REQUEST_CODE);
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK
                    && data != null && data.getData() != null && callback != null) {
                callback.onPicked(data.getData());
            }
        }
    }

    /**
     * Uncheck all, then select locales matching any code in the list.
     * Supports 2-letter (ja, ko) and 3-letter (jpn, kor, eng) ISO codes, case-insensitive.
     * Matches all region variants: "zh" matches zh, zh-rCN, zh-rTW, etc.
     */
    private void applyLocaleCodes(List<String> inputCodes) {
        for (CheckBox cb : checkBoxes) cb.setChecked(false);
        int matched = 0;
        for (int i = 0; i < localeCodes.size(); i++) {
            for (String inputCode : inputCodes) {
                if (localeMatchesCode(localeCodes.get(i), inputCode)) {
                    if (!checkBoxes.get(i).isChecked()) {
                        checkBoxes.get(i).setChecked(true);
                        matched++;
                    }
                    break;
                }
            }
        }
        updateSelectAllState();
        Toast.makeText(getContext(), matched + " language(s) selected", Toast.LENGTH_SHORT).show();
    }

    /**
     * Check if a locale code exactly matches a user-provided code (case-insensitive).
     * "bn" matches only "bn", not "bn-rIN" or "bn-rBD".
     * Also supports ISO 639-2 three-letter codes and the fil/tl alias.
     */
    private boolean localeMatchesCode(String localeCode, String inputCode) {
        localeCode = localeCode.toLowerCase();
        inputCode = inputCode.toLowerCase();

        if (localeCode.equals(inputCode)) return true;

        // Try ISO 639-2 three-letter code: "ben" matches "bn"
        try {
            java.util.Locale l = new java.util.Locale(localeCode);
            String iso3 = l.getISO3Language();
            if (iso3 != null && !iso3.isEmpty() && iso3.equals(inputCode)) return true;
        } catch (Exception ignored) {}

        // fil (Filipino/BCP47) ↔ tl (Tagalog/legacy Android code)
        if ("fil".equals(inputCode) && "tl".equals(localeCode)) return true;
        if ("tl".equals(inputCode) && "fil".equals(localeCode)) return true;

        return false;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                getContext().getResources().getDisplayMetrics());
    }
}
