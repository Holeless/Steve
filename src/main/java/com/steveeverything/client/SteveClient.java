package com.steveeverything.client;

import com.mojang.logging.LogUtils;
import com.steveeverything.SteveEverything;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.SpiderEyesLayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Spider;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.slf4j.Logger;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The first time a mob with a given texture is about to be drawn, we build a Steve version of that texture
 * and register it under the very same resource location. Every renderer, layer and shader then picks it up
 * with no mixins and no changes to vanilla code.
 */
public final class SteveClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation STEVE_SKIN =
            ResourceLocation.fromNamespaceAndPath(SteveEverything.MOD_ID, "textures/steve.png");

    /** Textures we have already replaced (cleared whenever resources reload). */
    private static final Set<ResourceLocation> DONE = new HashSet<>();

    /** Renderers whose red spider-eyes layer we have already taken off. */
    private static final Set<Object> EYES_REMOVED = Collections.newSetFromMap(new WeakHashMap<>());
    private static Field layersField;

    /** Renderers that already got their mini-head layer. */
    private static final Map<Object, MiniHeadLayer<?, ?>> MINI = new WeakHashMap<>();

    private static int[] stevePixels;
    private static int steveW, steveH;

    private SteveClient() {}

    // ------------------------------------------------------------------ events
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            DONE.clear();
            stevePixels = null;
            MiniHeadLayer.reset();
        });
    }

    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        handle(event);
    }

    @SuppressWarnings("unchecked")
    private static <T extends LivingEntity, M extends EntityModel<T>> void handle(RenderLivingEvent.Pre<T, M> event) {
        LivingEntity living = event.getEntity();
        if (!(living instanceof Mob)) return;                 // players, armor stands etc. are left alone
        T entity = (T) living;

        LivingEntityRenderer<T, M> renderer = event.getRenderer();
        if (living instanceof Spider) removeSpiderEyes(renderer);   // spider + cave spider only; the enderman's eyes stay

        ResourceLocation texture = null;
        try {
            texture = renderer.getTextureLocation(entity);
            if (texture == null || !DONE.add(texture)) return;
            steveify(texture, renderer);
        } catch (Throwable t) {
            LOGGER.warn("Could not turn {} into Steve; leaving it vanilla", texture, t);
        }
    }

    /** The red glowing eyes are a render layer; take it off the renderer's layer list (once per renderer). */
    private static void removeSpiderEyes(LivingEntityRenderer<?, ?> renderer) {
        if (!EYES_REMOVED.add(renderer)) return;
        try {
            if (layersField == null) {
                layersField = LivingEntityRenderer.class.getDeclaredField("layers");
                layersField.setAccessible(true);
            }
            Object layers = layersField.get(renderer);
            if (layers instanceof List<?> list) list.removeIf(layer -> layer instanceof SpiderEyesLayer);
        } catch (Throwable t) {
            LOGGER.warn("Could not remove the spider eyes layer", t);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void attachMiniHeads(LivingEntityRenderer renderer, Set<ModelPart.Cube> cubes) {
        MiniHeadLayer layer = MINI.get(renderer);
        if (layer == null) {
            layer = new MiniHeadLayer(renderer);
            MINI.put(renderer, layer);
            renderer.addLayer(layer);
        }
        layer.addTargets(cubes);
    }

    // ------------------------------------------------------------------ the work
    private static void steveify(ResourceLocation texture, LivingEntityRenderer<?, ?> renderer) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        ResourceManager resources = mc.getResourceManager();

        Optional<Resource> original = resources.getResource(texture);
        if (original.isEmpty()) return;                        // dynamic/generated texture, nothing to read
        int w, h;
        try (InputStream in = original.get().open(); NativeImage image = NativeImage.read(in)) {
            w = image.getWidth();
            h = image.getHeight();
        }

        if (!loadSteve(resources)) return;

        ModelScanner.Result scan = ModelScanner.scan(renderer.getModel(), w, h);
        List<SteveRemapper.CubeInfo> cubes = scan.cubes();
        if (cubes.isEmpty()) return;

        // heads too small for Steve's face are left transparent in the texture and drawn as a mini Steve head by a layer
        Set<Integer> miniIndices = SteveRemapper.miniHeadCubes(cubes);
        if (!miniIndices.isEmpty()) {
            Set<ModelPart.Cube> miniCubes = new HashSet<>();
            for (int i : miniIndices) miniCubes.add(scan.refs().get(i));
            attachMiniHeads(renderer, miniCubes);
        }

        int[] pixels = SteveRemapper.remap(cubes, w, h, stevePixels, steveW, steveH);
        NativeImage result = new NativeImage(w, h, true);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                result.setPixelRGBA(x, y, pixels[y * w + x]);
            }
        }
        mc.getTextureManager().register(texture, new DynamicTexture(result));
    }

    private static boolean loadSteve(ResourceManager resources) {
        if (stevePixels != null) return true;
        Optional<Resource> res = resources.getResource(STEVE_SKIN);
        if (res.isEmpty()) {
            LOGGER.error("Missing {}; mobs will stay vanilla", STEVE_SKIN);
            return false;
        }
        try (InputStream in = res.get().open(); NativeImage image = NativeImage.read(in)) {
            steveW = image.getWidth();
            steveH = image.getHeight();
            MiniHeadLayer.setSkinSize(steveW, steveH);
            int[] px = new int[steveW * steveH];
            for (int y = 0; y < steveH; y++) {
                for (int x = 0; x < steveW; x++) {
                    px[y * steveW + x] = image.getPixelRGBA(x, y);
                }
            }
            stevePixels = px;
            return true;
        } catch (Exception e) {
            LOGGER.error("Could not read {}", STEVE_SKIN, e);
            return false;
        }
    }
}
