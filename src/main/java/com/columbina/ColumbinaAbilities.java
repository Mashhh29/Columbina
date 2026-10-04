package com.columbina;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class ColumbinaAbilities {
    // Habilidad elemental: Eternal Tides
    private static final int SKILL_COOLDOWN = 17 * 20;
    private static final int SKILL_DURATION = 15 * 20;
    private static final int SKILL_HIT_EVERY = 2 * 20;   // ataca cada 2 s
    private static final double SKILL_RADIUS = 5.0;
    private static final float SKILL_DAMAGE = 4.0f;       // 2 corazones
    private static final int MAX_STACKS = 3;              // Gravity Interference
    private static final int STACK_DURATION = 10 * 20;

    // Ráfaga: Moonlit Melancholy
    private static final int BURST_COOLDOWN = 15 * 20;
    private static final int BURST_DURATION = 12 * 20;
    private static final double DOMAIN_RADIUS = 6.0;
    private static final float BURST_DAMAGE = 6.0f;
    private static final float DOMAIN_SKILL_BONUS = 1.5f;

    private static class Skill { int end; int nextHit; int stacks; int stackExpire; }
    private static class Domain { ServerWorld world; Vec3d pos; int end; }

    private static final Map<UUID, Skill> SKILLS = new HashMap<>();
    private static final Map<UUID, Domain> DOMAINS = new HashMap<>();
    private static final Map<UUID, Integer> SKILL_CD = new HashMap<>();
    private static final Map<UUID, Integer> BURST_CD = new HashMap<>();

    public static void clear() {
        SKILLS.clear(); DOMAINS.clear(); SKILL_CD.clear(); BURST_CD.clear();
    }

    public static boolean skill(ServerPlayerEntity p) {
        int now = p.getServer().getTicks();
        int cd = SKILL_CD.getOrDefault(p.getUuid(), 0);
        if (now < cd) {
            p.sendMessage(Text.literal("Eternal Tides en recarga: " + ((cd - now) / 20 + 1) + " s"), true);
            return false;
        }
        SKILL_CD.put(p.getUuid(), now + SKILL_COOLDOWN);
        Skill s = new Skill();
        s.end = now + SKILL_DURATION;
        s.nextHit = now;          // primer golpe inmediato
        SKILLS.put(p.getUuid(), s);
        ServerWorld w = p.getServerWorld();
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.PLAYERS, 1f, 1.2f);
        p.sendMessage(Text.literal("Eternal Tides"), true);
        return true;
    }

    public static boolean burst(ServerPlayerEntity p) {
        int now = p.getServer().getTicks();
        int cd = BURST_CD.getOrDefault(p.getUuid(), 0);
        if (now < cd) {
            p.sendMessage(Text.literal("Moonlit Melancholy en recarga: " + ((cd - now) / 20 + 1) + " s"), true);
            return false;
        }
        BURST_CD.put(p.getUuid(), now + BURST_COOLDOWN);
        ServerWorld w = p.getServerWorld();
        Domain d = new Domain();
        d.world = w;
        d.pos = p.getPos();
        d.end = now + BURST_DURATION;
        DOMAINS.put(p.getUuid(), d);

        // Golpe inicial de la ráfaga
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, boxAround(d.pos, DOMAIN_RADIUS),
                e -> e != p && e.isAlive() && e instanceof Monster && e.getPos().distanceTo(d.pos) <= DOMAIN_RADIUS)) {
            e.damage(w.getDamageSources().indirectMagic(p, p), BURST_DAMAGE);
        }
        ring(w, d.pos, DOMAIN_RADIUS, ParticleTypes.END_ROD, 0.2, 48, 0);
        w.spawnParticles(ParticleTypes.GLOW, d.pos.x, d.pos.y + 1, d.pos.z, 40, 2, 1, 2, 0.05);
        w.playSound(null, d.pos.x, d.pos.y, d.pos.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1f, 1.5f);
        p.sendMessage(Text.literal("Moonlit Melancholy"), true);
        return true;
    }

    private static boolean inDomain(ServerPlayerEntity p) {
        Domain d = DOMAINS.get(p.getUuid());
        return d != null && d.world == p.getServerWorld() && p.getPos().distanceTo(d.pos) <= DOMAIN_RADIUS;
    }

    public static void tick(MinecraftServer server) {
        int now = server.getTicks();

        // --- Habilidades activas ---
        Iterator<Map.Entry<UUID, Skill>> it = SKILLS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Skill> en = it.next();
            Skill s = en.getValue();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(en.getKey());
            if (p == null || !p.isAlive() || now >= s.end) { it.remove(); continue; }
            ServerWorld w = p.getServerWorld();

            if (s.stacks > 0 && now >= s.stackExpire) s.stacks = 0;

            if (now % 4 == 0) {
                double phase = now * 0.15;
                ring(w, p.getPos(), 3.0, ParticleTypes.SPLASH, 0.1, 10, phase);
                ring(w, p.getPos(), 2.0, ParticleTypes.FALLING_WATER, 2.5, 8, -phase);
            }

            if (now >= s.nextHit) {
                s.nextHit = now + SKILL_HIT_EVERY;
                // Gravity Interference: +1 acumulación (hasta 3), +5% de daño cada una
                s.stacks = Math.min(MAX_STACKS, s.stacks + 1);
                s.stackExpire = now + STACK_DURATION;

                float mult = 1f + 0.05f * s.stacks;
                if (inDomain(p)) mult *= DOMAIN_SKILL_BONUS;

                List<LivingEntity> targets = w.getEntitiesByClass(LivingEntity.class,
                        p.getBoundingBox().expand(SKILL_RADIUS),
                        e -> e != p && e.isAlive() && e instanceof Monster);
                for (LivingEntity e : targets) {
                    e.damage(w.getDamageSources().indirectMagic(p, p), SKILL_DAMAGE * mult);
                    w.spawnParticles(ParticleTypes.SPLASH, e.getX(), e.getY() + 1, e.getZ(), 15, 0.3, 0.5, 0.3, 0.1);
                }
                w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_PLAYER_SPLASH, SoundCategory.PLAYERS, 0.6f, 1.4f);
            }
        }

        // --- Dominios lunares activos ---
        Iterator<Map.Entry<UUID, Domain>> dit = DOMAINS.entrySet().iterator();
        while (dit.hasNext()) {
            Domain d = dit.next().getValue();
            if (now >= d.end) { dit.remove(); continue; }
            ServerWorld w = d.world;

            if (now % 8 == 0) {
                ring(w, d.pos, DOMAIN_RADIUS, ParticleTypes.END_ROD, 0.2, 36, now * 0.05);
                w.spawnParticles(ParticleTypes.GLOW, d.pos.x, d.pos.y + 0.5, d.pos.z, 4, 3, 0.3, 3, 0.01);
            }
            if (now % 20 == 0) {
                for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, boxAround(d.pos, DOMAIN_RADIUS),
                        e -> e.isAlive() && e.getPos().distanceTo(d.pos) <= DOMAIN_RADIUS)) {
                    if (e instanceof PlayerEntity) {
                        e.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 60, 0));
                        e.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 60, 0));
                    } else if (e instanceof Monster) {
                        e.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 60, 0));
                        e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 0));
                    }
                }
            }
        }
    }

    private static Box boxAround(Vec3d c, double r) {
        return new Box(c.add(-r, -r, -r), c.add(r, r, r));
    }

    private static void ring(ServerWorld w, Vec3d c, double r, ParticleEffect type, double dy, int n, double phase) {
        for (int i = 0; i < n; i++) {
            double a = phase + 2 * Math.PI * i / n;
            w.spawnParticles(type, c.x + Math.cos(a) * r, c.y + dy, c.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }
}
