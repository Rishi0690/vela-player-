import com.google.android.material.color.utilities.Scheme;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * v2.12 theme generator (v2.11 format + atmosphere/nav/glass levels). Writes the Material 3 + glass overlay styles for every theme in the gallery:
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
        String key, label, section, style; int seed, sec, ter, tint, glowA, glowB; float strength; boolean mono; float lightStrength;
        T(String key, String label, String section, int seed, int sec, int ter, int tint, int glowA, int glowB, float strength) {
            this.key = key; this.label = label; this.section = section; this.seed = seed; this.sec = sec; this.ter = ter;
            this.tint = tint == 0 ? seed : tint; this.strength = strength; this.lightStrength = Math.min(strength, 1f);
            this.glowA = glowA == 0 ? seed : glowA; this.glowB = glowB == 0 ? (sec != 0 ? sec : this.tint) : glowB;
            StringBuilder sb = new StringBuilder();
            for (String p : key.split("_")) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            this.style = sb.toString();
        }
    }

    static final List<T> themes = new ArrayList<>();
    /** key, label, section, accent seed, secondary seed, tertiary seed, background tint, glow A (low), glow B (high), strength. 0 = derive. */
    static T t(String key, String label, String section, int seed, int sec, int ter, int tint, int glowA, int glowB, float s) {
        T x = new T(key, label, section, seed, sec, ter, tint, glowA, glowB, s); themes.add(x); return x;
    }

    public static void main(String[] args) throws Exception {
        // v2.12 "Midnight Glass" catalogue. Every theme has its own atmosphere (window gradient + two soft glows),
        // glass levels, pill navigation colours and accents. v2.11 keys are mapped to these in ThemeManager.LEGACY.
        t("midnight_glass", "Midnight Glass", "Signature", 0xFF8FA8FF, 0xFFB79CFF, 0xFF7DD3FC, 0xFF2A3A8C, 0xFF5B47C9, 0xFF2F64C8, 1.0f);
        t("obsidian_noir", "Obsidian Noir", "Signature", 0xFFD7DBE4, 0, 0xFF8AB4FF, 0xFF1A1C22, 0xFF3A3E4A, 0xFF22252D, 0.6f).mono = true;
        t("aurora_glass", "Aurora Glass", "Signature", 0xFF5EEAD4, 0xFF818CF8, 0xFFC084FC, 0xFF143E5C, 0xFF0F9F8F, 0xFF6D4DE0, 1.0f);
        t("arctic_silver", "Arctic Silver", "Signature", 0xFFC9D6EA, 0xFF9DB4D8, 0xFF8AB4FF, 0xFF2A3446, 0xFF51627F, 0xFF3A4A66, 0.8f).mono = true;
        t("graphite_mist", "Graphite Mist", "Signature", 0xFFB8C0CC, 0xFF94A3B8, 0xFFA5B4FC, 0xFF262A33, 0xFF454C5C, 0xFF333844, 0.7f).mono = true;

        t("sapphire_depths", "Sapphire Depths", "Jewel", 0xFF5B9DFF, 0xFF7DD3FC, 0, 0xFF0F2C6B, 0xFF1E4FD8, 0xFF0E6BA8, 1.1f);
        t("violet_nebula", "Violet Nebula", "Jewel", 0xFFB28CFF, 0xFFF472B6, 0, 0xFF2E1A66, 0xFF7B3FE4, 0xFFB0378F, 1.1f);
        t("emerald_aura", "Emerald Aura", "Jewel", 0xFF34D399, 0xFF6EE7B7, 0, 0xFF0B3B2E, 0xFF0E9F6E, 0xFF1F6F8B, 1.0f);
        t("crimson_eclipse", "Crimson Eclipse", "Jewel", 0xFFFF5A6E, 0xFFFF9F7A, 0, 0xFF3A0D18, 0xFFB8203A, 0xFF5A1A6E, 0.9f);
        t("amethyst_night", "Amethyst Night", "Jewel", 0xFFC59BFF, 0xFF9B8CFF, 0, 0xFF2A1450, 0xFF8B4FD6, 0xFF4B3AA8, 1.0f);
        t("ruby_noir", "Ruby Noir", "Jewel", 0xFFE8335C, 0xFFFF7A9C, 0, 0xFF2A0710, 0xFF9E1236, 0xFF3A0A20, 0.8f);
        t("golden_eclipse", "Golden Eclipse", "Jewel", 0xFFF5C451, 0xFFFFA94D, 0, 0xFF2A1F08, 0xFFB7861A, 0xFF5A3A12, 0.8f);
        t("royal_indigo", "Royal Indigo", "Jewel", 0xFF8B8CFF, 0xFF60A5FA, 0, 0xFF1C1A5E, 0xFF4F46E5, 0xFF2563EB, 1.0f);

        t("sunset_ember", "Sunset Ember", "Warm", 0xFFFF8A4C, 0xFFFFC857, 0xFFFF5D8F, 0xFF3A160C, 0xFFE0552B, 0xFF8E2A5A, 0.9f);
        t("rose_gold_luxe", "Rose Gold Luxe", "Warm", 0xFFE8A598, 0xFFF3C9A0, 0, 0xFF331C1C, 0xFFB76E64, 0xFF8A5A44, 0.9f);
        t("copper_dusk", "Copper Dusk", "Warm", 0xFFE08A52, 0xFFC9A27A, 0, 0xFF2E1A10, 0xFFA8552A, 0xFF5E3A28, 0.9f);
        t("amber_glow", "Amber Glow", "Warm", 0xFFFFB547, 0xFFFFD27A, 0, 0xFF2E2008, 0xFFD48A12, 0xFF6E4A10, 0.8f);
        t("coral_reef", "Coral Reef", "Warm", 0xFFFF7F6E, 0xFF5EEAD4, 0, 0xFF331414, 0xFFE0554A, 0xFF16736E, 0.9f);
        t("mocha_velvet", "Mocha Velvet", "Warm", 0xFFD2A57E, 0xFFE7C8A0, 0, 0xFF241A14, 0xFF7A5238, 0xFF4A3A30, 0.8f);
        t("sakura_bloom", "Sakura Bloom", "Warm", 0xFFFF8FC0, 0xFFFFB3C7, 0, 0xFF331426, 0xFFD0508F, 0xFF7A3A8A, 0.9f);

        t("ocean_teal", "Ocean Teal", "Cool", 0xFF2DD4BF, 0xFF60A5FA, 0, 0xFF0A3340, 0xFF0D9488, 0xFF1D4ED8, 1.0f);
        t("glacier_blue", "Glacier Blue", "Cool", 0xFF9CC9F5, 0xFFB8E1FF, 0, 0xFF16304A, 0xFF3E7CB8, 0xFF2A5A8A, 0.9f);
        t("mint_frost", "Mint Frost", "Cool", 0xFF6EE7B7, 0xFF99F6E4, 0, 0xFF0E3530, 0xFF22A57A, 0xFF1E6E7A, 0.9f);
        t("deep_forest", "Deep Forest", "Cool", 0xFF86C98A, 0xFFC5E1A5, 0, 0xFF0F2A1A, 0xFF2E7D4F, 0xFF3A5A2A, 0.9f);
        t("nordic_fjord", "Nordic Fjord", "Cool", 0xFF88C0D0, 0xFF81A1C1, 0xFFB48EAD, 0xFF1F2A3A, 0xFF4C6A8A, 0xFF5E4A6E, 0.9f);
        t("lavender_haze", "Lavender Haze", "Cool", 0xFFC4B5FD, 0xFFF0ABFC, 0, 0xFF26203F, 0xFF7C6BC4, 0xFF9A5AB0, 0.9f);
        t("steel_blue", "Steel Blue", "Cool", 0xFF7FA7D6, 0xFFA7C4E6, 0, 0xFF18233A, 0xFF3D5F94, 0xFF2C4468, 0.9f);

        t("cyber_cyan", "Cyber Cyan", "Neon", 0xFF22E3F5, 0xFFA78BFA, 0, 0xFF062E3A, 0xFF0891B2, 0xFF5B21B6, 1.1f);
        t("neon_tokyo", "Neon Tokyo", "Neon", 0xFFFF4FD8, 0xFF22D3EE, 0, 0xFF2A0A3A, 0xFFC026D3, 0xFF0E7490, 1.1f);
        t("synthwave", "Synthwave", "Neon", 0xFFFF6AD5, 0xFFFFB86B, 0xFF8B5CF6, 0xFF2A0F4A, 0xFF9D2BD9, 0xFFE2456E, 1.1f);
        t("toxic_lime", "Toxic Lime", "Neon", 0xFFB8F35A, 0xFF5EEAD4, 0, 0xFF1A2A0A, 0xFF5E9E14, 0xFF146E5A, 0.9f);
        t("electric_blue", "Electric Blue", "Neon", 0xFF4D8BFF, 0xFF22D3EE, 0, 0xFF0A1A4A, 0xFF1D4ED8, 0xFF0891B2, 1.2f);

        t("pearl_champagne", "Pearl Champagne", "Soft & Light", 0xFFC8B79E, 0xFFE2D3BC, 0, 0xFF2A2620, 0xFF8A7A62, 0xFF5A5246, 0.6f);
        t("blush_quartz", "Blush Quartz", "Soft & Light", 0xFFF2A7C3, 0xFFF9C9D8, 0, 0xFF2E1E26, 0xFFB86A8A, 0xFF7A5A7A, 0.8f);
        t("sky_porcelain", "Sky Porcelain", "Soft & Light", 0xFF7DB8F0, 0xFFA5D8FF, 0, 0xFF1A2A3E, 0xFF4A86C4, 0xFF5A7AAE, 0.8f);

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
            // Monochrome: silver/graphite controls, the tertiary seed adds a single soft colour accent.
            if (dark) {
                item(sb, "colorPrimary", t.seed); item(sb, "colorOnPrimary", mix(0xFF0B0C10, t.tint, 0.3f));
                item(sb, "colorPrimaryContainer", mix(0xFF2A2D35, t.tint, 0.3f)); item(sb, "colorOnPrimaryContainer", mix(0xFFF0F2F7, t.seed, 0.2f));
                item(sb, "colorPrimaryInverse", mix(0xFF2A2D35, t.tint, 0.3f));
            } else {
                item(sb, "colorPrimary", mix(0xFF1B1D24, t.tint, 0.25f)); item(sb, "colorOnPrimary", 0xFFFFFFFF);
                item(sb, "colorPrimaryContainer", mix(0xFFE2E5EC, t.seed, 0.2f)); item(sb, "colorOnPrimaryContainer", 0xFF1B1D24);
                item(sb, "colorPrimaryInverse", t.seed);
            }
        } else {
            item(sb, "colorPrimary", s.getPrimary()); item(sb, "colorOnPrimary", s.getOnPrimary());
            item(sb, "colorPrimaryContainer", s.getPrimaryContainer()); item(sb, "colorOnPrimaryContainer", s.getOnPrimaryContainer());
            item(sb, "colorPrimaryInverse", s.getInversePrimary());
        }
    }

    static void secondRoles(StringBuilder sb, T t, boolean dark, Scheme s) {
        if (t.mono && t.sec == 0) {
            if (dark) {
                item(sb, "colorSecondary", 0xFFC6C9D2); item(sb, "colorOnSecondary", 0xFF2A2C31);
                item(sb, "colorSecondaryContainer", 0xFF26282D); item(sb, "colorOnSecondaryContainer", 0xFFE2E4EA);
            } else {
                item(sb, "colorSecondary", 0xFF50535B); item(sb, "colorOnSecondary", 0xFFFFFFFF);
                item(sb, "colorSecondaryContainer", 0xFFE4E6EC); item(sb, "colorOnSecondaryContainer", 0xFF1C1D21);
            }
            Scheme b = dark ? Scheme.dark(t.ter != 0 ? t.ter : t.seed) : Scheme.light(t.ter != 0 ? t.ter : t.seed);
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

    /**
     * Background, surfaces, text, glass levels, atmosphere and pill navigation. Dark: deep midnight/near-black
     * tinted with the theme hue, darker at the top, soft tinted glows lower down. Light: frosted pale blue-grey.
     */
    static void glassRoles(StringBuilder sb, T t, boolean dark) {
        int n = t.tint;
        if (dark) {
            float s = t.strength;
            int bTop = t.mono ? 0xFF000000 : 0xFF020308, bMid = t.mono ? 0xFF050506 : 0xFF060812, bBot = t.mono ? 0xFF0B0C0F : 0xFF0C0F1E;
            int top = mix(bTop, n, 0.10f * s), mid = mix(bMid, n, 0.22f * s), bot = mix(bBot, n, 0.42f * s);
            item(sb, "android:colorBackground", mid);
            item(sb, "colorSurface", mid);
            item(sb, "colorSurfaceDim", top);
            item(sb, "colorSurfaceBright", mix(0xFF2A2E40, n, 0.20f * s));
            item(sb, "colorSurfaceContainerLowest", top);
            item(sb, "colorSurfaceContainerLow", mix(0xFF0E1018, n, 0.14f * s));
            item(sb, "colorSurfaceContainer", mix(0xFF131622, n, 0.16f * s));
            item(sb, "colorSurfaceContainerHigh", mix(0xFF181B28, n, 0.18f * s));
            item(sb, "colorSurfaceContainerHighest", mix(0xFF1F2232, n, 0.20f * s));
            item(sb, "colorSurfaceVariant", mix(0xFF262A3A, n, 0.20f * s));
            item(sb, "colorSurfaceInverse", 0xFFE8EAF1);
            item(sb, "colorOnSurfaceInverse", 0xFF161821);
            item(sb, "colorOnSurface", mix(0xFFF1F3F9, n, 0.03f));
            item(sb, "colorOnBackground", mix(0xFFF1F3F9, n, 0.03f));
            item(sb, "colorOnSurfaceVariant", mix(0xFFA9AFC2, n, 0.12f * s));
            item(sb, "colorOutline", mix(0xFF7C8296, n, 0.12f * s));
            item(sb, "colorOutlineVariant", mix(0xFF2C3042, n, 0.15f * s));
            int w = mix(0xFFFFFFFF, n, 0.25f * s);
            item(sb, "glassSurface", alpha(w, 0x12));
            item(sb, "glassSurfaceElevated", alpha(w, 0x1C));
            item(sb, "glassSurfaceStrong", alpha(mix(0xFF10131F, n, 0.18f * s), 0xF2));
            item(sb, "glassStroke", alpha(mix(0xFFFFFFFF, n, 0.30f * s), 0x1F));
            item(sb, "glassHighlight", alpha(0xFFFFFFFF, 0x1A));
            item(sb, "glassScrim", alpha(mix(0xFF000000, n, 0.10f), 0x99));
            item(sb, "glassTint", alpha(mix(0xFFFFFFFF, n, 0.6f), 0x1F));
            item(sb, "atmoTop", top);
            item(sb, "atmoMid", mid);
            item(sb, "atmoBottom", bot);
            item(sb, "atmoGlowA", alpha(t.glowA, 0x5C));
            item(sb, "atmoGlowB", alpha(t.glowB, 0x3D));
            item(sb, "navContainer", alpha(mix(0xFF0A0C16, n, 0.15f * s), 0xD9));
            item(sb, "navSelected", mix(0xFFF4F6FB, n, 0.05f));
            item(sb, "navOnSelected", mix(0xFF0A0C16, n, 0.20f));
            item(sb, "navText", mix(0xFF9CA2B6, n, 0.12f * s));
        } else {
            float s = t.lightStrength;
            int top = mix(0xFFF5F7FB, n, 0.04f * s), mid = mix(0xFFEAEEF5, n, 0.06f * s), bot = mix(0xFFDBE2EE, n, 0.10f * s);
            item(sb, "android:colorBackground", mid);
            item(sb, "colorSurface", mid);
            item(sb, "colorSurfaceDim", mix(0xFFD8DDE7, n, 0.06f * s));
            item(sb, "colorSurfaceBright", 0xFFFFFFFF);
            item(sb, "colorSurfaceContainerLowest", 0xFFFFFFFF);
            item(sb, "colorSurfaceContainerLow", mix(0xFFF7F9FC, n, 0.025f * s));
            item(sb, "colorSurfaceContainer", mix(0xFFEEF1F6, n, 0.04f * s));
            item(sb, "colorSurfaceContainerHigh", mix(0xFFE8ECF2, n, 0.05f * s));
            item(sb, "colorSurfaceContainerHighest", mix(0xFFE1E6EE, n, 0.06f * s));
            item(sb, "colorSurfaceVariant", mix(0xFFDFE4EC, n, 0.08f * s));
            item(sb, "colorSurfaceInverse", 0xFF262A36);
            item(sb, "colorOnSurfaceInverse", 0xFFF2F4F8);
            item(sb, "colorOnSurface", mix(0xFF12141C, n, 0.06f * s));
            item(sb, "colorOnBackground", mix(0xFF12141C, n, 0.06f * s));
            item(sb, "colorOnSurfaceVariant", mix(0xFF4E5468, n, 0.10f * s));
            item(sb, "colorOutline", mix(0xFF767C90, n, 0.10f * s));
            item(sb, "colorOutlineVariant", mix(0xFFC9CFDB, n, 0.10f * s));
            item(sb, "glassSurface", alpha(0xFFFFFFFF, 0xA6));
            item(sb, "glassSurfaceElevated", alpha(0xFFFFFFFF, 0xD1));
            item(sb, "glassSurfaceStrong", alpha(mix(0xFFFCFDFF, n, 0.02f * s), 0xF5));
            item(sb, "glassStroke", alpha(mix(0xFF1A2033, n, 0.30f), 0x14));
            item(sb, "glassHighlight", alpha(0xFFFFFFFF, 0xE6));
            item(sb, "glassScrim", 0x55101420);
            item(sb, "glassTint", alpha(mix(0xFF000000, n, 0.7f), 0x14));
            item(sb, "atmoTop", top);
            item(sb, "atmoMid", mid);
            item(sb, "atmoBottom", bot);
            item(sb, "atmoGlowA", alpha(mix(t.glowA, 0xFFFFFFFF, 0.35f), 0x3D));
            item(sb, "atmoGlowB", alpha(mix(t.glowB, 0xFFFFFFFF, 0.40f), 0x33));
            item(sb, "navContainer", alpha(0xFFFFFFFF, 0xE0));
            item(sb, "navSelected", mix(0xFF141826, n, 0.12f));
            item(sb, "navOnSelected", 0xFFFFFFFF);
            item(sb, "navText", mix(0xFF5D6378, n, 0.10f));
        }
    }

    static String xml(boolean dark, String suffix) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- GENERATED by tools/themegen/Gen.java (v2.12). Do not edit by hand: change Gen.java and re-run it. -->\n<resources>\n");
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
        sb.append("package com.hark.velaplayer\n\n// GENERATED by tools/themegen/Gen.java (v2.12). Do not edit by hand.\n");
        sb.append("/** Every ready-made theme in the gallery, in display order, with its light/dark and player overlays. */\n");
        sb.append("object ThemeCatalog {\n    val sections = listOf(\"Signature\", \"Jewel\", \"Warm\", \"Cool\", \"Neon\", \"Soft & Light\")\n");
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
