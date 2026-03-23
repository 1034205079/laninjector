package com.baozi.laninjector.payload;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class LanguagePanelView extends LinearLayout {

    public interface OnStartCallback {
        void onStart(List<String> selectedLocales);
    }

    private final List<CheckBox> checkBoxes = new ArrayList<>();
    private final List<String> localeCodes = new ArrayList<>();
    private OnStartCallback startCallback;
    private CheckBox selectAllBox;

    public LanguagePanelView(Context context, String[] locales) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(0xF0FFFFFF);
        int pad = dp(16);
        setPadding(pad, pad, pad, pad);

        // Elevation/shadow
        setElevation(dp(8));

        buildUI(locales);
    }

    public void setStartCallback(OnStartCallback callback) {
        this.startCallback = callback;
    }

    private void buildUI(String[] locales) {
        // Title
        TextView title = new TextView(getContext());
        title.setText("Select Languages");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF212121);
        title.setPadding(0, 0, 0, dp(8));
        addView(title);

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
            CheckBox cb = new CheckBox(getContext());
            cb.setText(LocaleUtils.formatForDisplay(locale));
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
        for (CheckBox cb : checkBoxes) {
            if (!cb.isChecked()) {
                allChecked = false;
                break;
            }
        }
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

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                getContext().getResources().getDisplayMetrics());
    }
}
