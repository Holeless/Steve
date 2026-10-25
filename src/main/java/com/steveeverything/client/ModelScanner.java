package com.steveeverything.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds every cuboid in an entity model and records where its faces sit on the texture and which way they face. */
final class ModelScanner {
    private ModelScanner() {}

    /** The cubes' info plus the actual ModelPart.Cube objects, in the same order. */
    record Result(List<SteveRemapper.CubeInfo> cubes, List<ModelPart.Cube> refs) {}

    /** Every top-level ModelPart of a model, "root" first (the order scan() walks them in). */
    static List<ModelPart> rootParts(Object model) {
        Map<String, ModelPart> roots = new LinkedHashMap<>();
        collectParts(model, roots);
        List<Map.Entry<String, ModelPart>> ordered = new ArrayList<>(roots.entrySet());
        ordered.sort(Comparator.comparingInt(e -> e.getKey().equals("root") ? 0 : 1));
        List<ModelPart> parts = new ArrayList<>();
        for (Map.Entry<String, ModelPart> e : ordered) parts.add(e.getValue());
        return parts;
    }

    static Result scan(Object model, int texW, int texH) {
        // 1. find the model's ModelParts (mods and vanilla keep them in plain fields)
        Map<String, ModelPart> roots = new LinkedHashMap<>();
        collectParts(model, roots);
        List<Map.Entry<String, ModelPart>> ordered = new ArrayList<>(roots.entrySet());
        // a field literally called "root" holds the whole tree, so walk it first and keep its nice child names
        ordered.sort(Comparator.comparingInt(e -> e.getKey().equals("root") ? 0 : 1));

        // 2. walk each tree; ModelPart#visit gives us every cube plus its name path and pose
        Set<ModelPart.Cube> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<SteveRemapper.CubeInfo> result = new ArrayList<>();
        List<ModelPart.Cube> refs = new ArrayList<>();
        for (Map.Entry<String, ModelPart> entry : ordered) {
            String prefix = entry.getKey();
            entry.getValue().visit(new PoseStack(), (pose, path, index, cube) -> {
                if (!seen.add(cube)) return;
                QuadRecorder recorder = new QuadRecorder();
                cube.compile(pose, recorder, 0, 0, -1);
                SteveRemapper.CubeInfo info = toInfo(prefix + "/" + path, recorder, texW, texH);
                if (info != null) { result.add(info); refs.add(cube); }
            });
        }
        return new Result(result, refs);
    }

    private static SteveRemapper.CubeInfo toInfo(String path, QuadRecorder rec, int texW, int texH) {
        List<float[]> v = rec.vertices;
        if (v.size() < 4) return null;

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (float[] p : v) {
            minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
            minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]);
        }

        List<float[]> allN = new ArrayList<>(), allC = new ArrayList<>();   // every real quad: normal + center (flat-plane detection)
        List<int[]> rects = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();      // one per rect: which way that face points (model space, y is down)
        List<float[]> axes = new ArrayList<>();         // one per rect: model-space direction of increasing u, then of increasing v
        for (int q = 0; q + 3 < v.size(); q += 4) {
            float u0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v0 = Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
            float nx = 0f, ny = 0f, nz = 0f, qx = 0f, qy = 0f, qz = 0f;
            for (int k = 0; k < 4; k++) {
                float[] p = v.get(q + k);
                u0 = Math.min(u0, p[3]); u1 = Math.max(u1, p[3]);
                v0 = Math.min(v0, p[4]); v1 = Math.max(v1, p[4]);
                nx += p[5]; ny += p[6]; nz += p[7];
                qx += p[0]; qy += p[1]; qz += p[2];
            }
            int[] r = {Math.round(u0 * texW), Math.round(v0 * texH), Math.round(u1 * texW), Math.round(v1 * texH)};
            if (r[2] <= r[0] || r[3] <= r[1]) continue;          // zero-area face (flat planes)
            allN.add(new float[]{nx / 4f, ny / 4f, nz / 4f});
            allC.add(new float[]{qx / 4f, qy / 4f, qz / 4f});
            boolean duplicate = false;
            for (int[] o : rects) if (java.util.Arrays.equals(o, r)) { duplicate = true; break; }
            if (!duplicate) {
                rects.add(r);
                normals.add(new float[]{nx / 4f, ny / 4f, nz / 4f});
                axes.add(axesOf(v, q, (u0 + u1) / 2f, (v0 + v1) / 2f));
            }
        }
        if (rects.isEmpty()) return null;

        // a flat plane (wing, ear, fin, tail...) has two opposite-facing quads in the very same plane, however it is rotated
        boolean flat = false;
        for (int i = 0; i < allN.size() && !flat; i++) {
            for (int j = i + 1; j < allN.size(); j++) {
                float[] a = allN.get(i), b = allN.get(j);
                if (a[0] * b[0] + a[1] * b[1] + a[2] * b[2] > -0.9f) continue;
                float[] ca = allC.get(i), cb = allC.get(j);
                float dist = Math.abs(a[0] * (ca[0] - cb[0]) + a[1] * (ca[1] - cb[1]) + a[2] * (ca[2] - cb[2]));
                if (dist < 0.04f) { flat = true; break; }
            }
        }

        return new SteveRemapper.CubeInfo(path, rects,
                (minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f,
                maxX - minX, maxY - minY, maxZ - minZ, normals, axes, flat);
    }

    /** Which way (model space) texture u and v increase across the quad starting at vertex q. */
    private static float[] axesOf(List<float[]> v, int q, float uMid, float vMid) {
        float[] uHi = new float[3], uLo = new float[3], vHi = new float[3], vLo = new float[3];
        int nuHi = 0, nuLo = 0, nvHi = 0, nvLo = 0;
        for (int k = 0; k < 4; k++) {
            float[] p = v.get(q + k);
            if (p[3] > uMid) { for (int a = 0; a < 3; a++) uHi[a] += p[a]; nuHi++; }
            else { for (int a = 0; a < 3; a++) uLo[a] += p[a]; nuLo++; }
            if (p[4] > vMid) { for (int a = 0; a < 3; a++) vHi[a] += p[a]; nvHi++; }
            else { for (int a = 0; a < 3; a++) vLo[a] += p[a]; nvLo++; }
        }
        float[] out = new float[6];
        direction(uHi, nuHi, uLo, nuLo, out, 0);
        direction(vHi, nvHi, vLo, nvLo, out, 3);
        return out;
    }

    private static void direction(float[] hi, int nHi, float[] lo, int nLo, float[] out, int at) {
        if (nHi == 0 || nLo == 0) return;
        float x = hi[0] / nHi - lo[0] / nLo, y = hi[1] / nHi - lo[1] / nLo, z = hi[2] / nHi - lo[2] / nLo;
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-6f) return;
        out[at] = x / len; out[at + 1] = y / len; out[at + 2] = z / len;
    }

    // ---------------------------------------------------------------- reflection helpers
    private static void collectParts(Object model, Map<String, ModelPart> out) {
        Class<?> c = model.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    addValue(f.getName(), f.get(model), out);
                } catch (Throwable ignored) {
                    // inaccessible field: skip it
                }
            }
            c = c.getSuperclass();
        }
    }

    private static void addValue(String name, Object value, Map<String, ModelPart> out) {
        if (value instanceof ModelPart part) {
            out.putIfAbsent(name, part);
        } else if (value instanceof ModelPart[] array) {
            for (int i = 0; i < array.length; i++) if (array[i] != null) out.putIfAbsent(name + i, array[i]);
        } else if (value instanceof Iterable<?> iterable) {
            int i = 0;
            for (Object o : iterable) {
                if (o instanceof ModelPart part) out.putIfAbsent(name + i, part);
                if (++i > 64) break;
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() instanceof ModelPart part) out.putIfAbsent(name + "_" + e.getKey(), part);
            }
        }
    }
}
