import net.minecraft.client.Minecraft
import net.minecraft.util.EnumFacing


/**
 * A list of directions including diagonal directions
 * P = Plus
 * M = Minus
 */
enum class Direction(name: String) {
    XP("X-Plus"), XM("X-Minus"), ZP("Z-Plus"), ZM("Z-Minus"), XP_ZP("X-Plus, Z-Plus"), XM_ZP("X-Minus, Z-Plus"), XM_ZM("X-Minus, Z-Minus"), XP_ZM("X-Plus, Z-Minus");

    companion object {
        /**
         * Gets the direction the player is looking at
         */
        fun getDirection(): Direction {
            val facing = Minecraft.getMinecraft().player.horizontalFacing
            return if (facing == EnumFacing.NORTH) ZM else if (facing == EnumFacing.WEST) XM else if (facing == EnumFacing.SOUTH) ZP else XP
        }

        /**
         * Gets the closest diagonal direction player is looking at
         */
        fun getDiagonalDirection(): Direction {

            val facing = Minecraft.getMinecraft().player.horizontalFacing
            return if (facing == EnumFacing.NORTH) {
                val closest = getClosest(135.0, -135.0)
                if (closest == -135.0) XP_ZM else XM_ZM
            } else if (facing == EnumFacing.WEST) {
                val closest = getClosest(135.0, 45.0)
                if (closest == 135.0) XM_ZM else XM_ZP
            } else if (facing == EnumFacing.EAST) {
                val closest = getClosest(-45.0, -135.0)
                if (closest == -135.0) XP_ZM else XP_ZP
            } else {
                val closest = getClosest(45.0, -45.0)
                if (closest == 45.0) XM_ZP else XP_ZP
            }
        }

        //Returns the closer given yaw to the real yaw from a and b
        private fun getClosest(a: Double, b: Double): Double {
            var yaw = Minecraft.getMinecraft().player.rotationYaw.toDouble()
            yaw = if (yaw < -180) 360.let { yaw += it; yaw } else if (yaw > 180) 360.let { yaw -= it; yaw } else yaw
            return if (Math.abs(yaw - a) < Math.abs(yaw - b)) {
                a
            } else {
                b
            }
        }
    }
}