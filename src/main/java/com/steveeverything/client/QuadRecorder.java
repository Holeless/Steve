package com.steveeverything.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.ArrayList;
import java.util.List;

/**
 * A VertexConsumer that draws nothing. It just remembers the position, UV and normal of every vertex a ModelPart cube
 * emits, which is the one stable, public way to learn where a cube's faces live on the texture and which way they face.
 */
final class QuadRecorder implements VertexConsumer {
    /** Each entry is {x, y, z, u, v, nx, ny, nz}. Every 4 entries form one quad. */
    final List<float[]> vertices = new ArrayList<>();
    private float[] current;

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        current = new float[]{x, y, z, 0f, 0f, 0f, 0f, 0f};
        vertices.add(current);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        if (current != null) {
            current[3] = u;
            current[4] = v;
        }
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        if (current != null) {
            current[5] = x;
            current[6] = y;
            current[7] = z;
        }
        return this;
    }

    @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { return this; }
    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }
}
