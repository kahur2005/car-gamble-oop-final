import cv2
import numpy as np
import time

cap = cv2.VideoCapture('Video.mp4')
DURATION = 10   # seconds

min_width_react  = 80   # min bounding-box width
min_height_react = 80   # min bounding-box height
count_line_position = 550
offset = 6              # allowable pixel tolerance around the line

algo = cv2.bgsegm.createBackgroundSubtractorMOG()

def center_handle(x, y, w, h):
    return x + w // 2, y + h // 2

detect  = []
counter = 0
start   = time.time()

while True:
    ret, frame1 = cap.read()
    if not ret or (time.time() - start) >= DURATION:
        break

    grey     = cv2.cvtColor(frame1, cv2.COLOR_BGR2GRAY)
    blur     = cv2.GaussianBlur(grey, (3, 3), 5)
    img_sub  = algo.apply(blur)
    dilat    = cv2.dilate(img_sub, np.ones((5, 5)))
    kernel   = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
    dilatada = cv2.morphologyEx(dilat,    cv2.MORPH_CLOSE, kernel)
    dilatada = cv2.morphologyEx(dilatada, cv2.MORPH_CLOSE, kernel)
    counterShape, _ = cv2.findContours(dilatada, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)

    cv2.line(frame1, (25, count_line_position), (1200, count_line_position), (255, 127, 0), 3)

    for (_, c) in enumerate(counterShape):
        (x, y, w, h) = cv2.boundingRect(c)
        if w < min_width_react or h < min_height_react:
            continue

        cv2.rectangle(frame1, (x, y), (x + w, y + h), (0, 255, 0), 2)
        cv2.putText(frame1, "VEHICLE COUNTER: " + str(counter),
                    (x, max(y - 20, 20)),
                    cv2.FONT_HERSHEY_TRIPLEX, 1, (255, 244, 0), 2)

        center = center_handle(x, y, w, h)
        detect.append(center)
        cv2.circle(frame1, center, 4, (0, 0, 255), -1)

    # Check crossings — build a new list keeping only uncrossed centers
    # (avoids the list-modification-during-iteration bug in the original)
    crossed  = False
    remaining = []
    for (cx, cy) in detect:
        if count_line_position - offset < cy < count_line_position + offset:
            counter += 1
            crossed = True
            # Print live count so Java sidebar can update
            print(counter, flush=True)
        else:
            remaining.append((cx, cy))
    detect = remaining

    # Prevent unbounded growth if vehicles never cross the line
    if len(detect) > 200:
        detect = detect[-100:]

    line_color = (0, 127, 255) if crossed else (255, 127, 0)
    cv2.line(frame1, (25, count_line_position), (1200, count_line_position), line_color, 3)

    cv2.putText(frame1, "VEHICLE COUNTER: " + str(counter),
                (450, 70), cv2.FONT_HERSHEY_SIMPLEX, 2, (0, 0, 255), 5)

    cv2.imshow('Vehicle Detection', frame1)

    if cv2.waitKey(1) == 13:   # press Enter to stop early
        break

cv2.destroyAllWindows()
cap.release()

# Send the final count to Java on its own clearly-prefixed line
print("FINAL:" + str(counter), flush=True)
