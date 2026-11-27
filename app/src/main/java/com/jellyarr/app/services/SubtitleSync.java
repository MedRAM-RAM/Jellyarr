package com.jellyarr.app.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatic subtitle timing alignment against a reference subtitle that is known to fit the video.
 *
 * <p>The two files may be in different languages, so only <em>when something is being said</em> is compared: both
 * become an on/off activity signal, and the candidate is moved (offset) and stretched (frame-rate conversions such as
 * 23.976 to 25 fps) until the signals overlap best. The coarse search runs over FFT cross-correlation, then the
 * transform is refined from matched cue starts (Theil-Sen, robust to outliers).
 *
 * <p>The goal is the lowest possible error rate, so a result is applied only when it is clearly better than leaving
 * the file alone: the match must be strong, the peak must stand out from the next one, and it must beat the
 * unchanged file by a margin. Anything doubtful returns the file untouched.
 */
final class SubtitleSync {

    private SubtitleSync() {}

    static final class Cue {
        long start;
        long end;
        String text;

        Cue(long start, long end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    static final class Result {
        boolean applied;
        String note = "";
        double offsetSec;
        double ratio = 1;
        double scoreBefore;
        double scoreAfter;
        List<Cue> cues;
    }

    // ---------------------------------------------------------------- SRT

    private static final Pattern TIME = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})");

    private static long ms(String h, String m, String s, String frac) {
        String f = (frac + "00").substring(0, 3);
        return ((Long.parseLong(h) * 60 + Long.parseLong(m)) * 60 + Long.parseLong(s)) * 1000 + Long.parseLong(f);
    }

    static List<Cue> parse(String srt) {
        List<Cue> out = new ArrayList<>();
        if (srt == null) return out;
        String t = srt.replace("\r\n", "\n").replace('\r', '\n');
        if (t.startsWith("﻿")) t = t.substring(1);
        String[] lines = t.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            Matcher m = TIME.matcher(lines[i]);
            if (!m.find()) { i++; continue; }
            long st = ms(m.group(1), m.group(2), m.group(3), m.group(4));
            long en = ms(m.group(5), m.group(6), m.group(7), m.group(8));
            StringBuilder text = new StringBuilder();
            i++;
            while (i < lines.length && !lines[i].trim().isEmpty() && !TIME.matcher(lines[i]).find()) {
                if (text.length() > 0) text.append('\n');
                text.append(lines[i]);
                i++;
            }
            // a trailing cue-number line belongs to the next block
            int nl = text.lastIndexOf("\n");
            String last = nl >= 0 ? text.substring(nl + 1) : text.toString();
            if (i < lines.length && last.trim().matches("\\d{1,6}") && i + 0 < lines.length && TIME.matcher(lines[i]).find()) {
                text.setLength(Math.max(0, nl));
            }
            if (en < st) en = st;
            out.add(new Cue(st, en, text.toString()));
        }
        return out;
    }

    static String write(List<Cue> cues) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (Cue c : cues) {
            sb.append(n++).append('\n').append(fmt(c.start)).append(" --> ").append(fmt(c.end)).append('\n')
                    .append(c.text).append("\n\n");
        }
        return sb.toString();
    }

    private static String fmt(long ms) {
        if (ms < 0) ms = 0;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
    }

    // ---------------------------------------------------------------- FFT

    private static int nextPow2(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    private static void fft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? -1 : 1);
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
        if (inverse) for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
    }

    // ---------------------------------------------------------------- activity signals

    private static final double MAX_CUE_SEC = 8.0, MIN_CUE_SEC = 0.4;

    /** 1 where any cue is on screen. Cue times are mapped t' = t * ratio + offsetSec. */
    private static double[] signal(List<Cue> cues, double ratio, double offsetSec, double binSec, int n) {
        double[] s = new double[n];
        for (Cue c : cues) {
            double st = c.start / 1000.0 * ratio + offsetSec;
            double en = c.end / 1000.0 * ratio + offsetSec;
            if (en - st > MAX_CUE_SEC) en = st + MAX_CUE_SEC;
            if (en - st < MIN_CUE_SEC) en = st + MIN_CUE_SEC;
            int a = (int) Math.floor(st / binSec), b = (int) Math.ceil(en / binSec);
            for (int i = Math.max(0, a); i < Math.min(n, b); i++) s[i] = 1;
        }
        return s;
    }

    private static double sum(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return s;
    }

    private static double span(List<Cue> cues) {
        long m = 0;
        for (Cue c : cues) m = Math.max(m, c.end);
        return m / 1000.0;
    }

    /** Overlap of two activity signals, 0..1 (Ochiai coefficient). */
    static double score(List<Cue> ref, List<Cue> cand, double ratio, double offsetSec) {
        double bin = 0.1;
        int n = (int) Math.ceil(Math.max(span(ref), span(cand) * Math.max(1, ratio) + Math.abs(offsetSec)) / bin) + 10;
        double[] r = signal(ref, 1, 0, bin, n), c = signal(cand, ratio, offsetSec, bin, n);
        double sr = sum(r), sc = sum(c);
        if (sr == 0 || sc == 0) return 0;
        double dot = 0;
        for (int i = 0; i < n; i++) dot += r[i] * c[i];
        return dot / Math.sqrt(sr * sc);
    }

    // frame-rate pairs that real releases are converted between
    private static final double[] FPS = {23.976, 24.0, 25.0, 29.97, 30.0};

    private static double[] ratios() {
        List<Double> out = new ArrayList<>();
        out.add(1.0);
        for (double a : FPS) {
            for (double b : FPS) {
                if (a == b) continue;
                double r = Math.round(a / b * 10000.0) / 10000.0;
                boolean dup = false;
                for (double o : out) if (Math.abs(o - r) < 1e-4) dup = true;
                if (!dup) out.add(r);
            }
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    // ---------------------------------------------------------------- alignment

    private static final double MAX_LAG_SEC = 180;
    private static final double COARSE_BIN = 0.2;
    static final int MIN_CUES = 80;

    /** Aligns {@code cand} to {@code ref}. The result's cues are the shifted ones only when {@code applied}. */
    static Result align(List<Cue> ref, List<Cue> cand) {
        Result res = new Result();
        res.cues = cand;
        if (ref.size() < MIN_CUES || cand.size() < MIN_CUES) {
            res.note = "too few cues to compare";
            return res;
        }
        double refSpan = span(ref), candSpan = span(cand);
        if (candSpan < refSpan * 0.55 || candSpan > refSpan * 1.8) {
            res.note = "covers a different part of the film";
            return res;
        }

        // ---- coarse: FFT cross-correlation over offsets, for each frame-rate ratio
        int n = (int) Math.ceil((Math.max(refSpan, candSpan * 1.3) + MAX_LAG_SEC) / COARSE_BIN) + 4;
        int size = nextPow2(2 * n);
        double[] rs = signal(ref, 1, 0, COARSE_BIN, n);
        double sr = sum(rs);
        double[] rRe = new double[size], rIm = new double[size];
        System.arraycopy(rs, 0, rRe, 0, n);
        fft(rRe, rIm, false);
        int maxLag = (int) (MAX_LAG_SEC / COARSE_BIN);

        double bestScore = -1, bestRatio = 1;
        int bestLag = 0;
        double[] bestCurve = null;
        for (double ratio : ratios()) {
            double[] cs = signal(cand, ratio, 0, COARSE_BIN, n);
            double sc = sum(cs);
            if (sc == 0) continue;
            double[] cRe = new double[size], cIm = new double[size];
            System.arraycopy(cs, 0, cRe, 0, n);
            fft(cRe, cIm, false);
            for (int i = 0; i < size; i++) { // R * conj(C)
                double a = rRe[i], b = rIm[i], c = cRe[i], d = -cIm[i];
                cRe[i] = a * c - b * d;
                cIm[i] = a * d + b * c;
            }
            fft(cRe, cIm, true);
            double norm = Math.sqrt(sr * sc);
            double[] curve = new double[2 * maxLag + 1];
            for (int k = -maxLag; k <= maxLag; k++) {
                int idx = k >= 0 ? k : size + k;
                curve[k + maxLag] = cRe[idx] / norm;
                if (curve[k + maxLag] > bestScore) {
                    bestScore = curve[k + maxLag];
                    bestRatio = ratio;
                    bestLag = k;
                    bestCurve = curve;
                }
            }
        }
        if (bestCurve == null) { res.note = "no usable timing"; return res; }

        // how much the best peak stands out from the best peak elsewhere (at least 6 s away)
        double next = 0;
        int guard = (int) (6 / COARSE_BIN);
        for (int k = 0; k < bestCurve.length; k++) {
            if (Math.abs(k - maxLag - bestLag) > guard) next = Math.max(next, bestCurve[k]);
        }
        double prominence = bestScore - next;

        // ---- refine from matched cue starts: t' = a * t + b
        double a = bestRatio, b = bestLag * COARSE_BIN;
        double[] refStarts = starts(ref);
        double[] candStarts = starts(cand);
        double[] windows = {2.0, 1.2, 0.8};
        for (double win : windows) {
            double[][] pairs = matchStarts(refStarts, candStarts, a, b, win);
            if (pairs[0].length < 30) break;
            double[] fit = theilSen(pairs[0], pairs[1]);
            if (fit == null) break;
            if (Math.abs(fit[0] - bestRatio) > 0.01 * bestRatio) break; // a frame-rate step, not an arbitrary stretch
            a = fit[0];
            b = fit[1];
        }

        res.scoreBefore = score(ref, cand, 1, 0);
        res.scoreAfter = score(ref, cand, a, b);
        res.offsetSec = b;
        res.ratio = a;
        String desc = String.format(Locale.US, "offset %+.2fs, speed x%.4f, match %.2f (unchanged %.2f), peak margin %.2f",
                b, a, res.scoreAfter, res.scoreBefore, prominence);

        if (res.scoreAfter < 0.45) { res.note = "no reliable match: " + desc; return res; }
        // two equally good offsets mean a cut somewhere (or a repetitive film): only a per-part fix may use them
        boolean ambiguous = prominence < 0.04;

        // ---- is the offset the same across the whole film? (a different cut shifts only part of it)
        List<Cue> moved = transform(cand, a, b, 0, Long.MAX_VALUE, 0);
        double[] seg = segmentOffsets(ref, moved);   // per ~10 min: best extra shift in seconds, NaN when unusable
        List<Double> clusters = new ArrayList<>();
        int[] clusterOf = new int[seg.length];
        int valid = 0;
        for (int i = 0; i < seg.length; i++) {
            clusterOf[i] = -1;
            if (Double.isNaN(seg[i])) continue;
            valid++;
            for (int c = 0; c < clusters.size() && clusterOf[i] < 0; c++) if (Math.abs(clusters.get(c) - seg[i]) <= 1.5) clusterOf[i] = c;
            if (clusterOf[i] < 0) { clusters.add(seg[i]); clusterOf[i] = clusters.size() - 1; }
        }
        double residual = 0; // extra shift to add when the whole film is consistently a little off
        if (valid >= 3 && clusters.size() >= 2) {
            // one cut point only: A...A B...B
            int changes = 0, last = -1, firstB = -1, lastA = -1;
            for (int i = 0; i < seg.length; i++) {
                if (clusterOf[i] < 0) continue;
                if (last >= 0 && clusterOf[i] != last) { changes++; if (changes == 1) { firstB = i; } }
                if (changes == 0) lastA = i;
                last = clusterOf[i];
            }
            if (clusters.size() == 2 && changes == 1 && firstB > lastA) {
                double dA = median(seg, clusterOf, clusterOf[lastA]), dB = median(seg, clusterOf, clusterOf[firstB]);
                double from = SEG_SEC * lastA, to = SEG_SEC * (firstB + 1);
                double cutAt = bestCut(ref, moved, dA, dB, from, to);
                List<Cue> parts = transform(cand, a, b, 0, Long.MAX_VALUE, 0);
                List<Cue> fixed = new ArrayList<>();
                for (Cue c : parts) {
                    double shift = (c.start / 1000.0 < cutAt ? dA : dB) * 1000.0;
                    long st = Math.round(c.start + shift), en = Math.round(c.end + shift);
                    if (en > 0) fixed.add(new Cue(Math.max(0, st), Math.max(Math.max(0, st), en), c.text));
                }
                double[] seg2 = segmentOffsets(ref, fixed);
                boolean ok = true;
                for (double d : seg2) if (!Double.isNaN(d) && Math.abs(d) > 1.2) ok = false;
                double after = score(ref, fixed, 1, 0);
                if (ok && after >= 0.45 && after - res.scoreBefore >= 0.05) {
                    res.cues = fixed; res.applied = true; res.scoreAfter = after;
                    res.note = String.format(Locale.US, "applied in two parts (a cut at %.0fs: %+.2fs before, %+.2fs after): %s", cutAt, b + dA, b + dB, desc);
                    return res;
                }
            }
            res.note = "timing differs across the film (a different cut), left unchanged: " + desc;
            return res;
        }
        if (ambiguous) { res.note = "ambiguous: " + desc; return res; }
        if (clusters.size() == 1) residual = clusters.get(0);
        if (Math.abs(residual) > 0.3) b += residual;   // a consistent small leftover: fold it in

        double drift = Math.abs((a - 1) * refSpan);
        if (Math.abs(b) < 0.3 && drift < 0.5) {
            res.note = "already in sync: " + desc;
            return res;
        }
        res.offsetSec = b;
        res.scoreAfter = score(ref, cand, a, b);
        if (res.scoreAfter - res.scoreBefore < 0.05) { res.note = "no measurable gain: " + desc; return res; }

        res.cues = transform(cand, a, b, 0, Long.MAX_VALUE, 0);
        res.applied = true;
        res.note = "applied: " + desc;
        return res;
    }

    private static final double SEG_SEC = 600;

    private static List<Cue> transform(List<Cue> cues, double a, double b, long from, long to, double extra) {
        List<Cue> out = new ArrayList<>(cues.size());
        for (Cue c : cues) {
            long st = Math.round(c.start * a + (b + extra) * 1000.0), en = Math.round(c.end * a + (b + extra) * 1000.0);
            if (en <= 0) continue;
            out.add(new Cue(Math.max(0, st), Math.max(Math.max(0, st), en), c.text));
        }
        return out;
    }

    private static double median(double[] v, int[] cluster, int id) {
        List<Double> l = new ArrayList<>();
        for (int i = 0; i < v.length; i++) if (cluster[i] == id) l.add(v[i]);
        l.sort(null);
        return l.get(l.size() / 2);
    }

    /** Overlap of the reference and a candidate, counted only inside [fromSec, toSec]. */
    private static double scoreRange(List<Cue> ref, List<Cue> cand, double fromSec, double toSec) {
        double bin = 0.1;
        int n = (int) Math.ceil(toSec / bin) + 2, i0 = (int) (fromSec / bin);
        double[] r = signal(ref, 1, 0, bin, n), c = signal(cand, 1, 0, bin, n);
        double sr = 0, sc = 0, dot = 0;
        for (int i = i0; i < n; i++) { sr += r[i]; sc += c[i]; dot += r[i] * c[i]; }
        return sr == 0 || sc == 0 ? 0 : dot / Math.sqrt(sr * sc);
    }

    /**
     * Where the cut is: every pause in the candidate between the two stretches is tried as the place to switch from
     * the first shift to the second, and the one that overlaps the reference best wins.
     */
    private static double bestCut(List<Cue> ref, List<Cue> moved, double dA, double dB, double fromSec, double toSec) {
        List<Double> gaps = new ArrayList<>();
        double prevEnd = -1;
        for (Cue c : moved) {
            double st = c.start / 1000.0;
            if (prevEnd >= 0 && st - prevEnd >= 0.4 && st >= fromSec && prevEnd <= toSec) gaps.add((prevEnd + st) / 2);
            prevEnd = Math.max(prevEnd, c.end / 1000.0);
        }
        double best = -1, at = (fromSec + toSec) / 2;
        for (double g : gaps) {
            List<Cue> trial = new ArrayList<>(moved.size());
            for (Cue c : moved) {
                double sh = (c.start / 1000.0 < g ? dA : dB) * 1000.0;
                trial.add(new Cue(Math.round(c.start + sh), Math.round(c.end + sh), c.text));
            }
            double v = scoreRange(ref, trial, fromSec, toSec);
            if (v > best) { best = v; at = g; }
        }
        return at;
    }

    /**
     * For each ~10 minute stretch of the film: the extra shift (seconds) that makes the candidate overlap the
     * reference best within +-2 minutes, or NaN when the stretch has too little speech or no clear peak.
     */
    static double[] segmentOffsets(List<Cue> ref, List<Cue> cand) {
        double bin = 0.2;
        double total = Math.max(span(ref), span(cand));
        int n = (int) Math.ceil(total / bin) + 4;
        double[] r = signal(ref, 1, 0, bin, n), c = signal(cand, 1, 0, bin, n);
        double[] pc = new double[n + 1];
        for (int i = 0; i < n; i++) pc[i + 1] = pc[i] + c[i];
        int segBins = (int) (SEG_SEC / bin), maxLag = (int) (120 / bin);
        int count = Math.max(1, (int) Math.round(span(ref) / SEG_SEC));
        double[] out = new double[count];
        for (int s = 0; s < count; s++) {
            int i0 = s * segBins, i1 = Math.min(n, (s + 1) * segBins);
            if (s == count - 1) i1 = Math.min(n, (int) Math.ceil(span(ref) / bin));
            double sr = 0;
            for (int i = i0; i < i1; i++) sr += r[i];
            if (sr * bin < 30) { out[s] = Double.NaN; continue; }
            double best = -1, second = 0;
            int bestLag = 0;
            double[] scores = new double[2 * maxLag + 1];
            for (int lag = -maxLag; lag <= maxLag; lag++) {
                double dot = 0;
                int lo = Math.max(i0, lag), hi = Math.min(i1, n + lag);
                for (int i = lo; i < hi; i++) dot += r[i] * c[i - lag];
                int cl = Math.max(0, i0 - lag), ch = Math.min(n, i1 - lag);
                double sc = ch > cl ? pc[ch] - pc[cl] : 0;
                double v = sc > 0 ? dot / Math.sqrt(sr * sc) : 0;
                scores[lag + maxLag] = v;
                if (v > best) { best = v; bestLag = lag; }
            }
            int guard = (int) (6 / bin);
            for (int k = 0; k < scores.length; k++) if (Math.abs(k - maxLag - bestLag) > guard) second = Math.max(second, scores[k]);
            out[s] = best >= 0.40 && best - second >= 0.04 ? bestLag * bin : Double.NaN;
        }
        return out;
    }

    private static double[] starts(List<Cue> cues) {
        double[] s = new double[cues.size()];
        for (int i = 0; i < s.length; i++) s[i] = cues.get(i).start / 1000.0;
        Arrays.sort(s);
        return s;
    }

    /** Pairs (candidate start, reference start) that are each other's nearest within the window. */
    private static double[][] matchStarts(double[] ref, double[] cand, double a, double b, double win) {
        List<double[]> pairs = new ArrayList<>();
        for (int i = 0; i < cand.length; i++) {
            double y = a * cand[i] + b;
            int j = nearest(ref, y);
            if (j < 0 || Math.abs(ref[j] - y) > win) continue;
            // mutual: the candidate start nearest to that reference start (after the same transform) is this one
            double bestD = Double.MAX_VALUE;
            int bestI = -1;
            for (int k = Math.max(0, i - 6); k < Math.min(cand.length, i + 7); k++) {
                double d = Math.abs(a * cand[k] + b - ref[j]);
                if (d < bestD) { bestD = d; bestI = k; }
            }
            if (bestI == i) pairs.add(new double[]{cand[i], ref[j]});
        }
        double[] x = new double[pairs.size()], y = new double[pairs.size()];
        for (int i = 0; i < x.length; i++) { x[i] = pairs.get(i)[0]; y[i] = pairs.get(i)[1]; }
        return new double[][]{x, y};
    }

    private static int nearest(double[] sorted, double v) {
        int lo = 0, hi = sorted.length - 1;
        if (hi < 0) return -1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < v) lo = mid + 1; else hi = mid;
        }
        if (lo > 0 && Math.abs(sorted[lo - 1] - v) <= Math.abs(sorted[lo] - v)) return lo - 1;
        return lo;
    }

    /** Median-of-slopes line fit: returns {slope, intercept}, or null with too little spread. */
    private static double[] theilSen(double[] x, double[] y) {
        int n = x.length;
        if (n < 2) return null;
        Random rnd = new Random(7);
        double[] slopes = new double[4000];
        int m = 0;
        for (int t = 0; t < 40000 && m < slopes.length; t++) {
            int i = rnd.nextInt(n), j = rnd.nextInt(n);
            if (i == j || Math.abs(x[j] - x[i]) < 120) continue;
            slopes[m++] = (y[j] - y[i]) / (x[j] - x[i]);
        }
        if (m < 50) return null;
        double[] s = Arrays.copyOf(slopes, m);
        Arrays.sort(s);
        double slope = s[m / 2];
        double[] icpt = new double[n];
        for (int i = 0; i < n; i++) icpt[i] = y[i] - slope * x[i];
        Arrays.sort(icpt);
        return new double[]{slope, icpt[n / 2]};
    }
}
