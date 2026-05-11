import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

class SimulatedVideoProcessor extends AbstractVideoProcessor {

    private static final int DURATION_MS    = 14_000;
    private static final int SPAWN_MIN_MS   = 550;
    private static final int SPAWN_RAND_MS  = 500;
    private static final int FRAME_DELAY_MS = 16;
    private static final Random RNG = new Random();

    private final VideoPanel panel;
    private final Runnable   onCountUpdate;
    private final Runnable   onFinished;
    private volatile int     vehicleCount = 0;
    private final AtomicBoolean running   = new AtomicBoolean(false);

    public SimulatedVideoProcessor(VideoPanel panel, Runnable onCountUpdate, Runnable onFinished) {
        this.panel         = panel;
        this.onCountUpdate = onCountUpdate;
        this.onFinished    = onFinished;
    }

    @Override public int  getVehicleCount() { return vehicleCount; }
    @Override public void stopProcessing()  { running.set(false); }

    @Override
    @SuppressWarnings("BusyWait")
    public void run() {
        running.set(true);
        vehicleCount = 0;
        panel.setCount(0);
        panel.setRunning(true);

        long start          = System.currentTimeMillis();
        long lastSpawn      = 0;
        long nextSpawnDelay = spawnDelay();

        while (running.get() && (System.currentTimeMillis() - start) < DURATION_MS) {
            long now = System.currentTimeMillis();

            if (now - lastSpawn >= nextSpawnDelay) {
                int lane = VideoPanel.LANE_YS[RNG.nextInt(VideoPanel.LANE_YS.length)];
                panel.getVehicles().add(new VehicleSprite(lane));
                lastSpawn      = now;
                nextSpawnDelay = spawnDelay();
            }

            List<VehicleSprite> toRemove = new ArrayList<>();
            for (VehicleSprite v : panel.getVehicles()) {
                v.move();
                if (!v.isCounted() && v.getCenterX() >= VideoPanel.COUNT_LINE_X) {
                    v.markCounted();
                    vehicleCount++;
                    panel.setCount(vehicleCount);
                    panel.flashLine();
                    SwingUtilities.invokeLater(onCountUpdate);
                }
                if (v.isOffScreen()) toRemove.add(v);
            }
            panel.getVehicles().removeAll(toRemove);
            panel.repaint();

            try { Thread.sleep(FRAME_DELAY_MS); }
            catch (InterruptedException e) { break; }
        }

        panel.getVehicles().clear();
        panel.setRunning(false);
        panel.repaint();
        SwingUtilities.invokeLater(onFinished);
    }

    private static long spawnDelay() {
        return SPAWN_MIN_MS + RNG.nextInt(SPAWN_RAND_MS);
    }
}
