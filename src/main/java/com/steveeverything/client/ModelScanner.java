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

/** Finds every cuboid in an entity model and records where its faces sit on the texture. */
final class ModelScanner {
    private ModelScanner() {}

    static List<SteveRemapper.CubeInfo> scan(Object model, int texW, int texH) {
        // 1. find the model's ModelParts (mods and vanilla keep them in plain fields)
        Map<String, ModelPart> roots = new LinkedHashMap<>();
        collectParts(model, roots);
        List<Map.Entry<String, ModelPart>> ordered = new ArrayList<>(roots.entrySet());
        // a field literally called "root" holds the whole tree, so walk it first and keep its nice child names
        ordered.sort(Comparator.comparingInt(e -> e.getKey().equals("root") ? 0 : 1));

        // 2. walk each tree; ModelPart#visit gives us every cube plus its name path and pose
        Set<ModelPart.Cube> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<SteveRemapper.CubeInfo> result = new ArrayList<>();
        for (Map.Entry<String, ModelPart> entry : ordered) {
            String prefix = entry.getKey();
            entry.getValue().visit(new PoseStack(), (pose, path, index, cube) -> {
                if (!seen.add(cube)) return;
                QuadRecorder recorder = new QuadRecorder();
                cube.compile(pose, recorder, 0, 0, -1);
                SteveRemapper.CubeInfo info = toInfo(prefix + "/" + path, recorder, texW, texH);
                if (info != null) result.add(info);
            });
        }
        return result;
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

        List<int[]> rects = new ArrayList<>();
        for (int q = 0; q + 3 < v.size(); q += 4) {
            float u0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v0 = Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
            for (int k = 0; k < 4; k++) {
                float[] p = v.get(q + k);
                u0 = Math.min(u0, p[3]); u1 = Math.max(u1, p[3]);
                v0 = Math.min(v0, p[4]); v1 = Math.max(v1, p[4]);
            }
            int[] r = {Math.round(u0 * texW), Math.round(v0 * texH), Math.round(u1 * texW), Math.round(v1 * texH)};
            if (r[2] <= r[0] || r[3] <= r[1]) continue;          // zero-area face (flat planes)
            boolean duplicate = false;
            for (int[] o : rects) if (java.util.Arrays.equals(o, r)) { duplicate = true; break; }
            if (!duplicate) rects.add(r);
        }
        if (rects.isEmpty()) return null;

        return new SteveRemapper.CubeInfo(path, rects,
                (minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f,
                maxX - minX, maxY - minY, maxZ - minZ);
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
