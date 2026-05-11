import java.awt.*;
import javax.swing.*;
import javax.swing.border.*;

class GameWindow extends JFrame {

    private static final boolean PYTHON_AVAILABLE;
    static {
        boolean avail = false;
        for (String cmd : new String[]{"py", "python", "python3"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version")
                        .redirectErrorStream(true).start();
                if (p.waitFor() == 0) { avail = true; break; }
            } catch (Throwable ignored) {}
        }
        PYTHON_AVAILABLE = avail;
    }

    @SuppressWarnings("unused")
    private static final boolean OPENCV_AVAILABLE;
    static {
        boolean avail = false;
        try {
            Class<?> core = Class.forName("org.opencv.core.Core");
            String   lib  = (String) core.getField("NATIVE_LIBRARY_NAME").get(null);
            System.loadLibrary(lib);
            avail = true;
        } catch (Throwable ignored) {}
        OPENCV_AVAILABLE = avail;
    }

    // Dark theme palette
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
    private VideoPanel              videoPanel;

    private JLabel     balanceLabel;
    private JLabel     liveCountLabel;
    private JTextField guessField;
    private JTextField betField;
    private JButton    startBtn;
    private JButton    playAgainBtn;
    private JLabel     resultTitle;
    private JLabel     resultDetail;

    private int    pendingGuess;
    private double pendingBet;

    public GameWindow(Player player) {
        this.player = player;
        buildUI();
        startPreview();
    }

    // ── Preview lifecycle ─────────────────────────────────────────────────────

    private void startPreview() {
        if (!PYTHON_AVAILABLE) return;
        previewThread = new VideoPreviewThread(videoPanel);
        previewThread.start();
    }

    private void stopPreview() {
        if (previewThread != null) { previewThread.stopPreview(); previewThread = null; }
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        setTitle("Vehicle Detection Gambling Game");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        getContentPane().setBackground(BG_WINDOW);
        setLayout(new BorderLayout());

        add(buildHeader(), BorderLayout.NORTH);
        add(buildBody(),   BorderLayout.CENTER);

        pack();
        setMinimumSize(new Dimension(1180, 680));
        setLocationRelativeTo(null);
    }

    private JPanel buildHeader() {
        JPanel h = new JPanel(new BorderLayout(14, 0));
        h.setBackground(new Color(18, 17, 30));
        h.setBorder(new EmptyBorder(13, 18, 13, 18));

        JLabel title = label("VEHICLE DETECTION GAMBLING", TEXT_PRI, Font.BOLD, 21);

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

        body.add(videoPanel,     BorderLayout.CENTER);
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
        resultDetail = label(" ",      TEXT_SEC, Font.PLAIN, 12);
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

    // ── Game logic ────────────────────────────────────────────────────────────

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

        stopPreview();
        videoPanel.clearVideoFrame();

        processor = PYTHON_AVAILABLE
                ? new PythonVideoProcessor(videoPanel, this::onCountUpdate, this::onFinished)
                : new SimulatedVideoProcessor(videoPanel, this::onCountUpdate, this::onFinished);

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
        startPreview();
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

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
