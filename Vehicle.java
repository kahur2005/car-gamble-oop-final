import javax.swing.*;

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
