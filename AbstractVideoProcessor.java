abstract class AbstractVideoProcessor extends Thread {
    public AbstractVideoProcessor() { setDaemon(true); }
    public abstract int  getVehicleCount();
    public abstract void stopProcessing();
}
