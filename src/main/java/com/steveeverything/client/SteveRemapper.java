package com.steveeverything.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The "basic algorithm". Pure Java, no Minecraft classes, so it is easy to test.
 *
 * For every cuboid of a mob model we do these things:
 *   1. decide which body part of Steve it should look like (head / torso / arm / leg / eye / bare skin),
 *      first from the part's name ("right_hind_leg" -> leg), then from where it sits in the model;
 *   2. work out which of the cuboid's six UV rectangles is which (top, bottom, right, front, left, back);
 *   3. copy the same face of Steve's matching body part into that rectangle, stretched with
 *      nearest-neighbour sampling so it stays crisp and pixelated;
 *   4. eyes: cuboids named "eye" get Steve's eye pixels, and if a beak/snout/nose sits in front of Steve's eyes
 *      on a head, the eyes are moved to a spot that is actually visible.
 */
public final class SteveRemapper {
    private SteveRemapper() {}

    public enum Kind { HEAD, HAT, BODY, ARM, LEG, SKIN, EYE }

    /** One cuboid of a model: its name path, its UV rectangles in pixels and its size/position in model space. */
    public record CubeInfo(String path, List<int[]> rects,
                           float cx, float cy, float cz, float ex, float ey, float ez,
                           List<float[]> normals) {
        /** Without face normals (tests, simple callers). */
        public CubeInfo(String path, List<int[]> rects, float cx, float cy, float cz, float ex, float ey, float ez) {
            this(path, rects, cx, cy, cz, ex, ey, ez, null);
        }
    }

    // Slots of the standard Minecraft box-UV layout.
    static final int TOP = 0, BOTTOM = 1, RIGHT = 2, FRONT = 3, LEFT = 4, BACK = 5;

    // ------------------------------------------------------------------ Steve's skin layout (64-wide units)
    // {u, v, width, height, depth} of each body part's box on the skin.
    private static int[] box(Kind k) {
        return switch (k) {
            case HEAD, EYE -> new int[]{0, 0, 8, 8, 8};
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

    // ------------------------------------------------------------------ Steve's eyes (face = {8,8,8,8} on the skin)
    // Eye row is face row 4 (y = 12). Viewer's-left eye is face columns 1-2 (x = 9..10), viewer's-right eye 5-6 (x = 13..14).
    // Face row 3 (y = 11) is plain forehead skin, used to cover up an eye that has been moved.
    private static final int EYE_Y = 12, FOREHEAD_Y = 11, EYE_LEFT_X = 9, EYE_RIGHT_X = 13;
    private static final float EYE_H = 1f / 8f;
    /** The waist part of Steve's legs (the grey shoes at the bottom are left out). Used for the rear end of animals. */
    private static final int[] PANTS = {4, 20, 4, 8};

    private static int brightness(int[] steve, int sw, int sh, int x, int y) {
        double sc = sw / 64.0;
        int px = Math.max(0, Math.min(sw - 1, (int) (x * sc + sc / 2)));
        int py = Math.max(0, Math.min(sh - 1, (int) (y * sc + sc / 2)));
        int p = steve[py * sw + px];
        return (p & 0xFF) + ((p >> 8) & 0xFF) + ((p >> 16) & 0xFF);   // byte order doesn't matter for a sum
    }

    /**
     * Source rectangle {x, y, w, h} for one eye. When the target is 2+ texels wide we use both eye pixels (white + iris);
     * when it is only 1 texel wide we use the darker one (the iris) so the eye still reads as an eye.
     */
    static int[] eyeSource(boolean viewerLeft, int destWidth, int[] steve, int sw, int sh) {
        int x = viewerLeft ? EYE_LEFT_X : EYE_RIGHT_X;
        if (destWidth >= 2) return new int[]{x, EYE_Y, 2, 1};
        boolean firstDarker = brightness(steve, sw, sh, x, EYE_Y) <= brightness(steve, sw, sh, x + 1, EYE_Y);
        return new int[]{firstDarker ? x : x + 1, EYE_Y, 1, 1};
    }

    /** Source rectangle for just the white of one eye (the brighter of its two pixels). */
    static int[] eyeWhite(boolean viewerLeft, int[] steve, int sw, int sh) {
        int x = viewerLeft ? EYE_LEFT_X : EYE_RIGHT_X;
        boolean firstBrighter = brightness(steve, sw, sh, x, EYE_Y) >= brightness(steve, sw, sh, x + 1, EYE_Y);
        return new int[]{firstBrighter ? x : x + 1, EYE_Y, 1, 1};
    }

    // ------------------------------------------------------------------ step 1: which body part?
    private static final String[] EYE_W = {"eye", "eyeball", "pupil", "iris"};
    private static final String[] HAT_W = {"hat", "hair", "helmet", "hood", "cap", "headwear", "bonnet"};
    private static final String[] HEAD_W = {"head", "skull", "face", "cranium"};
    private static final String[] ARM_W = {"arm", "wing", "hand", "flipper", "fin", "shoulder", "sleeve", "claw", "pincer"};
    private static final String[] LEG_W = {"leg", "foot", "feet", "paw", "hoof", "toe", "thigh", "shin", "calf", "knee", "boot", "shoe"};
    private static final String[] BODY_W = {"body", "torso", "chest", "belly", "abdomen", "thorax", "spine", "back", "rump",
            "hip", "pelvis", "shell", "saddle", "hump", "cloak", "cape", "robe", "jacket", "coat", "dress", "skirt"};
    private static final String[] SKIN_W = {"nose", "snout", "muzzle", "beak", "bill", "chin", "tongue", "mouth", "jaw", "ear",
            "horn", "antler", "eyebrow", "brow", "crest", "comb", "wattle", "trunk", "tusk", "mandible", "fang", "tooth", "teeth",
            "whisker", "tail", "tentacle", "antenna", "stinger", "spike", "thorn", "udder", "mane", "lip", "gill", "feather",
            "tuft", "thing", "neck", "bag", "sack", "pouch", "nostril", "ridge", "plate", "scale"};

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
            if (has(EYE_W, w, false)) { rank = -1; k = Kind.EYE; }
            else if (has(HAT_W, w, false)) { rank = 0; k = Kind.HAT; }
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

    // ------------------------------------------------------------------ step 4: keep the eyes visible
    /** Texel range {a, b} (b exclusive, at least 1 wide) for a fractional range of the face. */
    private static int[] frac(float f0, float f1, int size) {
        int a = Math.max(0, Math.min(size - 1, Math.round(f0 * size)));
        int b = Math.max(a + 1, Math.min(size, Math.round(f1 * size)));
        return new int[]{a, b};
    }

    /**
     * Texel columns {a, b} (b exclusive) of the viewer's-left eye. Eyes are kept 2 texels wide whenever the face is at
     * least 4 wide, so white + iris both show (narrow faces like the chicken's end up white-purple-purple-white).
     * The right eye is the mirror image of this.
     */
    private static int[] eyeCols(float f0, float f1, int size) {
        int minW = size >= 4 ? 2 : 1;
        int a = Math.round(f0 * size);
        a = Math.max(0, Math.min(a, (size - 2 * minW) / 2));
        int b = Math.min(size, Math.max(Math.round(f1 * size), a + minW));
        a = Math.max(0, Math.min(a, b - 1));
        return new int[]{a, b};
    }

    /**
     * Redraws Steve's eyes on a head's front face where the plain stretched copy loses them:
     *  - narrow heads (chicken) squeeze Steve's 8-wide face, which eats the white of the eye;
     *  - beaks, snouts and noses are cubes sticking out in front of the face and can hide the eyes (chicken, pig...).
     * A covered eye is moved straight up to just above the covering cube, or sideways to just beside it.
     * Fractions below are of the head's front face: x from the viewer's left, y from the top.
     */
    private static void fixEyes(int[] out, int w, int h, int idx, List<CubeInfo> cubes, Kind[] kinds,
                                int[] fr, int[] rightRect, int[] leftRect, int[] steve, int sw, int sh) {
        CubeInfo head = cubes.get(idx);
        int dw = fr[2] - fr[0], dh = fr[3] - fr[1];
        if (dw < 2 || dh < 2 || head.ex() <= 0f || head.ey() <= 0f) return;

        float hx0 = head.cx() - head.ex() / 2f;
        float hy0 = head.cy() - head.ey() / 2f;
        float headFront = head.cz() - head.ez() / 2f;          // the mob faces -z
        final float eps = 0.002f;                              // blocks

        List<float[]> occluders = new ArrayList<>();
        for (int j = 0; j < cubes.size(); j++) {
            if (j == idx || kinds[j] != Kind.SKIN) continue;
            CubeInfo s = cubes.get(j);
            float sFront = s.cz() - s.ez() / 2f, sBack = s.cz() + s.ez() / 2f;
            if (!(sFront < headFront - eps && sBack >= headFront - eps)) continue;   // must stick out of the face
            float fx0 = (s.cx() - s.ex() / 2f - hx0) / head.ex();
            float fy0 = (s.cy() - s.ey() / 2f - hy0) / head.ey();
            float fx1 = fx0 + s.ex() / head.ex();
            float fy1 = fy0 + s.ey() / head.ey();
            if (fx1 > 0.02f && fx0 < 0.98f && fy1 > 0.02f && fy0 < 0.98f) occluders.add(new float[]{fx0, fx1, fy0, fy1});
        }
        boolean squeezed = dw < 8;
        if (occluders.isEmpty() && !squeezed) return;

        float eyeH = Math.max(EYE_H, 1f / dh);
        final float eyeX0 = 1f / 8f, eyeX1 = 3f / 8f, eyeY0 = 4f / 8f, eyeY1 = 5f / 8f;   // the left eye; the right one is its mirror

        for (int side = 0; side < 2; side++) {
            boolean mirror = side == 1;
            float[] hit = null;
            for (float[] o : occluders) {
                float ox0 = mirror ? 1f - o[1] : o[0], ox1 = mirror ? 1f - o[0] : o[1];
                if (ox0 < eyeX1 && ox1 > eyeX0 && o[2] < eyeY1 && o[3] > eyeY0) { hit = new float[]{ox0, ox1, o[2], o[3]}; break; }
            }

            float nx0 = eyeX0, nx1 = eyeX1, ny0 = eyeY0, ny1 = eyeY1;
            if (hit != null) {
                float top1 = hit[2], top0 = top1 - eyeH;
                if (top0 >= 0f) { ny0 = top0; ny1 = top1; }                                  // above the beak/snout
                else if (hit[0] - (eyeX1 - eyeX0) >= 0f) { nx1 = hit[0]; nx0 = nx1 - (eyeX1 - eyeX0); }   // beside it
                else if (!squeezed) continue;                                                // nowhere to go
            }

            // erase the old (stretched) eye completely, then draw the new one
            int ox0 = (int) Math.floor(eyeX0 * dw), ox1 = Math.min(dw, Math.max(ox0 + 1, (int) Math.ceil(eyeX1 * dw)));
            int oy0 = (int) Math.floor(eyeY0 * dh), oy1 = Math.min(dh, Math.max(oy0 + 1, (int) Math.ceil(eyeY1 * dh)));
            int[] nxr = eyeCols(nx0, nx1, dw), nyr = frac(ny0, ny1, dh);
            if (mirror) {
                int t = ox0; ox0 = dw - ox1; ox1 = dw - t;
                nxr = new int[]{dw - nxr[1], dw - nxr[0]};
            }
            int[] oldBox = {fr[0] + ox0, fr[1] + oy0, fr[0] + ox1, fr[1] + oy1};
            int[] newBox = {fr[0] + nxr[0], fr[1] + nyr[0], fr[0] + nxr[1], fr[1] + nyr[1]};
            paint(out, w, h, oldBox, new int[]{mirror ? EYE_RIGHT_X : EYE_LEFT_X, FOREHEAD_Y, 2, 1}, steve, sw, sh);
            if (dw < 5) {
                // very narrow head (chicken): the eye wraps around the corner. The iris sits on the outermost column of the
                // front face, the white on the edge of the side face next to it.
                int col = mirror ? dw - 1 : 0;
                int[] irisBox = {fr[0] + col, fr[1] + nyr[0], fr[0] + col + 1, fr[1] + nyr[1]};
                paint(out, w, h, irisBox, eyeSource(!mirror, 1, steve, sw, sh), steve, sw, sh);
                int[] sideRect = mirror ? leftRect : rightRect;       // the viewer's-left eye is the mob's right eye
                if (sideRect != null) {
                    int[] sy = frac(ny0, ny1, sideRect[3] - sideRect[1]);
                    int sx = mirror ? sideRect[0] : sideRect[2] - 1;      // the column that touches the front face
                    int[] whiteBox = {sx, sideRect[1] + sy[0], sx + 1, sideRect[1] + sy[1]};
                    paint(out, w, h, whiteBox, eyeWhite(!mirror, steve, sw, sh), steve, sw, sh);
                }
            } else {
                paint(out, w, h, newBox, eyeSource(!mirror, newBox[2] - newBox[0], steve, sw, sh), steve, sw, sh);
            }
        }
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
        Map<String, Float> biggest = new HashMap<>();
        for (CubeInfo c : cubes) biggest.merge(c.path(), volume(c), Math::max);

        float maxVolume = 0f;
        for (CubeInfo c : cubes) maxVolume = Math.max(maxVolume, volume(c));

        int n = cubes.size();
        Kind[] kinds = new Kind[n];
        for (int i = 0; i < n; i++) {
            CubeInfo c = cubes.get(i);
            Kind kind = classifyByName(c.path());
            boolean named = kind != null;
            if (kind == null) kind = classifyByGeometry(c, minY, maxY);
            // an unnamed tiny cube (chicken "red_thing", odd mod parts) is an add-on like a wattle: bare skin
            if (!named && volume(c) < 0.035f * maxVolume) kind = Kind.SKIN;
            if ((kind == Kind.HEAD || kind == Kind.BODY) && volume(c) < 0.12f * biggest.get(c.path())) {
                kind = Kind.SKIN;
            }
            kinds[i] = kind;
        }

        for (int i = 0; i < n; i++) {
            CubeInfo c = cubes.get(i);
            Kind kind = kinds[i];
            int[] slots = classifySlots(c.rects());
            int frontIndex = -1;
            // a body lying on its side (cow, pig, sheep, chicken...): its rear end (the face pointing +z) gets the pants
            boolean lyingBody = kind == Kind.BODY && c.ez() > 1.15f * c.ey() && c.ez() >= c.ex();
            int rightIndex = -1, leftIndex = -1;
            for (int k = 0; k < c.rects().size(); k++) {
                int[] r = c.rects().get(k);
                int slot = slots[k];

                if (kind == Kind.EYE) {
                    // model -x is the mob's right = the viewer's left when looking at its face
                    boolean viewerLeft = c.cx() < 1e-3f;
                    paint(out, w, h, r, eyeSource(viewerLeft, r[2] - r[0], steve, sw, sh), steve, sw, sh);
                    continue;
                }
                if (kind == Kind.HEAD && slot == FRONT && frontIndex < 0) frontIndex = k;
                if (kind == Kind.HEAD && slot == RIGHT && rightIndex < 0) rightIndex = k;
                if (kind == Kind.HEAD && slot == LEFT && leftIndex < 0) leftIndex = k;

                int[] src = steveRect(kind, slot);
                boolean dorsal = lyingBody && c.normals() != null && k < c.normals().size() && c.normals().get(k)[2] > 0.5f;
                // Steve's top/bottom strips are tiny (8x4). On animals the large back/belly is often a "top" or
                // "bottom" face, so when the target is much bigger, borrow the big front/back face instead.
                if (dorsal) {
                    src = PANTS;                                   // the butt is pants, not shirt
                } else if (kind != Kind.HEAD && kind != Kind.HAT && kind != Kind.SKIN && (slot == TOP || slot == BOTTOM)) {
                    long dest = (long) (r[2] - r[0]) * (r[3] - r[1]);
                    long have = (long) src[2] * src[3];
                    if (dest > 2 * have) src = steveRect(kind, slot == TOP ? FRONT : BACK);
                }
                paint(out, w, h, r, src, steve, sw, sh);
            }
            if (kind == Kind.HEAD && frontIndex >= 0) {
                fixEyes(out, w, h, i, cubes, kinds, c.rects().get(frontIndex),
                        rightIndex >= 0 ? c.rects().get(rightIndex) : null,
                        leftIndex >= 0 ? c.rects().get(leftIndex) : null, steve, sw, sh);
            }
        }
        return out;
    }
}
