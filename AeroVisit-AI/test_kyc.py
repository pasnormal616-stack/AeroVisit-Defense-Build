"""
Tests for kyc.py on synthetic images (no real faces or IDs needed).
Run: PYTHONIOENCODING=utf-8 python -m unittest test_kyc
"""
import base64
import unittest
from unittest import mock

import cv2
import numpy as np

import kyc

NAME_LINE = "DELA CRUZ, JUAN"


def make_card(width=856, height=540):
    """A plain ID-1 card: light blue, a portrait box and three text lines."""
    card = np.full((height, width, 3), (235, 215, 190), dtype=np.uint8)
    cv2.rectangle(card, (40, 120), (240, 420), (150, 150, 150), -1)
    cv2.putText(card, "REPUBLIC OF THE PHILIPPINES", (40, 70), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (60, 30, 0), 3)
    cv2.putText(card, NAME_LINE, (280, 220), cv2.FONT_HERSHEY_SIMPLEX, 1.4, (0, 0, 0), 3)
    cv2.putText(card, "ID NO 1234-5678", (280, 320), cv2.FONT_HERSHEY_SIMPLEX, 1.1, (0, 0, 0), 2)
    return card


def paste_card(background, top_left, card_width, tilt=0.06, thumb=True):
    """Warp the card onto the background with a little perspective and (optionally) a thumb over one corner."""
    card = make_card()
    h, w = card.shape[:2]
    x, y = top_left
    card_height = card_width * h / w
    dx = card_width * tilt
    dst = np.float32([[x + dx, y], [x + card_width, y + dx], [x + card_width - dx, y + card_height],
                      [x, y + card_height - dx]])
    m = cv2.getPerspectiveTransform(np.float32([[0, 0], [w, 0], [w, h], [0, h]]), dst)
    size = (background.shape[1], background.shape[0])
    warped = cv2.warpPerspective(card, m, size)
    mask = cv2.warpPerspective(np.full((h, w), 255, np.uint8), m, size)
    out = background.copy()
    out[mask > 0] = warped[mask > 0]
    if thumb:
        cv2.ellipse(out, (int(x + 10), int(y + card_height - 20)), (45, 70), 30, 0, 360, (120, 160, 210), -1)
    return out


def noisy_background(width=1400, height=1100, seed=3):
    rng = np.random.default_rng(seed)
    base = np.full((height, width, 3), 95, dtype=np.uint8)
    noise = rng.integers(0, 25, size=base.shape, dtype=np.uint8)
    return cv2.add(base, noise)


def to_b64(image, ext=".jpg"):
    ok, data = cv2.imencode(ext, image)
    assert ok
    return base64.b64encode(data.tobytes()).decode("ascii")


class DecodeTest(unittest.TestCase):

    def test_garbage_and_empty_input_are_not_images(self):
        self.assertIsNone(kyc.decode_image(""))
        self.assertIsNone(kyc.decode_image("not base64 !!!"))
        self.assertIsNone(kyc.decode_image(base64.b64encode(b"hello, not a jpeg").decode()))

    def test_tiny_and_oversized_input_are_refused(self):
        self.assertIsNone(kyc.decode_image(to_b64(np.zeros((20, 20, 3), np.uint8))))
        self.assertIsNone(kyc.decode_image("A" * (kyc.MAX_BASE64_CHARS + 4)))

    def test_data_url_prefix_is_accepted(self):
        image = kyc.decode_image("data:image/png;base64," + to_b64(noisy_background(200, 150), ".png"))
        self.assertEqual(image.shape[:2], (150, 200))


class CardDetectionTest(unittest.TestCase):

    def test_tilted_card_with_thumb_over_corner_is_found_and_flattened(self):
        scene = paste_card(noisy_background(), (650, 450), 600)
        quad = kyc.find_card_quad(scene, faces=[])
        self.assertIsNotNone(quad)
        crop = kyc.warp_card(scene, quad)
        self.assertEqual(crop.shape[:2], (kyc.CROP_HEIGHT, kyc.CROP_WIDTH))
        # Flattened card is mostly the card's light blue, not the gray background
        self.assertGreater(float(crop[..., 0].mean()), 170)

    def test_no_card_in_frame(self):
        self.assertIsNone(kyc.find_card_quad(noisy_background(), faces=[]))

    def test_a_box_around_the_face_is_not_taken_for_the_card(self):
        scene = paste_card(noisy_background(), (400, 300), 600, thumb=False)
        quad = kyc.find_card_quad(scene, faces=[(600, 380, 200, 200)])  # a big face inside that "card": a frame, not an ID
        self.assertIsNone(quad)

    def test_portrait_held_card_comes_out_landscape(self):
        scene = cv2.rotate(paste_card(noisy_background(), (650, 450), 600), cv2.ROTATE_90_CLOCKWISE)
        quad = kyc.find_card_quad(scene, faces=[])
        self.assertIsNotNone(quad)
        crop = kyc.warp_card(scene, quad)
        self.assertEqual(crop.shape[:2], (kyc.CROP_HEIGHT, kyc.CROP_WIDTH))


class AnalyzeTest(unittest.TestCase):

    def test_card_but_no_face_is_no_face(self):
        result = kyc.analyze_selfie(paste_card(noisy_background(), (650, 450), 600))
        self.assertEqual(result["reason"], kyc.NO_FACE)
        self.assertFalse(result["face_detected"])
        self.assertTrue(result["id_detected"])

    def test_face_and_card_is_ok(self):
        scene = paste_card(noisy_background(), (650, 450), 600)
        with mock.patch.object(kyc, "detect_faces", return_value=[(150, 150, 200, 200)]):
            result = kyc.analyze_selfie(scene)
        self.assertEqual(result["reason"], kyc.OK)
        png = cv2.imdecode(np.frombuffer(base64.b64decode(result["id_crop_b64"]), np.uint8), cv2.IMREAD_COLOR)
        self.assertEqual(png.shape[:2], (kyc.CROP_HEIGHT, kyc.CROP_WIDTH))

    def test_face_without_card_is_no_id_card(self):
        with mock.patch.object(kyc, "detect_faces", return_value=[(150, 150, 200, 200)]):
            result = kyc.analyze_selfie(noisy_background())
        self.assertEqual(result["reason"], kyc.NO_ID_CARD)
        self.assertIsNone(result["id_crop_b64"])

    def test_small_portrait_inside_the_card_does_not_hide_the_card(self):
        scene = paste_card(noisy_background(), (650, 450), 600)
        self.assertIsNotNone(kyc.find_card_quad(scene, faces=[(700, 560, 60, 60)]))

    def test_portrait_on_the_card_is_not_the_visitors_face(self):
        scene = paste_card(noisy_background(), (650, 450), 600)
        with mock.patch.object(kyc, "detect_faces", return_value=[(700, 560, 60, 60)]):  # inside the card
            result = kyc.analyze_selfie(scene)
        self.assertEqual(result["reason"], kyc.NO_FACE)


class EndpointTest(unittest.TestCase):

    def test_bad_image_is_a_normal_answer(self):
        self.assertEqual(kyc.extract_id(kyc.KycRequest(image_b64="nope"))["reason"], kyc.BAD_IMAGE)

    def test_unexpected_error_is_a_normal_answer(self):
        with mock.patch.object(kyc, "analyze_selfie", side_effect=RuntimeError("boom")):
            result = kyc.extract_id(kyc.KycRequest(image_b64=to_b64(noisy_background(300, 200))))
        self.assertEqual(result["reason"], kyc.INTERNAL_ERROR)
        self.assertFalse(result["id_detected"])

    def test_without_opencv_every_selfie_goes_to_review(self):
        with mock.patch.object(kyc, "CV_READY", False):
            self.assertEqual(kyc.extract_id(kyc.KycRequest(image_b64="x"))["reason"], kyc.OPENCV_UNAVAILABLE)


# --- Two-step capture: POST /kyc/verify (ID close-up + liveness selfie) ---

def unit_vector(seed):
    v = np.random.default_rng(seed).normal(size=128)
    return v / np.linalg.norm(v)


def two_step(id_image, live_image):
    return kyc.verify(kyc.TwoStepRequest(high_res_id_image=to_b64(id_image), liveness_image=to_b64(live_image)))


class TwoStepEndpointTest(unittest.TestCase):
    """Face finding and SFace are mocked here (synthetic images have no real faces); the card finder is real."""

    def setUp(self):
        self.id_closeup = paste_card(noisy_background(), (300, 250), 900, thumb=False)
        self.selfie = noisy_background(1280, 720, seed=5)

    def faces(self, id_found=True, live_found=True, id_score=0.95):
        self.searches = []

        def biggest(image, accept=lambda img, row: True, max_side=kyc.FACE_WORK_MAX_SIDE, score=kyc.FACE_SCORE_THRESHOLD):
            is_card = image.shape[:2] == (kyc.CROP_HEIGHT, kyc.CROP_WIDTH)  # the flattened card crop
            self.searches.append(("card" if is_card else "selfie", max_side, score))
            row = np.zeros(15, np.float32)
            row[14] = id_score if is_card else 0.95  # YuNet's confidence
            found = id_found if is_card else live_found
            return (image, row) if found else (None, None)
        return mock.patch.object(kyc, "_biggest_face_any_turn", side_effect=biggest)

    def features(self, id_vector, live_vector):
        # The live face is measured first (its vector is needed even when the ID side fails), then the ID face
        return mock.patch.object(kyc, "_feature", side_effect=[v for v in (live_vector, id_vector) if v is not None])

    def test_same_face_matches_and_returns_the_live_vector_and_the_card_crop(self):
        v = unit_vector(1)
        with mock.patch.object(kyc, "FACE_READY", True), self.faces(), self.features(v, v):
            result = two_step(self.id_closeup, self.selfie)
        self.assertEqual(result["reason"], kyc.OK)
        self.assertIs(result["is_face_match"], True)
        self.assertAlmostEqual(result["face_similarity"], 1.0, places=3)
        self.assertEqual(len(result["live_face_encoding"]), 128)
        self.assertIsNone(result["face_match_error"])
        crop = cv2.imdecode(np.frombuffer(base64.b64decode(result["id_crop_b64"]), np.uint8), cv2.IMREAD_COLOR)
        self.assertEqual(crop.shape[:2], (kyc.CROP_HEIGHT, kyc.CROP_WIDTH))

    def test_a_different_face_does_not_match_but_still_returns_the_vector(self):
        with mock.patch.object(kyc, "FACE_READY", True), self.faces(), self.features(unit_vector(1), unit_vector(2)):
            result = two_step(self.id_closeup, self.selfie)
        self.assertIs(result["is_face_match"], False)
        self.assertLess(result["face_similarity"], kyc.SFACE_COSINE_THRESHOLD)
        self.assertEqual(len(result["live_face_encoding"]), 128)

    def test_the_threshold_is_cosine_0_363(self):
        a = np.zeros(128); a[0] = 1.0
        just_over = np.zeros(128); just_over[0] = 0.364; just_over[1] = np.sqrt(1 - 0.364 ** 2)
        just_under = np.zeros(128); just_under[0] = 0.362; just_under[1] = np.sqrt(1 - 0.362 ** 2)
        with mock.patch.object(kyc, "FACE_READY", True), self.faces(), self.features(a, just_over):
            self.assertIs(two_step(self.id_closeup, self.selfie)["is_face_match"], True)
        with mock.patch.object(kyc, "FACE_READY", True), self.faces(), self.features(a, just_under):
            self.assertIs(two_step(self.id_closeup, self.selfie)["is_face_match"], False)

    # --- Graceful degradation: an unclear face routes to manual review (Java), never a refusal ---

    def assertUnclear(self, result, reason):
        self.assertEqual(result["reason"], reason)
        self.assertIs(result["is_face_match"], False)
        self.assertEqual(result["face_match_error"], "Face detection unclear - routing to manual review.")

    def test_no_face_on_the_id_is_unclear_and_keeps_the_live_vector_for_the_blacklist(self):
        with mock.patch.object(kyc, "FACE_READY", True), self.faces(id_found=False), self.features(None, unit_vector(4)):
            result = two_step(self.id_closeup, self.selfie)
        self.assertUnclear(result, kyc.NO_ID_FACE)
        # A banned person can't dodge the biometric blacklist by hiding the ID portrait under glare
        self.assertEqual(len(result["live_face_encoding"]), 128)

    def test_no_live_face_is_unclear(self):
        with mock.patch.object(kyc, "FACE_READY", True), self.faces(live_found=False), self.features(None, None):
            result = two_step(self.id_closeup, self.selfie)
        self.assertUnclear(result, kyc.NO_LIVE_FACE)
        self.assertIsNone(result["live_face_encoding"])

    def test_no_card_in_the_id_photo_is_unclear(self):
        # Only a face INSIDE the card may be the ID face, so without a card nothing can be compared
        with mock.patch.object(kyc, "FACE_READY", True), self.faces(), self.features(None, unit_vector(4)):
            result = two_step(noisy_background(), self.selfie)
        self.assertUnclear(result, kyc.NO_ID_CARD)
        self.assertFalse(result["id_detected"])
        self.assertEqual(len(result["live_face_encoding"]), 128)

    def test_a_mismatch_against_a_weakly_detected_id_face_is_unclear_not_a_mismatch(self):
        # Found only thanks to the lower ID threshold (glare, hologram): too unsure to refuse anyone
        with mock.patch.object(kyc, "FACE_READY", True), self.faces(id_score=0.65), \
                self.features(unit_vector(1), unit_vector(2)):
            result = two_step(self.id_closeup, self.selfie)
        self.assertUnclear(result, kyc.UNSURE_ID_FACE)
        self.assertEqual(result["id_face_score"], 0.65)
        self.assertEqual(len(result["live_face_encoding"]), 128)

    def test_a_match_against_a_weakly_detected_id_face_still_counts(self):
        v = unit_vector(1)
        with mock.patch.object(kyc, "FACE_READY", True), self.faces(id_score=0.65), self.features(v, v):
            result = two_step(self.id_closeup, self.selfie)
        self.assertEqual(result["reason"], kyc.OK)
        self.assertIs(result["is_face_match"], True)

    def test_a_confident_mismatch_stays_a_plain_mismatch_that_java_refuses(self):
        with mock.patch.object(kyc, "FACE_READY", True), self.faces(id_score=0.9), \
                self.features(unit_vector(1), unit_vector(2)):
            result = two_step(self.id_closeup, self.selfie)
        self.assertEqual(result["reason"], kyc.OK)
        self.assertIs(result["is_face_match"], False)
        self.assertIsNone(result["face_match_error"])

    def test_the_id_portrait_is_searched_at_full_crop_size_with_the_lower_threshold(self):
        v = unit_vector(1)
        with mock.patch.object(kyc, "FACE_READY", True), self.faces(), self.features(v, v):
            two_step(self.id_closeup, self.selfie)
        self.assertIn(("card", kyc.CROP_WIDTH, kyc.ID_FACE_SCORE_THRESHOLD), self.searches)
        self.assertIn(("selfie", kyc.FACE_WORK_MAX_SIDE, kyc.FACE_SCORE_THRESHOLD), self.searches)  # selfie unchanged
        self.assertEqual(kyc.ID_FACE_SCORE_THRESHOLD, 0.6)

    def test_system_problems_have_no_face_match_error_so_java_sends_them_to_review(self):
        with mock.patch.object(kyc, "FACE_READY", False):
            self.assertEqual(two_step(self.id_closeup, self.selfie)["reason"], kyc.FACE_MODELS_UNAVAILABLE)
        with mock.patch.object(kyc, "CV_READY", False):
            self.assertEqual(two_step(self.id_closeup, self.selfie)["reason"], kyc.OPENCV_UNAVAILABLE)
        with mock.patch.object(kyc, "FACE_READY", True):
            bad = kyc.verify(kyc.TwoStepRequest(high_res_id_image="nope", liveness_image=to_b64(self.selfie)))
        self.assertEqual(bad["reason"], kyc.BAD_IMAGE)
        with mock.patch.object(kyc, "FACE_READY", True), \
                mock.patch.object(kyc, "verify_two_step", side_effect=RuntimeError("boom")):
            boom = two_step(self.id_closeup, self.selfie)
        self.assertEqual(boom["reason"], kyc.INTERNAL_ERROR)
        for answer in (bad, boom):
            self.assertIsNone(answer["face_match_error"])
            self.assertIsNone(answer["is_face_match"])


@unittest.skipUnless(kyc.FACE_READY, "YuNet/SFace models not in ./models")
class RealFaceModelTest(unittest.TestCase):
    """The real YuNet model on synthetic images: a card with an empty grey portrait box has no face to compare,
    even at the lower ID threshold (no invented faces)."""

    def test_blank_id_portrait_is_no_id_face(self):
        result = two_step(paste_card(noisy_background(), (300, 250), 900, thumb=False), noisy_background(1280, 720))
        self.assertEqual(result["reason"], kyc.NO_ID_FACE)
        self.assertEqual(result["face_match_error"], kyc.FACE_UNCLEAR_ERROR)
        self.assertIsNone(kyc.find_id_face(kyc.warp_card(paste_card(noisy_background(), (300, 250), 900, thumb=False),
                                                         kyc.order_corners([[300, 250], [1200, 250], [1200, 818], [300, 818]])))[1])


if __name__ == "__main__":
    unittest.main()
