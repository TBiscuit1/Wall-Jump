package genandnic.walljump.client;

import genandnic.walljump.Config;
import genandnic.walljump.WallJump;
import genandnic.walljump.network.PacketHandler;
import genandnic.walljump.network.message.MessageFallDistance;
import genandnic.walljump.network.message.MessageWallJump;
import genandnic.walljump.proxy.ClientProxy;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public class WallJumpLogic {

    public static int ticksWallClinged;
    private static int ticksKeyDown;
    private static double clingX, clingZ;
    private static double lastJumpY = Double.MAX_VALUE;

    private static final TagKey<Block> SLIPPERY = BlockTags.create(new ResourceLocation("mymod", "myitemgroup"));
    private static final TagKey<Block> NO_CLING = BlockTags.create(new ResourceLocation(WallJump.MOD_ID, "unclingable"));
    private static final TagKey<Block> NO_WALL_JUMP = BlockTags.create(new ResourceLocation(WallJump.MOD_ID, "no_wall_jump"));
    private static final TagKey<Block> WALL_JUMP = BlockTags.create(new ResourceLocation(WallJump.MOD_ID, "wall_jump"));
    private static final TagKey<Block> CAN_CLING = BlockTags.create(new ResourceLocation(WallJump.MOD_ID, "clingable"));
    private static final TagKey<Block> CAN_RE_CLING = BlockTags.create(new ResourceLocation(WallJump.MOD_ID, "reclingable"));

    public static void doWallJump(LocalPlayer pl) {

        Block block = pl.level.getBlockState(getWallPos(pl)).getBlock();

        if (pl.isOnGround() || pl.getAbilities().flying || pl.isInWater()) {

            ticksWallClinged = 0;
            clingX = Double.NaN;
            clingZ = Double.NaN;
            lastJumpY = Double.MAX_VALUE;
            staleWalls.clear();

            return;
        }

        WallJumpLogic.updateWalls(pl);
        ticksKeyDown = ClientProxy.KEY_WALLJUMP.isDown() ? ticksKeyDown + 1 : 0;

        if (ticksWallClinged < 1) {

            if (ticksKeyDown > 0 && ticksKeyDown < 4 && !walls.isEmpty() && canWallCling(pl, block)) {

                if (Config.COMMON.autoRotation.get())
                    pl.setYRot(getClingDirection().getOpposite().toYRot());

                ticksWallClinged = 1;
                clingX = pl.position().x;
                clingZ = pl.position().z;

                playHitSound(pl, getWallPos(pl));
                spawnWallParticle(pl, getWallPos(pl));
            }

            return;
        }

        if (!ClientProxy.KEY_WALLJUMP.isDown() || pl.isOnGround() || pl.isInWater() || walls.isEmpty() || pl.getFoodData().getFoodLevel() < 1) {

            ticksWallClinged = 0;

            if ((pl.input.forwardImpulse != 0 ||
                    pl.input.leftImpulse != 0) &&
                    !pl.isOnGround() &&
                    !walls.isEmpty() &&
                    canWallJump(pl, block)) {

                pl.fallDistance = 0.0F;
                PacketHandler.INSTANCE.sendToServer(new MessageWallJump());

                wallJump(pl, Config.COMMON.wallJumpHeight.get().floatValue());
                staleWalls = new HashSet<>(walls);

            }

            return;
        }

        pl.setPos(clingX, pl.position().y, clingZ);

        double motionY = pl.getDeltaMovement().y;
        if (checkBlockTag(SLIPPERY, block)) {
            motionY = Math.min(motionY, -0.1);
            spawnWallParticle(pl, getWallPos(pl));
        } else if (motionY > 0.0) {
            motionY = 0.0;
        } else if (motionY < -0.6) {
            motionY = motionY + 0.2;
            spawnWallParticle(pl, getWallPos(pl));
        } else if (ticksWallClinged++ > Config.COMMON.wallSlideDelay.get()) {
            motionY = -0.1;
            spawnWallParticle(pl, getWallPos(pl));
        } else {
            motionY = 0.0;
        }

        if (pl.fallDistance > 2) {
            pl.fallDistance = 0;
            PacketHandler.INSTANCE.sendToServer(new MessageFallDistance((float) (motionY * motionY * 8)));
        }

        pl.setDeltaMovement(0.0, motionY, 0.0);
    }

    private static boolean canWallJump(LocalPlayer pl, Block block) {

        if (checkBlockTag(WALL_JUMP, block))
            return true;

        if (checkBlockTag(NO_WALL_JUMP, block))
            return false;

        if (Config.COMMON.useWallJump.get()) return true;

        ItemStack stack = pl.getItemBySlot(EquipmentSlot.FEET);
        if (!stack.isEmpty()) {
            Map<Enchantment, Integer> enchantments = EnchantmentHelper.getEnchantments(stack);
            return enchantments.containsKey(WallJump.WALLJUMP_ENCHANT);
        }

        return false;
    }

    private static boolean canWallCling(LocalPlayer pl, Block block) {

        if (checkBlockTag(NO_CLING, block))
            return false;

        if (pl.onClimbable() || pl.getDeltaMovement().y > 0.1 || pl.getFoodData().getFoodLevel() < 1)
            return false;

        if (ClientProxy.collidesWithBlock(pl.level, pl.getBoundingBox().move(0, -0.8, 0))) return false;

        if (checkBlockTag(CAN_CLING, block) || checkBlockTag(CAN_RE_CLING, block)) return true;

        if (!Config.COMMON.useClinging.get()) {
            ItemStack stack = pl.getItemBySlot(EquipmentSlot.FEET);
            if (!stack.isEmpty()) {
                Map<Enchantment, Integer> enchantments = EnchantmentHelper.getEnchantments(stack);
                return enchantments.containsKey(WallJump.WALLJUMP_ENCHANT);
            }
        }

        if (Config.COMMON.allowReClinging.get()
                || pl.position().y < lastJumpY - 1
                || checkBlockTag(CAN_RE_CLING, block))
            return true;

        return !staleWalls.containsAll(walls);
    }

    private static Set<Direction> walls = new HashSet<>();
    private static Set<Direction> staleWalls = new HashSet<>();

    private static void updateWalls(LocalPlayer pl) {

        Vec3 pos = pl.position();
        AABB box = new AABB(pos.x - 0.001, pos.y, pos.z - 0.001, pos.x + 0.001, pos.y + pl.getEyeHeight(), pos.z + 0.001);

        double dist = (pl.getBbWidth() / 2) + (ticksWallClinged > 0 ? 0.1 : 0.06);
        AABB[] axes = {box.expandTowards(0, 0, dist), box.expandTowards(-dist, 0, 0), box.expandTowards(0, 0, -dist), box.expandTowards(dist, 0, 0)};

        int i = 0;
        Direction direction;
        WallJumpLogic.walls = new HashSet<>();
        for (AABB axis : axes) {
            direction = Direction.from2DDataValue(i++);
            if (ClientProxy.collidesWithBlock(pl.level, axis)) {
                walls.add(direction);
                pl.horizontalCollision = true;
            }
        }

    }

    private static Direction getClingDirection() {
        return walls.isEmpty() ? Direction.UP : walls.iterator().next();
    }

    private static BlockPos getWallPos(LocalPlayer player) {

        BlockPos pos = player.getOnPos().relative(getClingDirection());
        return player.level.getBlockState(pos).getMaterial().isSolid() ? pos : pos.relative(Direction.UP);

    }

    private static void wallJump(LocalPlayer pl, float up) {

        float strafe = Math.signum(pl.input.leftImpulse) * up * up;
        float forward = Math.signum(pl.input.forwardImpulse) * up * up;

        float f = (float) (1.0F / Math.sqrt(strafe * strafe + up * up + forward * forward));
        strafe = strafe * f;
        forward = forward * f;

        float f1 = (float) (Math.sin(pl.getYRot() * 0.017453292F) * 0.45f);
        float f2 = (float) (Math.cos(pl.getYRot() * 0.017453292F) * 0.45f);

        int jumpBoostLevel = 0;
        MobEffectInstance jumpBoostEffect = pl.getEffect(MobEffect.byId(8));
        if (jumpBoostEffect != null) jumpBoostLevel = jumpBoostEffect.getAmplifier() + 1;

        Vec3 motion = pl.getDeltaMovement();
        pl.setDeltaMovement(motion.x + (strafe * f2 - forward * f1), up + (jumpBoostLevel * .125), motion.z + (forward * f2 + strafe * f1));

        lastJumpY = pl.position().y;
        playBreakSound(pl, getWallPos(pl));
        spawnWallParticle(pl, getWallPos(pl));

    }

    private static void playHitSound(Entity entity, BlockPos pos) {

        BlockState state = entity.level.getBlockState(pos);
        SoundType soundtype = state.getBlock().getSoundType(state, entity.level, pos, entity);
        entity.playSound(soundtype.getHitSound(), soundtype.getVolume() * 0.25F, soundtype.getPitch());

    }

    private static void playBreakSound(Entity entity, BlockPos pos) {

        BlockState state = entity.level.getBlockState(pos);
        SoundType soundtype = state.getBlock().getSoundType(state, entity.level, pos, entity);
        entity.playSound(soundtype.getFallSound(), soundtype.getVolume() * 0.5F, soundtype.getPitch());

    }

    private static void spawnWallParticle(Entity entity, BlockPos blockPos) {

        BlockState state = entity.level.getBlockState(blockPos);
        if (state.getRenderShape() != RenderShape.INVISIBLE) {

            Vec3 pos = entity.position();
            Vec3i motion = getClingDirection().getNormal();

            entity.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state).setPos(blockPos), pos.x, pos.y,
                    pos.z, motion.getX() * -1.0D, -1.0D, motion.getZ() * -1.0D);

        }

    }

    private static boolean checkBlockTag(TagKey<Block> tag, Block block) {
        return ForgeRegistries.BLOCKS.tags().getTag(tag).contains(block);
    }

}
