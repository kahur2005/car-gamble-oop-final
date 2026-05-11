import java.awt.*;
import java.util.Random;

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

    public void move()           { x += speed; }
    public boolean isOffScreen() { return x > 960; }
    public boolean isCounted()   { return counted; }
    public void markCounted()    { counted = true; }
    public int getCenterX()      { return (int)(x + w / 2.0); }
    public int getCenterY()      { return y + h / 2; }
    public int getLeft()         { return (int) x; }
    public int getTop()          { return y; }
    public int getWidth()        { return w; }
    public int getHeight()       { return h; }

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
        g2.fillOval(ix + w / 6     - wr, y + h * 3 / 4 - wr / 2, wr * 2, wr * 2);
        g2.fillOval(ix + w * 5 / 6 - wr, y + h * 3 / 4 - wr / 2, wr * 2, wr * 2);
        g2.setColor(new Color(80, 80, 80));
        g2.fillOval(ix + w / 6     - wr / 2, y + h * 3 / 4, wr, wr);
        g2.fillOval(ix + w * 5 / 6 - wr / 2, y + h * 3 / 4, wr, wr);

        // green bounding box
        g2.setColor(new Color(0, 255, 0));
        g2.setStroke(new BasicStroke(2));
        g2.drawRect(ix - 4, y - 4, w + 8, h + 8);

        // yellow counter label
        g2.setColor(new Color(255, 244, 0));
        g2.setFont(new Font("Arial", Font.BOLD, 11));
        g2.drawString("VEHICLE COUNTER: " + vehicleCount, ix, y - 8);

        // red center dot
        g2.setColor(new Color(255, 0, 0));
        g2.fillOval(getCenterX() - 4, getCenterY() - 4, 8, 8);

        g2.setStroke(new BasicStroke(1));
    }
}
