import cv2
import numpy as np
import time
import random
import sys
import threading
from collections import deque

cctvLinks = [
    "https://camera.jtd.co.id/camera/share/tios/2/27/index.m3u8",
    "https://cctv.kkdm.co.id/api/cctv/1/1/index.m3u8",
    "https://jmlive.jasamarga.com/hls/2/05068266-1621-4265-8f95-2064804c1e88/index.m3u8"
]

pickedLink = cctvLinks[2]
cap = cv2.VideoCapture(pickedLink, cv2.CAP_FFMPEG)
cap.set(cv2.CAP_PROP_BUFFERSIZE, 3)
cap.set(cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, 10000)
cap.set(cv2.CAP_PROP_READ_TIMEOUT_MSEC, 5000)

DURATION = 10 
BUFFER_SECONDS = 5
TARGET_FPS = 10

if not cap.isOpened():
    print("Error: Could not open stream", file=sys.stderr)
    print("FINAL: 0", flush=True)
    exit()

print("Successfully loaded video stream", file=sys.stderr)
sys.stderr.flush()

frame_buffer = deque(maxlen=TARGET_FPS * 60) 
grab_running = True
frames_grabbed = 0


def grab_frames():
    """Continuously grab frames into the buffer."""
    global frames_grabbed
    while grab_running:
        ret, frame = cap.read()
        if ret:
            frame_buffer.append(frame)
            frames_grabbed += 1
        else:
            time.sleep(0.01)


grabber = threading.Thread(target=grab_frames, daemon=True)
grabber.start()

sys.stderr.flush()

buffer_start = time.time()
BUFFER_TIMEOUT = 20 
min_buffer = TARGET_FPS * BUFFER_SECONDS

while len(frame_buffer) < min_buffer:
    if time.time() - buffer_start > BUFFER_TIMEOUT:
        print("Buffer timeout — starting with partial buffer", file=sys.stderr)
        break
    time.sleep(0.1)

    blank = np.zeros((520, 900, 3), dtype=np.uint8)
    pct = len(frame_buffer) / (TARGET_FPS * BUFFER_SECONDS) * 100
    cv2.putText(blank, f"Buffering: {pct:.0f}%", (300, 260),
                cv2.FONT_HERSHEY_SIMPLEX, 1.2, (100, 100, 255), 2)
    cv2.imshow('Vehicle Detection', blank)
    cv2.waitKey(1)

print(f"Buffer ready ({len(frame_buffer)} frames). Starting detection.", file=sys.stderr)
sys.stderr.flush()

TARGET_W = 900
TARGET_H = 520
count_line_position = int(TARGET_H * 0.75)
min_width_react = int(TARGET_W * 0.04)
min_height_react = int(TARGET_H * 0.08)
offset = 15

algo = cv2.createBackgroundSubtractorMOG2(history=500,
    varThreshold=40,
    detectShadows=True)
algo.setShadowThreshold(0.5)

ROI_MARGIN_X = int(TARGET_W * 0.03)
ROI_MARGIN_TOP = int(TARGET_H * 0.10)
ROI_MARGIN_BOTTOM = int(TARGET_H * 0.05) 

def center_handle(x, y, w, h):
    return x + w // 2, y + h // 2

def is_in_roi(x, y, w, h):
    """Check if the detection is within the region of interest."""
    cx, cy = x + w // 2, y + h // 2
    if cx < ROI_MARGIN_X or cx > (TARGET_W - ROI_MARGIN_X):
        return False
    if cy < ROI_MARGIN_TOP or cy > (TARGET_H - ROI_MARGIN_BOTTOM):
        return False
    return True

detect = []
counter = 0
frame_interval = 1.0 / TARGET_FPS
frames_processed = 0
start = time.time()

while True:
    now = time.time()

    # Check duration
    if (now - start) >= DURATION:
        break

    # Get frame from buffer (OLDEST = delayed playback)
    if len(frame_buffer) == 0:
        # Buffer starved — wait briefly
        time.sleep(0.01)
        continue

    frame1 = frame_buffer.popleft()  # Process oldest (delayed) frame
    frames_processed += 1
    frame1 = cv2.resize(frame1, (TARGET_W, TARGET_H))

    # ─── Background subtraction & detection ───────────────────
    grey = cv2.cvtColor(frame1, cv2.COLOR_BGR2GRAY)
    blur = cv2.GaussianBlur(grey, (7, 7), 5)
    img_sub = algo.apply(blur)
    _, img_sub = cv2.threshold(img_sub, 200, 255, cv2.THRESH_BINARY)
    img_sub = cv2.erode(img_sub, np.ones((2, 2)), iterations=1)

    dilat = cv2.dilate(img_sub, np.ones((5, 5)), iterations=1)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
    dilatada = cv2.morphologyEx(dilat, cv2.MORPH_CLOSE, kernel)
    dilatada = cv2.morphologyEx(dilatada, cv2.MORPH_CLOSE, kernel)

    counterShape, _ = cv2.findContours(dilatada, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    cv2.line(frame1, (25, count_line_position),
             (TARGET_W - 25, count_line_position), (255, 127, 0), 3)

    for (_, c) in enumerate(counterShape):
        (x, y, w, h) = cv2.boundingRect(c)
        if not is_in_roi(x, y, w, h):
            continue
        if w < min_width_react or h < min_height_react:
            continue
        aspect_ratio = w / h
        if aspect_ratio > 4.0 or aspect_ratio < 0.25:
            continue
        cv2.rectangle(frame1, (x, y), (x + w, y + h), (0, 255, 0), 2)
        label_y = max(y - 10, 20)
        cv2.putText(frame1, "Vehicle", (x, label_y),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 244, 0), 2)
        center = center_handle(x, y, w, h)
        detect.append(center)
        cv2.circle(frame1, center, 4, (0, 0, 255), -1)

    # ─── Check line crossings ─────────────────────────────────
    crossed = False
    remaining = []
    for (cx, cy) in detect:
        if count_line_position - offset < cy < count_line_position + offset:
            counter += 1
            crossed = True
            print(counter, flush=True)
        else:
            remaining.append((cx, cy))
    detect = remaining

    if len(detect) > 200:
        detect = detect[-100:]

    # ─── Draw overlays ────────────────────────────────────────
    line_color = (0, 127, 255) if crossed else (255, 127, 0)
    cv2.line(frame1, (25, count_line_position),
             (TARGET_W, count_line_position), line_color, 3)

    # HUD - counter
    cv2.rectangle(frame1, (10, 10), (350, 60), (0, 0, 0), -1)
    cv2.putText(frame1, f"VEHICLES: {counter}",
                (20, 48), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 0, 255), 3)

    # HUD - timer
    elapsed = now - start
    remaining_time = max(0, DURATION - elapsed)
    cv2.rectangle(frame1, (TARGET_W - 200, 10), (TARGET_W - 10, 60), (0, 0, 0), -1)
    cv2.putText(frame1, f"Time: {remaining_time:.1f}s",
                (TARGET_W - 190, 48), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2)

    # HUD - buffer status
    cv2.rectangle(frame1, (10, TARGET_H - 35), (320, TARGET_H - 5), (0, 0, 0), -1)
    cv2.putText(frame1, f"Buffer: {len(frame_buffer)} frames",
                (15, TARGET_H - 12), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (150, 150, 150), 1)

    cv2.imshow('Vehicle Detection', frame1)

    if cv2.waitKey(1) == 13:
        break

    process_time = time.time() - now
    sleep_time = frame_interval - process_time
    if sleep_time > 0:
        time.sleep(sleep_time)

grab_running = False
cv2.destroyAllWindows()
cap.release()

print("FINAL:" + str(counter), flush=True)