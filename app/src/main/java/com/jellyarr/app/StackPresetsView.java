package com.jellyarr.app;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jellyarr.app.services.StackPresets;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Content of the "Stack presets" dialog: pick Prowlarr indexers, quality profiles and a few options.
 * The caller reads the result from {@link #selection()} when the user taps APPLY.
 */
final class StackPresetsView {
    private final Context ctx;
    private final StackPresets presets;
    private final StackPresets.Selection sel = new StackPresets.Selection();
    private final LinearLayout root;

    StackPresetsView(Context ctx, StackPresets presets) {
        this.ctx = ctx;
        this.presets = presets;
        this.root = UiKit.column(ctx);
        build();
    }

    View view() { return root; }

    StackPresets.Selection selection() { return sel; }

    private void build() {
        for (StackPresets.Profile p : presets.profiles) if ("balanced".equals(p.id)) sel.profileIds.add(p.id);
        for (StackPresets.Indexer i : presets.indexers) if (i.recommended) sel.indexerIds.add(i.id);

        root.addView(UiKit.caption(ctx, "Pick what to set up. Nothing you created yourself is changed, and applying again only updates what Jellyarr created."));

        section("QUALITY PROFILES (Radarr and Sonarr)");
        for (final StackPresets.Profile p : presets.profiles) {
            row(presets.fullName(p.name), p.summary, sel.profileIds.contains(p.id), false,
                    on -> toggle(sel.profileIds, p.id, on));
        }

        section("INDEXERS (Prowlarr)");
        Map<String, String> groups = new LinkedHashMap<>();
        groups.put("general", "General");
        groups.put("movies", "Movies");
        groups.put("tv", "TV shows");
        groups.put("anime", "Anime");
        for (Map.Entry<String, String> g : groups.entrySet()) {
            boolean header = false;
            for (final StackPresets.Indexer i : presets.indexers) {
                if (!g.getKey().equals(i.group)) continue;
                if (!header) {
                    TextView h = UiKit.overline(ctx, g.getValue());
                    h.setPadding(0, UiKit.dp(ctx, 10), 0, 0);
                    root.addView(h);
                    header = true;
                }
                row(i.label, i.note, sel.indexerIds.contains(i.id), i.cloudflare, on -> toggle(sel.indexerIds, i.id, on));
            }
        }
        root.addView(UiKit.caption(ctx, "Each indexer is tested when it is added. One that fails is skipped, so a dead site never breaks the stack."));

        section("OPTIONS");
        row("Apply to Radarr", "Movies", sel.radarr, false, on -> sel.radarr = on);
        row("Apply to Sonarr", "TV shows", sel.sonarr, false, on -> sel.sonarr = on);
        row("Set download size limits", "Per quality, in MB per minute. These are global in Radarr and Sonarr: the last preset applied wins.",
                sel.sizeLimits, false, on -> sel.sizeLimits = on);
        row("Smart filters", "Blocks CAM, 3D and hardcoded subtitles; the 720p profile also prefers x265.",
                sel.smartFilters, false, on -> sel.smartFilters = on);
    }

    private interface Toggle { void on(boolean checked); }

    private static void toggle(java.util.Set<String> set, String id, boolean on) {
        if (on) set.add(id); else set.remove(id);
    }

    private void section(String title) {
        TextView t = UiKit.overline(ctx, title);
        t.setPadding(0, UiKit.dp(ctx, 18), 0, UiKit.dp(ctx, 2));
        root.addView(t);
    }

    private void row(String title, String note, boolean checked, boolean warn, final Toggle t) {
        final LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, UiKit.dp(ctx, 4), 0, UiKit.dp(ctx, 4));

        final CheckBox cb = UiStyle.newCheckBox(ctx);
        cb.setChecked(checked);
        cb.setOnCheckedChangeListener((b, c) -> t.on(c));
        line.addView(cb);

        LinearLayout text = UiKit.column(ctx);
        TextView name = UiKit.text(ctx, title, 13.5f, AppTheme.text(), UiKit.MEDIUM);
        text.addView(name);
        if (note != null && !note.isEmpty()) text.addView(UiKit.caption(ctx, note));
        if (warn) {
            TextView w = UiKit.caption(ctx, "May need a Cloudflare solver");
            w.setTextColor(AppTheme.warning());
            text.addView(w);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMarginStart(UiKit.dp(ctx, 6));
        line.addView(text, lp);

        line.setClickable(true);
        line.setOnClickListener((View v) -> cb.setChecked(!cb.isChecked()));
        root.addView(line);
    }
}
