#!/usr/bin/env python3
"""Add the opt-in N500 compact forecast to the *isolated preview* variant.

Runs after patch_glass.py and patch_preview.py. No stable APK is touched.
"""
from __future__ import annotations

from pathlib import Path
import sys

SOURCE = "app/src/main/java/com/gathin/tvhome/MainActivity.java"
LAYOUT = "app/src/main/res/layout/activity_main.xml"
NEW = "app/src/main/java/com/gathin/tvhome/N500MiniForecast.java"


def once(path: Path, before: str, after: str) -> None:
    original = path.read_text(encoding="utf-8")
    count = original.count(before)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one matching anchor, got {count}: {before[:75]!r}")
    path.write_text(original.replace(before, after, 1), encoding="utf-8")
    print(f"Patched {path.name}: {before[:50]!r}")


JAVA = r'''package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.util.TypedValue;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Configurable, light Home forecast. Uses the SAME Open-Meteo cache as WeatherActivity.
 * Never shows the upstream WeatherActivity's synthetic fallback forecast as real data.
 */
final class N500MiniForecast {
    private static final String PREFS = "n500_weather_options";
    private static final String MODE = "mode";
    private static final String DAYS = "days";
    private static final String RAIN = "rain";
    private static final String LOW = "high_low";
    private static final String[] MODES = {"original", "mini", "both", "off"};

    private N500MiniForecast() {}

    private static int dp(Context context, float value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    private static SharedPreferences options(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String mode(Context context) {
        String value = options(context).getString(MODE, "original");
        for (String allowed : MODES) {
            if (allowed.equals(value)) return value;
        }
        return "original";
    }

    static void update(Activity activity, TextView original, LinearLayout forecast) {
        if (original == null || forecast == null) return;
        String display = mode(activity);
        boolean showOriginal = "original".equals(display) || "both".equals(display);
        boolean showMini = "mini".equals(display) || "both".equals(display);
        original.setVisibility(showOriginal ? View.VISIBLE : View.GONE);
        forecast.setVisibility(showMini ? View.VISIBLE : View.GONE);
        if (!showMini) {
            forecast.removeAllViews();
            return;
        }

        // When original is hidden, locate mini row immediately below the clock.
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) forecast.getLayoutParams();
        params.removeRule(RelativeLayout.BELOW);
        params.addRule(RelativeLayout.BELOW,
                showOriginal ? R.id.weatherAirQuality : R.id.leftBlock);
        forecast.setLayoutParams(params);

        forecast.removeAllViews();
        SharedPreferences opts = options(activity);
        int requested = opts.getInt(DAYS, 5);
        if (requested != 3 && requested != 5 && requested != 7) requested = 5;
        boolean rain = opts.getBoolean(RAIN, true);
        boolean highLow = opts.getBoolean(LOW, true);

        List<WeatherAirQuality.DailyItem> data = null;
        try {
            // WeatherAirQuality.getFullWeather() fabricates sample days offline.
            // Require actual JSON whose first forecast day matches today's LOCAL DATE.
            SharedPreferences weather = activity.getSharedPreferences("weather_aqi_cache", Context.MODE_PRIVATE);
            String cached = weather.getString("weather_json", null);
            if (cached != null) {
                JSONObject root = new JSONObject(cached);
                JSONObject daily = root.optJSONObject("daily");
                JSONArray dates = daily == null ? null : daily.optJSONArray("time");
                JSONArray highs = daily == null ? null : daily.optJSONArray("temperature_2m_max");
                JSONArray lows = daily == null ? null : daily.optJSONArray("temperature_2m_min");
                if (dates != null && highs != null && lows != null &&
                        dates.length() > 0 && highs.length() > 0 && lows.length() > 0) {
                    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
                    format.setTimeZone(TimeZone.getTimeZone("Asia/Kuala_Lumpur"));
                    String today = format.format(new Date());
                    if (today.equals(dates.optString(0))) {
                        data = WeatherAirQuality.getFullWeather(activity).daily;
                    }
                }
            }
        } catch (Exception ignored) {
            // Do not show made-up or corrupt weather data on Home.
        }

        if (data == null || data.isEmpty()) {
            TextView placeholder = createText(activity, "Forecast unavailable\nOK to open Weather");
            placeholder.setPadding(dp(activity, 14), dp(activity, 7),
                    dp(activity, 14), dp(activity, 7));
            forecast.addView(placeholder);
            return;
        }

        for (int i = 0; i < Math.min(requested, data.size()); i++) {
            WeatherAirQuality.DailyItem day = data.get(i);
            StringBuilder label = new StringBuilder();
            label.append(day.day).append("\n").append(day.icon).append(" ");
            label.append(day.maxTemp).append("°");
            if (highLow) label.append("/").append(day.minTemp).append("°");
            if (rain) label.append("\n").append("☂ ").append(day.rainChance).append("%");
            TextView text = createText(activity, label.toString());
            text.setMinWidth(dp(activity, highLow ? 83 : 69));
            text.setPadding(dp(activity, 7), dp(activity, 5),
                    dp(activity, 7), dp(activity, 5));
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cell.setMarginEnd(dp(activity, 3));
            forecast.addView(text, cell);
        }
    }

    private static TextView createText(Context context, String label) {
        TextView text = new TextView(context);
        text.setText(label);
        text.setGravity(Gravity.CENTER);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setIncludeFontPadding(false);
        text.setLineSpacing(dp(context, 2), 1.0f);
        text.setBackgroundResource(R.drawable.weather_pill_bg);
        text.setFocusable(false);
        return text;
    }

    /** Device-remote friendly, persistent controls. The original display is the default. */
    static void showSettings(Activity activity, Runnable onChanged) {
        SharedPreferences prefs = options(activity);
        String mode = mode(activity);
        int days = prefs.getInt(DAYS, 5);
        boolean rain = prefs.getBoolean(RAIN, true);
        boolean low = prefs.getBoolean(LOW, true);

        String[] options = {
                ("original".equals(mode) ? "● " : "○ ") + "Original weather only",
                ("mini".equals(mode) ? "● " : "○ ") + "Mini forecast only",
                ("both".equals(mode) ? "● " : "○ ") + "Original + mini forecast",
                ("off".equals(mode) ? "● " : "○ ") + "Hide all Home weather",
                (days == 3 ? "● " : "○ ") + "3-day forecast",
                (days == 5 ? "● " : "○ ") + "5-day forecast",
                (days == 7 ? "● " : "○ ") + "7-day forecast",
                "Rain chance: " + (rain ? "ON" : "OFF"),
                "High/low temperatures: " + (low ? "ON" : "OFF")
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(
                activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle("N500 Weather Layout");
        builder.setItems(options, (dialog, which) -> {
            SharedPreferences.Editor editor = prefs.edit();
            if (which < 4) editor.putString(MODE, MODES[which]);
            else if (which < 7) editor.putInt(DAYS, new int[]{3, 5, 7}[which - 4]);
            else if (which == 7) editor.putBoolean(RAIN, !rain);
            else if (which == 8) editor.putBoolean(LOW, !low);
            editor.apply();
            if (onChanged != null) onChanged.run();
            dialog.dismiss();
            showSettings(activity, onChanged);
        });
        builder.setNegativeButton("Done", (dialog, which) -> dialog.dismiss());
        builder.show();
    }
}
'''


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 tools/patch_mini_weather.py _upstream/launcher")
    root = Path(sys.argv[1])
    manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    if "android.intent.category.HOME" in manifest:
        raise RuntimeError("This opt-in weather patch is PREVIEW ONLY; refusing HOME-capable build.")
    java = root / NEW
    if java.exists():
        raise RuntimeError(f"{NEW} already exists — refusing to overwrite")
    java.write_text(JAVA, encoding="utf-8")

    activity = root / SOURCE
    layout = root / LAYOUT

    once(layout, '            <!-- Top Menu Buttons: Strictly in the same row, vertically centered with the clock -->',
         '''            <!-- Optional N500 mini forecast, kept hidden with the original weather UI by default. -->
            <LinearLayout
                android:id="@+id/n500MiniForecast"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_below="@id/weatherAirQuality"
                android:layout_alignParentStart="true"
                android:layout_marginTop="6dp"
                android:orientation="horizontal"
                android:gravity="center_vertical"
                android:focusable="true"
                android:clickable="true"
                android:visibility="gone" />

            <!-- Top Menu Buttons: Strictly in the same row, vertically centered with the clock -->''')

    once(activity, "    private TextView weatherAirQualityView;",
         "    private TextView weatherAirQualityView;\n    private LinearLayout n500MiniForecastView;")

    # Initialize the mini strip before the first weather/AQI update.
    once(activity, "        updateWeatherAqi();\n        WeatherAirQuality.syncIfDue(this, this::updateWeatherAqi);\n\n        islamicShelf",
         '''        n500MiniForecastView = findViewById(R.id.n500MiniForecast);
        if (n500MiniForecastView != null) {
            n500MiniForecastView.setOnClickListener(v ->
                    startActivity(new Intent(this, WeatherActivity.class)));
            n500MiniForecastView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusTile(0);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    View target = (btnInputs != null && btnInputs.getVisibility() == View.VISIBLE) ? btnInputs
                            : (btnWifi != null && btnWifi.getVisibility() == View.VISIBLE) ? btnWifi
                            : findViewById(R.id.btnSettings);
                    if (target != null) target.requestFocus();
                    return target != null;
                }
                return false;
            });
            n500MiniForecastView.setOnFocusChangeListener((v, hasFocus) ->
                    v.animate().scaleX(hasFocus ? 1.04f : 1.0f)
                            .scaleY(hasFocus ? 1.04f : 1.0f).setDuration(150).start());
        }
        updateWeatherAqi();
        WeatherAirQuality.syncIfDue(this, this::updateWeatherAqi);

        islamicShelf''')

    once(activity, '''    private void updateWeatherAqi() {
        if (weatherAirQualityView != null) {
            weatherAirQualityView.setText(WeatherAirQuality.getFormatted(this));
        }
    }''',
         '''    private void updateWeatherAqi() {
        if (weatherAirQualityView != null) {
            weatherAirQualityView.setText(WeatherAirQuality.getFormatted(this));
        }
        N500MiniForecast.update(this, weatherAirQualityView, n500MiniForecastView);
    }''')

    once(activity, '''        // Row 2: Location API Haze''',
         '''        // N500 optional widget: original / mini / both / off, 3/5/7 days.
        addInputRow(rows, close, R.drawable.ic_weather_partly_cloudy, "Weather Layout", -1, () -> {
            SoundEffects.playSelect();
            N500MiniForecast.showSettings(MainActivity.this, MainActivity.this::updateWeatherAqi);
        });

        // Row 2: Location API Haze''')

    once(activity, '''                    if (weatherAirQualityView != null && weatherAirQualityView.getVisibility() == View.VISIBLE) {
                        weatherAirQualityView.requestFocus();
                        return true;
                    }''',
         '''                    if (weatherAirQualityView != null && weatherAirQualityView.getVisibility() == View.VISIBLE) {
                        weatherAirQualityView.requestFocus();
                        return true;
                    }
                    if (n500MiniForecastView != null && n500MiniForecastView.getVisibility() == View.VISIBLE) {
                        n500MiniForecastView.requestFocus();
                        return true;
                    }''')

    # Ensure no additional network fetches and all elements default to original display.
    code = java.read_text(encoding="utf-8")
    assert '"original"' in code and '"mini"' in code and '"both"' in code and '"off"' in code
    assert 'WeatherAirQuality.getFullWeather' in code and '"weather_json"' in code
    assert "http://" not in code and "https://" not in code
    assert "@+id/n500MiniForecast" in layout.read_text(encoding="utf-8")
    print("N500 mini weather patch ready: preview only, settings persisted, original mode default.")


if __name__ == "__main__":
    main()
