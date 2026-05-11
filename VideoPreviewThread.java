import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;

class VideoPreviewThread extends Thread {

    // Inline Python: loop Video.mp4, encode each frame as JPEG, write to stdout
    // with a 4-byte big-endian length prefix so Java can frame the stream.
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
        try {
            String pythonCommand = findPythonCommand();
            if (pythonCommand == null) {
                System.err.println("[VideoPreview] No Python interpreter found!");
                return;
            }

            process = new ProcessBuilder(pythonCommand, "-c", SCRIPT)
                    .directory(new java.io.File(System.getProperty("user.dir")))
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
