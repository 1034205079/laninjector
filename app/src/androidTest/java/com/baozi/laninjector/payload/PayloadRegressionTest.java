package com.baozi.laninjector.payload;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.baozi.laninjector.MainActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import android.view.View;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PayloadRegressionTest {
    @Test public void languageArrowsGoBackwardAndForwardAndDraggingDoesNotSwitch() {
        ActivityTracker tracker = new ActivityTracker();
        AtomicReference<FloatingMenuManager> manager = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                tracker.onActivityStarted(activity); tracker.onActivityResumed(activity);
                FloatingMenuManager menu = new FloatingMenuManager(activity, new String[]{"en", "ja"}, tracker);
                manager.set(menu);
                try {
                    java.lang.reflect.Method start = FloatingMenuManager.class.getDeclaredMethod("startCycling", java.util.List.class);
                    start.setAccessible(true); start.invoke(menu, java.util.Arrays.asList("en", "ja"));
                } catch (Exception e) { throw new AssertionError(e); }
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                FloatingBallView ball = (FloatingBallView) getBall(manager.get());
                assertTrue(ball.getWidth() > 0);
                tapBall(ball, ball.getWidth() * .25f);
                assertEquals(LocaleUtils.formatForDisplay("ja"), ball.getDisplayText());
                tapBall(ball, ball.getWidth() * .75f);
                assertEquals(LocaleUtils.formatForDisplay("en"), ball.getDisplayText());
                long now = android.os.SystemClock.uptimeMillis();
                android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, 0, 20, 20, 0);
                android.view.MotionEvent move = android.view.MotionEvent.obtain(now, now + 20, 2, 60, 20, 0);
                android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 30, 1, 60, 20, 0);
                try { ball.onTouchEvent(down); ball.onTouchEvent(move); ball.onTouchEvent(up); }
                finally { down.recycle(); move.recycle(); up.recycle(); }
                assertEquals(LocaleUtils.formatForDisplay("en"), ball.getDisplayText());
                try {
                    java.lang.reflect.Method reset = FloatingMenuManager.class.getDeclaredMethod("resetToIdle");
                    reset.setAccessible(true); reset.invoke(manager.get());
                } catch (Exception e) { throw new AssertionError(e); }
                tracker.onActivityStopped(activity);
            });
        }
    }

    private void tapBall(FloatingBallView ball, float x) {
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, 0, x, ball.getHeight() * .75f, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 10, 1, x, ball.getHeight() * .75f, 0);
        try { ball.onTouchEvent(down); ball.onTouchEvent(up); } finally { down.recycle(); up.recycle(); }
    }

    @Test public void rtlLanguageReturnsToLtrWithoutClosingDialogOrChangingExplicitDirection() {
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        AtomicReference<LinearLayout> contentRef = new AtomicReference<>();
        AtomicReference<TextView> fixedRef = new AtomicReference<>();
        AtomicReference<EditText> inputRef = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
                TextView fixed = new TextView(activity); fixed.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); fixed.setText("Fixed LTR");
                EditText input = new EditText(activity); input.setText("Keep input 123");
                content.addView(fixed); content.addView(input);
                Dialog dialog = new Dialog(activity); dialog.setContentView(content); dialog.show();
                dialogRef.set(dialog); contentRef.set(content); fixedRef.set(fixed); inputRef.set(input);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                Dialog dialog = dialogRef.get();
                try {
                    for (String locale : new String[]{"ar", "en", "ar", "zh-rCN", "ar", "ja"}) {
                        LocaleSwitcher.switchLocale(activity, locale);
                        int expected = locale.equals("ar") ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
                        assertEquals(expected, activity.getWindow().getDecorView().getLayoutDirection());
                        assertEquals(expected, dialog.getWindow().getDecorView().getLayoutDirection());
                        assertEquals(expected, contentRef.get().getLayoutDirection());
                        assertEquals(View.LAYOUT_DIRECTION_LTR, fixedRef.get().getLayoutDirection());
                        assertEquals("Keep input 123", inputRef.get().getText().toString());
                        assertTrue(dialog.isShowing()); assertFalse(activity.isDestroyed());
                    }
                } finally { dialog.dismiss(); }
            });
        }
    }

    @Test public void languageRecommendationsDefaultSelectAndRememberManualChanges() {
        main(() -> {
            android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            android.content.SharedPreferences prefs = context.getSharedPreferences("laninjector_language_selection", android.content.Context.MODE_PRIVATE);
            java.util.Set<String> prior = prefs.contains("selected") ? new java.util.HashSet<>(prefs.getStringSet("selected", java.util.Collections.emptySet())) : null;
            prefs.edit().remove("selected").commit();
            try {
                LanguagePanelView panel = new LanguagePanelView(context, new String[]{"ja", "ko"});
                android.widget.ScrollView scroll = null;
                for (int i = 0; i < panel.getChildCount(); i++) if (panel.getChildAt(i) instanceof android.widget.ScrollView) scroll = (android.widget.ScrollView) panel.getChildAt(i);
                assertNotNull(scroll);
                android.view.ViewGroup list = (android.view.ViewGroup) scroll.getChildAt(0);
                assertTrue(((android.widget.CheckBox) list.getChildAt(0)).isChecked());
                assertFalse(((android.widget.CheckBox) list.getChildAt(1)).isChecked());
                ((android.widget.CheckBox) list.getChildAt(1)).setChecked(true);
                LanguagePanelView reopened = new LanguagePanelView(context, new String[]{"ja", "ko"});
                for (int i = 0; i < reopened.getChildCount(); i++) if (reopened.getChildAt(i) instanceof android.widget.ScrollView) {
                    android.view.ViewGroup restored = (android.view.ViewGroup) ((android.widget.ScrollView) reopened.getChildAt(i)).getChildAt(0);
                    assertTrue(((android.widget.CheckBox) restored.getChildAt(1)).isChecked());
                }
            } finally {
                android.content.SharedPreferences.Editor edit = prefs.edit();
                if (prior == null) edit.remove("selected"); else edit.putStringSet("selected", prior);
                edit.commit();
            }
        });
    }

    @Test public void anonymousLayoutLabelsUseExactResourceDespiteDuplicateText() throws Exception {
        android.content.Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ResourceStringIndex.warm(app);
        for (int i = 0; i < 100 && ResourceStringIndex.layouts().isEmpty(); i++) Thread.sleep(50);
        assertFalse(ResourceStringIndex.layouts().isEmpty());
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        AtomicReference<android.view.ViewGroup> layoutRef = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Configuration config = new Configuration(activity.getResources().getConfiguration());
                config.setLocale(Locale.ENGLISH);
                android.content.Context context = activity.createConfigurationContext(config);
                android.view.ViewGroup layout = (android.view.ViewGroup) android.view.LayoutInflater.from(context)
                        .inflate(com.baozi.laninjector.R.layout.payload_test_anonymous, null);
                Dialog dialog = new Dialog(activity); dialog.setContentView(layout); dialog.show();
                dialogRef.set(dialog); layoutRef.set(layout);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                Dialog dialog = dialogRef.get(); android.view.ViewGroup layout = layoutRef.get();
                assertTrue(layout.isAttachedToWindow());
                try {
                    LiveWindowLocaleRefresh refresh = LiveWindowLocaleRefresh.capture(activity);
                    assertNotNull("Anonymous label structural binding", LayoutStructureBindings.get(layout.getChildAt(1)));
                    assertEquals(com.baozi.laninjector.R.string.payload_test_anonymous,
                            LayoutStructureBindings.get(layout.getChildAt(1))[0]);
                    refresh.updateResources(Locale.JAPANESE); refresh.refreshText();
                    assertEquals("視聴履歴", ((TextView) layout.getChildAt(1)).getText().toString());
                    LiveWindowLocaleRefresh cached = LiveWindowLocaleRefresh.capture(activity);
                    cached.updateResources(Locale.KOREAN); cached.refreshText();
                    assertEquals("시청 기록", ((TextView) layout.getChildAt(1)).getText().toString());
                    TextView replacement = new TextView(layout.getContext());
                    replacement.setText(com.baozi.laninjector.R.string.payload_test_anonymous);
                    layout.removeViewAt(1); layout.addView(replacement);
                    LayoutStructureBindings.capture(layout);
                    assertNotNull("Replaced anonymous child must invalidate layout reuse", LayoutStructureBindings.get(replacement));
                    assertEquals(com.baozi.laninjector.R.string.payload_test_anonymous, LayoutStructureBindings.get(replacement)[0]);
                    assertTrue(dialog.isShowing());
                } finally { dialog.dismiss(); }
            });
        }
    }

    @Test public void signingIdentitySurvivesRepeatedAndConcurrentLoads() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        com.baozi.laninjector.model.SigningConfig config = new com.baozi.laninjector.model.SigningConfig(false, null, java.util.Collections.emptyList());
        com.baozi.laninjector.injection.KeyStoreManager manager = new com.baozi.laninjector.injection.KeyStoreManager(context);
        byte[] expected = manager.getSigningKey(config).getCertificate().getEncoded();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<byte[]>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(pool.submit(() -> new com.baozi.laninjector.injection.KeyStoreManager(context).getSigningKey(config).getCertificate().getEncoded()));
            for (java.util.concurrent.Future<byte[]> result : results) assertArrayEquals(expected, result.get());
        } finally { pool.shutdownNow(); }
        try {
            manager.getSigningKey(new com.baozi.laninjector.model.SigningConfig(true, null, java.util.Collections.emptyList()));
            fail("Invalid custom configuration must not silently switch certificates");
        } catch (IllegalStateException expectedFailure) { }
    }

    private void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    @Test public void stoppingOldActivityDoesNotHideCurrentActivity() throws Exception {
        ActivityTracker tracker = new ActivityTracker();
        AtomicReference<Activity> firstRef = new AtomicReference<>();
        AtomicReference<Activity> secondRef = new AtomicReference<>();
        main(() -> { firstRef.set(new Activity()); secondRef.set(new Activity()); });
        Activity first = firstRef.get();
        Activity second = secondRef.get();
        main(() -> {
            tracker.onActivityStarted(first);
            tracker.onActivityResumed(first);
            tracker.onActivityStarted(second);
            tracker.onActivityResumed(second);
            tracker.onActivityStopped(first);
        });
        Thread.sleep(450);
        main(() -> { assertTrue(tracker.isForeground()); assertSame(second, tracker.getCurrentActivity()); });
        main(() -> tracker.onActivityStopped(second));
        Thread.sleep(450);
        main(() -> assertFalse(tracker.isForeground()));
    }

    @Test public void pausingForDialogDoesNotHideControlsAndSameActivityResumeNotifies() throws Exception {
        ActivityTracker tracker = new ActivityTracker();
        AtomicReference<Activity> activityRef = new AtomicReference<>();
        main(() -> activityRef.set(new Activity()));
        Activity activity = activityRef.get();
        AtomicInteger resumes = new AtomicInteger();
        main(() -> {
            tracker.setOnActivityChangedListener(a -> resumes.incrementAndGet());
            tracker.onActivityStarted(activity);
            tracker.onActivityResumed(activity);
            tracker.onActivityPaused(activity);
        });
        Thread.sleep(450);
        main(() -> {
            assertTrue(tracker.isForeground());
            tracker.onActivityResumed(activity);
            assertEquals(2, resumes.get());
            tracker.onActivityStopped(activity);
        });
        Thread.sleep(450);
    }

    @Test public void languageSwitchTranslatesDialogWithoutRecreatingOrChangingInput() throws Exception {
        ResourceStringIndex.warm(InstrumentationRegistry.getInstrumentation().getTargetContext());
        for (int i = 0; i < 100 && ResourceStringIndex.ids().isEmpty(); i++) Thread.sleep(50);
        assertFalse(ResourceStringIndex.ids().isEmpty());
        Locale previous = Locale.getDefault();
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        AtomicReference<TextView> labelRef = new AtomicReference<>();
        AtomicReference<EditText> inputRef = new AtomicReference<>();
        AtomicReference<TextView> amountRef = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Dialog dialog = new Dialog(activity);
                LinearLayout content = new LinearLayout(activity);
                content.setOrientation(LinearLayout.VERTICAL);
                TextView label = new TextView(activity);
                label.setText(android.R.string.cancel);
                EditText input = new EditText(activity);
                input.setText("Keep my input");
                input.setHint(com.baozi.laninjector.R.string.payload_test_hint);
                TextView amount = new TextView(activity);
                amount.setText(activity.getString(com.baozi.laninjector.R.string.payload_test_minimum, "$0.1"));
                content.addView(label);
                content.addView(input);
                content.addView(amount);
                dialog.setContentView(content);
                dialog.show();
                dialogRef.set(dialog);
                labelRef.set(label);
                inputRef.set(input);
                amountRef.set(amount);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                Dialog dialog = dialogRef.get();
                TextView label = labelRef.get();
                EditText input = inputRef.get();
                assertTrue(label.isAttachedToWindow());
                Configuration config = new Configuration(activity.getResources().getConfiguration());
                config.setLocales(LocaleList.forLanguageTags("ja"));
                String expected = activity.createConfigurationContext(config).getString(android.R.string.cancel);
                LocaleSwitcher.switchLocale(activity, "ja");
                assertTrue(dialog.isShowing());
                assertFalse(activity.isDestroyed());
                assertEquals(expected, label.getText().toString());
                assertEquals("Keep my input", input.getText().toString());
                assertEquals(activity.createConfigurationContext(config).getString(
                        com.baozi.laninjector.R.string.payload_test_hint), input.getHint().toString());
                assertEquals(activity.createConfigurationContext(config).getString(
                        com.baozi.laninjector.R.string.payload_test_minimum, "$0.1"), amountRef.get().getText().toString());
                LocaleSwitcher.switchLocale(activity, "ko");
                config.setLocales(LocaleList.forLanguageTags("ko"));
                assertEquals(activity.createConfigurationContext(config).getString(android.R.string.cancel),
                        label.getText().toString());
                assertTrue(dialog.isShowing());
                dialog.dismiss();
                assertEquals("Keep my input", input.getText().toString());
                assertEquals(activity.createConfigurationContext(config).getString(
                        com.baozi.laninjector.R.string.payload_test_hint), input.getHint().toString());
                assertEquals(activity.createConfigurationContext(config).getString(
                        com.baozi.laninjector.R.string.payload_test_minimum, "$0.1"), amountRef.get().getText().toString());
            });
        } finally { Locale.setDefault(previous); }
    }

    @Test public void detachedMenuRepairsAndReturnsFromBackgroundOnSameActivity() throws Exception {
        ActivityTracker tracker = new ActivityTracker();
        AtomicReference<FloatingMenuManager> manager = new AtomicReference<>();
        AtomicReference<Activity> current = new AtomicReference<>();
        android.app.Application app = (android.app.Application)
                InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        main(() -> app.registerActivityLifecycleCallbacks(tracker));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                current.set(activity);
                FloatingMenuManager menu = new FloatingMenuManager(activity, new String[] {"en", "ja"}, tracker);
                manager.set(menu);
                try {
                    java.lang.reflect.Method show = FloatingMenuManager.class.getDeclaredMethod("showBall", Activity.class);
                    show.setAccessible(true);
                    show.invoke(menu, activity);
                } catch (Exception e) { throw new AssertionError(e); }
                tracker.onActivityResumed(activity);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            main(() -> {
                View ball = getBall(manager.get());
                assertTrue(ball.isAttachedToWindow());
                try {
                    java.lang.reflect.Method remove = FloatingMenuManager.class.getDeclaredMethod("removeFromWindow", View.class);
                    remove.setAccessible(true);
                    remove.invoke(manager.get(), ball);
                } catch (Exception e) { throw new AssertionError(e); }
            });
            Thread.sleep(1400);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            main(() -> {
                assertTrue(getBall(manager.get()).isAttachedToWindow());
                assertEquals(View.VISIBLE, getBall(manager.get()).getVisibility());
                tracker.onActivityStopped(current.get());
            });
            Thread.sleep(450);
            main(() -> {
                assertEquals(View.GONE, getBall(manager.get()).getVisibility());
                tracker.onActivityStarted(current.get());
                tracker.onActivityResumed(current.get());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            main(() -> assertEquals(View.VISIBLE, getBall(manager.get()).getVisibility()));
        } finally {
            main(() -> {
                tracker.onActivityStopped(current.get());
                app.unregisterActivityLifecycleCallbacks(tracker);
            });
            Thread.sleep(450);
        }
    }

    private View getBall(FloatingMenuManager manager) {
        try {
            java.lang.reflect.Field field = FloatingMenuManager.class.getDeclaredField("ballView");
            field.setAccessible(true);
            return (View) field.get(manager);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test public void navigationMenuTitlesRemainTranslatedAfterSelectingTabs() throws Exception {
        ResourceStringIndex.warm(InstrumentationRegistry.getInstrumentation().getTargetContext());
        for (int i = 0; i < 100 && ResourceStringIndex.ids().isEmpty(); i++) Thread.sleep(50);
        assertTrue(ResourceStringIndex.menus().contains(com.baozi.laninjector.R.menu.payload_test_navigation));
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        AtomicReference<MenuModelView> navigationRef = new AtomicReference<>();
        Locale previous = Locale.getDefault();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                MenuModelView navigation = new MenuModelView(activity);
                navigation.select(com.baozi.laninjector.R.id.payload_test_nav_home);
                Dialog dialog = new Dialog(activity);
                dialog.setContentView(navigation);
                dialog.show();
                dialogRef.set(dialog);
                navigationRef.set(navigation);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                MenuModelView navigation = navigationRef.get();
                for (String language : new String[] {"ja", "ko", "zh-CN", "zh-rCN", "ja"}) {
                    int clicks = navigation.clicks;
                    int selected = navigation.selected;
                    LocaleSwitcher.switchLocale(activity, language);
                    assertEquals(clicks, navigation.clicks);
                    assertEquals(selected, navigation.selected);
                    assertTrue(navigation.getMenu().findItem(selected).isChecked());
                    Configuration config = new Configuration(activity.getResources().getConfiguration());
                    config.setLocales(new LocaleList(LocaleUtils.parseLocaleQualifier(language)));
                    String expected = activity.createConfigurationContext(config).getString(android.R.string.cancel);
                    assertEquals(expected, navigation.getMenu().findItem(
                            com.baozi.laninjector.R.id.payload_test_nav_profile).getTitle().toString());
                    navigation.select(com.baozi.laninjector.R.id.payload_test_nav_profile);
                    assertEquals(expected, navigation.label.getText().toString());
                    assertTrue(dialogRef.get().isShowing());
                }
                dialogRef.get().dismiss();
            });
        } finally { Locale.setDefault(previous); }
    }

    public static final class MenuModelView extends LinearLayout {
        final android.view.Menu menu;
        final TextView label;
        int selected;
        int clicks;

        MenuModelView(android.content.Context context) {
            super(context);
            menu = new android.widget.PopupMenu(context, this).getMenu();
            new android.view.MenuInflater(context).inflate(com.baozi.laninjector.R.menu.payload_test_navigation, menu);
            label = new TextView(context);
            addView(label);
        }

        public android.view.Menu getMenu() { return menu; }

        void select(int id) {
            selected = id;
            clicks++;
            menu.findItem(id).setChecked(true);
            label.setText(menu.findItem(id).getTitle());
        }
    }

    @Test public void formattedAndConcatenatedLabelsPreserveArgumentsAndRejectUnsafeMatches() {
        ResourceTextTemplate.Match formatted = ResourceTextTemplate.match(
                "最低提现金额为 %1$s", "最低提现金额为 $0.1");
        assertNotNull(formatted);
        assertEquals("Montant minimum : $0.1", formatted.render("Montant minimum : %1$s"));
        assertNull(formatted.render("Montant minimum : %2$s"));
        ResourceTextTemplate.Match joined = ResourceTextTemplate.match("最低提现金额为", "最低提现金额为 $0.1");
        assertNotNull(joined);
        assertEquals("Minimum amount is $0.1", joined.render("Minimum amount is"));
        assertNull(ResourceTextTemplate.match("最低提现金额为", "最低提现金额为 Alice"));
        ResourceTextTemplate.Match reordered = ResourceTextTemplate.match(
                "Account %1$s minimum %2$s", "Account PayPal minimum $0.1");
        assertNotNull(reordered);
        assertEquals("Minimum $0.1 for PayPal", reordered.render("Minimum %2$s for %1$s"));
        android.text.SpannableString rich = new android.text.SpannableString("Minimum amount : %1$s");
        android.text.style.StyleSpan bold = new android.text.style.StyleSpan(android.graphics.Typeface.BOLD);
        rich.setSpan(bold, 0, rich.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        CharSequence rendered = formatted.renderText(rich);
        assertTrue(rendered instanceof android.text.Spanned);
        assertEquals("Minimum amount : $0.1", rendered.toString());
        android.text.Spanned spanned = (android.text.Spanned) rendered;
        assertEquals(0, spanned.getSpanStart(bold));
        assertEquals(rendered.length(), spanned.getSpanEnd(bold));
    }

    @Test public void arraysPluralsStylesAccessibilityAndWidgetModelsFollowLocale() throws Exception {
        ResourceStringIndex.warm(InstrumentationRegistry.getInstrumentation().getTargetContext());
        for (int i = 0; i < 100 && ResourceStringIndex.ids().isEmpty(); i++) Thread.sleep(50);
        assertTrue(ResourceStringIndex.arrays().contains(com.baozi.laninjector.R.array.payload_test_choices));
        assertTrue(ResourceStringIndex.plurals().contains(com.baozi.laninjector.R.plurals.payload_test_count));
        AtomicReference<ExtendedFixture> fixtureRef = new AtomicReference<>();
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        Locale previous = Locale.getDefault();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ExtendedFixture fixture = new ExtendedFixture(activity);
                Dialog dialog = new Dialog(activity);
                dialog.setContentView(fixture);
                dialog.show();
                fixtureRef.set(fixture);
                dialogRef.set(dialog);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ExtendedFixture fixture = fixtureRef.get();
                for (String language : new String[] {"ja", "ko", "zh-rCN", "en"}) {
                    LocaleSwitcher.switchLocale(activity, language);
                    Configuration config = new Configuration(activity.getResources().getConfiguration());
                    config.setLocales(new LocaleList(LocaleUtils.parseLocaleQualifier(language)));
                    android.content.res.Resources expected = activity.createConfigurationContext(config).getResources();
                    assertEquals(expected.getStringArray(com.baozi.laninjector.R.array.payload_test_choices)[0],
                            fixture.arrayLabel.getText().toString());
                    assertEquals(expected.getQuantityString(com.baozi.laninjector.R.plurals.payload_test_count, 3, 3),
                            fixture.countLabel.getText().toString());
                    String hint = expected.getString(com.baozi.laninjector.R.string.payload_test_hint);
                    assertEquals(hint, fixture.styled.getText().toString());
                    assertEquals(0xff123456, fixture.styled.getCurrentTextColor());
                    assertEquals(hint, fixture.described.getContentDescription().toString());
                    assertEquals(hint, fixture.described.getTooltipText().toString());
                    assertEquals(hint, fixture.described.getAccessibilityPaneTitle().toString());
                    assertEquals(hint, fixture.described.getStateDescription().toString());
                    assertEquals(hint, fixture.toolbar.getTitle().toString());
                    assertEquals(expected.getString(android.R.string.cancel), fixture.toolbar.getSubtitle().toString());
                    assertEquals(expected.getString(android.R.string.ok), fixture.toolbar.getNavigationContentDescription().toString());
                    assertEquals(expected.getString(android.R.string.ok), fixture.toggle.getTextOn().toString());
                    assertEquals(expected.getString(android.R.string.cancel), fixture.toggle.getTextOff().toString());
                    assertTrue(fixture.toggle.isChecked());
                    fixture.toggle.setChecked(false);
                    assertEquals(expected.getString(android.R.string.cancel), fixture.toggle.getText().toString());
                    fixture.toggle.setChecked(true);
                    assertEquals("Do not change my input", fixture.input.getText().toString());
                    assertTrue(dialogRef.get().isShowing());
                }
                dialogRef.get().dismiss();
            });
        } finally { Locale.setDefault(previous); }
    }

    @Test public void recycledArrayLabelIsRepairedWithoutReplacingWidgetOrInput() throws Exception {
        ResourceStringIndex.warm(InstrumentationRegistry.getInstrumentation().getTargetContext());
        for (int i = 0; i < 100 && ResourceStringIndex.ids().isEmpty(); i++) Thread.sleep(50);
        AtomicReference<ExtendedFixture> fixtureRef = new AtomicReference<>();
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        AtomicReference<String> expectedRef = new AtomicReference<>();
        Locale previous = Locale.getDefault();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ExtendedFixture fixture = new ExtendedFixture(activity);
                Dialog dialog = new Dialog(activity);
                dialog.setContentView(fixture);
                dialog.show();
                fixtureRef.set(fixture); dialogRef.set(dialog);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                LocaleSwitcher.switchLocale(activity, "ko");
                ExtendedFixture fixture = fixtureRef.get();
                Configuration config = new Configuration(activity.getResources().getConfiguration());
                config.setLocales(LocaleList.forLanguageTags("ko"));
                expectedRef.set(activity.createConfigurationContext(config).getResources()
                        .getStringArray(com.baozi.laninjector.R.array.payload_test_choices)[1]);
                // Simulate an adapter rebinding a different cached row on the same TextView.
                fixture.arrayLabel.setText(fixture.originalChoices[1]);
                fixture.requestLayout();
            });
            Thread.sleep(300);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertEquals(expectedRef.get(), fixtureRef.get().arrayLabel.getText().toString());
                assertEquals("Do not change my input", fixtureRef.get().input.getText().toString());
                assertTrue(dialogRef.get().isShowing());
                TextView lateLabel = new TextView(activity);
                lateLabel.setText(fixtureRef.get().originalChoices[0]);
                fixtureRef.get().addView(lateLabel);
                // Switch again before the new row has had its first layout pass.
                LocaleSwitcher.switchLocale(activity, "ja");
                Configuration config = new Configuration(activity.getResources().getConfiguration());
                config.setLocales(LocaleList.forLanguageTags("ja"));
                assertEquals(activity.createConfigurationContext(config).getResources()
                        .getStringArray(com.baozi.laninjector.R.array.payload_test_choices)[0], lateLabel.getText().toString());
                dialogRef.get().dismiss();
            });
        } finally { Locale.setDefault(previous); }
    }

    private static final class ExtendedFixture extends LinearLayout {
        final TextView arrayLabel;
        final TextView countLabel;
        final TextView styled;
        final View described;
        final android.widget.Toolbar toolbar;
        final android.widget.ToggleButton toggle;
        final EditText input;
        final String[] originalChoices;

        ExtendedFixture(Activity activity) {
            super(activity);
            setOrientation(VERTICAL);
            View inflated = activity.getLayoutInflater().inflate(com.baozi.laninjector.R.layout.payload_test_extended, this, false);
            addView(inflated);
            styled = inflated.findViewById(com.baozi.laninjector.R.id.payload_test_styled);
            described = inflated.findViewById(com.baozi.laninjector.R.id.payload_test_described);
            String hint = activity.getString(com.baozi.laninjector.R.string.payload_test_hint);
            described.setTooltipText(hint);
            described.setAccessibilityPaneTitle(hint);
            described.setStateDescription(hint);
            originalChoices = getResources().getStringArray(com.baozi.laninjector.R.array.payload_test_choices);
            arrayLabel = new TextView(activity); arrayLabel.setText(originalChoices[0]); addView(arrayLabel);
            countLabel = new TextView(activity);
            countLabel.setText(getResources().getQuantityString(com.baozi.laninjector.R.plurals.payload_test_count, 3, 3));
            addView(countLabel);
            toolbar = new android.widget.Toolbar(activity);
            toolbar.setTitle(hint); toolbar.setSubtitle(android.R.string.cancel);
            toolbar.setNavigationContentDescription(android.R.string.ok); addView(toolbar);
            toggle = new android.widget.ToggleButton(activity);
            toggle.setTextOn(activity.getString(android.R.string.ok));
            toggle.setTextOff(activity.getString(android.R.string.cancel));
            toggle.setChecked(true); addView(toggle);
            input = new EditText(activity); input.setText("Do not change my input"); addView(input);
        }
    }
}
