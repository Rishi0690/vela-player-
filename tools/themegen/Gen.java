import com.google.android.material.color.utilities.Scheme;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * v2.11 theme generator. Writes the Material 3 + glass overlay styles for every theme in the gallery:
 *   app/src/main/res/values/accents.xml         (light mode)
 *   app/src/main/res/values-night/accents.xml   (dark mode)
 *   app/src/main/res/values/accents_player.xml  (player, always dark)
 *   app/src/main/java/com/hark/velaplayer/ThemeCatalog.kt  (the list the app reads)
 * Per theme three overlays: Accent.X (everything), Glass.X (background, surfaces, text, glass attrs only),
 * Second.X (secondary + tertiary accent roles only). Player variants are AccentDark/GlassDark/SecondDark.
 *
 * Run (from the repo root, after one gradle build so material-1.11.0 is in the gradle cache):
 *   JAR=$(find ~/.gradle/caches -name 'material-1.11.0-runtime.jar' | head -1)
 *   javac -cp $JAR -d /tmp/tg tools/themegen/Gen.java && java -cp $JAR:/tmp/tg Gen .
 */
public class Gen {
    static class T {
        String key, label, section, style; int seed, sec, ter, tint; float strength; boolean mono; float lightStrength;
        T(String key, String label, String section, int seed, int sec, int ter, int tint, float strength) {
            this.key = key; this.label = label; this.section = section; this.seed = seed; this.sec = sec; this.ter = ter;
            this.tint = tint == 0 ? seed : tint; this.strength = strength; this.lightStrength = Math.min(strength, 1f);
            StringBuilder sb = new StringBuilder();
            for (String p : key.split("_")) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            this.style = sb.toString();
        }
    }

    static final List<T> themes = new ArrayList<>();
    static void t(String key, String label, String section, int seed, int sec, int ter, int tint, float s) { themes.add(new T(key, label, section, seed, sec, ter, tint, s)); }

    public static void main(String[] args) throws Exception {
        // Keys violet/ocean/teal/emerald/sunset/crimson/rose/amber/slate and their seeds are the pre-2.11 presets:
        // keep them so a saved selection still resolves.
        t("pure_black", "Pure Black Glass", "Glass Dark", 0xFF8AB4FF, 0, 0, 0xFF000000, 0f);
        themes.get(0).mono = true;
        t("midnight", "Midnight Blue", "Glass Dark", 0xFF4C6FFF, 0, 0, 0, 1.4f);
        t("nebula", "Purple Nebula", "Glass Dark", 0xFFA855F7, 0xFFEC4899, 0, 0, 1.3f);
        t("crimson", "Crimson Noir", "Glass Dark", 0xFFE53950, 0, 0, 0, 0.8f);
        t("slate", "Arctic Silver", "Glass Dark", 0xFF6F7FA0, 0, 0, 0, 0.9f);
        t("aurora", "Aurora", "Glass Dark", 0xFF3B82F6, 0xFF14B8A6, 0xFFA855F7, 0xFF2A6F8F, 1.2f);

        t("violet", "Vela Violet", "Vivid", 0xFF8B6CFF, 0, 0, 0, 1f);
        t("ocean", "Sapphire Blue", "Vivid", 0xFF2F7DF6, 0, 0, 0, 1f);
        t("ruby", "Ruby Red", "Vivid", 0xFFD0103A, 0, 0, 0, 0.9f);
        t("indigo", "Indigo", "Vivid", 0xFF5B5BD6, 0, 0, 0, 1f);
        t("cyan", "Cyan Glass", "Vivid", 0xFF06B6D4, 0, 0, 0, 1f);
        t("emerald", "Emerald Glass", "Vivid", 0xFF2EAD5B, 0, 0, 0, 1f);

        t("sunset", "Sunset Amber", "Warm", 0xFFFF7A2F, 0xFFF2B705, 0, 0, 0.9f);
        t("amber", "Gold Leaf", "Warm", 0xFFF2B705, 0, 0, 0, 0.8f);
        t("rose_gold", "Rose Gold", "Warm", 0xFFD4917F, 0xFFE8B07A, 0, 0, 0.9f);
        t("coral", "Coral", "Warm", 0xFFFF6F61, 0, 0, 0, 0.9f);
        t("copper", "Copper", "Warm", 0xFFC2703D, 0, 0, 0, 0.9f);
        t("rose", "Sakura Pink", "Warm", 0xFFF0609E, 0, 0, 0, 1f);

        t("teal", "Ocean Teal", "Cool", 0xFF00A896, 0xFF2F7DF6, 0, 0, 1.1f);
        t("mint", "Mint", "Cool", 0xFF3DDC97, 0, 0, 0, 0.9f);
        t("lavender", "Lavender", "Cool", 0xFFB39DDB, 0, 0, 0, 1f);
        t("glacier", "Glacier", "Cool", 0xFF8FB8DE, 0, 0, 0, 1f);
        t("lime", "Lime", "Cool", 0xFF8BC34A, 0, 0, 0, 0.8f);

        t("pearl", "Champagne", "Light-friendly", 0xFFA79B8E, 0, 0, 0, 0.6f);
        t("blush", "Blush", "Light-friendly", 0xFFE8A0BF, 0, 0, 0, 0.8f);
        t("sky", "Sky", "Light-friendly", 0xFF5AA9E6, 0, 0, 0, 0.9f);

        String root = args.length > 0 ? args[0] : ".";
        write(root + "/app/src/main/res/values/accents.xml", xml(false, ""));
        write(root + "/app/src/main/res/values-night/accents.xml", xml(true, ""));
        write(root + "/app/src/main/res/values/accents_player.xml", xml(true, "Dark"));
        write(root + "/app/src/main/java/com/hark/velaplayer/ThemeCatalog.kt", kotlin());
        System.out.println("themes: " + themes.size());
    }

    // ---- colour helpers ----
    static int mix(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
    static int alpha(int c, int a) { return (a << 24) | (c & 0xFFFFFF); }
    static String hex(int c) { return String.format("#%08X", c); }

    static void item(StringBuilder sb, String name, int c) { sb.append("        <item name=\"").append(name).append("\">").append(hex(c)).append("</item>\n"); }

    static void primaryRoles(StringBuilder sb, T t, boolean dark, Scheme s) {
        if (t.mono) {
            // Monochrome showcase: silver controls on black, with a soft blue third accent.
            if (dark) {
                item(sb, "colorPrimary", 0xFFE8EAF0); item(sb, "colorOnPrimary", 0xFF111214);
                item(sb, "colorPrimaryContainer", 0xFF2B2D33); item(sb, "colorOnPrimaryContainer", 0xFFE8EAF0);
                item(sb, "colorPrimaryInverse", 0xFF2B2D33);
            } else {
                item(sb, "colorPrimary", 0xFF1C1D21); item(sb, "colorOnPrimary", 0xFFFFFFFF);
                item(sb, "colorPrimaryContainer", 0xFFE2E4EA); item(sb, "colorOnPrimaryContainer", 0xFF1C1D21);
                item(sb, "colorPrimaryInverse", 0xFFE8EAF0);
            }
        } else {
            item(sb, "colorPrimary", s.getPrimary()); item(sb, "colorOnPrimary", s.getOnPrimary());
            item(sb, "colorPrimaryContainer", s.getPrimaryContainer()); item(sb, "colorOnPrimaryContainer", s.getOnPrimaryContainer());
            item(sb, "colorPrimaryInverse", s.getInversePrimary());
        }
    }

    static void secondRoles(StringBuilder sb, T t, boolean dark, Scheme s) {
        if (t.mono) {
            if (dark) {
                item(sb, "colorSecondary", 0xFFC6C9D2); item(sb, "colorOnSecondary", 0xFF2A2C31);
                item(sb, "colorSecondaryContainer", 0xFF26282D); item(sb, "colorOnSecondaryContainer", 0xFFE2E4EA);
            } else {
                item(sb, "colorSecondary", 0xFF50535B); item(sb, "colorOnSecondary", 0xFFFFFFFF);
                item(sb, "colorSecondaryContainer", 0xFFE4E6EC); item(sb, "colorOnSecondaryContainer", 0xFF1C1D21);
            }
            Scheme b = dark ? Scheme.dark(t.seed) : Scheme.light(t.seed);
            item(sb, "colorTertiary", b.getPrimary()); item(sb, "colorOnTertiary", b.getOnPrimary());
            item(sb, "colorTertiaryContainer", b.getPrimaryContainer()); item(sb, "colorOnTertiaryContainer", b.getOnPrimaryContainer());
            return;
        }
        if (t.sec != 0) {
            Scheme b = dark ? Scheme.dark(t.sec) : Scheme.light(t.sec);
            item(sb, "colorSecondary", b.getPrimary()); item(sb, "colorOnSecondary", b.getOnPrimary());
            item(sb, "colorSecondaryContainer", b.getPrimaryContainer()); item(sb, "colorOnSecondaryContainer", b.getOnPrimaryContainer());
        } else {
            item(sb, "colorSecondary", s.getSecondary()); item(sb, "colorOnSecondary", s.getOnSecondary());
            item(sb, "colorSecondaryContainer", s.getSecondaryContainer()); item(sb, "colorOnSecondaryContainer", s.getOnSecondaryContainer());
        }
        if (t.ter != 0) {
            Scheme b = dark ? Scheme.dark(t.ter) : Scheme.light(t.ter);
            item(sb, "colorTertiary", b.getPrimary()); item(sb, "colorOnTertiary", b.getOnPrimary());
            item(sb, "colorTertiaryContainer", b.getPrimaryContainer()); item(sb, "colorOnTertiaryContainer", b.getOnPrimaryContainer());
        } else {
            item(sb, "colorTertiary", s.getTertiary()); item(sb, "colorOnTertiary", s.getOnTertiary());
            item(sb, "colorTertiaryContainer", s.getTertiaryContainer()); item(sb, "colorOnTertiaryContainer", s.getOnTertiaryContainer());
        }
    }

    /** Background, surfaces, text and the glass attributes. Deep black/charcoal tinted with the theme hue in dark mode, frosted light in light mode. */
    static void glassRoles(StringBuilder sb, T t, boolean dark) {
        int n = t.tint;
        if (dark) {
            float s = t.strength;
            int base = t.mono ? 0xFF000000 : 0xFF060608;
            int bg = mix(base, n, 0.045f * s);
            item(sb, "android:colorBackground", bg);
            item(sb, "colorSurface", bg);
            item(sb, "colorSurfaceDim", bg);
            item(sb, "colorSurfaceBright", mix(0xFF2C2C32, n, 0.09f * s));
            item(sb, "colorSurfaceContainerLowest", mix(0xFF000000, n, 0.03f * s));
            item(sb, "colorSurfaceContainerLow", mix(0xFF111114, n, 0.055f * s));
            item(sb, "colorSurfaceContainer", mix(0xFF16161A, n, 0.065f * s));
            item(sb, "colorSurfaceContainerHigh", mix(0xFF1D1D22, n, 0.075f * s));
            item(sb, "colorSurfaceContainerHighest", mix(0xFF26262C, n, 0.085f * s));
            item(sb, "colorSurfaceVariant", mix(0xFF2A2A31, n, 0.10f * s));
            item(sb, "colorSurfaceInverse", 0xFFE6E6EA);
            item(sb, "colorOnSurfaceInverse", 0xFF1A1A1E);
            item(sb, "colorOnSurface", mix(0xFFEDEDF1, n, 0.035f * s));
            item(sb, "colorOnBackground", mix(0xFFEDEDF1, n, 0.035f * s));
            item(sb, "colorOnSurfaceVariant", mix(0xFFBDBDC6, n, 0.08f * s));
            item(sb, "colorOutline", mix(0xFF80808A, n, 0.10f * s));
            item(sb, "colorOutlineVariant", mix(0xFF33333A, n, 0.10f * s));
            item(sb, "glassSurface", alpha(mix(0xFFFFFFFF, n, 0.30f * s), 0x14));
            item(sb, "glassSurfaceStrong", alpha(mix(0xFF1A1A1F, n, 0.08f * s), 0xEB));
            item(sb, "glassStroke", alpha(mix(0xFFFFFFFF, n, 0.35f * s), 0x24));
            item(sb, "glassHighlight", alpha(0xFFFFFFFF, 0x1F));
            item(sb, "glassScrim", 0x99000000);
            item(sb, "glassTint", alpha(mix(0xFFFFFFFF, n, 0.6f), 0x1F));
        } else {
            float s = t.lightStrength;
            int bg = mix(0xFFF3F3F6, n, 0.045f * s);
            item(sb, "android:colorBackground", bg);
            item(sb, "colorSurface", bg);
            item(sb, "colorSurfaceDim", mix(0xFFDCDCE2, n, 0.05f * s));
            item(sb, "colorSurfaceBright", 0xFFFFFFFF);
            item(sb, "colorSurfaceContainerLowest", 0xFFFFFFFF);
            item(sb, "colorSurfaceContainerLow", mix(0xFFF9F9FB, n, 0.025f * s));
            item(sb, "colorSurfaceContainer", mix(0xFFEDEDF1, n, 0.04f * s));
            item(sb, "colorSurfaceContainerHigh", mix(0xFFE8E8ED, n, 0.05f * s));
            item(sb, "colorSurfaceContainerHighest", mix(0xFFE2E2E8, n, 0.06f * s));
            item(sb, "colorSurfaceVariant", mix(0xFFE0E0E7, n, 0.08f * s));
            item(sb, "colorSurfaceInverse", 0xFF2B2B30);
            item(sb, "colorOnSurfaceInverse", 0xFFF2F2F5);
            item(sb, "colorOnSurface", mix(0xFF16161A, n, 0.05f * s));
            item(sb, "colorOnBackground", mix(0xFF16161A, n, 0.05f * s));
            item(sb, "colorOnSurfaceVariant", mix(0xFF4A4A54, n, 0.10f * s));
            item(sb, "colorOutline", mix(0xFF7A7A85, n, 0.10f * s));
            item(sb, "colorOutlineVariant", mix(0xFFCACAD3, n, 0.10f * s));
            item(sb, "glassSurface", alpha(0xFFFFFFFF, 0xB8));
            item(sb, "glassSurfaceStrong", alpha(mix(0xFFFBFBFD, n, 0.02f * s), 0xF2));
            item(sb, "glassStroke", alpha(mix(0xFF000000, n, 0.30f), 0x17));
            item(sb, "glassHighlight", alpha(0xFFFFFFFF, 0xCC));
            item(sb, "glassScrim", 0x66000000);
            item(sb, "glassTint", alpha(mix(0xFF000000, n, 0.7f), 0x14));
        }
    }

    static String xml(boolean dark, String suffix) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- GENERATED by tools/themegen/Gen.java (v2.11). Do not edit by hand: change Gen.java and re-run it. -->\n<resources>\n");
        for (T t : themes) {
            Scheme s = dark ? Scheme.dark(t.seed) : Scheme.light(t.seed);
            sb.append("    <style name=\"Accent").append(suffix).append(".").append(t.style).append("\" parent=\"\">\n");
            primaryRoles(sb, t, dark, s);
            secondRoles(sb, t, dark, s);
            glassRoles(sb, t, dark);
            item(sb, "colorAccentSeed", t.seed);
            sb.append("    </style>\n");
            sb.append("    <style name=\"Glass").append(suffix).append(".").append(t.style).append("\" parent=\"\">\n");
            glassRoles(sb, t, dark);
            sb.append("    </style>\n");
            sb.append("    <style name=\"Second").append(suffix).append(".").append(t.style).append("\" parent=\"\">\n");
            secondRoles(sb, t, dark, s);
            sb.append("    </style>\n");
        }
        sb.append("</resources>\n");
        return sb.toString();
    }

    static String kotlin() {
        StringBuilder sb = new StringBuilder();
        sb.append("package com.hark.velaplayer\n\n// GENERATED by tools/themegen/Gen.java (v2.11). Do not edit by hand.\n");
        sb.append("/** Every ready-made theme in the gallery, in display order, with its light/dark and player overlays. */\n");
        sb.append("object ThemeCatalog {\n    val sections = listOf(\"Glass Dark\", \"Vivid\", \"Warm\", \"Cool\", \"Light-friendly\")\n");
        sb.append("    val all = listOf(\n");
        for (T t : themes) {
            sb.append(String.format("        ThemeManager.Preset(\"%s\", \"%s\", \"%s\", 0x%08X.toInt(), R.style.Accent_%s, R.style.AccentDark_%s, R.style.Glass_%s, R.style.GlassDark_%s, R.style.Second_%s, R.style.SecondDark_%s),\n",
                    t.key, t.label, t.section, t.seed, t.style, t.style, t.style, t.style, t.style, t.style));
        }
        sb.append("    )\n}\n");
        return sb.toString();
    }

    static void write(String path, String content) throws Exception {
        File f = new File(path); f.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(f)) { w.write(content); }
    }
}
