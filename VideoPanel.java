import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.*;

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

    private volatile String      pythonStatus = null;
    private volatile BufferedImage videoFrame  = null;

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

    public void setVideoFrame(BufferedImage frame) {
        this.videoFrame = frame;
        repaint();
    }

    public void clearVideoFrame() {
        this.videoFrame = null;
        repaint();
    }

    public void setPythonStatus(String msg) {
        this.pythonStatus = msg;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        // ── Video frame mode ──────────────────────────────────────────────────
        if (videoFrame != null) {
            int W = getWidth(), H = getHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(videoFrame, 0, 0, W, H, null);

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

        // ── Simulation mode ───────────────────────────────────────────────────
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int W = getWidth(), H = getHeight();

        g2.setPaint(new GradientPaint(0, 0, BG_TOP, 0, H * 2 / 5, BG_BTM));
        g2.fillRect(0, 0, W, H);

        g2.setColor(ROAD_CLR);
        g2.fillRect(0, H / 5, W, H);

        g2.setColor(KERB_CLR);
        g2.fillRect(0, H / 5,  W, 6);
        g2.fillRect(0, H - 30, W, 6);

        g2.setColor(LANE_CLR);
        g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                1f, new float[]{ 28f, 18f }, 0f));
        for (int ly : LANE_YS) {
            int sy = ly * H / 520;
            g2.drawLine(0, sy, W, sy);
        }
        g2.setStroke(new BasicStroke(1));

        boolean flash = lineFlash && (System.currentTimeMillis() - flashTime < 280);
        if (!flash) lineFlash = false;
        g2.setColor(flash ? LINE_HIT : LINE_NORM);
        g2.setStroke(new BasicStroke(3));
        g2.drawLine(COUNT_LINE_X, H / 5, COUNT_LINE_X, H);
        g2.setStroke(new BasicStroke(1));

        for (VehicleSprite v : vehicles) {
            v.draw(g2, vehicleCount);
        }

        if (running || vehicleCount > 0) {
            g2.setColor(new Color(0, 0, 0, 160));
            g2.fillRoundRect(12, 12, 290, 52, 10, 10);
            g2.setColor(HUD_TEXT);
            g2.setFont(new Font("Arial", Font.BOLD, 26));
            g2.drawString("VEHICLE COUNTER: " + vehicleCount, 18, 50);
        }

        if (!running && vehicleCount == 0) {
            g2.setColor(new Color(130, 130, 145));
            g2.setFont(new Font("Arial", Font.PLAIN, 15));
            String hint = "Enter your guess & bet, then press START";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(hint, (W - fm.stringWidth(hint)) / 2, H / 2 + 6);
        }

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
