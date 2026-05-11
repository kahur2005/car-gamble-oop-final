import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

class PythonVideoProcessor extends AbstractVideoProcessor {

    private static final String SCRIPT = "vehicle.py";
    private static final String STATUS = "Python detection running — watch the Python window";

    private final VideoPanel    panel;
    private final Runnable      onCountUpdate;
    private final Runnable      onFinished;
    private volatile int        vehicleCount = 0;
    private final AtomicBoolean running      = new AtomicBoolean(false);
    private Process             process;

    public PythonVideoProcessor(VideoPanel panel, Runnable onCountUpdate, Runnable onFinished) {
        this.panel         = panel;
        this.onCountUpdate = onCountUpdate;
        this.onFinished    = onFinished;
    }

    @Override public int  getVehicleCount() { return vehicleCount; }

    @Override public void stopProcessing() {
        running.set(false);
        if (process != null) process.destroy();
    }

    private static String findPythonCommand() {
        for (String cmd : new String[]{"py", "python", "python3"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version")
                        .redirectErrorStream(true).start();
                if (p.waitFor() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        return null;
    }

    @Override
    public void run() {
        running.set(true);
        vehicleCount = 0;
        panel.setCount(0);
        panel.setRunning(true);
        panel.setPythonStatus(STATUS);

        try {
            String pythonCmd = findPythonCommand();
            if (pythonCmd == null) {
                System.err.println("[PythonVideoProcessor] No Python interpreter found.");
                panel.setRunning(false);
                panel.setPythonStatus(null);
                SwingUtilities.invokeLater(onFinished);
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(pythonCmd, SCRIPT);
            pb.directory(new File(System.getProperty("user.dir")));
            pb.redirectErrorStream(false);
            process = pb.start();

            // Drain stderr on a daemon thread so the subprocess never blocks on a full pipe
            Thread stderrDrain = new Thread(() -> {
                try (BufferedReader err = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String l;
                    while ((l = err.readLine()) != null)
                        System.err.println("[vehicle.py] " + l);
                } catch (Exception ignored) {}
            });
            stderrDrain.setDaemon(true);
            stderrDrain.start();

            System.out.println("[PythonVideoProcessor] started via " + pythonCmd);

            BufferedReader out = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = out.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("FINAL:")) {
                    try {
                        vehicleCount = Integer.parseInt(line.substring(6));
                        panel.setCount(vehicleCount);
                        SwingUtilities.invokeLater(onCountUpdate);
                    } catch (NumberFormatException ignored) {}
                } else {
                    try {
                        vehicleCount = Integer.parseInt(line);
                        panel.setCount(vehicleCount);
                        panel.flashLine();
                        SwingUtilities.invokeLater(onCountUpdate);
                    } catch (NumberFormatException ignored) {}
                }
            }

            process.waitFor();

        } catch (Exception e) {
            System.err.println("[PythonVideoProcessor] " + e.getMessage());
        }

        panel.setRunning(false);
        panel.setPythonStatus(null);
        SwingUtilities.invokeLater(onFinished);
    }
}
