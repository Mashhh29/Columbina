package com.columbina;

import java.util.UUID;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * Movimiento estilo Genshin (solo cliente):
 *  - Estamina con barra en pantalla
 *  - Correr rápido (sprint) que gasta estamina
 *  - Escalar paredes: avanzar contra una pared = subir; agacharse = bajar; soltar = quedarse colgado
 *  - Planear: pulsar saltar otra vez en el aire
 * Tecla G = activar/desactivar.
 */
public class ColumbinaClient implements ClientModInitializer {
    // ---- Ajustes (cámbialos para probar) ----
    private static final float MAX_STAMINA = 100f;
    private static final float SPRINT_DRAIN = 0.4f;     // por tick
    private static final float CLIMB_UP_DRAIN = 0.5f;
    private static final float CLIMB_HOLD_DRAIN = 0.2f;
    private static final float CLIMB_DOWN_DRAIN = 0.1f;
    private static final float REGEN = 0.8f;            // por tick
    private static final int REGEN_DELAY = 20;          // ticks sin gastar antes de recuperar
    private static final float EXHAUST_RECOVER = 25f;   // estamina mínima para volver a usar
    private static final double SPRINT_BONUS = 0.35;    // +35% de velocidad
    private static final double CLIMB_SPEED = 0.14;
    private static final double GLIDE_SPEED_FWD = 0.30;
    private static final double GLIDE_SPEED_IDLE = 0.12;
    private static final double GLIDE_FALL = -0.02;

    private static final UUID SPRINT_UUID = UUID.fromString("6f1b3a52-9a7e-4c43-8d11-3b1c0d9e5a01");

    private static KeyBinding toggleKey;
    private static boolean enabled = true;
    private static float stamina = MAX_STAMINA;
    private static boolean exhausted = false;
    private static boolean climbing = false;
    private static boolean gliding = false;
    private static boolean wasJump = false;
    private static int idle = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.columbina.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.columbina"));
        ClientTickEvents.END_CLIENT_TICK.register(ColumbinaClient::tick);
        HudRenderCallback.EVENT.register(ColumbinaClient::hud);
    }

    private static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;

        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            p.sendMessage(Text.literal("Movimiento Genshin: " + (enabled ? "ACTIVADO" : "DESACTIVADO")), true);
            if (!enabled) resetState(p);
        }
        if (!enabled) return;

        if (p.isSpectator() || p.getAbilities().flying || p.isFallFlying()
                || p.isTouchingWater() || p.isInLava() || p.hasVehicle()) {
            resetState(p);
            return;
        }

        GameOptions o = mc.options;
        boolean fwd = o.forwardKey.isPressed();
        boolean jump = o.jumpKey.isPressed();
        boolean sneak = o.sneakKey.isPressed();
        boolean jumpEdge = jump && !wasJump;
        wasJump = jump;

        Vec3d v = p.getVelocity();
        float drain = 0f;
        double yaw = Math.toRadians(p.getYaw());
        double dirX = -Math.sin(yaw);
        double dirZ = Math.cos(yaw);

        // ---------- Escalar ----------
        boolean nearWall = !mc.world.isSpaceEmpty(p, p.getBoundingBox().expand(0.1, 0, 0.1));
        boolean climbNow = !exhausted && stamina > 0
                && ((fwd && p.horizontalCollision) || (climbing && nearWall))
                && !(p.isOnGround() && !fwd);
        boolean wasClimbing = climbing;

        if (climbNow) {
            climbing = true;
            gliding = false;
            double vy = fwd ? CLIMB_SPEED : (sneak ? -0.12 : 0.0);
            p.setVelocity(0, vy, 0);
            p.fallDistance = 0;
            drain = fwd ? CLIMB_UP_DRAIN : (sneak ? CLIMB_DOWN_DRAIN : CLIMB_HOLD_DRAIN);
        } else {
            climbing = false;
            // Impulso al llegar arriba de la pared
            if (wasClimbing && fwd && !exhausted && stamina > 0) {
                p.setVelocity(dirX * 0.2, 0.3, dirZ * 0.2);
            }

            // ---------- Planear ----------
            if (gliding) {
                if (p.isOnGround() || sneak) gliding = false;
            } else if (jumpEdge && !p.isOnGround()) {
                gliding = true;
            }
            if (gliding) {
                double s = fwd ? GLIDE_SPEED_FWD : GLIDE_SPEED_IDLE;
                p.setVelocity(dirX * s, Math.max(v.y, GLIDE_FALL), dirZ * s);
                p.fallDistance = 0;
            }
        }

        // ---------- Correr rápido ----------
        boolean moving = v.x * v.x + v.z * v.z > 0.0025;
        boolean sprinting = p.isSprinting() && !climbing && !gliding;
        setSprintBonus(p, sprinting && !exhausted && stamina > 0);
        if (sprinting && moving && !exhausted) drain = Math.max(drain, SPRINT_DRAIN);
        if (exhausted && p.isSprinting()) {
            p.setSprinting(false);
            o.sprintKey.setPressed(false);
        }

        // ---------- Estamina ----------
        if (drain > 0) {
            stamina = Math.max(0f, stamina - drain);
            idle = 0;
            if (stamina <= 0f) exhausted = true;
        } else {
            idle++;
            if (idle > REGEN_DELAY && p.isOnGround()) {
                stamina = Math.min(MAX_STAMINA, stamina + REGEN);
            }
        }
        if (exhausted && stamina >= EXHAUST_RECOVER) exhausted = false;
    }

    private static void resetState(ClientPlayerEntity p) {
        climbing = false;
        gliding = false;
        setSprintBonus(p, false);
    }

    private static void setSprintBonus(ClientPlayerEntity p, boolean on) {
        EntityAttributeInstance a = p.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (a == null) return;
        EntityAttributeModifier m = a.getModifier(SPRINT_UUID);
        if (on && m == null) {
            a.addTemporaryModifier(new EntityAttributeModifier(SPRINT_UUID, "columbina_sprint",
                    SPRINT_BONUS, EntityAttributeModifier.Operation.MULTIPLY_TOTAL));
        } else if (!on && m != null) {
            a.removeModifier(SPRINT_UUID);
        }
    }

    private static void hud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!enabled || mc.player == null || mc.options.hudHidden) return;
        if (stamina >= MAX_STAMINA - 0.5f && !gliding && !climbing) return;

        int w = mc.getWindow().getScaledWidth();
        int h = mc.getWindow().getScaledHeight();
        int bw = 80;
        int x = w / 2 - bw / 2;
        int y = h / 2 + 16;
        ctx.fill(x - 1, y - 1, x + bw + 1, y + 5, 0x99000000);
        int fill = (int) (bw * stamina / MAX_STAMINA);
        ctx.fill(x, y, x + fill, y + 4, exhausted ? 0xFFFF4444 : 0xFF8FE3C8);
    }
}
