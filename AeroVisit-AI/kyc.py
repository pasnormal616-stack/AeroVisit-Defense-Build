"""
🪪 TWO-STEP KYC (100% local OpenCV, no hosted API)

POST /kyc/verify is the endpoint Spring Boot (KycService) calls: the browser sends a close-up photo of the ID alone
plus the liveness selfie. The card is cut from the close-up by contour detection and flattened with a perspective warp
(Java OCRs that crop with Tesseract), the ID portrait is compared 1:1 with the live face (OpenCV YuNet + SFace, ONNX
models in ./models), and the live face's 128-number vector is returned so Java can check it against the biometric
blacklist.

It never raises to the caller: every problem comes back as a normal 200 answer with a reason code, and Java turns any
answer it can't trust into PENDING_MANUAL_REVIEW (human-in-the-loop).

LEGACY: POST /kyc/extract-id (single selfie holding the ID, Haar face check) is the first version of this check. Java no
longer calls it; it is kept, with its tests, only because the card-finding helpers below are shared with /kyc/verify.
A Haar face check proves a face is in the photo, not that it is a live person.
"""
import base64
import binascii
import os
import threading

from fastapi import APIRouter
from pydantic import BaseModel

try:
    import cv2
    import numpy as np

    _FACE_CASCADE = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
    CV_READY = not _FACE_CASCADE.empty()
except Exception as cv_error:  # opencv-python-headless missing: the endpoint still answers, every selfie goes to manual review
    print(f"⚠️ KYC: OpenCV unavailable ({cv_error}); selfies will go to manual review.")
    cv2 = None
    np = None
    _FACE_CASCADE = None
    CV_READY = False

# Reason codes (Java logs them; anything but OK means manual review)
OK = "OK"
OPENCV_UNAVAILABLE = "OPENCV_UNAVAILABLE"
BAD_IMAGE = "BAD_IMAGE"
NO_FACE = "NO_FACE"
NO_ID_CARD = "NO_ID_CARD"
INTERNAL_ERROR = "INTERNAL_ERROR"

MAX_BASE64_CHARS = 22_000_000      # ~16 MB image, just above Spring's 15 MB upload limit
WORK_MAX_SIDE = 1600               # detection runs on a downscaled copy; the crop is cut from the full image
ID_ASPECT = 85.6 / 53.98           # ISO/IEC 7810 ID-1: PhilSys, driver's license, UMID, school/company IDs
ASPECT_MIN, ASPECT_MAX = 1.25, 2.0 # generous: the card is usually tilted toward the camera
MIN_CARD_AREA = 0.03               # share of the frame the card must cover
MAX_CARD_AREA = 0.85
MIN_RECTANGULARITY = 0.75          # contour area / its rotated bounding box (thumbs over a corner still pass)
FACE_VETO_SHARE = 0.12             # a face this big inside a candidate means it's a frame around the head, not a card
CROP_WIDTH = 1712                  # 2x ID-1 at 254 dpi; big enough for Tesseract
CROP_HEIGHT = round(CROP_WIDTH / ID_ASPECT)


def decode_image(image_b64):
    """Base64 (optionally a data: URL) -> BGR image, or None when it is not a readable picture."""
    if not image_b64 or len(image_b64) > MAX_BASE64_CHARS:
        return None
    if image_b64.startswith("data:"):
        image_b64 = image_b64.split(",", 1)[-1]
    try:
        raw = base64.b64decode(image_b64, validate=True)
    except (binascii.Error, ValueError):
        return None
    image = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None or image.shape[0] < 64 or image.shape[1] < 64:
        return None
    return image


def _downscale(image):
    """A copy whose longest side is at most WORK_MAX_SIDE, and the factor to map its points back."""
    height, width = image.shape[:2]
    scale = min(1.0, WORK_MAX_SIDE / max(height, width))
    if scale == 1.0:
        return image, 1.0
    return cv2.resize(image, (round(width * scale), round(height * scale)), interpolation=cv2.INTER_AREA), scale


def detect_faces(gray):
    """Face boxes (x, y, w, h). Faces smaller than 6% of the short side are ignored (background people, tiny noise)."""
    min_side = max(24, int(min(gray.shape[:2]) * 0.06))
    faces = _FACE_CASCADE.detectMultiScale(cv2.equalizeHist(gray), scaleFactor=1.1, minNeighbors=5,
                                           minSize=(min_side, min_side))
    return [tuple(int(v) for v in f) for f in faces]


_ROTATIONS = [None, cv2.ROTATE_90_CLOCKWISE, cv2.ROTATE_180, cv2.ROTATE_90_COUNTERCLOCKWISE] if cv2 else []


def upright_with_faces(image):
    """(image turned so a face is upright, faces found on the downscaled copy, scale). Faces empty when none in any turn."""
    for rotation in _ROTATIONS:
        turned = image if rotation is None else cv2.rotate(image, rotation)
        small, scale = _downscale(turned)
        faces = detect_faces(cv2.cvtColor(small, cv2.COLOR_BGR2GRAY))
        if faces:
            return turned, faces, scale
    small, scale = _downscale(image)
    return image, [], scale


def order_corners(points):
    """4 points -> top-left, top-right, bottom-right, bottom-left."""
    pts = np.asarray(points, dtype=np.float32).reshape(4, 2)
    total = pts.sum(axis=1)
    diff = np.diff(pts, axis=1).ravel()
    return np.array([pts[np.argmin(total)], pts[np.argmin(diff)], pts[np.argmax(total)], pts[np.argmax(diff)]],
                    dtype=np.float32)


def _edge_maps(gray):
    """A few binary maps so one bad lighting setup doesn't hide the card's outline."""
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    median = float(np.median(blurred))
    yield cv2.dilate(cv2.Canny(blurred, int(max(0, 0.66 * median)), int(min(255, 1.33 * median))), kernel)
    yield cv2.dilate(cv2.Canny(blurred, 30, 90), kernel)
    _, otsu = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    yield cv2.morphologyEx(otsu, cv2.MORPH_CLOSE, kernel)
    yield cv2.morphologyEx(255 - otsu, cv2.MORPH_CLOSE, kernel)


def _box_contains_point(quad, x, y):
    return cv2.pointPolygonTest(quad.reshape(-1, 1, 2), (float(x), float(y)), False) >= 0


def find_card_quad(small, faces):
    """Best card-shaped quadrilateral on the downscaled image, or None.

    A candidate must be ID-1 shaped, cover 3-85% of the frame and be close to a filled rectangle. It must NOT
    contain a face that covers more than FACE_VETO_SHARE of it: that is a frame around the visitor's head
    (door, monitor, the head-and-shoulders area). The small portrait printed on the ID stays allowed.
    """
    frame_area = small.shape[0] * small.shape[1]
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    face_boxes = [(x + w / 2, y + h / 2, w * h) for (x, y, w, h) in faces]
    best, best_score = None, 0.0

    for binary in _edge_maps(gray):
        contours, _ = cv2.findContours(binary, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        for contour in contours:
            area = cv2.contourArea(contour)
            if not (MIN_CARD_AREA * frame_area <= area <= MAX_CARD_AREA * frame_area):
                continue
            approx = cv2.approxPolyDP(contour, 0.02 * cv2.arcLength(contour, True), True)
            if len(approx) == 4 and cv2.isContourConvex(approx):
                quad = order_corners(approx)
            else:
                # Fingers over a corner give 5-8 points: fall back to the rotated bounding box
                quad = order_corners(cv2.boxPoints(cv2.minAreaRect(contour)))
            quad_area = cv2.contourArea(quad)
            if quad_area <= 0 or area / quad_area < MIN_RECTANGULARITY:
                continue
            width = (np.linalg.norm(quad[1] - quad[0]) + np.linalg.norm(quad[2] - quad[3])) / 2
            height = (np.linalg.norm(quad[3] - quad[0]) + np.linalg.norm(quad[2] - quad[1])) / 2
            if min(width, height) == 0:
                continue
            aspect = max(width, height) / min(width, height)
            if not (ASPECT_MIN <= aspect <= ASPECT_MAX):
                continue
            if any(face_area > FACE_VETO_SHARE * quad_area and _box_contains_point(quad, cx, cy)
                   for (cx, cy, face_area) in face_boxes):
                continue
            # Prefer the biggest, most ID-1-like, most rectangular candidate
            score = (quad_area / frame_area) * (1 - abs(aspect - ID_ASPECT) / ID_ASPECT) * (area / quad_area)
            if score > best_score:
                best, best_score = quad, score
    return best


def warp_card(image, quad):
    """Flatten the card to CROP_WIDTH x CROP_HEIGHT, turning portrait-held cards to landscape."""
    width = np.linalg.norm(quad[1] - quad[0])
    height = np.linalg.norm(quad[3] - quad[0])
    if height > width:  # card held upright: start from the bottom-left corner so text ends up horizontal
        quad = np.array([quad[3], quad[0], quad[1], quad[2]], dtype=np.float32)
    target = np.array([[0, 0], [CROP_WIDTH - 1, 0], [CROP_WIDTH - 1, CROP_HEIGHT - 1], [0, CROP_HEIGHT - 1]],
                      dtype=np.float32)
    return cv2.warpPerspective(image, cv2.getPerspectiveTransform(quad, target), (CROP_WIDTH, CROP_HEIGHT))


def analyze_selfie(image):
    """The whole check on a decoded BGR image -> response dict (see KycResponse)."""
    upright, faces, scale = upright_with_faces(image)
    small = upright if scale == 1.0 else cv2.resize(upright, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    quad = find_card_quad(small, faces)

    # The visitor's own face is a face OUTSIDE the card; the portrait printed on the ID doesn't count
    live_faces = [f for f in faces if quad is None or not _box_contains_point(quad, f[0] + f[2] / 2, f[1] + f[3] / 2)]
    crop_b64 = None
    if quad is not None:
        ok, png = cv2.imencode(".png", warp_card(upright, quad / scale))
        crop_b64 = base64.b64encode(png.tobytes()).decode("ascii") if ok else None

    if not live_faces:
        reason = NO_FACE
    elif crop_b64 is None:
        reason = NO_ID_CARD
    else:
        reason = OK
    return {"face_detected": bool(live_faces), "face_count": len(live_faces), "id_detected": crop_b64 is not None,
            "id_crop_b64": crop_b64, "reason": reason}


def _failure(reason):
    return {"face_detected": False, "face_count": 0, "id_detected": False, "id_crop_b64": None, "reason": reason}


class KycRequest(BaseModel):
    image_b64: str


router = APIRouter()


@router.post("/kyc/extract-id")
def extract_id(req: KycRequest):
    # Plain def: FastAPI runs it in a worker thread, so OpenCV work never blocks the chatbot's event loop
    if not CV_READY:
        return _failure(OPENCV_UNAVAILABLE)
    try:
        image = decode_image(req.image_b64)
        if image is None:
            return _failure(BAD_IMAGE)
        return analyze_selfie(image)
    except Exception as e:  # never a 500: Java treats any failure as "manual review"
        print(f"❌ KYC check failed: {e}")
        return _failure(INTERNAL_ERROR)


# =====================================================================================================================
# 🧬 TWO-STEP CAPTURE: 1:1 FACE MATCH (ID portrait vs live selfie) + live face vector, 100% local
# =====================================================================================================================
MODEL_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "models")
YUNET_MODEL = os.path.join(MODEL_DIR, "face_detection_yunet_2023mar.onnx")
SFACE_MODEL = os.path.join(MODEL_DIR, "face_recognition_sface_2021dec.onnx")

# OpenCV's published SFace threshold: cosine similarity >= 0.363 means the same person (Java uses the same number)
SFACE_COSINE_THRESHOLD = 0.363
FACE_SCORE_THRESHOLD = 0.8      # YuNet confidence for the live selfie (and for trusting an ID face enough to refuse)
FACE_WORK_MAX_SIDE = 1280       # faces are found on a copy this size; SFace itself only uses a 112 x 112 aligned crop
MIN_FACE_SIDE = 48              # px on that copy: smaller faces carry too little detail to compare
LIVE_FACE_MIN_SHARE = 0.12      # the live face must be at least this share of the selfie's short side
# The ID portrait is small and often under holograms or glare: it is searched on the flattened card crop at its full
# 1712 px (no longer shrunk to FACE_WORK_MAX_SIDE) with a lower YuNet threshold. A face found only thanks to that lower
# threshold is never trusted enough to refuse a visitor: a mismatch against it goes to manual review instead.
# Scratch experiment, 120 damaged synthetic ID portraits (glare, hologram print, blur, dim light, small cards) + 30 blank
# ones: this setting found 98.3% (was 91.7%) with 0 false faces on blank portraits. An extra 2x INTER_CUBIC upscale was
# tried and rejected: at 0.6 it "found" a face on 26 of 30 blank portraits and almost no face scored >= 0.8 any more.
ID_FACE_SCORE_THRESHOLD = 0.6

FACE_MODELS_UNAVAILABLE = "FACE_MODELS_UNAVAILABLE"
NO_ID_FACE = "NO_ID_FACE"
NO_LIVE_FACE = "NO_LIVE_FACE"
UNSURE_ID_FACE = "UNSURE_ID_FACE"
# HUMAN-IN-THE-LOOP: whenever a face can't be found (or only weakly), the booking is routed to staff, never refused

# --- 🧭 OUTCOME (one field Java routes on) ------------------------------------------------------------------------
SUCCESS = "SUCCESS"            # faces match -> normal approval queue (a human still approves)
POOR_QUALITY = "POOR_QUALITY"  # can't judge (no/weak face, blur, borderline score) -> manual review, never a refusal
MISMATCH = "MISMATCH"          # two sharp, confidently found faces that are clearly different people -> REJECTED
ERROR = "ERROR"                # system problem (no OpenCV/models, unreadable upload) -> manual review

# 🔎 BLUR GATE: a blurry face lowers SFace's similarity even for the SAME person, so it must never reach the mismatch
# rule. Measured on each face (not the whole photo: a plain wall behind a sharp face would read as "blurry"), resized
# to SFace's own 112 x 112 input, as the variance of the Laplacian. STARTING value, not yet calibrated on real phone
# photos: the readings come back as live_sharpness / id_sharpness, tune with KYC_MIN_FACE_SHARPNESS.
MIN_FACE_SHARPNESS = float(os.getenv("KYC_MIN_FACE_SHARPNESS", "40"))
# The ID portrait is a small PRINTED photo, enlarged to 112 x 112: it always reads far softer than a live face
# (real phone bookings: selfie 264-346, ID portrait 10-24). Its own threshold, so the selfie keeps the strict one.
# Defaults to the selfie value (unchanged behaviour); set KYC_MIN_ID_FACE_SHARPNESS to calibrate.
MIN_ID_FACE_SHARPNESS = float(os.getenv("KYC_MIN_ID_FACE_SHARPNESS", str(MIN_FACE_SHARPNESS)))
SHARPNESS_SIDE = 112
BLURRY_LIVE_FACE = "BLURRY_LIVE_FACE"
BLURRY_ID_FACE = "BLURRY_ID_FACE"
# Only a CLEAR mismatch is rejected automatically: a score between this and SFACE_COSINE_THRESHOLD (old ID photo,
# glasses, lighting) goes to a human instead
CLEAR_MISMATCH_BELOW = float(os.getenv("KYC_CLEAR_MISMATCH_BELOW", "0.25"))
BORDERLINE_MATCH = "BORDERLINE_MATCH"


def face_sharpness(image, row):
    """Variance of the Laplacian over one detected face (YuNet row on `image`), on a 112 x 112 grey copy."""
    x, y, w, h = (int(round(float(v))) for v in row[:4])
    height, width = image.shape[:2]
    x0, y0, x1, y1 = max(0, x), max(0, y), min(width, x + w), min(height, y + h)
    if x1 - x0 < 8 or y1 - y0 < 8:
        return 0.0
    face = cv2.cvtColor(image[y0:y1, x0:x1], cv2.COLOR_BGR2GRAY)
    shrink = (x1 - x0) >= SHARPNESS_SIDE
    face = cv2.resize(face, (SHARPNESS_SIDE, SHARPNESS_SIDE), interpolation=cv2.INTER_AREA if shrink else cv2.INTER_CUBIC)
    return float(cv2.Laplacian(face, cv2.CV_64F).var())
FACE_UNCLEAR_ERROR = "Face detection unclear - routing to manual review."

try:
    _FACE_DETECTOR = cv2.FaceDetectorYN.create(YUNET_MODEL, "", (320, 320), FACE_SCORE_THRESHOLD, 0.3, 5000)
    _FACE_RECOGNIZER = cv2.FaceRecognizerSF.create(SFACE_MODEL, "")
    FACE_READY = True
except Exception as face_error:  # models missing or OpenCV too old: every booking goes to manual review
    print(f"⚠️ KYC: face models unavailable ({face_error}); two-step bookings will go to manual review.")
    _FACE_DETECTOR = _FACE_RECOGNIZER = None
    FACE_READY = False

# One detector/recognizer pair shared by FastAPI's worker threads: setInputSize + detect must not interleave
_FACE_LOCK = threading.Lock()


def _faces_on(image, max_side=FACE_WORK_MAX_SIDE, score=FACE_SCORE_THRESHOLD):
    """YuNet rows (x, y, w, h, 5 landmarks, score) on a copy of at most max_side, biggest first,
    and that copy (SFace's alignCrop needs the same image the landmarks were found on)."""
    small, _ = _downscale_to(image, max_side)
    height, width = small.shape[:2]
    with _FACE_LOCK:
        _FACE_DETECTOR.setScoreThreshold(score)  # set on every call: the detector is shared by both searches
        _FACE_DETECTOR.setInputSize((width, height))
        _, found = _FACE_DETECTOR.detect(small)
    rows = [] if found is None else [row for row in found if min(row[2], row[3]) >= MIN_FACE_SIDE]
    rows.sort(key=lambda row: row[2] * row[3], reverse=True)
    return small, rows


def _downscale_to(image, max_side):
    height, width = image.shape[:2]
    scale = min(1.0, max_side / max(height, width))
    if scale == 1.0:
        return image, 1.0
    return cv2.resize(image, (round(width * scale), round(height * scale)), interpolation=cv2.INTER_AREA), scale


def _biggest_face_any_turn(image, accept=lambda img, row: True, max_side=FACE_WORK_MAX_SIDE, score=FACE_SCORE_THRESHOLD):
    """(image copy, face row) for the biggest accepted face, trying 0/90/180/270 degrees; (None, None) if none."""
    for rotation in _ROTATIONS:
        turned = image if rotation is None else cv2.rotate(image, rotation)
        small, rows = _faces_on(turned, max_side, score)
        for row in rows:
            if accept(small, row):
                return small, row
    return None, None


def _feature(image, row):
    """The face's SFace vector, scaled to length 1 so a plain dot product is the cosine similarity."""
    with _FACE_LOCK:
        vector = _FACE_RECOGNIZER.feature(_FACE_RECOGNIZER.alignCrop(image, row)).ravel().astype(np.float64)
    norm = np.linalg.norm(vector)
    return vector / norm if norm > 0 else vector


def _is_live_face(image, row):
    # The visitor's own face fills the oval; the small portrait on the ID they hold in step 2 is skipped
    return min(row[2], row[3]) >= LIVE_FACE_MIN_SHARE * min(image.shape[:2])


def find_id_face(card):
    """(image copy, face row) for the ID portrait on the flattened card, or (None, None): searched at the crop's full
    size (not shrunk to FACE_WORK_MAX_SIDE) at the lower ID threshold."""
    return _biggest_face_any_turn(card, max_side=max(card.shape[:2]), score=ID_FACE_SCORE_THRESHOLD)


def _unclear(result, reason):
    """Human-in-the-loop: a face that can't be found (or only weakly) sends the booking to staff, it never refuses it.
    Any live face vector already in result is kept, so Java can still check the biometric blacklist."""
    return {**result, "status": POOR_QUALITY, "reason": reason, "is_face_match": False, "face_match_error": FACE_UNCLEAR_ERROR}


def verify_two_step(id_image, live_image):
    """Both decoded BGR images -> response dict (see /kyc/verify)."""
    result = {"status": SUCCESS, "reason": OK, "id_detected": False, "id_crop_b64": None, "is_face_match": None,
              "face_similarity": None, "face_match_error": None, "live_face_encoding": None, "id_face_score": None,
              "live_sharpness": None, "id_sharpness": None}

    # 1. The live face first: the biggest face in the selfie, large enough to be the visitor rather than their ID photo.
    # Its vector is returned even when the ID side fails, so a banned face can't slip through by hiding the ID portrait.
    live_small, live_row = _biggest_face_any_turn(live_image, _is_live_face)
    live_vector = None if live_row is None else _feature(live_small, live_row)
    if live_vector is not None:
        result["live_face_encoding"] = [round(float(v), 6) for v in live_vector]
    live_sharp = None if live_row is None else face_sharpness(live_small, live_row)
    if live_sharp is not None:
        result["live_sharpness"] = round(live_sharp, 1)

    # 2. The card, from the ID-only close-up. No face veto here: nobody's head is in this photo.
    small, scale = _downscale(id_image)
    quad = find_card_quad(small, faces=[])
    if quad is None:
        return _unclear(result, NO_ID_CARD)
    card = warp_card(id_image, quad / scale)
    ok, png = cv2.imencode(".png", card)
    result.update(id_detected=bool(ok), id_crop_b64=base64.b64encode(png.tobytes()).decode("ascii") if ok else None)

    # 3. The ID portrait: only a face INSIDE the card counts, so a real head behind the card can never be used
    card_face_image, id_row = find_id_face(card)
    if id_row is None:
        return _unclear(result, NO_ID_FACE)
    result["id_face_score"] = round(float(id_row[14]), 3)
    if live_vector is None:
        return _unclear(result, NO_LIVE_FACE)

    # 4. Blur gate before any comparison: a blurry photo is an innocent mistake, never a mismatch
    id_sharp = face_sharpness(card_face_image, id_row)
    result["id_sharpness"] = round(id_sharp, 1)
    if live_sharp < MIN_FACE_SHARPNESS:
        return _unclear(result, BLURRY_LIVE_FACE)
    if id_sharp < MIN_ID_FACE_SHARPNESS:
        return _unclear(result, BLURRY_ID_FACE)

    similarity = float(np.dot(_feature(card_face_image, id_row), live_vector))
    result.update(is_face_match=similarity >= SFACE_COSINE_THRESHOLD, face_similarity=round(similarity, 4))
    if result["is_face_match"]:
        return {**result, "status": SUCCESS}
    # A mismatch is only trusted (and rejected by Java) when the ID face was found with full confidence AND the score is
    # clearly low; against a portrait found only at the lower threshold (glare, hologram), or with a score just under
    # the match line (old ID photo, glasses, lighting), it goes to manual review instead
    if id_row[14] < FACE_SCORE_THRESHOLD:
        return _unclear(result, UNSURE_ID_FACE)
    if similarity >= CLEAR_MISMATCH_BELOW:
        return _unclear(result, BORDERLINE_MATCH)
    return {**result, "status": MISMATCH}


def _two_step_failure(reason):
    return {"status": ERROR, "reason": reason, "id_detected": False, "id_crop_b64": None, "is_face_match": None,
            "face_similarity": None, "face_match_error": None, "live_face_encoding": None, "id_face_score": None,
            "live_sharpness": None, "id_sharpness": None}


class TwoStepRequest(BaseModel):
    high_res_id_image: str
    liveness_image: str


@router.post("/kyc/verify")
def verify(req: TwoStepRequest):
    # Never a 500. A system problem (no OpenCV, no models, unreadable upload) has no face_match_error, so Java sends
    # the booking to manual review; face_match_error is only set when a photo is readable but a face is missing.
    if not CV_READY:
        return _two_step_failure(OPENCV_UNAVAILABLE)
    if not FACE_READY:
        return _two_step_failure(FACE_MODELS_UNAVAILABLE)
    try:
        id_image = decode_image(req.high_res_id_image)
        live_image = decode_image(req.liveness_image)
        if id_image is None or live_image is None:
            return _two_step_failure(BAD_IMAGE)
        return verify_two_step(id_image, live_image)
    except Exception as e:
        print(f"❌ Two-step KYC check failed: {e}")
        return _two_step_failure(INTERNAL_ERROR)
