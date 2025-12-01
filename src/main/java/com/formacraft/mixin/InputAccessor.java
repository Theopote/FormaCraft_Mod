package com.formacraft.mixin;

import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor 接口，用于访问 Input 类中的字段
 */
@Mixin(Input.class)
public interface InputAccessor {
    @Accessor
    void setMovementForward(float movementForward);
    
    @Accessor
    float getMovementForward();
    
    @Accessor
    void setMovementSideways(float movementSideways);
    
    @Accessor
    float getMovementSideways();
    
    @Accessor
    void setPlayerInput(PlayerInput playerInput);
    
    @Accessor
    PlayerInput getPlayerInput();
}

