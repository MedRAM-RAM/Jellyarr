package com.fishbowl.app;

import android.os.Bundle;

import com.termux.R;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Full-screen host of the Media storage tool ({@link MediaLibrariesView}), opened from Settings and the
 * storage health cards. The same tool is shown in the Media tab.
 */
public class JellyfinStorageActivity extends AppCompatActivity {

    private MediaLibrariesView storage;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(I18n.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppTheme.init(this);
        AppTheme.applyNightMode();
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat bars = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!AppTheme.isDark());
        bars.setAppearanceLightNavigationBars(!AppTheme.isDark());
        getWindow().setStatusBarColor(AppTheme.bg());
        getWindow().setNavigationBarColor(AppTheme.bg());

        LinearLayout outer = UiKit.column(this);
        outer.setBackgroundColor(AppTheme.bg());
        ViewCompat.setOnApplyWindowInsetsListener(outer, (v, insets) -> {
            androidx.core.graphics.Insets sb = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            androidx.core.graphics.Insets nb = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(0, sb.top, 0, nb.bottom);
            return insets;
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout page = UiKit.column(this);
        UiKit.pagePadding(page);

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(AppTheme.text());
        back.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 8), UiKit.dp(this, 8), UiKit.dp(this, 8));
        back.setBackground(UiKit.rowRipple(this));
        back.setContentDescription(I18n.tr("Back"));
        back.setOnClickListener(v -> finish());
        LinearLayout top = UiKit.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 40), UiKit.dp(this, 40)));
        page.addView(top);

        page.addView(UiKit.header(this, I18n.tr("Media storage"),
                I18n.tr("Movies and TV libraries, their folders and drives"), null));
        storage = new MediaLibrariesView(this);
        page.addView(storage.getView());

        scroll.addView(page);
        outer.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(I18n.attach(outer));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (storage != null) storage.refresh(false);
    }
}
