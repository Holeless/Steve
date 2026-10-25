package com.steveeverything.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.steveeverything.SteveEverything;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Draws a small Steve head (his real skin, head box) in place of cubes the texture remap left transparent because
 * Steve's face can't be stretched onto them (cod, salmon, parrot heads, endermite and silverfish segments...).
 * It follows the animated pose of the cube it replaces.
 */
final class MiniHeadLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
    private static final ResourceLocation STEVE_SKIN =
            ResourceLocation.fromNamespaceAndPath(SteveEverything.MOD_ID, "textures/steve.png");
    private static final float MIN_SIDE = 2f, MAX_SIDE = 6f;     // pixels

    private static int skinW = 64, skinH = 64;
    private static ModelPart head;

    private final Set<ModelPart.Cube> targets = new HashSet<>();
    private final Map<Object, List<ModelPart>> roots = new WeakHashMap<>();

    MiniHeadLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    static void setSkinSize(int w, int h) {
        if (w != skinW || h != skinH) { skinW = w; skinH = h; head = null; }
    }

    static void reset() {
        head = null;
    }

    void addTargets(Collection<ModelPart.Cube> cubes) {
        targets.addAll(cubes);
    }

    private static ModelPart head() {
        if (head == null) {
            MeshDefinition mesh = new MeshDefinition();
            mesh.getRoot().addOrReplaceChild("head",
                    CubeListBuilder.create().texOffs(0, 0).addBox(-4f, -4f, -4f, 8f, 8f, 8f), PartPose.ZERO);
            // UVs are fractions of the texture, so describe the skin in 64-wide units whatever its real resolution
            head = LayerDefinition.create(mesh, 64, Math.max(1, skinH * 64 / Math.max(1, skinW))).bakeRoot();
        }
        return head;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (targets.isEmpty() || entity.isInvisible()) return;

        ModelPart mini = head();
        List<ModelPart> parts = roots.computeIfAbsent(getParentModel(), ModelScanner::rootParts);
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(STEVE_SKIN));
        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0f);
        Set<ModelPart.Cube> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        for (ModelPart part : parts) {
            // visit() applies each part's current (animated) pose to the stack and hands us every cube in it
            part.visit(poseStack, (pose, path, index, cube) -> {
                if (!targets.contains(cube) || !seen.add(cube)) return;
                float dx = cube.maxX - cube.minX, dy = cube.maxY - cube.minY, dz = cube.maxZ - cube.minZ;
                float side = Math.max(MIN_SIDE, Math.min(MAX_SIDE, (dx + dy + dz) / 3f));
                float k = side / 8f;
                poseStack.pushPose();
                poseStack.translate((cube.minX + cube.maxX) / 32f, (cube.minY + cube.maxY) / 32f, (cube.minZ + cube.maxZ) / 32f);
                poseStack.scale(k, k, k);
                mini.render(poseStack, consumer, packedLight, overlay);
                poseStack.popPose();
            });
        }
    }
}
