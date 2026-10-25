package com.steveeverything.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The "basic algorithm". Pure Java, no Minecraft classes, so it is easy to test.
 *
 * For every cuboid of a mob model we do three things:
 *   1. decide which body part of Steve it should look like (head / torso / arm / leg / bare skin),
 *      first from the part's name ("right_hind_leg" -> leg), then from where it sits in the model;
 *   2. work out which of the cuboid's six UV rectangles is which (top, bottom, right, front, left, back);
 *   3. copy the same face of Steve's matching body part into that rectangle, stretched with
 *      nearest-neighbour sampling so it stays crisp and pixelated.
 */
public final class SteveRemapper {
    private SteveRemapper() {}

    public enum Kind { HEAD, HAT, BODY, ARM, LEG, SKIN }

    /** One cuboid of a model: its name path, its UV rectangles in pixels and its size/position in model space. */
    public record CubeInfo(String path, List<int[]> rects,
                           float cx, float cy, float cz, float ex, float ey, float ez) {}

    // Slots of the standard Minecraft box-UV layout.
    static final int TOP = 0, BOTTOM = 1, RIGHT = 2, FRONT = 3, LEFT = 4, BACK = 5;

    // ------------------------------------------------------------------ Steve's skin layout (64-wide units)
    // {u, v, width, height, depth} of each body part's box on the skin.
    private static int[] box(Kind k) {
        return switch (k) {
            case HEAD -> new int[]{0, 0, 8, 8, 8};
            case HAT -> new int[]{32, 0, 8, 8, 8};     // second layer; transparent on the classic Steve skin
            case BODY -> new int[]{16, 16, 8, 12, 4};
            case ARM, SKIN -> new int[]{40, 16, 4, 12, 4};
            case LEG -> new int[]{0, 16, 4, 12, 4};
        };
    }

    /** Rectangle {x, y, w, h} on the skin for a body part and box slot. */
    static int[] steveRect(Kind k, int slot) {
        if (k == Kind.SKIN) return new int[]{44, 26, 4, 6}; // lower front of the arm = bare skin
        int[] b = box(k);
        int u = b[0], v = b[1], w = b[2], h = b[3], d = b[4];
        return switch (slot) {
            case TOP -> new int[]{u + d, v, w, d};
            case BOTTOM -> new int[]{u + d + w, v, w, d};
            case RIGHT -> new int[]{u, v + d, d, h};
            case LEFT -> new int[]{u + d + w, v + d, d, h};
            case BACK -> new int[]{u + 2 * d + w, v + d, w, h};
            default -> new int[]{u + d, v + d, w, h};      // FRONT
        };
    }

    // ------------------------------------------------------------------ step 1: which body part?
    private static final String[] HAT_W = {"hat", "hair", "helmet", "hood", "cap", "headwear", "bonnet"};
    private static final String[] HEAD_W = {"head", "skull", "face", "cranium"};
    private static final String[] ARM_W = {"arm", "wing", "hand", "flipper", "fin", "shoulder", "sleeve", "claw", "pincer"};
    private static final String[] LEG_W = {"leg", "foot", "feet", "paw", "hoof", "toe", "thigh", "shin", "calf", "knee", "boot", "shoe"};
    private static final String[] BODY_W = {"body", "torso", "chest", "belly", "abdomen", "thorax", "spine", "back", "rump",
            "hip", "pelvis", "shell", "saddle", "hump", "cloak", "cape", "robe", "jacket", "coat", "dress", "skirt"};
    private static final String[] SKIN_W = {"nose", "snout", "muzzle", "beak", "bill", "chin", "tongue", "mouth", "jaw", "ear",
            "horn", "antler", "eye", "brow", "crest", "comb", "wattle", "trunk", "tusk", "mandible", "fang", "tooth", "teeth",
            "whisker", "tail", "tentacle", "antenna", "stinger", "spike", "thorn", "udder", "mane", "lip", "gill", "feather",
            "tuft", "neck", "bag", "sack", "pouch", "nostril", "ridge", "plate", "scale"};

    private static boolean has(String[] keys, String word, boolean suffixAllowed) {
        for (String k : keys) {
            if (word.equals(k)) return true;
            if (suffixAllowed && word.length() > k.length() && word.endsWith(k)) return true; // forearm, foreleg, forehead...
        }
        return false;
    }

    static Kind classifyByName(String path) {
        if (path == null) return null;
        // split camelCase, then lowercase
        String p = path.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        String[] segs = p.split("[/\\\\.]+");
        for (int i = segs.length - 1; i >= 0; i--) {          // deepest name first
            if (segs[i].isEmpty()) continue;
            String[] words = segs[i].split("[^a-z]+");
            Kind k = kindOfWords(words);
            if (k != null) return k;
        }
        return null;
    }

    private static Kind kindOfWords(String[] words) {
        Kind found = null;
        int best = 99;
        for (String raw : words) {
            if (raw.isEmpty()) continue;
            String w = raw.length() > 3 && raw.endsWith("s") ? raw.substring(0, raw.length() - 1) : raw;
            int rank; Kind k;
            if (has(HAT_W, w, false)) { rank = 0; k = Kind.HAT; }
            else if (has(HEAD_W, w, true)) { rank = 1; k = Kind.HEAD; }
            else if (has(ARM_W, w, true)) { rank = 2; k = Kind.ARM; }
            else if (has(LEG_W, w, true)) { rank = 3; k = Kind.LEG; }
            else if (has(BODY_W, w, false)) { rank = 4; k = Kind.BODY; }
            else if (has(SKIN_W, w, false)) { rank = 5; k = Kind.SKIN; }
            else continue;
            if (rank < best) { best = rank; found = k; }
        }
        return found;
    }

    /** Fallback for cuboids with unhelpful names: judge by position and shape. Model space is y-down. */
    static Kind classifyByGeometry(CubeInfo c, float minY, float maxY) {
        float height = Math.max(1e-4f, maxY - minY);
        float t = (c.cy() - minY) / height;                 // 0 = top of the model, 1 = bottom
        float horizontal = Math.max(c.ex(), c.ez());
        if (t > 0.5f && c.ey() > 1.4f * horizontal) return Kind.LEG;
        if (t < 0.28f) return Kind.HEAD;
        return Kind.BODY;
    }

    // ------------------------------------------------------------------ step 2: which UV rectangle is which face?
    static int[] classifySlots(List<int[]> rects) {
        int n = rects.size();
        int[] slot = new int[n];
        Arrays.fill(slot, FRONT);
        if (n == 0) return slot;
        int vmin = Integer.MAX_VALUE;
        for (int[] r : rects) vmin = Math.min(vmin, r[1]);

        List<Integer> top = new ArrayList<>(), side = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int[] r = rects.get(i);
            boolean hasBelow = false;
            for (int j = 0; j < n; j++) if (j != i && rects.get(j)[1] == r[3]) { hasBelow = true; break; }
            if (r[1] == vmin && hasBelow) top.add(i); else side.add(i);
        }
        top.sort((a, b) -> Integer.compare(rects.get(a)[0], rects.get(b)[0]));
        side.sort((a, b) -> Integer.compare(rects.get(a)[0], rects.get(b)[0]));
        for (int k = 0; k < top.size(); k++) slot[top.get(k)] = k == 0 ? TOP : BOTTOM;

        int m = side.size();
        int[] four = {RIGHT, FRONT, LEFT, BACK};
        for (int k = 0; k < m; k++) {
            int s;
            if (m == 2) s = k == 0 ? FRONT : BACK;           // flat plane: front + back
            else if (m == 1) s = FRONT;
            else s = four[k % 4];
            slot[side.get(k)] = s;
        }
        return slot;
    }

    // ------------------------------------------------------------------ step 3: copy pixels
    private static void paint(int[] out, int w, int h, int[] r, int[] src, int[] steve, int sw, int sh) {
        int dw = r[2] - r[0], dh = r[3] - r[1];
        if (dw <= 0 || dh <= 0) return;
        double sc = sw / 64.0;                                // supports HD skins
        double sx0 = src[0] * sc, sy0 = src[1] * sc, sW = src[2] * sc, sH = src[3] * sc;
        for (int j = 0; j < dh; j++) {
            int dy = r[1] + j;
            if (dy < 0 || dy >= h) continue;
            int sy = (int) Math.floor(sy0 + (j + 0.5) * sH / dh);
            sy = Math.max(0, Math.min(sh - 1, sy));
            for (int i = 0; i < dw; i++) {
                int dx = r[0] + i;
                if (dx < 0 || dx >= w) continue;
                int sx = (int) Math.floor(sx0 + (i + 0.5) * sW / dw);
                sx = Math.max(0, Math.min(sw - 1, sx));
                out[dy * w + dx] = steve[sy * sw + sx];
            }
        }
    }

    private static float volume(CubeInfo c) {
        return Math.max(1e-6f, c.ex() * c.ey() * c.ez());
    }

    /**
     * Build a Steve-ified version of a mob texture.
     *
     * @param cubes every cuboid of the mob's model
     * @param w     width of the mob's original texture
     * @param h     height of the mob's original texture
     * @param steve Steve's skin pixels (any 4-byte pixel format; they are copied untouched)
     * @return a w*h pixel array; areas the model never uses stay 0 (transparent)
     */
    public static int[] remap(List<CubeInfo> cubes, int w, int h, int[] steve, int sw, int sh) {
        int[] out = new int[w * h];
        if (cubes.isEmpty()) return out;

        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (CubeInfo c : cubes) {
            minY = Math.min(minY, c.cy() - c.ey() / 2f);
            maxY = Math.max(maxY, c.cy() + c.ey() / 2f);
        }
        // Horns, ears, udders etc. are often extra cubes inside the head/body part rather than parts of their own.
        // Remember the biggest cube of every part so such small add-ons can be recognised below.
        java.util.Map<String, Float> biggest = new java.util.HashMap<>();
        for (CubeInfo c : cubes) biggest.merge(c.path(), volume(c), Math::max);

        for (CubeInfo c : cubes) {
            Kind kind = classifyByName(c.path());
            if (kind == null) kind = classifyByGeometry(c, minY, maxY);
            if ((kind == Kind.HEAD || kind == Kind.BODY) && volume(c) < 0.12f * biggest.get(c.path())) {
                kind = Kind.SKIN;
            }
            int[] slots = classifySlots(c.rects());
            for (int i = 0; i < c.rects().size(); i++) {
                int[] r = c.rects().get(i);
                int slot = slots[i];
                int[] src = steveRect(kind, slot);
                // Steve's top/bottom strips are tiny (8x4). On animals the large back/belly is often a "top" or
                // "bottom" face, so when the target is much bigger, borrow the big front/back face instead.
                if (kind != Kind.HEAD && kind != Kind.HAT && kind != Kind.SKIN && (slot == TOP || slot == BOTTOM)) {
                    long dest = (long) (r[2] - r[0]) * (r[3] - r[1]);
                    long have = (long) src[2] * src[3];
                    if (dest > 2 * have) src = steveRect(kind, slot == TOP ? FRONT : BACK);
                }
                paint(out, w, h, r, src, steve, sw, sh);
            }
        }
        return out;
    }
}
