import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;

// Common base for SimulatedVideoProcessor and OpenCVVideoProcessor.
// GameWindow holds an AbstractVideoProcessor reference so it works with either.
abstract class AbstractVideoProcessor extends Thread {
    public AbstractVideoProcessor() { setDaemon(true); }
    public abstract int  getVehicleCount();
    public abstract void stopProcessing();
}

class VehicleSprite {

    private double x;
    private final int y, w, h;
    private final Color bodyColor;
    private final double speed;
    private boolean counted;

    private static final int[]   WIDTHS  = { 110, 130, 90, 155, 100 };
    private static final int[]   HEIGHTS = {  52,  58, 46,  62,  50 };
    private static final Color[] PALETTE = {
        new Color(210, 55,  55),
        new Color(45,  115, 210),
        new Color(45,  170, 75),
        new Color(210, 170, 45),
        new Color(160, 70,  210),
        new Color(60,  190, 195),
        new Color(210, 120, 40),
    };
    private static final Random RNG = new Random();

    public VehicleSprite(int laneY) {
        int idx   = RNG.nextInt(WIDTHS.length);
        this.w    = WIDTHS[idx];
        this.h    = HEIGHTS[idx];
        this.x    = -w - 20;
        this.y    = laneY - h / 2;
        this.bodyColor = PALETTE[RNG.nextInt(PALETTE.length)];
        this.speed     = 2.2 + RNG.nextDouble() * 3.0;
        this.counted   = false;
    }

    public void move()              { x += speed; }
    public boolean isOffScreen()    { return x > 960; }
    public boolean isCounted()      { return counted; }
    public void markCounted()       { counted = true; }
    public int getCenterX()         { return (int)(x + w / 2.0); }
    public int getCenterY()         { return y + h / 2; }
    public int getLeft()            { return (int) x; }
    public int getTop()             { return y; }
    public int getWidth()           { return w; }
    public int getHeight()          { return h; }

    // Draws car body + OpenCV-style overlay (green box, yellow label, red dot)
    public void draw(Graphics2D g2, int vehicleCount) {
        int ix = (int) x;

        g2.setColor(bodyColor);
        g2.fillRoundRect(ix, y + h / 4, w, h * 3 / 4, 10, 10);
        g2.fillRoundRect(ix + w / 8, y + 2, w * 3 / 4, h / 2 + 2, 8, 8);

        g2.setColor(new Color(170, 225, 255, 190));
        g2.fillRoundRect(ix + w / 5,      y + 5, w * 3 / 10, h * 2 / 5, 4, 4);
        g2.fillRoundRect(ix + w * 9 / 16, y + 5, w * 3 / 10, h * 2 / 5, 4, 4);

        g2.setColor(new Color(30, 30, 30));
        int wr = h / 5;
        g2.fillOval(ix + w / 6       - wr, y + h * 3 / 4 - wr / 2, wr * 2, wr * 2);
        g2.fillOval(ix + w * 5 / 6   - wr, y + h * 3 / 4 - wr / 2, wr * 2, wr * 2);
        g2.setColor(new Color(80, 80, 80));
        g2.fillOval(ix + w / 6       - wr / 2, y + h * 3 / 4, wr, wr);
        g2.fillOval(ix + w * 5 / 6   - wr / 2, y + h * 3 / 4, wr, wr);

        // Green bounding box (cv2.rectangle equivalent)
        g2.setColor(new Color(0, 255, 0));
        g2.setStroke(new BasicStroke(2));
        g2.drawRect(ix - 4, y - 4, w + 8, h + 8);

        // Yellow counter label (cv2.putText equivalent)
        g2.setColor(new Color(255, 244, 0));
        g2.setFont(new Font("Arial", Font.BOLD, 11));
        g2.drawString("VEHICLE COUNTER: " + vehicleCount, ix, y - 8);

        // Red center dot (cv2.circle equivalent)
        g2.setColor(new Color(255, 0, 0));
        g2.fillOval(getCenterX() - 4, getCenterY() - 4, 8, 8);

        g2.setStroke(new BasicStroke(1));
    }
}

class Player {
    private final String name;
    private double balance;

    public Player(String name, double startBalance) {
        this.name    = name;
        this.balance = startBalance;
    }

    public String getName()    { return name; }
    public double getBalance() { return balance; }

    public boolean canAfford(double amount) {
        return amount >= 1.0 && amount <= balance;
    }

    public void win(double bet)  { balance += bet * 2.0; }
    public void lose(double bet) { balance -= bet; }
}

class VideoPanel extends JPanel {

    // Simulation mode — vertical counting line x-position
    static final int COUNT_LINE_X = 600;

    // Four lane centre Y positions
    static final int[] LANE_YS = { 165, 255, 345, 430 };

    private static final Color BG_TOP    = new Color(8,  12, 22);
    private static final Color BG_BTM    = new Color(15, 18, 30);
    private static final Color ROAD_CLR  = new Color(55, 58, 62);
    private static final Color KERB_CLR  = new Color(80, 82, 86);
    private static final Color LANE_CLR  = new Color(195, 185, 80, 110);
    private static final Color LINE_NORM = new Color(255, 127, 0);
    private static final Color LINE_HIT  = new Color(0,   127, 255);
    private static final Color HUD_TEXT  = new Color(0,   0,   255);

    private final List<VehicleSprite> vehicles = new CopyOnWriteArrayList<>();
    private volatile int     vehicleCount = 0;
    private volatile boolean lineFlash    = false;
    private long             flashTime    = 0;
    private volatile boolean running      = false;

    // Python mode — status overlay text while subprocess is running
    private volatile String pythonStatus = null;

    // OpenCV video mode — set by OpenCVVideoProcessor each frame
    private volatile BufferedImage videoFrame = null;

    public VideoPanel() {
        setBackground(BG_TOP);
        setPreferredSize(new Dimension(900, 520));
    }

    public List<VehicleSprite> getVehicles() { return vehicles; }
    public void setCount(int c)              { vehicleCount = c; }
    public void setRunning(boolean r)        { running = r; }

    public void flashLine() {
        lineFlash = true;
        flashTime = System.currentTimeMillis();
    }

    // Called by OpenCVVideoProcessor to push a processed frame
    public void setVideoFrame(BufferedImage frame) {
        this.videoFrame = frame;
        repaint();
    }

    // Called when starting a new round to clear the previous frame
    public void clearVideoFrame() {
        this.videoFrame = null;
        repaint();
    }

    // Python mode overlay — shown while the Python subprocess is running
    public void setPythonStatus(String msg) {
        this.pythonStatus = msg;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        // ── Video frame mode (preview loop or OpenCV overlay) ─────────────────
        if (videoFrame != null) {
            int W = getWidth(), H = getHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(videoFrame, 0, 0, W, H, null);

            // While idle (no round running) show a hint bar at the bottom
            if (!running) {
                g2.setColor(new Color(0, 0, 0, 170));
                g2.fillRect(0, H - 46, W, 46);
                g2.setColor(new Color(220, 220, 235));
                g2.setFont(new Font("Arial", Font.BOLD, 15));
                String hint = "Enter your guess & bet on the right, then press START DETECTION";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(hint, (W - fm.stringWidth(hint)) / 2, H - 16);
            }

            g2.dispose();
            return;
        }

        // Simulation mode 
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,    RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int W = getWidth(), H = getHeight();

        // Sky gradient
        g2.setPaint(new GradientPaint(0, 0, BG_TOP, 0, H * 2 / 5, BG_BTM));
        g2.fillRect(0, 0, W, H);

        // Road surface
        g2.setColor(ROAD_CLR);
        g2.fillRect(0, H / 5, W, H);

        // Kerb lines
        g2.setColor(KERB_CLR);
        g2.fillRect(0, H / 5,  W, 6);
        g2.fillRect(0, H - 30, W, 6);

        // Dashed lane markings
        g2.setColor(LANE_CLR);
        g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                1f, new float[]{ 28f, 18f }, 0f));
        for (int ly : LANE_YS) {
            int sy = ly * H / 520;
            g2.drawLine(0, sy, W, sy);
        }
        g2.setStroke(new BasicStroke(1));

        // Counting line (orange normally, blue flash on hit)
        boolean flash = lineFlash && (System.currentTimeMillis() - flashTime < 280);
        if (!flash) lineFlash = false;
        g2.setColor(flash ? LINE_HIT : LINE_NORM);
        g2.setStroke(new BasicStroke(3));
        g2.drawLine(COUNT_LINE_X, H / 5, COUNT_LINE_X, H);
        g2.setStroke(new BasicStroke(1));

        // Vehicles
        for (VehicleSprite v : vehicles) {
            v.draw(g2, vehicleCount);
        }

        // HUD counter
        if (running || vehicleCount > 0) {
            g2.setColor(new Color(0, 0, 0, 160));
            g2.fillRoundRect(12, 12, 290, 52, 10, 10);
            g2.setColor(HUD_TEXT);
            g2.setFont(new Font("Arial", Font.BOLD, 26));
            g2.drawString("VEHICLE COUNTER: " + vehicleCount, 18, 50);
        }

        // Idle hint
        if (!running && vehicleCount == 0) {
            g2.setColor(new Color(130, 130, 145));
            g2.setFont(new Font("Arial", Font.PLAIN, 15));
            String hint = "Enter your guess & bet, then press START";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(hint, (W - fm.stringWidth(hint)) / 2, H / 2 + 6);
        }

        // Python subprocess status banner
        if (pythonStatus != null) {
            g2.setFont(new Font("Arial", Font.BOLD, 14));
            FontMetrics fm = g2.getFontMetrics();
            int sw = fm.stringWidth(pythonStatus);
            int bx = (W - sw) / 2 - 14, by = H * 2 / 3 - 22;
            g2.setColor(new Color(0, 0, 0, 180));
            g2.fillRoundRect(bx, by, sw + 28, 34, 10, 10);
            g2.setColor(new Color(100, 220, 120));
            g2.drawString(pythonStatus, bx + 14, by + 22);
        }

        g2.dispose();
    }
}

class SimulatedVideoProcessor extends AbstractVideoProcessor {

    private static final int DURATION_MS    = 14_000;
    private static final int SPAWN_MIN_MS   = 550;
    private static final int SPAWN_RAND_MS  = 500;
    private static final int FRAME_DELAY_MS = 16;
    private static final Random RNG = new Random();

    private final VideoPanel panel;
    private final Runnable   onCountUpdate;
    private final Runnable   onFinished;
    private volatile int vehicleCount = 0;
    private final AtomicBoolean running = new AtomicBoolean(false);

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

        long start         = System.currentTimeMillis();
        long lastSpawn     = 0;
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

// Spawns "python vehicle.py" as a subprocess, reads its stdout, and updates the
// game with live crossing counts. When the video ends Python prints "FINAL:N"
// which is used as the authoritative count for win/lose decisions.
//
// vehicle.py handles all detection (MOG background subtraction, contours, etc.)
// exactly as the original Python script — Java only manages the subprocess I/O.
class PythonVideoProcessor extends AbstractVideoProcessor {

    private static final String SCRIPT  = "vehicle.py";
    private static final String STATUS  = "Python detection running — watch the Python window";

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

    @Override
    public void run() {
        running.set(true);
        vehicleCount = 0;
        panel.setCount(0);
        panel.setRunning(true);
        panel.setPythonStatus(STATUS);

        try {
            ProcessBuilder pb = new ProcessBuilder("py", SCRIPT);
            pb.directory(new File("."));
            pb.redirectErrorStream(false);   // keep stderr out of our count stream
            process = pb.start();

            System.out.println("running");

            BufferedReader out = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = out.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("FINAL:")) {
                    // Authoritative final count sent by vehicle.py at the very end
                    try {
                        vehicleCount = Integer.parseInt(line.substring(6));
                        panel.setCount(vehicleCount);
                        SwingUtilities.invokeLater(onCountUpdate);
                    } catch (NumberFormatException ignored) {}
                } else {
                    // Live count printed each time a vehicle crosses the line
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

class VideoPreviewThread extends Thread {

     private String findPythonCommand() {
        for (String cmd : new String[]{"py", "python", "python3"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version")
                        .redirectErrorStream(true).start();
                if (p.waitFor() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        return null;
    }

    // Inline Python: loop Video.mp4, encode each frame as JPEG, write to stdout
    private static final String SCRIPT =
        "import cv2,sys,struct,time\n" +
        "sys.stderr.write('Starting preview...\\n')\n" +
        "sys.stderr.flush()\n" +
        "cap=cv2.VideoCapture('Video.mp4')\n" +
        "if not cap.isOpened():\n" +
        " sys.stderr.write('ERROR: Cannot open stream\\n')\n" +
        " sys.stderr.flush()\n" +
        " sys.exit(1)\n" +
        "fps=cap.get(cv2.CAP_PROP_FPS)\n" +
        "if fps<=0 or fps>120: fps=25\n" +
        "delay=1.0/fps\n" +
        "sys.stderr.write(f'Stream opened, fps={fps}\\n')\n" +
        "sys.stderr.flush()\n" +
        "while True:\n" +
        " ret,frame=cap.read()\n" +
        " if not ret:\n" +
        "  cap.set(cv2.CAP_PROP_POS_FRAMES,0)\n" +
        "  time.sleep(0.1)\n" +
        "  continue\n" +
        " frame=cv2.resize(frame,(640,360))\n" +
        " ok,buf=cv2.imencode('.jpg',frame,[cv2.IMWRITE_JPEG_QUALITY,70])\n" +
        " if ok:\n" +
        "  data=buf.tobytes()\n" +
        "  sys.stdout.buffer.write(struct.pack('>I',len(data))+data)\n" +
        "  sys.stdout.buffer.flush()\n" +
        " time.sleep(delay)\n";

    private final VideoPanel panel;
    private volatile boolean active = true;
    private Process process;

    public VideoPreviewThread(VideoPanel panel) {
        this.panel = panel;
        setDaemon(true);
    }

    public void stopPreview() {
        active = false;
        if (process != null) process.destroy();
    }

    @Override
    public void run() {
        try {
            String pythonCommand = findPythonCommand();
            if (pythonCommand == null) {
                System.err.println("[VideoPreview] No Python interpreter found!");
                return;
            }

            process = new ProcessBuilder(pythonCommand, "-c", SCRIPT)
                    .redirectErrorStream(false)
                    .start();

            DataInputStream dis = new DataInputStream(
                    new BufferedInputStream(process.getInputStream(), 65536));

            while (active) {
                int size = dis.readInt();
                if (size <= 0 || size > 10_000_000) break;
                byte[] buf = new byte[size];
                dis.readFully(buf);
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(buf));
                if (img != null && active) panel.setVideoFrame(img);
            }
        } catch (Exception ignored) {
            // Normal path when stopPreview() destroys the process
        }
    }
}

class GameWindow extends JFrame {

    // In the static block, also try "python" and "python3"
    private static final boolean PYTHON_AVAILABLE;
    static {
        boolean avail = false;
        // Don't require vehicle.py for preview-only mode
        for (String cmd : new String[]{"py", "python", "python3"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version")
                        .redirectErrorStream(true).start();
                if (p.waitFor() == 0) { avail = true; break; }
            } catch (Throwable ignored) {}
        }
        PYTHON_AVAILABLE = avail;
    }

    private static final boolean OPENCV_AVAILABLE;
    static {
        boolean avail = false;
        try {
            Class<?> core = Class.forName("org.opencv.core.Core");
            String   lib  = (String) core.getField("NATIVE_LIBRARY_NAME").get(null);
            System.loadLibrary(lib);
            avail = true;
        } catch (Throwable ignored) { }
        OPENCV_AVAILABLE = avail;
    }

    // Colour palette (dark theme)
    private static final Color BG_WINDOW = new Color(13,  13,  20);
    private static final Color BG_PANEL  = new Color(22,  22,  35);
    private static final Color BG_CARD   = new Color(32,  30,  50);
    private static final Color ACCENT_G  = new Color(76,  175, 80);
    private static final Color ACCENT_R  = new Color(244, 67,  54);
    private static final Color ACCENT_B  = new Color(33,  150, 243);
    private static final Color ACCENT_Y  = new Color(255, 193, 7);
    private static final Color TEXT_PRI  = new Color(235, 235, 245);
    private static final Color TEXT_SEC  = new Color(148, 148, 165);
    private static final Color BORDER    = new Color(55,  55,  78);

    private final Player player;
    private AbstractVideoProcessor processor;
    private VideoPreviewThread      previewThread;
    private VideoPanel videoPanel;

    private JLabel    balanceLabel;
    private JLabel    liveCountLabel;
    private JTextField guessField;
    private JTextField betField;
    private JButton   startBtn;
    private JButton   playAgainBtn;
    private JLabel    resultTitle;
    private JLabel    resultDetail;

    private int    pendingGuess;
    private double pendingBet;

    public GameWindow(Player player) {
        this.player = player;
        buildUI();
        startPreview();
    }

    private void startPreview() {
        if (!PYTHON_AVAILABLE) return;
        previewThread = new VideoPreviewThread(videoPanel);
        previewThread.start();
    }

    private void stopPreview() {
        if (previewThread != null) { previewThread.stopPreview(); previewThread = null; }
    }


    private void buildUI() {
        setTitle("Vehicle Detection Gambling Game");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        getContentPane().setBackground(BG_WINDOW);
        setLayout(new BorderLayout());

        add(buildHeader(),  BorderLayout.NORTH);
        add(buildBody(),    BorderLayout.CENTER);

        pack();
        setMinimumSize(new Dimension(1180, 680));
        setLocationRelativeTo(null);
    }

    private JPanel buildHeader() {
        JPanel h = new JPanel(new BorderLayout(14, 0));
        h.setBackground(new Color(18, 17, 30));
        h.setBorder(new EmptyBorder(13, 18, 13, 18));

        JLabel title = label("VEHICLE DETECTION GAMBLING", TEXT_PRI, Font.BOLD, 21);

        // Mode badge — priority: Python > Simulation
        String modeText  = PYTHON_AVAILABLE ? "Python OpenCV Mode" : "Simulation Mode";
        Color  modeColor = PYTHON_AVAILABLE ? ACCENT_G : TEXT_SEC;
        JLabel modeLabel = label("[" + modeText + "]", modeColor, Font.BOLD, 13);

        balanceLabel = label("Balance:  $" + fmt(player.getBalance()), ACCENT_G, Font.BOLD, 19);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        right.setOpaque(false);
        right.add(modeLabel);
        right.add(balanceLabel);

        h.add(title, BorderLayout.WEST);
        h.add(right, BorderLayout.EAST);
        return h;
    }

    private JPanel buildBody() {
        JPanel body = new JPanel(new BorderLayout(12, 0));
        body.setBackground(BG_WINDOW);
        body.setBorder(new EmptyBorder(10, 12, 12, 12));

        videoPanel = new VideoPanel();
        videoPanel.setBorder(BorderFactory.createLineBorder(BORDER, 1));

        body.add(videoPanel,    BorderLayout.CENTER);
        body.add(buildSidebar(), BorderLayout.EAST);
        return body;
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBackground(BG_PANEL);
        sidebar.setPreferredSize(new Dimension(268, 0));
        sidebar.setBorder(new EmptyBorder(8, 8, 8, 8));

        sidebar.add(buildPlayerCard());
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(buildBetCard());
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(buildResultCard());
        sidebar.add(Box.createVerticalGlue());
        return sidebar;
    }

    private JPanel buildPlayerCard() {
        JPanel c = card("PLAYER INFO");
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));

        JLabel nameLabel = label("Player:  " + player.getName(), TEXT_PRI, Font.BOLD, 14);
        nameLabel.setAlignmentX(LEFT_ALIGNMENT);

        liveCountLabel = label("Detected:  0", ACCENT_Y, Font.BOLD, 14);
        liveCountLabel.setAlignmentX(LEFT_ALIGNMENT);

        c.add(nameLabel);
        c.add(Box.createVerticalStrut(6));
        c.add(liveCountLabel);
        return c;
    }

    private JPanel buildBetCard() {
        JPanel c = card("PLACE YOUR BET");
        c.setLayout(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.fill    = GridBagConstraints.HORIZONTAL;
        g.weightx = 1.0;
        g.insets  = new Insets(3, 0, 3, 0);

        g.gridy = 0; c.add(label("Your guess (number of vehicles):", TEXT_SEC, Font.PLAIN, 12), g);
        g.gridy = 1; guessField = field(); c.add(guessField, g);
        g.gridy = 2; c.add(label("Your bet  ($):", TEXT_SEC, Font.PLAIN, 12), g);
        g.gridy = 3; betField   = field(); c.add(betField,   g);

        g.gridy  = 4;
        g.insets = new Insets(12, 0, 0, 0);
        startBtn = btn("START DETECTION", ACCENT_B);
        startBtn.addActionListener(e -> onStart());
        c.add(startBtn, g);
        return c;
    }

    private JPanel buildResultCard() {
        JPanel c = card("RESULT");
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));

        resultTitle  = label("—", TEXT_SEC, Font.BOLD, 26);
        resultDetail = label(" ", TEXT_SEC, Font.PLAIN, 12);
        resultTitle .setAlignmentX(CENTER_ALIGNMENT);
        resultDetail.setAlignmentX(CENTER_ALIGNMENT);

        playAgainBtn = btn("PLAY AGAIN", ACCENT_G);
        playAgainBtn.setAlignmentX(CENTER_ALIGNMENT);
        playAgainBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        playAgainBtn.setVisible(false);
        playAgainBtn.addActionListener(e -> onNewRound());

        c.add(Box.createVerticalStrut(4));
        c.add(resultTitle);
        c.add(Box.createVerticalStrut(4));
        c.add(resultDetail);
        c.add(Box.createVerticalStrut(10));
        c.add(playAgainBtn);
        return c;
    }

    // Game logic 
    private void onStart() {
        int    guess;
        double bet;

        try {
            guess = Integer.parseInt(guessField.getText().trim());
            if (guess < 0) { err("Guess must be 0 or higher."); return; }
        } catch (NumberFormatException ex) {
            err("Enter a whole number for your guess."); return;
        }

        try {
            bet = Double.parseDouble(betField.getText().trim());
            if (!player.canAfford(bet)) {
                err(String.format("Bet must be between $1.00 and $%.2f.", player.getBalance()));
                return;
            }
        } catch (NumberFormatException ex) {
            err("Enter a valid dollar amount for your bet."); return;
        }

        pendingGuess = guess;
        pendingBet   = bet;

        guessField  .setEnabled(false);
        betField    .setEnabled(false);
        startBtn    .setEnabled(false);
        resultTitle .setText("—");
        resultTitle .setForeground(TEXT_SEC);
        resultDetail.setText("Detection running...");
        resultDetail.setForeground(TEXT_SEC);
        playAgainBtn.setVisible(false);

        stopPreview();          // stop the preview loop before detection begins
        videoPanel.clearVideoFrame();

        // Priority: Python detection (accurate) → Simulation (fallback)
        if (PYTHON_AVAILABLE) {
            processor = new PythonVideoProcessor(videoPanel, this::onCountUpdate, this::onFinished);
        } else {
            processor = new SimulatedVideoProcessor(videoPanel, this::onCountUpdate, this::onFinished);
        }

        processor.start();
    }

    private void onCountUpdate() {
        if (processor != null)
            liveCountLabel.setText("Detected:  " + processor.getVehicleCount());
    }

    private void onFinished() {
        int detected = (processor != null) ? processor.getVehicleCount() : 0;
        liveCountLabel.setText("Detected:  " + detected);

        if (pendingGuess == detected) {
            player.win(pendingBet);
            resultTitle .setText("YOU WIN!");
            resultTitle .setForeground(ACCENT_G);
            resultDetail.setText(String.format(
                "+$%.2f  |  Detected: %d  |  Guess: %d", pendingBet * 2, detected, pendingGuess));
            resultDetail.setForeground(ACCENT_G);
        } else {
            player.lose(pendingBet);
            resultTitle .setText("YOU LOSE");
            resultTitle .setForeground(ACCENT_R);
            resultDetail.setText(String.format(
                "-$%.2f  |  Detected: %d  |  Guess: %d", pendingBet, detected, pendingGuess));
            resultDetail.setForeground(ACCENT_R);
        }

        balanceLabel.setText("Balance:  $" + fmt(player.getBalance()));

        if (player.getBalance() < 1.0) {
            resultDetail.setText(resultDetail.getText() + "  —  Out of funds!");
            playAgainBtn.setEnabled(false);
        } else {
            playAgainBtn.setEnabled(true);
        }
        playAgainBtn.setVisible(true);
    }

    private void onNewRound() {
        guessField  .setEnabled(true);
        betField    .setEnabled(true);
        guessField  .setText("");
        betField    .setText("");
        startBtn    .setEnabled(true);
        resultTitle .setText("—");
        resultTitle .setForeground(TEXT_SEC);
        resultDetail.setText(" ");
        playAgainBtn.setVisible(false);
        liveCountLabel.setText("Detected:  0");
        videoPanel.setCount(0);
        videoPanel.clearVideoFrame();
        videoPanel.repaint();
        startPreview();         // resume video preview for next round
    }

    // UI helpers
    private JPanel card(String title) {
        JPanel p = new JPanel();
        p.setBackground(BG_CARD);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        TitledBorder tb = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(BORDER, 1), title,
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Arial", Font.BOLD, 11), TEXT_SEC);
        p.setBorder(BorderFactory.createCompoundBorder(tb, new EmptyBorder(6, 8, 8, 8)));
        return p;
    }

    private static JLabel label(String text, Color color, int style, int size) {
        JLabel l = new JLabel(text);
        l.setForeground(color);
        l.setFont(new Font("Arial", style, size));
        return l;
    }

    private JTextField field() {
        JTextField tf = new JTextField();
        tf.setBackground(new Color(44, 44, 62));
        tf.setForeground(TEXT_PRI);
        tf.setCaretColor(TEXT_PRI);
        tf.setFont(new Font("Arial", Font.PLAIN, 15));
        tf.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(7, 10, 7, 10)));
        return tf;
    }

    private JButton btn(String text, Color bg) {
        JButton b = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color c = !isEnabled()             ? bg.darker().darker()
                         : getModel().isPressed()  ? bg.darker()
                         : getModel().isRollover() ? bg.brighter()
                                                   : bg;
                g2.setColor(c);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.setColor(isEnabled() ? Color.WHITE : new Color(180, 180, 180));
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),
                    (getWidth()  - fm.stringWidth(getText())) / 2,
                    (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        };
        b.setFont(new Font("Arial", Font.BOLD, 13));
        b.setForeground(Color.WHITE);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(252, 42));
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        return b;
    }

    private void err(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Input Error", JOptionPane.WARNING_MESSAGE);
    }

    private static String fmt(double v) { return String.format("%.2f", v); }
}

// ── Entry point ───────────────────────────────────────────────────────────────
public class Vehicle {
    public static void main(String[] args) {
        String name = JOptionPane.showInputDialog(
                null,
                "Enter your name to start with $100.00:",
                "Vehicle Detection Gambling Game",
                JOptionPane.PLAIN_MESSAGE);

        if (name == null) return;
        if (name.trim().isEmpty()) name = "Player";

        Player player = new Player(name.trim(), 100.0);
        SwingUtilities.invokeLater(() -> new GameWindow(player).setVisible(true));
    }
}
