package com.example.ee475project;

public class ImuData {
    public final float accelX;
    public final float accelY;
    public final float accelZ;
    public final float gyroX;
    public final float gyroY;
    public final float gyroZ;

    public ImuData(float accelX, float accelY, float accelZ, float gyroX, float gyroY, float gyroZ) {
        this.accelX = accelX;
        this.accelY = accelY;
        this.accelZ = accelZ;
        this.gyroX = gyroX;
        this.gyroY = gyroY;
        this.gyroZ = gyroZ;
    }

    @Override
    public String toString() {
        return "A: (" + accelX + ", " + accelY + ", " + accelZ + ")\n" +
               "G: (" + gyroX + ", " + gyroY + ", " + gyroZ + ")";
    }
}
